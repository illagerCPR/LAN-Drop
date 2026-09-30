package io.github.illagercpr.landrop.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * LAN-Drop 协议 v1 —— 客户端侧模型。
 *
 * 事实源在 `packages/protocol`（TypeScript + JSON Schema）。本文件是 Kotlin 侧镜像，
 * 字段名与 `@SerialName` 必须与事实源逐字一致；后续会用生成器替换手写，避免两端漂移。
 *
 * 约定：控制面走 WebSocket（JSON），数据面走 HTTP（裸字节流，支持 Range 断点续传）。
 */
object ProtocolVersion {
    const val CURRENT: Int = 1
}

/** REST 路径，对应事实源的 `ApiPath`。 */
object ApiPath {
    const val PREFIX: String = "/api/v1"
    const val INFO: String = "$PREFIX/info"
    const val PAIR: String = "$PREFIX/pair"
    const val MESSAGES: String = "$PREFIX/messages"
    const val UPLOADS: String = "$PREFIX/uploads"
    const val FILES: String = "$PREFIX/files"
    const val WS: String = "$PREFIX/ws"
}

/** WebSocket 事件类型，取值与事实源 `WsEventType` 一一对应。 */
object WsEventType {
    /** 连接建立后的第一条服务端消息，告知身份与当前水位 */
    const val HELLO = "hello"

    /** 新消息落库（文字或文件） */
    const val MESSAGE_NEW = "message.new"

    /** 消息被撤回/删除 */
    const val MESSAGE_DELETED = "message.deleted"

    /** 服务端按保留策略自动清除旧消息（`payload.uptoSeq` 之前的可安全清除） */
    const val MESSAGES_PURGED = "messages.purged"

    /** 传输进度（大文件节流后推送） */
    const val TRANSFER_PROGRESS = "transfer.progress"

    /** 设备上线/下线 */
    const val DEVICE_ONLINE = "device.online"
    const val DEVICE_OFFLINE = "device.offline"

    /** 配对请求被批准 */
    const val PAIR_APPROVED = "pair.approved"

    /** 对端正在输入（瞬时状态，不落库） */
    const val TYPING = "typing"

    /** 心跳 */
    const val PING = "ping"
    const val PONG = "pong"
}

// ------------------------------------------------------------------ 元信息 / 配对

/** 服务端基本信息，对应 `GET /api/v1/info`。 */
@Serializable
data class ServerInfoDto(
    @SerialName("protocolVersion") val protocolVersion: Int,
    @SerialName("serverId") val serverId: String,
    @SerialName("serverName") val serverName: String,
    @SerialName("tls") val tls: Boolean = false,
    /** 服务端是否要求配对后才可收发 */
    @SerialName("pairingRequired") val pairingRequired: Boolean = true,
)

/** 配对请求，对应 `POST /api/v1/pair`。 */
@Serializable
data class PairRequestDto(
    /** 二维码或服务端控制台给出的一次性配对码 */
    @SerialName("code") val code: String,
    @SerialName("deviceName") val deviceName: String,
    @SerialName("platform") val platform: String,
)

/** 配对响应：长期凭据。 */
@Serializable
data class PairResponseDto(
    @SerialName("deviceId") val deviceId: String,
    @SerialName("deviceToken") val deviceToken: String,
    @SerialName("serverId") val serverId: String,
    @SerialName("serverName") val serverName: String,
)

// ------------------------------------------------------------------ 消息

/** 消息类型。 */
@Serializable
enum class MessageKind {
    @SerialName("text")
    TEXT,

    @SerialName("link")
    LINK,

    @SerialName("file")
    FILE,
}

/** 文件引用，嵌在消息里。 */
@Serializable
data class FileRefDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("size") val size: Long,
    @SerialName("mime") val mime: String? = null,
    @SerialName("sha256") val sha256: String? = null,
    @SerialName("width") val width: Int? = null,
    @SerialName("height") val height: Int? = null,
)

/** 一条消息（文字 / 链接 / 文件），`seq` 由服务端权威分配且单调递增。 */
@Serializable
data class MessageDto(
    @SerialName("seq") val seq: Long,
    @SerialName("id") val id: String,
    @SerialName("kind") val kind: MessageKind,
    @SerialName("senderId") val senderId: String,
    @SerialName("senderName") val senderName: String,
    @SerialName("createdAt") val createdAt: Long,
    @SerialName("text") val text: String? = null,
    @SerialName("file") val file: FileRefDto? = null,
)

/** 增量拉取响应，对应 `GET /api/v1/messages?since=<seq>`。 */
@Serializable
data class MessagePageDto(
    @SerialName("items") val items: List<MessageDto>,
    /** 服务端当前最大 seq，客户端据此校准本地游标 */
    @SerialName("latestSeq") val latestSeq: Long,
    @SerialName("hasMore") val hasMore: Boolean = false,
    /** 保留策略历史水位：`seq <= purgedUpto` 的消息服务端已删（旧服务端无此字段时默认 0） */
    @SerialName("purgedUpto") val purgedUpto: Long = 0,
)

