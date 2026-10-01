import { randomUUID } from "node:crypto";
import { createReadStream } from "node:fs";
import { stat } from "node:fs/promises";
import { join } from "node:path";
import type { Readable } from "node:stream";

import type { FastifyInstance } from "fastify";

import {
  ApiPath,
  WsEventType,
  type CreateUploadResponse,
  type UploadListDto,
  type UploadStatusDto,
} from "@lan-drop/protocol";

import type { AppContext } from "../context.ts";
import { toMessageDto, toUploadStatusDto } from "../dto.ts";
import {
  appendStreamToFile,
  contentDisposition,
  ensureDir,
  fileExists,
  moveIntoPlace,
  parseRange,
  relativeStoragePath,
  removeFileQuietly,
  sanitizeFileName,
  sha256File,
  truncateTo,
  writeEmptyFile,
} from "../storage.ts";
import { createAuthHook } from "./pair.ts";

/** 建议分片大小。4 MiB 在千兆/百兆局域网下都能跑满，且断点重传代价可控。 */
const CHUNK_SIZE = 4 * 1024 * 1024;

/** 单文件大小上限，防止手滑或恶意请求把磁盘写满。 */
const MAX_FILE_SIZE = 100 * 1024 * 1024 * 1024; // 100 GiB

/** 一次最多列出多少条上传会话。 */
const MAX_UPLOAD_LIST = 100;

