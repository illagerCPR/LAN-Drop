package io.github.illagercpr.landrop.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 消息方向（相对本机）。 */
object MessageDirection {
    const val INBOUND = "inbound"
    const val OUTBOUND = "outbound"
}

/** 本机侧的发送状态。 */
object MessageLocalState {
    const val SYNCED = "synced"
    const val PENDING = "pending"
    const val FAILED = "failed"
}

/**
 * 会话消息的本地缓存。
 *
 * `seq` 为服务端权威序号，唯一索引保证增量同步不会写入重复行；
 * 断线重连时以本地最大 `seq` 作为 `since` 游标拉取补偿。
 */
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["seq"], unique = true),
        // 注意：Room 的 Index 用「列名」而非 Kotlin 属性名，
        // createdAt 属性经 @ColumnInfo 映射为 created_at 列。
        Index(value = ["created_at"]),
    ],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val seq: Long,
    val kind: String,
    val text: String?,
    @ColumnInfo(name = "file_id") val fileId: String?,
    @ColumnInfo(name = "file_name") val fileName: String?,
    @ColumnInfo(name = "file_size") val fileSize: Long?,
    @ColumnInfo(name = "file_mime") val fileMime: String?,
    @ColumnInfo(name = "sender_id") val senderId: String,
    @ColumnInfo(name = "sender_name") val senderName: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val direction: String,
    @ColumnInfo(name = "local_state") val localState: String,
)