/** 发送文字/链接的请求体，对应 `POST /api/v1/messages`。 */
@Serializable
data class SendMessageRequestDto(
    @SerialName("kind") val kind: String,
    @SerialName("text") val text: String,
)

// ------------------------------------------------------------------ 上传 / 下载

/** 创建上传会话的请求体。 */
@Serializable
data class CreateUploadRequestDto(
    @SerialName("name") val name: String,
    @SerialName("size") val size: Long,
    @SerialName("mime") val mime: String? = null,
    @SerialName("sha256") val sha256: String? = null,
)

/** 创建上传会话的响应体。 */
@Serializable
data class CreateUploadResponseDto(
    @SerialName("uploadId") val uploadId: String,
    /** 已落盘字节数；客户端据此决定从哪个 offset 续传 */
    @SerialName("receivedBytes") val receivedBytes: Long,
    /** 建议分片大小（字节） */
    @SerialName("chunkSize") val chunkSize: Long,
)

/** 分片追加的响应体，对应 `PATCH /api/v1/uploads/:id?offset=`。 */
@Serializable
data class UploadPatchResponseDto(
    @SerialName("uploadId") val uploadId: String,
    /** 服务端已确认落盘的总字节数，是下一次分片的 offset 锚点 */
    @SerialName("receivedBytes") val receivedBytes: Long,
    @SerialName("size") val size: Long,
)

/** 服务端错误体。字段随错误类型变化，按需取用。 */
@Serializable
data class ApiErrorDto(
    @SerialName("error") val error: String,
    /** 409 时回传的权威进度，客户端据此对齐 offset */
    @SerialName("receivedBytes") val receivedBytes: Long? = null,
    @SerialName("size") val size: Long? = null,
    @SerialName("state") val state: String? = null,
    @SerialName("max") val max: Long? = null,
)

/** 上传会话在服务端的状态（客户端只读）。 */
object UploadState {
    const val OPEN = "open"
    const val COMPLETED = "completed"
    const val ABORTED = "aborted"
}

/**
 * 上传会话的可续传视图，对应 `GET /api/v1/uploads/:id`。
 *
 * 进程被杀后本地只剩一个 `uploadId`，必须回来问服务端「你到底收了多少字节」，
 * [receivedBytes] 就是续传锚点。
 */
@Serializable
data class UploadStatusDto(
    @SerialName("uploadId") val uploadId: String,
    @SerialName("name") val name: String,
    @SerialName("size") val size: Long,
    @SerialName("mime") val mime: String? = null,
    @SerialName("receivedBytes") val receivedBytes: Long,
    @SerialName("state") val state: String,
    /** 服务端认为还能继续追加（open 且未收满） */
    @SerialName("resumable") val resumable: Boolean = false,
    /** 建议分片大小；续传时照此切片 */
    @SerialName("chunkSize") val chunkSize: Long = 4L * 1024 * 1024,
    @SerialName("createdAt") val createdAt: Long = 0,
    @SerialName("updatedAt") val updatedAt: Long = 0,
)

/** 上传会话列表，对应 `GET /api/v1/uploads`。 */
@Serializable
data class UploadListDto(
    @SerialName("items") val items: List<UploadStatusDto> = emptyList(),
)

// ------------------------------------------------------------------ WebSocket

/** WebSocket 事件信封。`type` 决定 `payload` 的具体类型。 */
@Serializable
data class WsEnvelopeDto<T>(
    @SerialName("type") val type: String,
    @SerialName("payload") val payload: T? = null,
)

/** WS `hello` 事件负载：连接建立后的第一条服务端消息。 */
@Serializable
data class WsHelloPayloadDto(
    @SerialName("deviceId") val deviceId: String,
    @SerialName("serverId") val serverId: String,
    @SerialName("protocolVersion") val protocolVersion: Int,
    /** 服务端当前最大 seq；客户端据此判断离线期间是否错过消息 */
    @SerialName("latestSeq") val latestSeq: Long,
    @SerialName("onlineCount") val onlineCount: Int = 0,
)

/** WS 设备上线/下线事件负载。 */
@Serializable
data class DevicePresencePayloadDto(
    @SerialName("deviceId") val deviceId: String,
    @SerialName("deviceName") val deviceName: String,
    @SerialName("onlineCount") val onlineCount: Int? = null,
)

/** WS `typing` 事件负载（瞬时状态，不落库）。 */
@Serializable
data class TypingPayloadDto(
    @SerialName("deviceId") val deviceId: String,
    @SerialName("deviceName") val deviceName: String,
)

/** WS 保留策略清理事件负载：服务端删掉了 `seq <= uptoSeq` 的消息（严格前缀删除）。 */
@Serializable
data class MessagesPurgedPayloadDto(
    @SerialName("uptoSeq") val uptoSeq: Long,
)

/** WS 传输进度负载。`state` 取值见 `data.local.TransferState`。 */
@Serializable
data class TransferProgressPayloadDto(
    @SerialName("transferId") val transferId: String,
    @SerialName("messageId") val messageId: String? = null,
    @SerialName("direction") val direction: String,
    @SerialName("transferredBytes") val transferredBytes: Long,
    @SerialName("totalBytes") val totalBytes: Long,
    @SerialName("state") val state: String,
)
