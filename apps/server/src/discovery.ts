import { createSocket, type RemoteInfo, type Socket } from "node:dgram";

import type { ServerConfig } from "./config.ts";
import { isPrivateAddress } from "./net.ts";

/** 客户端探测报文的固定内容（整包精确匹配，避免把任意 UDP 流量当发现请求）。 */
export const DISCOVERY_MAGIC = "LANDROP-DISCOVER-v1";

/** 发现应答负载：客户端用它拼出 baseUrl，并判断「是不是原来那台服务端」。 */
export interface DiscoveryAnnounce {
  service: "lan-drop";
  /** 发现协议自身的版本号，与业务协议版本无关 */
  v: 1;
  id: string;
  name: string;
  port: number;
}

/** 发现服务的最小日志接口（传入 Fastify 的 logger，避免依赖其完整类型）。 */
export interface DiscoveryLogger {
  info(obj: object, msg?: string): void;
  warn(obj: object, msg?: string): void;
}

/**
 * UDP 局域网自动发现。
 *
 * 采用「客户端广播提问、服务端单播应答」而不是服务端定时广播：
 *   - 局域网平时没有噪声流量，也不打扰同网段其他设备；
 *   - 手机端按需扫一次即可，重连找回时同样能触发；
 *   - 应答是单播，Android 侧不申请 MulticastLock 也收得到。
 *
 * 端口与 HTTP 端口解耦（默认 8788）：Windows 防火墙规则里 TCP/UDP 各自独立，
 * 若复用 8787 就得给 HTTP 端口再开一条 UDP 规则，语义混乱。
 *
 * UDP 绑定失败（端口被占用等）只降级不致命：自动发现没了，手输地址照常工作。
 */
export class DiscoveryService {
  private socket: Socket | null = null;

  private readonly config: ServerConfig;

  private readonly serverId: string;

  constructor(config: ServerConfig, serverId: string) {
    this.config = config;
    this.serverId = serverId;
  }

  start(log: DiscoveryLogger): void {
    if (!this.config.discoveryEnabled) {
      log.info({ port: this.config.discoveryPort }, "UDP 自动发现已通过配置关闭");
      return;
    }

    const socket = createSocket("udp4");

    // dgram 的 error 事件没有处理器会直接炸掉进程；绑定失败属于可降级故障
    socket.on("error", (error: NodeJS.ErrnoException) => {
      log.warn({ err: error, port: this.config.discoveryPort }, "UDP 自动发现不可用，已降级");
      this.stop();
    });

    socket.on("message", (raw: Buffer, rinfo: RemoteInfo) => {
      // 与 HTTP 的 privateNetworkOnly 同一策略：只应答私有网段的提问
      if (this.config.privateNetworkOnly && !isPrivateAddress(rinfo.address)) return;
      if (raw.toString("utf8").trim() !== DISCOVERY_MAGIC) return;

      const announce: DiscoveryAnnounce = {
        service: "lan-drop",
        v: 1,
        id: this.serverId,
        name: this.config.serverName,
        port: this.config.port,
      };
      socket.send(JSON.stringify(announce), rinfo.port, rinfo.address);
    });

    socket.bind(this.config.discoveryPort, () => {
      log.info({ port: this.config.discoveryPort }, "UDP 自动发现已就绪");
    });
    this.socket = socket;
  }

  stop(): void {
    this.socket?.close();
    this.socket = null;
  }
}