export function registerFileRoutes(app: FastifyInstance, ctx: AppContext): void {
  const authHook = createAuthHook(ctx);
  // 下载是唯一允许 ?token= 的 HTTP 接口：<img src> / <a download> 带不了请求头
  const downloadAuthHook = createAuthHook(ctx, { allowQueryToken: true });

  // ---------------------------------------------------------------- 创建上传会话
  app.post<{ Body: { name?: string; size?: number; mime?: string; sha256?: string } }>(
    ApiPath.uploads,
    { preHandler: authHook },
    async (request, reply) => {
      const device = request.device!;
      const body = request.body;

      const rawName = typeof body?.name === "string" ? body.name : "";
      const size = typeof body?.size === "number" ? body.size : Number.NaN;

      if (rawName.trim().length === 0) {
        return reply.code(400).send({ error: "missing_name" });
      }
      if (!Number.isSafeInteger(size) || size < 0) {
        return reply.code(400).send({ error: "invalid_size" });
      }
      if (size > MAX_FILE_SIZE) {
        return reply.code(413).send({ error: "file_too_large", max: MAX_FILE_SIZE });
      }

      const safeName = sanitizeFileName(rawName);
      const tempDir = join(ctx.config.dataRoot, "tmp");
      await ensureDir(tempDir);

      const upload = ctx.store.createUpload({
        deviceId: device.id,
        name: safeName,
        size,
        mime: typeof body?.mime === "string" && body.mime.length > 0 ? body.mime : null,
        sha256:
          typeof body?.sha256 === "string" && /^[0-9a-fA-F]{64}$/.test(body.sha256)
            ? body.sha256.toLowerCase()
            : null,
        tempPath: join(tempDir, `${randomUUID()}.part`),
      });

      // 0 字节文件没有任何分片请求（分片接口对 remaining<=0 一律 409），
      // 临时文件必须在这里就落盘，complete 才有东西可校验、可搬移。
      if (size === 0) {
        await writeEmptyFile(upload.tempPath);
      }

      const response: CreateUploadResponse = {
        uploadId: upload.id,
        receivedBytes: 0,
        chunkSize: CHUNK_SIZE,
      };

      return response;
    },
  );

  // ---------------------------------------------------------------- 查询上传会话
  //
  // 断点续传的服务端一半：客户端进程被杀后本地只剩一个 uploadId，必须回来问
  // 「你收了多少字节」。这两个接口都是只读的，且都按设备隔离。
  app.get<{ Querystring: { state?: string; limit?: string } }>(
    ApiPath.uploads,
    { preHandler: authHook },
    async (request): Promise<UploadListDto> => {
      const device = request.device!;
      const requested = request.query.state;
      const state =
        requested === "open" || requested === "completed" || requested === "aborted"
          ? requested
          : null;

      const items = ctx.store
        .listUploadsByDevice(device.id, state, MAX_UPLOAD_LIST)
        .map((upload) => toUploadStatusDto(upload, CHUNK_SIZE));

      return { items };
    },
  );

  app.get<{ Params: { id: string } }>(
    `${ApiPath.uploads}/:id`,
    { preHandler: authHook },
    async (request, reply) => {
      const device = request.device!;
      const upload = ctx.store.findUpload(request.params.id);

      if (!upload) {
        return reply.code(404).send({ error: "upload_not_found" });
      }
      if (upload.deviceId !== device.id) {
        return reply.code(403).send({ error: "not_upload_owner" });
      }

      const status: UploadStatusDto = toUploadStatusDto(upload, CHUNK_SIZE);
      return status;
    },
  );

  // ---------------------------------------------------------------- 分片续传
  //
  // 语义是「追加写」而非「随机写」：服务端只接受 offset 恰好等于已收字节数的分片。
  // 不匹配就回 409 并把真实 offset 告诉客户端，让它自己对齐——这比在服务端做
  // 稀疏文件随机写简单得多，也天然避免了乱序分片写坏文件。
  app.patch<{ Params: { id: string }; Querystring: { offset?: string } }>(
    `${ApiPath.uploads}/:id`,
    { preHandler: authHook },
    async (request, reply) => {
      const device = request.device!;
      const upload = ctx.store.findUpload(request.params.id);

      if (!upload) {
        return reply.code(404).send({ error: "upload_not_found" });
      }
      if (upload.deviceId !== device.id) {
        return reply.code(403).send({ error: "not_upload_owner" });
      }
      if (upload.state !== "open") {
        return reply
          .code(409)
          .send({ error: "upload_not_open", state: upload.state, receivedBytes: upload.receivedBytes });
      }

      const offset = Number.parseInt(String(request.query.offset ?? ""), 10);
      if (!Number.isFinite(offset) || offset !== upload.receivedBytes) {
        return reply
          .code(409)
          .send({ error: "offset_mismatch", receivedBytes: upload.receivedBytes });
      }

      const remaining = upload.size - upload.receivedBytes;
      if (remaining <= 0) {
        return reply
          .code(409)
          .send({ error: "already_complete", receivedBytes: upload.receivedBytes });
      }

      const body = request.body as Readable | undefined;
      if (!body || typeof body.pipe !== "function") {
        return reply.code(415).send({ error: "expected_octet_stream_body" });
      }

      // 丢掉上一次中途断流留下的残字节，保证「开始追加时文件长度 = 本片 offset」。
      // 没有这一步，断点续传会把新数据接在残字节后面，静默写坏文件。
      await truncateTo(upload.tempPath, upload.receivedBytes);

      const result = await appendStreamToFile(body, upload.tempPath, remaining);

      if (result.overflowed) {
        ctx.store.setUploadState(upload.id, "aborted");
        await removeFileQuietly(upload.tempPath);
        return reply.code(413).send({ error: "exceeds_declared_size", declaredSize: upload.size });
      }

      const receivedBytes = ctx.store.addUploadBytes(upload.id, result.bytesWritten);

      return { uploadId: upload.id, receivedBytes, size: upload.size };
    },
  );

  // ---------------------------------------------------------------- 完成上传
  app.post<{ Params: { id: string } }>(
    `${ApiPath.uploads}/:id/complete`,
    { preHandler: authHook },
    async (request, reply) => {
      const device = request.device!;
      const upload = ctx.store.findUpload(request.params.id);

      if (!upload) {
        return reply.code(404).send({ error: "upload_not_found" });
      }
      if (upload.deviceId !== device.id) {
        return reply.code(403).send({ error: "not_upload_owner" });
      }
      if (upload.state !== "open") {
        return reply.code(409).send({ error: "upload_not_open", state: upload.state });
      }
      if (upload.receivedBytes !== upload.size) {
        return reply.code(409).send({
          error: "incomplete",
          receivedBytes: upload.receivedBytes,
          size: upload.size,
        });
      }

      // 权威进度（received_bytes）之外再核对一次盘上真实字节数：两者由两条路径
      // 维护（DB 在追加成功后推进、文件在流写入时增长），任何一侧丢了事件都会
      // 静默漂移。收尾是最后一道关：不一致就拒绝入库，绝不把错位文件当成功；
      // 顺带把「临时文件不存在」从 sha256File 的 ENOENT 500 变成明确的上游错误。
      const onDisk = await stat(upload.tempPath).catch(() => null);
      if (onDisk === null || onDisk.size !== upload.size) {
        return reply.code(409).send({
          error: "size_mismatch_on_disk",
          receivedBytes: upload.receivedBytes,
          size: upload.size,
          onDiskSize: onDisk?.size ?? null,
        });
      }

      const digest = await sha256File(upload.tempPath);

      if (upload.sha256 && upload.sha256 !== digest) {
        ctx.store.setUploadState(upload.id, "aborted");
        await removeFileQuietly(upload.tempPath);
        return reply
          .code(422)
          .send({ error: "sha256_mismatch", expected: upload.sha256, actual: digest });
      }

      const fileId = randomUUID();
      const relPath = relativeStoragePath(fileId, upload.name);
      const absPath = join(ctx.config.filesRoot, relPath);

      await moveIntoPlace(upload.tempPath, absPath);

      const file = ctx.store.createFile({
        id: fileId,
        name: upload.name,
        size: upload.size,
        mime: upload.mime,
        sha256: digest,
        relPath,
      });

      const record = ctx.store.appendFileMessage({
        senderId: device.id,
        senderName: device.name,
        fileId: file.id,
      });
      ctx.store.setUploadState(upload.id, "completed");

      const dto = toMessageDto(record);
      ctx.hub.broadcast({ type: WsEventType.messageNew, payload: dto });

      app.log.info(
        { fileId: file.id, name: file.name, size: file.size, from: device.name },
        "文件接收完成",
      );

      return dto;
    },
  );

  // ---------------------------------------------------------------- 中止上传
  app.delete<{ Params: { id: string } }>(
    `${ApiPath.uploads}/:id`,
    { preHandler: authHook },
    async (request, reply) => {
      const device = request.device!;
      const upload = ctx.store.findUpload(request.params.id);

      if (!upload) {
        return reply.code(404).send({ error: "upload_not_found" });
      }
      if (upload.deviceId !== device.id) {
        return reply.code(403).send({ error: "not_upload_owner" });
      }

      ctx.store.setUploadState(upload.id, "aborted");
      await removeFileQuietly(upload.tempPath);

      return { aborted: true, uploadId: upload.id };
    },
  );

  // ---------------------------------------------------------------- 下载（支持 Range）
  app.get<{ Params: { id: string }; Querystring: { token?: string; download?: string } }>(
    `${ApiPath.files}/:id`,
    { preHandler: downloadAuthHook },
    async (request, reply) => {
      const file = ctx.store.findFile(request.params.id);
      if (!file) {
        return reply.code(404).send({ error: "file_not_found" });
      }

      const absPath = join(ctx.config.filesRoot, file.relPath);
      if (!(await fileExists(absPath))) {
        // 元数据在但文件没了：多半是用户手工清理过文件仓库，明确区分于 404
        return reply.code(410).send({ error: "file_missing_on_disk" });
      }

      const info = await stat(absPath);
      const size = info.size;
      const range = parseRange(request.headers.range, size);

      reply.header("accept-ranges", "bytes");

      // 越界/格式错的 Range 必须在设置 content-type 之前处理：
      // 一旦把 content-type 设成 application/octet-stream，Fastify 就无法再
      // 序列化 JSON 错误体（会抛 FST_ERR_REP_INVALID_PAYLOAD_TYPE 变成 500）。
      if (request.headers.range && !range) {
        reply.header("content-range", `bytes */${size}`);
        return reply.code(416).send({ error: "range_not_satisfiable", size });
      }

      reply.header("cache-control", "private, max-age=0, must-revalidate");
      reply.header("content-type", file.mime ?? "application/octet-stream");

      // 默认 inline，图片/文本可直接预览；?download=1 强制另存
      reply.header(
        "content-disposition",
        request.query.download === "1"
          ? contentDisposition(file.name)
          : `inline; filename*=UTF-8''${encodeURIComponent(file.name)}`,
      );

      if (range) {
        reply.code(206);
        reply.header("content-range", `bytes ${range.start}-${range.end}/${size}`);
        reply.header("content-length", String(range.end - range.start + 1));
        return reply.send(createReadStream(absPath, { start: range.start, end: range.end }));
      }

      reply.header("content-length", String(size));
      return reply.send(createReadStream(absPath));
    },
  );
}
