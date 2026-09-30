package io.github.illagercpr.landrop.net

import io.github.illagercpr.landrop.protocol.DevicePresencePayloadDto
import io.github.illagercpr.landrop.protocol.MessageDto
import io.github.illagercpr.landrop.protocol.TypingPayloadDto
import io.github.illagercpr.landrop.protocol.WsEventType
import io.github.illagercpr.landrop.protocol.WsHelloPayloadDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 解析 WebSocket 事件信封。
 *
 * 之所以先解成 [JsonObject] 再按 `type` 二次解码，而不是直接反序列化泛型信封：
 * `payload` 的具体类型由 `type` 决定，泛型信息在运行时已擦除，直接解会丢掉类型。
 *
 * 返回值：
 *  - 未知 `type` → null（协议向前兼容：服务端加事件不该让老客户端崩）
 *  - JSON 畸形或 payload 结构与声明不符 → null（同样选择静默忽略）
 */
internal fun parseWsEnvelope(raw: String, json: Json = ProtocolJson): WsEvent? {
    val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
    val type = root["type"]?.jsonPrimitive?.contentOrNull ?: return null
    val payload = root["payload"] as? JsonObject

    return runCatching {
        when (type) {
            WsEventType.HELLO -> payload?.let {
                WsEvent.Hello(json.decodeFromJsonElement(WsHelloPayloadDto.serializer(), it))
            }

            WsEventType.MESSAGE_NEW -> payload?.let {
                WsEvent.MessageNew(json.decodeFromJsonElement(MessageDto.serializer(), it))
            }

            WsEventType.MESSAGE_DELETED -> WsEvent.MessagesCleared

            WsEventType.DEVICE_ONLINE -> presence(json, payload, online = true)
            WsEventType.DEVICE_OFFLINE -> presence(json, payload, online = false)

            WsEventType.TYPING -> payload?.let {
                val dto = json.decodeFromJsonElement(TypingPayloadDto.serializer(), it)
                WsEvent.Typing(dto.deviceName)
            }

            else -> null
        }
    }.getOrNull()
}

private fun presence(json: Json, payload: JsonObject?, online: Boolean): WsEvent? {
    val dto = payload?.let {
        json.decodeFromJsonElement(DevicePresencePayloadDto.serializer(), it)
    } ?: return null

    return WsEvent.Presence(
        online = online,
        deviceName = dto.deviceName,
        onlineCount = dto.onlineCount,
    )
}
