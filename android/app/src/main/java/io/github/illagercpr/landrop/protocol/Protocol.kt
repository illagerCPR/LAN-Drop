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
)

/** WebSocket 事件信封。`type` 决定 `payload` 的具体类型。 */
@Serializable
data class WsEnvelopeDto(
    @SerialName("type") val type: String,
    @SerialName("payload") val payload: String? = null,
)

object WsEventType {
    const val MESSAGE_NEW = "message.new"
    const val MESSAGE_DELETED = "message.deleted"
    const val TRANSFER_PROGRESS = "transfer.progress"
    const val DEVICE_ONLINE = "device.online"
    const val DEVICE_OFFLINE = "device.offline"
    const val PAIR_APPROVED = "pair.approved"
}
