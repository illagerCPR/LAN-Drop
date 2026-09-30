import type {
  FileRefDto,
  MessageDto,
  UploadStatusDto,
} from "@lan-drop/protocol";

import type { MessageRecord, UploadRecord } from "./types.ts";

/**
 * 内部记录 → 线上 DTO。
 *
 * 刻意手写而不是直接返回数据库行：内部行里有 `tokenHash`、`tempPath` 这类
 * 绝不能外泄的字段，显式白名单式映射能从根上避免「加了个字段就被顺手带出去」。
 */
export function toMessageDto(record: MessageRecord): MessageDto {
  const dto: MessageDto = {
    seq: record.seq,
    id: record.id,
    kind: record.kind,
    senderId: record.senderId,
    senderName: record.senderName,
    createdAt: record.createdAt,
  };

  if (record.text !== null) {
    dto.text = record.text;
  }

  if (record.file) {
    const file: FileRefDto = {
      id: record.file.id,
      name: record.file.name,
      size: record.file.size,
    };
    if (record.file.mime) file.mime = record.file.mime;
    if (record.file.sha256) file.sha256 = record.file.sha256;
    dto.file = file;
  }

  return dto;
}

/** 上传会话 → 可续传视图。注意 `tempPath` 是白名单外的字段，只在这里被挡掉。 */
export function toUploadStatusDto(record: UploadRecord, chunkSize: number): UploadStatusDto {
  const dto: UploadStatusDto = {
    uploadId: record.id,
    name: record.name,
    size: record.size,
    receivedBytes: record.receivedBytes,
    state: record.state,
    resumable: record.state === "open" && record.receivedBytes < record.size,
    chunkSize,
    createdAt: record.createdAt,
    updatedAt: record.updatedAt,
  };

  if (record.mime) dto.mime = record.mime;

  return dto;
}

/** 解析十进制整数查询参数，带范围钳制与回退值。 */
export function parseClampedInt(
  raw: unknown,
  fallback: number,
  min: number,
  max: number,
): number {
  if (typeof raw !== "string" || raw.length === 0) return fallback;
  const parsed = Number.parseInt(raw, 10);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.min(max, Math.max(min, parsed));
}

/** 从任意 query 对象里取出 token（文件下载走查询参数携带凭据）。 */
export function extractQueryToken(query: unknown): string | undefined {
  if (typeof query !== "object" || query === null) return undefined;
  const value = (query as Record<string, unknown>)["token"];
  return typeof value === "string" && value.length > 0 ? value : undefined;
}
