import type { FastifyInstance, FastifyReply, FastifyRequest } from "fastify";

import {
  ApiPath,
  type PairRequest,
  type PairResponse,
} from "@lan-drop/protocol";

import { issueCredential, resolveDevice } from "../auth.ts";
import type { AppContext } from "../context.ts";
import { extractQueryToken } from "../dto.ts";
import { isLoopback, lanAddresses } from "../net.ts";

/** 设备名长度上限，避免被塞入超长字符串。 */
const MAX_DEVICE_NAME = 64;

export interface AuthHookOptions {
  /**
   * 允许用 `?token=` 携带凭据。
   *
   * 查询参数会留在服务端日志、浏览器历史与各中间层，属于高泄漏面凭据；
   * 只有「带不了请求头的消费者」（`<img src>` / `<a download>`）才配得上这个口子，
   * 因此默认关闭，仅文件下载路由显式打开（WS 在 app.ts 里单独处理，同样是浏览器约束）。
   */
  allowQueryToken?: boolean;
}

/**
 * 统一的鉴权前置钩子。
 *
 * 钩子内直接 `reply.send()` 后 Fastify 会跳过后续 handler，
 * 因此调用方不必在业务代码里再判一次「有没有登录」。
 */
export function createAuthHook(ctx: AppContext, options: AuthHookOptions = {}) {
  const allowQueryToken = options.allowQueryToken === true;

  return async function authHook(
    request: FastifyRequest,
    reply: FastifyReply,
  ): Promise<void> {
    const device = resolveDevice(
      ctx.store,
      request.headers.authorization,
      allowQueryToken ? extractQueryToken(request.query) : undefined,
    );

    if (!device) {
      await reply.code(401).send({ error: "unauthorized" });
      return;
    }

    request.device = device;
  };
}

/** 只有宿主自己（回环地址）才能使用的接口在此统一拦截。 */
function denyNonLoopback(request: FastifyRequest, reply: FastifyReply): boolean {
  if (isLoopback(request.ip)) return false;
  void reply.code(403).send({ error: "loopback_only" });
  return true;
}

export function registerPairRoutes(app: FastifyInstance, ctx: AppContext): void {
  // 供 PC 端界面展示配对码与二维码；只有本机可读，手机拿不到（否则免配对就没意义了）
  app.get(`${ApiPath.pair}/code`, async (request, reply) => {
    if (denyNonLoopback(request, reply)) return reply;

    const { code, expiresAt } = ctx.pairing.current();
    const port = ctx.config.port;

    return {
      code,
      expiresAt,
      port,
      // 二维码内容直接用带 hash 的 URL，手机扫码后打开页面即可自动带出配对码
      urls: lanAddresses().map((ip) => `http://${ip}:${port}/#pair=${code}`),
    };
  });

  app.post<{ Body: PairRequest }>(ApiPath.pair, async (request, reply) => {
    const body = request.body;

    if (!body || typeof body.code !== "string") {
      return reply.code(400).send({ error: "invalid_body" });
    }

    if (!ctx.pairing.verify(body.code)) {
      return reply.code(403).send({ error: "invalid_pairing_code" });
    }

    const name =
      typeof body.deviceName === "string" && body.deviceName.trim().length > 0
        ? body.deviceName.trim().slice(0, MAX_DEVICE_NAME)
        : "未命名设备";
    const platform =
      typeof body.platform === "string" && body.platform.trim().length > 0
        ? body.platform.trim().slice(0, 32)
        : "unknown";

    const { device, token } = issueCredential(ctx.store, name, platform);

    // 一次性使用：配对成功立刻轮换，旧码立即失效
    ctx.pairing.rotate();

    const response: PairResponse = {
      deviceId: device.id,
      deviceToken: token,
      serverId: ctx.serverId,
      serverName: ctx.config.serverName,
    };

    app.log.info({ device: device.name, platform }, "设备配对成功");
    return response;
  });

  // 本机浏览器免配对：PC 就是服务端宿主，再让它手输配对码纯属折腾
  app.post<{ Body: { deviceName?: string } }>(`${ApiPath.pair}/local`, async (request, reply) => {
    if (denyNonLoopback(request, reply)) return reply;

    const rawName = request.body?.deviceName;
    const name =
      typeof rawName === "string" && rawName.trim().length > 0
        ? rawName.trim().slice(0, MAX_DEVICE_NAME)
        : "本机浏览器";

    const { device, token } = issueCredential(ctx.store, name, "web-local");

    const response: PairResponse = {
      deviceId: device.id,
      deviceToken: token,
      serverId: ctx.serverId,
      serverName: ctx.config.serverName,
    };

    return response;
  });

  // 在线设备列表（设置页展示用）
  app.get(`${ApiPath.pair}/devices`, { preHandler: createAuthHook(ctx) }, async () => {
    const online = new Set(ctx.hub.onlineDeviceIds());
    return {
      items: ctx.store.listDevices().map((device) => ({
        id: device.id,
        name: device.name,
        platform: device.platform,
        createdAt: device.createdAt,
        lastSeenAt: device.lastSeenAt,
        online: online.has(device.id),
      })),
    };
  });
}
