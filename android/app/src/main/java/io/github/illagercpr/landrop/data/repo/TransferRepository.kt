package io.github.illagercpr.landrop.data.repo

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import io.github.illagercpr.landrop.data.local.LanDropDatabase
import io.github.illagercpr.landrop.data.local.TransferDirection
import io.github.illagercpr.landrop.data.local.TransferEntity
import io.github.illagercpr.landrop.data.local.TransferState
import io.github.illagercpr.landrop.data.local.toEntity
import io.github.illagercpr.landrop.data.prefs.ConnectionStore
import io.github.illagercpr.landrop.net.ApiException
import io.github.illagercpr.landrop.net.LanDropApi
import io.github.illagercpr.landrop.net.readChunk
import io.github.illagercpr.landrop.net.toUserMessage
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** SAF 选中文件的元信息。 */
data class PickedFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mime: String?,
)

/**
 * 文件传输：上传（手机 → PC）与下载（PC → 手机）。
 *
 * 上传走「创建会话 → 分片追加 → 收尾」三步，服务端只接受 offset 恰好等于
 * 已收字节数的分片；一旦不一致（409）就按服务端回传的真实进度重新对齐再发，
 * 这套语义天然支持将来做暂停/恢复。
 *
 * 下载落到系统「下载/LAN-Drop」，用 MediaStore 写，因此不需要任何存储权限
 * （Android 10+ 应用只能往自己插入的 MediaStore 行里写）。
 */
