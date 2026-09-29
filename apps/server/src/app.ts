import { mkdir } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import Fastify, { type FastifyInstance, type FastifyRequest } from "fastify";
import fastifyStatic from "@fastify/static";
import fastifyWebsocket from "@fastify/websocket";
// 注意：必须用 ws 的 WebSocket 类型，不能用全局 WebSocket
// （Node 22+ 暴露的全局 WebSocket 来自 undici，与 @fastify/websocket 的 handler 签名不兼容）
import type { WebSocket } from "ws";

import {
  ApiPath,
  PROTOCOL_VERSION,
  WsEventType,
  type ServerInfoDto,
} from "@lan-drop/protocol";

import type { ServerConfig } from "./config.ts";
import { loadOrCreateIdentity } from "./identity.ts";

const serverRoot = join(dirname(fileURLToPath(import.meta.url)), "..");
const publicDir = join(serverRoot, "public");

/** ws 的 readyState 常量。 */
const WS_READY_OPEN = 1;

/**
 * 是否属于私有/本机地址。
 *
 * LAN-Drop 是局域网工具，没有任何理由接受公网来源：
 * 万一路由器做了端口映射、或跑在云主机上忘了改配置，
 * 这条防线能让服务保持「只有内网可达」。
 */
export function isPrivateAddress(rawIp: string): boolean {
  const ip = rawIp.startsWith("::ffff:") ? rawIp.slice("::ffff:".length) : rawIp;

  if (ip === "::1" || ip === "127.0.0.1") return true;
  if (ip.startsWith("10.")) return true;
  if (ip.startsWith("192.168.")) return true;
  if (ip.startsWith("169.254.")) return true;
  // 唯一本地地址（IPv6 ULA）
  if (/^f[cd][0-9a-f]{2}:/i.test(ip)) return true;
  // 172.16.0.0/12
  if (/^172\.(1[6-9]|2\d|3[01])\./.test(ip)) return true;

  return false;
}

export async function buildServer(config: ServerConfig): Promise<FastifyInstance> {
  await mkdir(config.filesRoot, { recursive: true });
  const identity = await loadOrCreateIdentity(config.dataRoot);

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

  await app.register(fastifyWebsocket);
  await app.register(fastifyStatic, { root: publicDir, prefix: "/" });

  // ---- GET /api/v1/info ----
  app.get(ApiPath.info, async (): Promise<ServerInfoDto> => ({
    protocolVersion: PROTOCOL_VERSION,
    serverId: identity.serverId,
    serverName: config.serverName,
    tls: false,
    pairingRequired: config.pairingRequired,
  }));

  app.get("/healthz", async () => ({ ok: true }));

  // ---- WS /api/v1/ws ----
  // P0 只做连通性验证：回显 ping、推送欢迎事件；P1 接入消息广播与传输进度。
  app.get(ApiPath.ws, { websocket: true }, (socket: WebSocket, request: FastifyRequest) => {
    request.log.info({ ip: request.ip }, "WebSocket 客户端已连接");

    const send = (type: string, payload?: unknown): void => {
      // 用字面量而非 socket.OPEN：ws 的 OPEN 是静态成员，
      // 各版本 @types/ws 对实例属性的声明不一致，这里避开该差异。
      if (socket.readyState === WS_READY_OPEN) {
        socket.send(JSON.stringify({ type, payload }));
      }
    };

    send(WsEventType.messageNew, {
      serverId: identity.serverId,
      protocolVersion: PROTOCOL_VERSION,
      message: "LAN-Drop 服务端已连接",
    });

    socket.on("message", (raw: Buffer) => {
      let type: string | undefined;
      try {
        type = (JSON.parse(raw.toString("utf8")) as { type?: string }).type;
      } catch {
        return;
      }

      if (type === WsEventType.ping) {
        send(WsEventType.pong, { at: Date.now() });
      }
    });

    socket.on("close", () => {
      request.log.info({ ip: request.ip }, "WebSocket 客户端已断开");
    });
  });

  return app;
}
