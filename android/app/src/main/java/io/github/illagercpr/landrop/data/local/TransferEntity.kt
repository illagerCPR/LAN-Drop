package io.github.illagercpr.landrop.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 传输方向。 */
object TransferDirection {
    const val UPLOAD = "upload"
    const val DOWNLOAD = "download"
}

/** 传输状态机。 */
object TransferState {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val PAUSED = "paused"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELED = "canceled"
}

/**
 * 传输记录（客户端本地缓存，用于「传输记录」页与断点续传恢复）。
 *
 * `transferredBytes` 是续传的锚点：进程被杀后凭它 + 服务端上传会话重新对齐 offset。
 */
@Entity(
    tableName = "transfers",
    indices = [
        Index(value = ["message_id"]),
        Index(value = ["state"]),
    ],
)
data class TransferEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "message_id") val messageId: String,
    val direction: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "total_bytes") val totalBytes: Long,
    @ColumnInfo(name = "transferred_bytes") val transferredBytes: Long,
    val state: String,
    /** 落盘后的本地 URI（下载完成时写入） */
    @ColumnInfo(name = "local_uri") val localUri: String?,
    /** 服务端上传会话 ID（上传方向使用） */
    @ColumnInfo(name = "upload_id") val uploadId: String?,
    val error: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
