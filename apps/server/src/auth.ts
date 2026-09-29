import { timingSafeEqual } from "node:crypto";

import type { DeviceRow } from "./types.ts";
import { generatePairingCode, generateToken, hashToken, type Store } from "./store.ts";

declare module "fastify" {
  interface FastifyRequest {
    /** 由鉴权钩子写入；未通过鉴权的请求上不存在该字段。 */
    device?: DeviceRow;
  }
}

const DEFAULT_TTL_MS = 10 * 60 * 1000;

/**
 * 配对码管理。
 *
 * 设计取舍：配对码**每次启动重新生成**且**用完即换**，只在内存里活着。
 * 局域网工具的威胁模型是「同网段的陌生设备」，短时效 + 一次性足够，
 * 换来的是「不用管配对码持久化、也不用担心旧码泄漏」。
 */
export class PairingManager {
  readonly #ttlMs: number;
  #code: string;
  #expiresAt: number;

  constructor(ttlMs: number = DEFAULT_TTL_MS) {
    this.#ttlMs = ttlMs;
    this.#code = generatePairingCode();
    this.#expiresAt = Date.now() + ttlMs;
  }

  current(): { code: string; expiresAt: number } {
    // 过期后自动轮换，避免对外一直暴露同一个码
    if (Date.now() > this.#expiresAt) {
      return this.rotate();
    }
    return { code: this.#code, expiresAt: this.#expiresAt };
  }

  rotate(): { code: string; expiresAt: number } {
    this.#code = generatePairingCode();
    this.#expiresAt = Date.now() + this.#ttlMs;
    return { code: this.#code, expiresAt: this.#expiresAt };
  }

  /** 恒定时间比较，避免通过响应耗时逐字符爆破配对码。 */
  verify(input: string): boolean {
    const { code } = this.current();
    const a = Buffer.from(code.toUpperCase(), "utf8");
    const b = Buffer.from(input.trim().toUpperCase(), "utf8");

    if (a.length !== b.length) return false;
    return timingSafeEqual(a, b);
  }
}

export interface IssuedCredential {
  device: DeviceRow;
  token: string;
}

/** 发放长期凭据：库中只留 token 的 sha256，原文只回给客户端一次。 */
export function issueCredential(
  store: Store,
  name: string,
  platform: string,
): IssuedCredential {
  const token = generateToken();
  const device = store.createDevice(name, platform, hashToken(token));
  return { device, token };
}

/** 从 `Authorization: Bearer <token>` 或 `?token=` 解析出设备。 */
export function resolveDevice(store: Store, authorization: string | undefined, queryToken: string | undefined): DeviceRow | null {
  let token: string | undefined;

  if (authorization && authorization.startsWith("Bearer ")) {
    token = authorization.slice("Bearer ".length).trim();
  } else if (queryToken) {
    // 文件下载要走 <img src> / <a download>，没法自定义请求头，因此允许查询参数携带
    token = queryToken;
  }

  if (!token) return null;

  const device = store.findDeviceByTokenHash(hashToken(token));
  if (device) {
    store.touchDevice(device.id);
  }
  return device;
}
