/**
 * LAN-Drop 协议 v1 —— 单一事实源（Single Source of Truth）。
 *
 * 本文件同时被服务端与 Web 前端直接引用；Android 侧的 Kotlin 模型
 * （`android/app/src/main/java/io/github/illagercpr/landrop/protocol/Protocol.kt`）
 * 必须与此处逐字段对齐，字段名与序列化名以本文件为准。
 *
 * 分层原则：
 *   - 控制面走 WebSocket（JSON 信封），承载文字消息、事件、在线状态、传输进度；
 *   - 数据面走 HTTP 裸字节流，承载文件上传/下载，天然支持 Range 断点续传。
 *   文件字节永远不进 WebSocket 通道，避免与聊天消息互相队头阻塞。
 */

/** 协议版本。破坏性变更时递增，服务端在 /info 中告知客户端。 */
export const PROTOCOL_VERSION = 1;

/** REST 路径前缀。 */
export const API_PREFIX = "/api/v1";

export const ApiPath = {
  info: `${API_PREFIX}/info`,
  pair: `${API_PREFIX}/pair`,
  messages: `${API_PREFIX}/messages`,
  uploads: `${API_PREFIX}/uploads`,
  files: `${API_PREFIX}/files`,
  ws: `${API_PREFIX}/ws`,
} as const;

/** WebSocket 事件类型。 */
export const WsEventType = {
  /** 连接建立后的第一条服务端消息，告知身份与当前水位 */
  hello: "hello",
  /** 新消息落库（文字或文件） */
  messageNew: "message.new",
  /** 消息被撤回/删除 */
  messageDeleted: "message.deleted",
  /** 传输进度（大文件节流后推送） */
  transferProgress: "transfer.progress",
  /** 设备上线/下线 */
  deviceOnline: "device.online",
  deviceOffline: "device.offline",
  /** 配对请求被批准 */
  pairApproved: "pair.approved",
  /** 对端正在输入（瞬时状态，不落库） */
  typing: "typing",
  /** 心跳 */
  ping: "ping",
  pong: "pong",
} as const;

export type WsEventTypeValue = (typeof WsEventType)[keyof typeof WsEventType];

/** 消息类型。 */
export type MessageKind = "text" | "link" | "file";

/** 传输方向。 */
export type TransferDirection = "upload" | "download";

/** 传输状态机。 */
export type TransferState =
  | "queued"
  | "running"
  | "paused"
  | "completed"
  | "failed"
  | "canceled";

export interface ServerInfoDto {
  protocolVersion: number;
  serverId: string;
  serverName: string;
  /** 是否启用 TLS（首版恒为 false，P4 可选） */
  tls: boolean;
  pairingRequired: boolean;
}

export interface FileRefDto {
  id: string;
  name: string;
  size: number;
  mime?: string;
  sha256?: string;
  width?: number;
  height?: number;
}

export interface MessageDto {
  /** 服务端权威、单调递增；客户端以此为增量同步游标 */
  seq: number;
  id: string;
  kind: MessageKind;
  senderId: string;
  senderName: string;
  /** Unix 毫秒 */
  createdAt: number;
  text?: string;
  file?: FileRefDto;
}

export interface MessagePageDto {
  items: MessageDto[];
  /** 服务端当前最大 seq，客户端据此校准本地游标 */
  latestSeq: number;
  hasMore: boolean;
}

/** WebSocket 事件信封：`type` 决定 `payload` 的解析方式。 */
export interface WsEnvelopeDto<T = unknown> {
  type: WsEventTypeValue;
  payload?: T;
}

/** 创建上传会话的请求体。 */
export interface CreateUploadRequest {
  name: string;
  size: number;
  mime?: string;
  sha256?: string;
}

/** 创建上传会话的响应体。 */
export interface CreateUploadResponse {
  uploadId: string;
  /** 已落盘字节数；客户端据此决定从哪个 offset 续传 */
  receivedBytes: number;
  /** 建议分片大小（字节） */
  chunkSize: number;
}

/** 传输进度事件负载。 */
export interface TransferProgressPayload {
  transferId: string;
  messageId?: string;
  direction: TransferDirection;
  transferredBytes: number;
  totalBytes: number;
  state: TransferState;
}

/** WS `hello` 事件负载：连接建立后的第一条服务端消息。 */
export interface WsHelloPayload {
  deviceId: string;
  serverId: string;
  protocolVersion: number;
  /** 服务端当前最大 seq；客户端据此判断离线期间是否错过消息 */
  latestSeq: number;
  onlineCount: number;
}

/** WS 设备上线/下线事件负载（device.online / device.offline）。 */
export interface DevicePresencePayload {
  deviceId: string;
  deviceName: string;
  /** 仅 device.online 携带：广播时的在线连接数 */
  onlineCount?: number;
}

/** WS `typing` 事件负载（瞬时状态，不落库）。 */
export interface TypingPayload {
  deviceId: string;
  deviceName: string;
}

/** 配对请求。 */
export interface PairRequest {
  /** 二维码或服务端控制台给出的一次性配对码 */
  code: string;
  deviceName: string;
  platform: string;
}

/** 配对响应：长期凭据，客户端存入 Keystore/EncryptedSharedPreferences。 */
export interface PairResponse {
  deviceId: string;
  deviceToken: string;
  serverId: string;
  serverName: string;
}
