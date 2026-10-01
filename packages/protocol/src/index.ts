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
  /** 服务端按保留策略自动清除旧消息（P4-5），`payload.uptoSeq` 之前的可安全清除 */
  messagesPurged: "messages.purged",
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

/**
 * 一段文字是否「整段就是一个 http(s) 链接」。
 *
 * 这既是发送端判定 `link` 的依据，也是渲染端把消息变成可点链接的**唯一**许可条件：
 * 两个客户端必须用同一条规则，否则同一句话在手机上是链接、在网页上是纯文本。
 * 只认 http/https——`kind` 由发送方自填、服务端不做语义校验，而 `javascript:` 之类的
 * 伪协议一旦进了 `<a href>` 就是注入（Android 侧的同名实现见
 * `android/.../protocol/LinkText.kt`，改动必须两边同步）。
 */
export function isLinkText(text: string): boolean {
  return /^https?:\/\/\S+$/i.test(text.trim());
}

/** 传输方向。 */
export type TransferDirection = "upload" | "download";

/**
 * 上传会话在服务端的状态。
 *
 * 与客户端的 `TransferState` 不是一回事：后者是「任务」的本地状态（含暂停、
 * 完成等），这里是「服务端那一半会话」的状态，客户端只能读不能写。
 * 客户端要表达「暂停」，做法是什么都不做——会话留在 `open`，临时文件留在磁盘，
 * 恢复时接着追加即可。
 */
export type UploadState = "open" | "completed" | "aborted";

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
  /**
   * 服务端保留策略的历史累计水位：`seq <= purgedUpto` 的消息已从服务端删除。
   * 客户端在同步时应用它（删本地同区间的缓存行），即可补上离线期间错过的
   * `messages.purged` 事件；幂等，重复应用无副作用。`0` 表示从未清理。
   */
  purgedUpto: number;
}

/**
 * `messages.purged` 事件负载：服务端保留策略删掉了 `seq <= uptoSeq` 的消息。
 *
 * 与 `message.deleted`（用户手动清空全部、seq 不回退）不同，这是**按 seq 截断**的自动清理；
 * 客户端删掉本地 `seq <= uptoSeq` 的缓存行即可，游标无需回退（新消息的 seq 仍单调递增）。
 * 离线错过本事件的客户端会继续保留本地缓存——「客户端缓存聊天记录」本就是产品定位，
 * 服务端策略只保证磁盘不再无限增长，不追杀客户端已经拿到的数据。
 */
export interface MessagesPurgedPayload {
  uptoSeq: number;
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

/**
 * 收尾上传的请求体。
 *
 * `sha256` 是客户端对**整个文件**的自证摘要：分片接口逐片校验会让重传代价
 * 翻倍，完整性只能靠收尾这一步校验。客户端不声明时服务端只计算并存档摘要，
 * 不做校验（Web 端目前就是不声明的那个）。
 */
export interface CompleteUploadRequest {
  sha256?: string;
}

/**
 * 上传会话的可续传视图（`GET /uploads/:id`、`GET /uploads`）。
 *
 * 存在的意义只有一个：客户端进程被杀之后，本地只剩一个 `uploadId`，
 * 必须回来问服务端「你到底收了多少字节」，才能从正确的 offset 接着发。
 */
export interface UploadStatusDto {
  uploadId: string;
  name: string;
  size: number;
  mime?: string;
  /** 已落盘字节数；**这就是续传锚点** */
  receivedBytes: number;
  state: UploadState;
  /** 服务端认为还能继续追加（`open` 且尚未收满） */
  resumable: boolean;
  /** 建议分片大小（字节）；续传时客户端照此切片，不必自己硬编码 */
  chunkSize: number;
  createdAt: number;
  updatedAt: number;
}

/** 上传会话列表（`GET /uploads`）。 */
export interface UploadListDto {
  items: UploadStatusDto[];
}

/** 已配对设备（`GET /pair/devices`）。 */
export interface DeviceInfoDto {
  id: string;
  name: string;
  platform: string;
  /** Unix 毫秒 */
  createdAt: number;
  /** Unix 毫秒；从未连接过时为 null */
  lastSeenAt: number | null;
  /** 当前是否持有活跃 WebSocket 连接 */
  online: boolean;
}

/** 设备列表（`GET /pair/devices`）。 */
export interface DeviceListDto {
  items: DeviceInfoDto[];
}

/** 撤销设备（`DELETE /pair/devices/:id`）的响应体。 */
export interface DeviceRevokeResponse {
  revoked: boolean;
  deviceId: string;
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
