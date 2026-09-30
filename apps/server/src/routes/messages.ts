import type { FastifyInstance } from "fastify";

import {
  ApiPath,
  WsEventType,
  type MessageDto,
  type MessageKind,
  type MessagePageDto,
} from "@lan-drop/protocol";

import type { AppContext } from "../context.ts";
import { parseClampedInt, toMessageDto } from "../dto.ts";
import { isLoopback } from "../net.ts";
import { createAuthHook } from "./pair.ts";

/** 单条文字消息的长度上限（字符）。 */
const MAX_TEXT_LENGTH = 16 * 1024;
/** 一次增量拉取的默认/最大条数。 */
const DEFAULT_PAGE_SIZE = 200;
const MAX_PAGE_SIZE = 1000;

const ALLOWED_KINDS: ReadonlySet<string> = new Set(["text", "link", "file"]);

export function registerMessageRoutes(app: FastifyInstance, ctx: AppContext): void {
  const authHook = createAuthHook(ctx);

  /**
   * 增量拉取。
   *
   * `since` 语义是「严格大于」，客户端传本地最大 seq 即可无缝续接；
   * 首次全量拉取传 0。响应里带 `latestSeq`，客户端可据此校准游标
   * （例如本地库被清空后，避免游标停留在旧值导致漏消息）。
   */
  app.get<{ Querystring: { since?: string; limit?: string } }>(
    ApiPath.messages,
    { preHandler: authHook },
    async (request): Promise<MessagePageDto> => {
      const since = parseClampedInt(request.query.since, 0, 0, Number.MAX_SAFE_INTEGER);
      const limit = parseClampedInt(request.query.limit, DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE);

      const { items, hasMore } = ctx.store.listMessagesSince(since, limit);

      return {
        items: items.map(toMessageDto),
        latestSeq: ctx.store.latestSeq(),
        hasMore,
        purgedUpto: ctx.store.purgedUpto(),
      };
    },
  );

  /** 发送文字或链接。文件走上传接口，不从这里进来。 */
  app.post<{ Body: { kind?: string; text?: string } }>(
    ApiPath.messages,
    { preHandler: authHook },
    async (request, reply) => {
      const device = request.device!;
      const body = request.body;

      const rawText = typeof body?.text === "string" ? body.text : "";
      const text = rawText.trim();

      if (text.length === 0) {
        return reply.code(400).send({ error: "empty_text" });
      }
      if (text.length > MAX_TEXT_LENGTH) {
        return reply.code(413).send({ error: "text_too_long", max: MAX_TEXT_LENGTH });
      }

      const requestedKind = typeof body?.kind === "string" ? body.kind : "text";
      if (!ALLOWED_KINDS.has(requestedKind) || requestedKind === "file") {
        return reply.code(400).send({ error: "invalid_kind" });
      }

      const record = ctx.store.appendTextMessage({
        senderId: device.id,
        senderName: device.name,
        kind: requestedKind as MessageKind,
        text,
      });

      const dto: MessageDto = toMessageDto(record);

      // 广播给所有连接（含发送者自己的其他标签页）；发送者本端可直接乐观渲染，
      // 但保留回推更简单也更不容易出现「A 发的消息 B 看不到」这类分叉。
      ctx.hub.broadcast({ type: WsEventType.messageNew, payload: dto });

      return dto;
    },
  );

  /** 清空会话（仅本机可操作，避免任意一台已配对设备清掉所有人的记录）。 */
  app.delete(ApiPath.messages, { preHandler: authHook }, async (request, reply) => {
    if (!isLoopback(request.ip)) {
      return reply.code(403).send({ error: "loopback_only" });
    }
    const removed = ctx.store.clearMessages();
    ctx.hub.broadcast({ type: WsEventType.messageDeleted, payload: { cleared: true } });
    return { removed };
  });
}
