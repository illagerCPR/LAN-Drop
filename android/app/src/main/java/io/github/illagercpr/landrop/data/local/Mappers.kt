package io.github.illagercpr.landrop.data.local

import io.github.illagercpr.landrop.protocol.MessageDto

/**
 * 协议模型 → 本地缓存行。
 *
 * `direction` 不由协议携带（服务端视角没有「谁的本机」），而是拿发送者 ID
 * 与本机设备 ID 比对得出——同一份数据在不同设备上渲染方向相反是正确的。
 */
fun MessageDto.toEntity(myDeviceId: String): MessageEntity = MessageEntity(
    id = id,
    seq = seq,
    kind = kind.name.lowercase(),
    text = text,
    fileId = file?.id,
    fileName = file?.name,
    fileSize = file?.size,
    fileMime = file?.mime,
    senderId = senderId,
    senderName = senderName,
    createdAt = createdAt,
    direction = if (senderId == myDeviceId) MessageDirection.OUTBOUND else MessageDirection.INBOUND,
    localState = MessageLocalState.SYNCED,
)