class TransferRepository(
    context: Context,
    private val store: ConnectionStore,
    private val db: LanDropDatabase,
    private val api: LanDropApi,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val dao = db.transferDao()
    private val messageDao = db.messageDao()

    /** 传输记录（新 → 旧）。 */
    val transfers: Flow<List<TransferEntity>> = dao.observeAll()

    /** 正在进行的任务，用于取消。 */
    private val jobs = mutableMapOf<String, Job>()

    // ------------------------------------------------------------------ 上传

    /**
     * 把 SAF 选中的文件发给服务端。
     *
     * 失败不抛出（错误写进传输记录的 `error` 字段），让 UI 只订阅一条 Flow 即可；
     * 这是与 [MessageRepository.sendText] 有意的差异——文件传输耗时长、失败面广，
     * 用「记录 + 状态」表达比异常更适合。
     */
    fun upload(uri: Uri, mime: String?) {
        val transferId = UUID.randomUUID().toString()
        val job = scope.launch {
            runUpload(transferId, uri, mime)
        }
        jobs[transferId] = job
        job.invokeOnCompletion { jobs.remove(transferId) }
    }

    private suspend fun runUpload(transferId: String, uri: Uri, mime: String?) =
        withContext(Dispatchers.IO) {
            val connection = store.connection.value ?: return@withContext

            val picked = runCatching { resolvePickedFile(uri) }.getOrElse { error ->
                recordFailure(transferId, "读取文件失败：${error.toUserMessage()}")
                return@withContext
            }

            if (picked.size <= 0) {
                recordFailure(transferId, "无法确定文件大小（${picked.name}）")
                return@withContext
            }

            var entity = TransferEntity(
                id = transferId,
                messageId = "",
                direction = TransferDirection.UPLOAD,
                fileName = picked.name,
                totalBytes = picked.size,
                transferredBytes = 0,
                state = TransferState.QUEUED,
                localUri = null,
                uploadId = null,
                error = null,
                updatedAt = System.currentTimeMillis(),
            )
            dao.upsert(entity)

            try {
                val session = api.createUpload(connection, picked.name, picked.size, mime ?: picked.mime)
                entity = entity.copy(
                    uploadId = session.uploadId,
                    state = TransferState.RUNNING,
                    updatedAt = System.currentTimeMillis(),
                )
                dao.upsert(entity)

                var offset = session.receivedBytes
                var realignments = 0
                var stream = openStream(uri)
                try {
                    stream.skipFully(offset)
                    val buffer = ByteArray(session.chunkSize.toInt())

                    while (offset < picked.size) {
                        coroutineContext.ensureActive()

                        val read = stream.readChunk(buffer)
                        if (read <= 0) break

                        val response = try {
                            api.uploadChunk(connection, session.uploadId, offset, buffer, read)
                        } catch (e: ApiException) {
                            val authoritative = e.errorBody?.receivedBytes
                            if (e.statusCode == 409 && authoritative != null && realignments < MAX_REALIGN) {
                                // 服务端进度与本地不一致：跳回权威位置重发这一片
                                realignments++
                                offset = authoritative
                                entity = entity.copy(
                                    transferredBytes = offset,
                                    updatedAt = System.currentTimeMillis(),
                                )
                                dao.upsert(entity)

                                stream.close()
                                stream = openStream(uri)
                                stream.skipFully(offset)
                                continue
                            }
                            throw e
                        }

                        offset = response.receivedBytes
                        entity = entity.copy(
                            transferredBytes = offset,
                            updatedAt = System.currentTimeMillis(),
                        )
                        dao.upsert(entity)
                    }
                } finally {
                    runCatching { stream.close() }
                }

                if (offset < picked.size) {
                    recordFailure(
                        transferId,
                        "文件读取中断：已发送 $offset / ${picked.size} 字节",
                    )
                    return@withContext
                }

                val message = api.completeUpload(connection, session.uploadId)
                messageDao.upsert(message.toEntity(connection.deviceId))

                dao.upsert(
                    entity.copy(
                        messageId = message.id,
                        transferredBytes = picked.size,
                        state = TransferState.COMPLETED,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            } catch (e: CancellationException) {
                // 用户取消：把本地记录收尾，服务端会话尽力中止。
                // 协程已被取消，普通挂起调用会立刻抛出，因此必须放进 NonCancellable。
                withContext(NonCancellable) { markCanceled(transferId, entity.uploadId) }
                throw e
            } catch (e: Exception) {
                entity.uploadId?.let { runCatching { api.abortUpload(connection, it) } }
                dao.upsert(
                    entity.copy(
                        state = TransferState.FAILED,
                        error = e.toUserMessage(),
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }

    // ------------------------------------------------------------------ 下载

    /**
     * 把服务端文件下载到系统「下载/LAN-Drop」。
     *
     * [messageId] 仅用于把传输记录关联回消息，可为空。
     */
    fun download(fileId: String, fileName: String, mime: String?, size: Long, messageId: String) {
        val transferId = UUID.randomUUID().toString()
        val job = scope.launch {
            runDownload(transferId, fileId, fileName, mime, size, messageId)
        }
        jobs[transferId] = job
        job.invokeOnCompletion { jobs.remove(transferId) }
    }

    private suspend fun runDownload(
        transferId: String,
        fileId: String,
        fileName: String,
        mime: String?,
        size: Long,
        messageId: String,
    ) = withContext(Dispatchers.IO) {
        val connection = store.connection.value ?: return@withContext

        var entity = TransferEntity(
            id = transferId,
            messageId = messageId,
            direction = TransferDirection.DOWNLOAD,
            fileName = fileName,
            totalBytes = size,
            transferredBytes = 0,
            state = TransferState.RUNNING,
            localUri = null,
            uploadId = null,
            error = null,
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsert(entity)

        var target: Uri? = null
        try {
            target = createDownloadTarget(fileName, mime)
                ?: throw IllegalStateException("无法在「下载/LAN-Drop」创建文件")

            // 立刻记下目标 URI：进程若在下载途中被杀，启动收尾时才能删掉这条
            // MediaStore 的 IS_PENDING 隐藏行，不留半截文件。
            entity = entity.copy(localUri = target.toString())
            dao.upsert(entity)

            val response = api.openDownload(connection, fileId, rangeFrom = null)
            response.use { resp ->
                val body = resp.body
                val total = body.contentLength().takeIf { it >= 0 } ?: size
                entity = entity.copy(totalBytes = total)
                dao.upsert(entity)

                val sink = resolver.openOutputStream(target)
                    ?: throw IllegalStateException("无法写入目标文件")

                sink.use { out ->
                    val source = body.byteStream()
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    var copied = 0L
                    var reported = 0L

                    while (true) {
                        coroutineContext.ensureActive()

                        val read = source.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        copied += read

                        if (copied - reported >= PROGRESS_STEP_BYTES) {
                            reported = copied
                            entity = entity.copy(
                                transferredBytes = copied,
                                updatedAt = System.currentTimeMillis(),
                            )
                            dao.upsert(entity)
                        }
                    }
                    out.flush()

                    entity = entity.copy(transferredBytes = copied)
                }
            }

            // 从 pending 转为对用户可见
            resolver.update(
                target,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )

            dao.upsert(
                entity.copy(
                    state = TransferState.COMPLETED,
                    localUri = target.toString(),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        } catch (e: CancellationException) {
            target?.let { runCatching { resolver.delete(it, null, null) } }
            withContext(NonCancellable) { markCanceled(transferId, uploadId = null) }
            throw e
        } catch (e: Exception) {
            target?.let { runCatching { resolver.delete(it, null, null) } }
            dao.upsert(
                entity.copy(
                    state = TransferState.FAILED,
                    error = e.toUserMessage(),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    // ------------------------------------------------------------------ 取消

    fun cancel(transferId: String) {
        jobs[transferId]?.cancel()
    }

    /**
     * 启动时收尾「上次进程被杀而中断的传输」。
     *
     * 没有这一步，被系统杀掉进程后传输记录会永远停在「进行中」：界面上是一条
     * 永不结束的进度条，服务端还留着一个 open 会话和 `.part` 临时文件
     * （要等 24h 的 TTL 才被回收）。
     *
     * 首版不做跨进程续传，因此这里统一标记为失败、让用户重新发起；
     * 等 P3 做断点续传时，这里会改成「读回服务端进度并恢复」。
     */
    suspend fun reconcileInterruptedTransfers() = withContext(Dispatchers.IO) {
        val connection = store.connection.value
        val active = dao.loadByStates(listOf(TransferState.QUEUED, TransferState.RUNNING))
        if (active.isEmpty()) return@withContext

        for (transfer in active) {
            if (transfer.direction == TransferDirection.UPLOAD &&
                transfer.uploadId != null &&
                connection != null
            ) {
                // 顺手让服务端删掉半截临时分片，别留孤儿文件
                runCatching { api.abortUpload(connection, transfer.uploadId) }
            }

            if (transfer.direction == TransferDirection.DOWNLOAD && transfer.localUri != null) {
                // 未完成的下载在 MediaStore 里是 IS_PENDING 的隐藏行，删掉它
                runCatching { resolver.delete(Uri.parse(transfer.localUri), null, null) }
            }

            dao.upsert(
                transfer.copy(
                    state = TransferState.FAILED,
                    error = INTERRUPTED_MESSAGE,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    private suspend fun markCanceled(transferId: String, uploadId: String?) {
        uploadId?.let { id ->
            store.connection.value?.let { connection ->
                runCatching { api.abortUpload(connection, id) }
            }
        }
        val current = dao.findById(transferId) ?: return
        dao.upsert(
            current.copy(state = TransferState.CANCELED, updatedAt = System.currentTimeMillis()),
        )
    }

    // ------------------------------------------------------------------ 工具

    /** 读 SAF Uri 的显示名与大小；大小缺失时退回文件描述符长度。 */
    fun resolvePickedFile(uri: Uri): PickedFile {
        var name: String? = null
        var size: Long? = null

        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    .takeIf { it >= 0 }
                    ?.let { name = cursor.getString(it) }

                cursor.getColumnIndex(OpenableColumns.SIZE)
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { size = cursor.getLong(it) }
            }
        }

        val resolvedSize = size
            ?: runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull()
            ?: -1L

        return PickedFile(
            uri = uri,
            name = name?.takeIf { it.isNotBlank() } ?: "file-${System.currentTimeMillis()}",
            size = resolvedSize,
            mime = resolver.getType(uri),
        )
    }

    private fun openStream(uri: Uri): InputStream =
        resolver.openInputStream(uri) ?: throw IllegalStateException("无法打开所选文件")

    private fun recordFailure(transferId: String, message: String) {
        scope.launch {
            val existing = dao.findById(transferId)
            dao.upsert(
                TransferEntity(
                    id = transferId,
                    messageId = existing?.messageId.orEmpty(),
                    direction = existing?.direction ?: TransferDirection.UPLOAD,
                    fileName = existing?.fileName ?: "未知文件",
                    totalBytes = existing?.totalBytes ?: 0,
                    transferredBytes = existing?.transferredBytes ?: 0,
                    state = TransferState.FAILED,
                    localUri = null,
                    uploadId = existing?.uploadId,
                    error = message,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun createDownloadTarget(fileName: String, mime: String?): Uri? {
        val displayName = uniqueDisplayName(sanitize(fileName))
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(
                MediaStore.Downloads.MIME_TYPE,
                mime?.takeIf { it.isNotBlank() } ?: "application/octet-stream",
            )
            put(MediaStore.Downloads.RELATIVE_PATH, DOWNLOAD_SUBDIR)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        return resolver.insert(downloadCollection(), values)
    }

    /** 重名时追加 ` (1)`、` (2)`……避免 MediaStore 插入冲突或静默改名。 */
    private fun uniqueDisplayName(name: String): String {
        val existing = existingDownloadNames()
        if (name !in existing) return name

        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""

        for (index in 1..MAX_NAME_ATTEMPTS) {
            val candidate = "$base ($index)$ext"
            if (candidate !in existing) return candidate
        }
        return "$base-${System.currentTimeMillis()}$ext"
    }

    private fun existingDownloadNames(): Set<String> {
        val names = mutableSetOf<String>()
        val projection = arrayOf(MediaStore.Downloads.DISPLAY_NAME)
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"

        resolver.query(
            downloadCollection(),
            projection,
            selection,
            arrayOf("$DOWNLOAD_SUBDIR%"),
            null,
        )?.use { cursor ->
            val column = cursor.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
            if (column >= 0) {
                while (cursor.moveToNext()) {
                    cursor.getString(column)?.let(names::add)
                }
            }
        }
        return names
    }

    private fun downloadCollection(): Uri =
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** 去掉路径分隔符等对文件系统不友好的字符（服务端也会清洗，这里是第二道防线）。 */
    private fun sanitize(raw: String): String {
        val cleaned = raw.replace(Regex("[/\\\\:*?\"<>|\\u0000-\\u001f]"), "_").trim()
        return cleaned.ifEmpty { "file-${System.currentTimeMillis()}" }.take(MAX_NAME_LENGTH)
    }

    private companion object {
        /** 与 `Environment.DIRECTORY_DOWNLOADS` 对应的相对路径。 */
        const val DOWNLOAD_SUBDIR = "Download/LAN-Drop"

        /** 传输被进程死亡打断时的收尾文案（P3 做续传后会被「已恢复」取代）。 */
        const val INTERRUPTED_MESSAGE = "传输因应用退出而中断，请重新发起"

        const val DOWNLOAD_BUFFER_SIZE = 64 * 1024

        /** 下载进度写库的步长：1 MiB 一次，避免每 64 KiB 就写一次 SQLite。 */
        const val PROGRESS_STEP_BYTES = 1024L * 1024L

        /** 409 重对齐的最大次数，防止服务端行为异常时死循环。 */
        const val MAX_REALIGN = 3

        const val MAX_NAME_ATTEMPTS = 50
        const val MAX_NAME_LENGTH = 120
    }
}

/** `InputStream.skip` 不保证跳过请求的字节数，这里循环跳到目标位置。 */
private fun InputStream.skipFully(target: Long) {
    var remaining = target
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
            continue
        }
        // 部分流不支持 skip，退回逐字节读取
        if (read() < 0) break
        remaining--
    }
}
