import type { WebSocket } from "ws";

import { WsEventType, type WsEnvelopeDto } from "@lan-drop/protocol";

import type { DeviceRow } from "./types.ts";

/** ws 的 readyState 常量（避开各版本 @types/ws 对实例属性声明不一致的问题）。 */
const WS_READY_OPEN = 1;
/** 自定义关闭码：鉴权失败（4000-4999 为应用保留区间）。 */
const WS_CLOSE_UNAUTHORIZED = 4401;

interface Client {
  socket: WebSocket;
  device: DeviceRow;
}

/**
 * WebSocket 连接中枢。
 *
 * 只负责「谁在线」和「把事件推给谁」，不持有业务状态——
 * 业务状态一律以 SQLite 为准，这样客户端断线重连后靠 `since=seq` 就能补齐，
 * 不需要服务端做消息缓存或补发队列。
 */
export class Hub {
  readonly #clients = new Set<Client>();

  add(socket: WebSocket, device: DeviceRow): void {
    this.#clients.add({ socket, device });

    socket.on("close", () => {
      for (const client of this.#clients) {
        if (client.socket === socket) {
          this.#clients.delete(client);
          break;
        }
      }
      this.broadcast({
        type: WsEventType.deviceOffline,
        payload: { deviceId: device.id, deviceName: device.name },
      });
    });

    this.broadcast({
      type: WsEventType.deviceOnline,
      payload: { deviceId: device.id, deviceName: device.name, onlineCount: this.#clients.size },
    });
  }

  /** 广播事件；`exceptDeviceId` 用于「不要回推给消息发起者」。 */
  broadcast(event: WsEnvelopeDto, exceptDeviceId?: string): void {
    const text = JSON.stringify(event);

    for (const client of this.#clients) {
      if (exceptDeviceId && client.device.id === exceptDeviceId) continue;
      if (client.socket.readyState !== WS_READY_OPEN) continue;
      try {
        client.socket.send(text);
      } catch {
        // 单个客户端发送失败不应影响其他客户端；其 close 事件会负责摘除
      }
    }
  }

  onlineCount(): number {
    return this.#clients.size;
  }

  onlineDeviceIds(): string[] {
    return [...this.#clients].map((client) => client.device.id);
  }

  /**
   * 踢掉某设备的全部在线连接（撤销设备时用）。
   *
   * 以 4401 关闭——Android 端把它识别为「凭据失效」并停止重连、提示解除配对，
   * 而不是进入无意义的无限重连。返回踢掉的连接数。
   */
  kickDevice(deviceId: string): number {
    let kicked = 0;

    for (const client of this.#clients) {
      if (client.device.id !== deviceId) continue;
      try {
        client.socket.close(WS_CLOSE_UNAUTHORIZED, "device_revoked");
        kicked += 1;
      } catch {
        // socket 已死就无需再踢；其 close 事件会负责摘除
      }
    }

    return kicked;
  }
}
