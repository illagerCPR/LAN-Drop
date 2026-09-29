import { WsEventType, type WsEnvelopeDto } from "@lan-drop/protocol";

export type WsStatus = "connecting" | "open" | "closed";

export interface LanDropSocketHandlers {
  onEvent(event: WsEnvelopeDto): void;
  onStatus(status: WsStatus): void;
}

/**
 * 带自动重连与心跳的 WebSocket 封装。
 *
 * 重连采用指数退避（1s 起步，封顶 10s）；重连成功后调用方靠
 * `hello.latestSeq` 与本地游标比对即可补齐离线期间的消息，
 * 服务端不做任何补发缓存。
 */
export class LanDropSocket {
  readonly #url: string;
  readonly #handlers: LanDropSocketHandlers;
  #socket: WebSocket | null = null;
  #reconnectTimer: number | null = null;
  #heartbeatTimer: number | null = null;
  #attempt = 0;
  #disposed = false;

  constructor(url: string, handlers: LanDropSocketHandlers) {
    this.#url = url;
    this.#handlers = handlers;
  }

  connect(): void {
    if (this.#disposed || this.#socket !== null) return;
    this.#handlers.onStatus("connecting");

    const socket = new WebSocket(this.#url);
    this.#socket = socket;

    socket.onopen = () => {
      this.#attempt = 0;
      this.#handlers.onStatus("open");
      this.#startHeartbeat();
    };

    socket.onmessage = (raw: MessageEvent) => {
      try {
        const event = JSON.parse(String(raw.data)) as WsEnvelopeDto;
        this.#handlers.onEvent(event);
      } catch {
        // 非 JSON 帧直接忽略
      }
    };

    socket.onclose = () => {
      this.#stopHeartbeat();
      this.#socket = null;
      if (this.#disposed) return;
      this.#handlers.onStatus("closed");
      this.#scheduleReconnect();
    };

    // 出错后必然伴随 close，统一在 onclose 里处理重连
    socket.onerror = () => undefined;
  }

  sendEvent(type: string, payload?: unknown): void {
    const socket = this.#socket;
    if (socket === null || socket.readyState !== WebSocket.OPEN) return;
    socket.send(JSON.stringify(payload === undefined ? { type } : { type, payload }));
  }

  close(): void {
    this.#disposed = true;
    if (this.#reconnectTimer !== null) {
      window.clearTimeout(this.#reconnectTimer);
      this.#reconnectTimer = null;
    }
    this.#stopHeartbeat();
    this.#socket?.close();
    this.#socket = null;
  }

  #scheduleReconnect(): void {
    const delay = Math.min(1000 * 2 ** this.#attempt, 10_000);
    this.#attempt += 1;
    this.#reconnectTimer = window.setTimeout(() => {
      this.#reconnectTimer = null;
      this.connect();
    }, delay);
  }

  #startHeartbeat(): void {
    this.#stopHeartbeat();
    this.#heartbeatTimer = window.setInterval(() => {
      this.sendEvent(WsEventType.ping);
    }, 25_000);
  }

  #stopHeartbeat(): void {
    if (this.#heartbeatTimer !== null) {
      window.clearInterval(this.#heartbeatTimer);
      this.#heartbeatTimer = null;
    }
  }
}
