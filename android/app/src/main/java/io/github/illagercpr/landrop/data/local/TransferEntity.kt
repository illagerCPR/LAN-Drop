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
 * 断点续传靠这三个字段撑着，缺一不可：
 *   - [localUri]：上传时是 SAF 源文件，下载时是 MediaStore 目标文件；
 *   - [transferredBytes]：本地认为的进度，恢复时还要与文件真实长度取小；
 *   - [uploadId] / [remoteFileId]：服务端那一半的句柄，凭它回去问权威进度。
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
    /** 本地文件 URI：上传方向为 SAF 源文件，下载方向为 MediaStore 目标文件 */
    @ColumnInfo(name = "local_uri") val localUri: String?,
    /** 服务端上传会话 ID（上传方向使用） */
    @ColumnInfo(name = "upload_id") val uploadId: String?,
    /** 服务端文件 ID（下载方向使用，续传时要靠它重新发起 Range 请求） */
    @ColumnInfo(name = "remote_file_id") val remoteFileId: String? = null,
    val error: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
