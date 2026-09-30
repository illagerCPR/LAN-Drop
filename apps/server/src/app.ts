import { existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import Fastify, { type FastifyError, type FastifyInstance } from "fastify";
import fastifyStatic from "@fastify/static";
import fastifyWebsocket from "@fastify/websocket";
// 必须用 ws 的 WebSocket 类型，不能用全局 WebSocket
// （Node 22+ 暴露的全局 WebSocket 来自 undici，与 @fastify/websocket 的 handler 签名不兼容）
import type { WebSocket } from "ws";

import {
  ApiPath,
  PROTOCOL_VERSION,
  WsEventType,
  type ServerInfoDto,
  type WsHelloPayload,
} from "@lan-drop/protocol";

import { resolveDevice } from "./auth.ts";
import type { AppContext } from "./context.ts";
import { extractQueryToken, toMessageDto } from "./dto.ts";
import { isPrivateAddress } from "./net.ts";
import { registerFileRoutes } from "./routes/files.ts";
import { registerMessageRoutes } from "./routes/messages.ts";
import { registerPairRoutes } from "./routes/pair.ts";

const serverRoot = join(dirname(fileURLToPath(import.meta.url)), "..");
const fallbackPublicDir = join(serverRoot, "public");

/**
 * 定位静态资源根目录，取第一个含 `index.html` 的候选。
 *
 *   1. `LAN_DROP_WEB_ROOT` 显式指定（排查/自定义部署用）；
 *   2. `serverRoot/web/dist` —— 便携包布局（`app/server/server.js` + `app/web/dist`）；
 *   3. `serverRoot/../web/dist` —— 仓库开发布局（`apps/server/src` + `apps/web/dist`）；
 *   4. `serverRoot/public` —— P0 连通性冒烟页（前端未构建时的兜底）。
 *
 * 不能写死「入口文件上一层再 `../web/dist`」：esbuild 出包后入口是 `app/server/server.js`，
 * 与源码树层级不同，写死会让便携包找不到 `web/dist` 而**静默**退回冒烟页
 * （页面能打开但没有聊天界面，极难排查）。
 */
function resolveStaticRoot(): string {
  const override = process.env["LAN_DROP_WEB_ROOT"]?.trim();
  const candidates = [
    ...(override ? [override] : []),
    join(serverRoot, "web", "dist"),
    join(serverRoot, "..", "web", "dist"),
    fallbackPublicDir,
  ];

  for (const dir of candidates) {
    if (existsSync(join(dir, "index.html"))) return dir;
  }
  return fallbackPublicDir;
}

/** ws 的 readyState 常量。 */
const WS_READY_OPEN = 1;
/** 自定义关闭码：鉴权失败（4000-4999 为应用保留区间）。 */
const WS_CLOSE_UNAUTHORIZED = 4401;

export function createApp(ctx: AppContext): FastifyInstance {
  const { config } = ctx;

  const app = Fastify({
    logger: { level: process.env["LAN_DROP_LOG_LEVEL"] ?? "info" },
    trustProxy: false,
  });

  // ---- 全局：拒绝非私有网段来源 ----
  if (config.privateNetworkOnly) {
    app.addHook("onRequest", async (request, reply) => {
      if (!isPrivateAddress(request.ip)) {
        request.log.warn({ ip: request.ip }, "拒绝非私有网段来源");
        await reply.code(403).send({ error: "only_private_network_allowed" });
      }
    });
  }

  // ---- 上传分片是裸字节流，不能按 JSON 解析 ----
  // 直接透传 Readable，避免 Fastify 把请求体整体读进内存（大文件会直接撑爆堆）。
  app.addContentTypeParser("application/octet-stream", (_request, payload, done) => {
    done(null, payload);
  });

  app.register(fastifyWebsocket);

  // 优先托管前端构建产物；未构建时退回 P0 的连通性冒烟页，方便环境排查。
  const staticRoot = resolveStaticRoot();
  app.register(fastifyStatic, { root: staticRoot, prefix: "/" });
  app.log.info({ staticRoot }, "静态资源目录");

  // ---------------------------------------------------------------- 元信息
  app.get(ApiPath.info, async (): Promise<ServerInfoDto> => ({
    protocolVersion: PROTOCOL_VERSION,
    serverId: ctx.serverId,
    serverName: config.serverName,
    tls: false,
    pairingRequired: config.pairingRequired,
  }));

  app.get("/healthz", async () => ({
    ok: true,
    onlineClients: ctx.hub.onlineCount(),
    latestSeq: ctx.store.latestSeq(),
  }));

  // ---------------------------------------------------------------- 业务路由
  registerPairRoutes(app, ctx);
  registerMessageRoutes(app, ctx);
  registerFileRoutes(app, ctx);

  // ---------------------------------------------------------------- WebSocket
  //
  // ⚠️ WS 路由必须注册在 fastifyWebsocket 的**子作用域**里（官方 README 同款写法）。
  // 该插件的 onRoute 钩子只包装「本作用域及更深」的路由；若直接写在根实例上，
  // Fastify 会把它当普通 GET 路由，handler 收到 (request, reply) 而非 (socket, request)
  // ——socket 参数实际是 Request 对象，socket.close() 直接 TypeError，鉴权字段也全部读空。
  app.register(async function websocketRoutes(instance) {
    instance.get(ApiPath.ws, { websocket: true }, (socket: WebSocket, request) => {
      const device = resolveDevice(
        ctx.store,
        request.headers.authorization,
        extractQueryToken(request.query),
      );

      if (!device) {
        socket.close(WS_CLOSE_UNAUTHORIZED, "unauthorized");
        return;
      }

      ctx.hub.add(socket, device);
      request.log.info({ device: device.name }, "WebSocket 已连接");

      const send = (type: string, payload?: unknown): void => {
        if (socket.readyState === WS_READY_OPEN) {
          socket.send(JSON.stringify({ type, payload }));
        }
      };

      // 连接即告知当前水位，客户端据此发现「离线期间错过了消息」
      const hello: WsHelloPayload = {
        deviceId: device.id,
        serverId: ctx.serverId,
        protocolVersion: PROTOCOL_VERSION,
        latestSeq: ctx.store.latestSeq(),
        onlineCount: ctx.hub.onlineCount(),
      };
      send(WsEventType.hello, hello);

      socket.on("message", (raw: Buffer) => {
        let parsed: { type?: string };
        try {
          parsed = JSON.parse(raw.toString("utf8")) as { type?: string };
        } catch {
          return;
        }

        switch (parsed.type) {
          case WsEventType.ping:
            send(WsEventType.pong, { at: Date.now() });
            break;

          // 打字中之类的瞬时状态：纯透传，不落库
          case WsEventType.typing:
            ctx.hub.broadcast(
              {
                type: WsEventType.typing,
                payload: { deviceId: device.id, deviceName: device.name },
              },
              device.id,
            );
            break;

          default:
            break;
        }
      });
    });
  });

  // ---------------------------------------------------------------- 错误兜底
  app.setErrorHandler(async (error: FastifyError, request, reply) => {
    request.log.error({ err: error }, "请求处理失败");

    // SQLite 约束错误等不应把内部细节透给客户端
    const status = error.statusCode && error.statusCode >= 400 ? error.statusCode : 500;

    // 显式指定 JSON：若此前已被设成 application/octet-stream（如下载路由中途出错），
    // Fastify 会因无法序列化对象而再抛 FST_ERR_REP_INVALID_PAYLOAD_TYPE，
    // 把真实错误盖成另一个 500，排查时极具误导性。
    await reply
      .code(status)
      .type("application/json")
      .send({
        error: status === 500 ? "internal_error" : (error.code ?? "request_failed"),
        message: status === 500 ? "服务器内部错误" : error.message,
      });
  });

  app.setNotFoundHandler(async (request, reply) => {
    // API 路径返回结构化 404；其余交给 SPA（前端用 hash 路由，实际很少用到）
    if (request.url.startsWith(ApiPath.info.split("/").slice(0, 3).join("/"))) {
      return reply.code(404).send({ error: "not_found", path: request.url });
    }
    return reply.code(404).send({ error: "not_found", path: request.url });
  });

  // 暴露给 index.ts 做优雅退出
  app.addHook("onClose", async () => {
    ctx.store.close();
  });

  return app;
}

export { toMessageDto };
