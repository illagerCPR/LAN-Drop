package io.github.illagercpr.landrop.data.repo

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import io.github.illagercpr.landrop.data.local.LanDropDatabase
import io.github.illagercpr.landrop.data.local.TransferDirection
import io.github.illagercpr.landrop.data.local.TransferEntity
import io.github.illagercpr.landrop.data.local.TransferState
import io.github.illagercpr.landrop.data.local.toEntity
import io.github.illagercpr.landrop.data.prefs.Connection
import io.github.illagercpr.landrop.data.prefs.ConnectionStore
import io.github.illagercpr.landrop.net.ApiException
import io.github.illagercpr.landrop.net.LanDropApi
import io.github.illagercpr.landrop.net.readChunk
import io.github.illagercpr.landrop.net.toUserMessage
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** SAF 选中文件的元信息。 */
data class PickedFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mime: String?,
)

/**
 * 前台服务的拉起入口。
 *
 * 抽成接口是为了让仓储层只说「有活了」，不依赖 Android 的 Service 生命周期：
 * 服务起来之后自己去订阅 Room 拿进度，仓储不需要向它推送任何东西。
 */
fun interface TransferServiceLauncher {
    fun ensureRunning()
}

/**
 * 文件传输：上传（手机 → PC）与下载（PC → 手机），支持暂停与断点续传。
 *
 * ## 断点续传的两半
 *
 * 传输的「真相」分散在两端，恢复时必须让权威的那一半说话：
 *   - **上传**：权威进度在服务端（`GET /uploads/:id` 的 `receivedBytes`），
 *     本地 `transferredBytes` 只是给界面看的估计值。恢复时按服务端给的 offset
 *     重新定位输入流；服务端在追加前会把自己那侧的残字节截掉。
 *   - **下载**：权威进度在本地（MediaStore 文件的真实长度），恢复时用它向服务端
 *     发 `Range`，并把自己这侧的残字节截掉。
 *
 * 两端共享同一条铁律：**开始写之前，先把自己这边的长度对齐到权威 offset**。
 * 少做任何一半，续传都会静默写坏文件——而且要到全部传完校验 sha256 时才暴露。
 *
 * ## 「暂停」是怎么实现的
 *
 * 暂停 = 取消协程，但**不动两端的现场**：服务端会话留在 `open`、临时分片留在磁盘，
 * 本地的 MediaStore 半成品也留在原地（仍是隐藏的 `IS_PENDING` 行）。
 * 因为「暂停」和「取消」在协程里都表现为 [CancellationException]，靠 [intents]
 * 里记的意图区分二者——这是本类唯一一处「取消语义不能只看异常类型」的地方。
 */
class TransferRepository(
    context: Context,
    private val store: ConnectionStore,
    private val db: LanDropDatabase,
    private val api: LanDropApi,
    private val scope: CoroutineScope,
    private val foreground: TransferServiceLauncher,
) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val dao = db.transferDao()
    private val messageDao = db.messageDao()

    /** 传输记录（新 → 旧）。 */
    val transfers: Flow<List<TransferEntity>> = dao.observeAll()

    /** 正在跑的协程，用于暂停/取消。 */
    private val jobs = mutableMapOf<String, Job>()

    /**
     * 传输要连的连接：恢复任务按记录里的 serverId 找它自己的服务端，
     * 新任务（serverId 为空）用当前活动服务端。任务归属在创建时定格，
     * 之后切换服务端不影响在途任务连谁——凭据也只对各自服务端有效。
     */
    private fun connectionFor(serverId: String?): Connection? = when {
        serverId.isNullOrBlank() -> store.connection.value
        else -> store.byServerId(serverId) ?: store.connection.value
    }

    /**
     * 上传串行闸门。
     *
     * 上传一律排队：单发与批量共用同一把锁，避免「多选五个文件同时开五条连接」
     * 把局域网带宽切成五份、并堆五条前台服务通知。下载不走这把锁（各下各的互不影响）。
     */
    private val uploadQueue = Mutex()

    /**
     * 协程被取消时该怎么收尾。
     *
     * 暂停与取消在协程层面完全一样（都是 [CancellationException]），
     * 但一个要保住现场、一个要清掉现场，只能靠发起时记下的意图区分。
     */
    private val intents = mutableMapOf<String, CancelIntent>()

    private enum class CancelIntent { PAUSE, CANCEL }

    // ------------------------------------------------------------------ 上传

    /**
     * 把 SAF 选中的文件发给服务端。
     *
     * 失败不抛出（错误写进传输记录的 `error` 字段），让 UI 只订阅一条 Flow 即可；
     * 这是与 [MessageRepository.sendText] 有意的差异——文件传输耗时长、失败面广，
     * 用「记录 + 状态」表达比异常更适合。
     */
    fun upload(uri: Uri, mime: String?) {
        scope.launch {
            uploadQueue.withLock {
                startUpload(uri, mime)
            }
        }
    }

    /**
     * 批量上传（应用内多选、系统分享多文件）。
     *
     * **顺序发送，不并发**：局域网带宽就是瓶颈，同时开三条只会让每条都慢三倍，
     * 还会同时堆三条前台服务通知；顺序发送还让接收端看到的顺序与用户选择顺序一致。
     * 整批共用 [uploadQueue] 的锁，所以两批之间也不会互相插队。
     */
    fun uploadAll(uris: List<Uri>) {
        if (uris.isEmpty()) return

        scope.launch {
            uploadQueue.withLock {
                for (uri in uris) {
                    // 批内前一条失败不该拖累后面：startUpload 内部把错误写进记录，
                    // 单条失败返回后继续下一条（用户在传输列表里逐条看到结果）。
                    startUpload(uri, mime = null)
                }
            }
        }
    }

    private suspend fun startUpload(uri: Uri, mime: String?) {
        // SAF 给的读权限默认只活到本进程结束。不落持久授权，进程被系统杀掉之后
        // 就算记着进度也打不开源文件，续传无从谈起。
        //
        // 系统分享进来的 URI 落不了持久授权（那是临时 grant），runCatching 会静默跳过，
        // 于是它的可续传范围就只剩「本进程还活着」——见 share/ShareInbox.kt 的说明。
        takePersistableRead(uri)

        val id = UUID.randomUUID().toString()
        val job = startJob(id) { runUpload(id, uri, mime, existing = null) }
        // 等这一条跑完再发下一条，批内顺序即用户选择顺序
        job?.join()
    }

    /**
     * 从一条已有的传输记录恢复。
     *
     * 入口只有这一个（不分上传/下载），因为调用方——传输记录页的「继续」按钮和
     * 启动收尾逻辑——并不关心方向，只认记录里的现场信息。
     */
    fun resume(transferId: String) {
        val previous = jobs[transferId]

        // 上一轮还在「取消」收尾时不要抢跑（现场正在被清掉）；
        // 暂停收尾则可以排队等它，否则用户连点「继续」会静默没反应。
        if (previous != null && intents[transferId] != CancelIntent.PAUSE) return

        // 恢复同样要把前台服务叫起来，否则「锁屏中点继续」会在几秒后被系统冻住。
        // 放在 launch 之前：先有保护再开工，别指望那几毫秒里系统不会冻结进程。
        foreground.ensureRunning()

        val job = scope.launch {
            previous?.join()
            // 重新读一次记录：收尾逻辑可能刚把状态改掉
            val entity = dao.findById(transferId) ?: return@launch
            resumeFrom(entity)
        }

        jobs[transferId] = job
        job.invokeOnCompletion { if (jobs[transferId] === job) jobs.remove(transferId) }
    }

    private suspend fun resumeFrom(entity: TransferEntity) {
        when (entity.direction) {
            TransferDirection.UPLOAD -> {
                val uri = entity.localUri?.let(Uri::parse)
                if (uri == null) {
                    fail(entity, "源文件信息已丢失，请重新选择文件发送")
                    return
                }
                runUpload(entity.id, uri, null, existing = entity)
            }

            else -> {
                val fileId = entity.remoteFileId
                if (fileId == null) {
                    fail(entity, "缺少服务端文件标识，请重新发起下载")
                    return
                }
                runDownload(
                    transferId = entity.id,
                    fileId = fileId,
                    fileName = entity.fileName,
                    mime = null,
                    size = entity.totalBytes,
                    messageId = entity.messageId,
                    existing = entity,
                )
            }
        }
    }

    /**
     * 上传主流程。[existing] 非空表示这是「恢复」而不是新任务：
     * 会先问服务端收了多少，再决定是从头来还是接着传。
     */
    private suspend fun runUpload(
        transferId: String,
        uri: Uri,
        mime: String?,
        existing: TransferEntity?,
    ) = withContext(Dispatchers.IO) {
        // 恢复任务连「任务自己的」服务端；新任务用当前活动服务端
        val connection = connectionFor(existing?.serverId) ?: return@withContext

        val picked = runCatching { resolvePickedFile(uri) }.getOrElse { error ->
            failBeforeStart(transferId, existing, "读取文件失败：${error.toUserMessage()}", connection.serverId)
            return@withContext
        }

        // 只有「大小解析不出来」（resolvePickedFile 以 -1 表示）才失败；
        // 0 是合法的空文件，走「无分片、直接收尾」路径，服务端同样支持。
        if (picked.size < 0) {
            failBeforeStart(transferId, existing, "无法确定文件大小（${picked.name}）", connection.serverId)
            return@withContext
        }

        var entity = existing?.copy(
            fileName = picked.name,
            totalBytes = picked.size,
            localUri = existing.localUri ?: uri.toString(),
            state = TransferState.RUNNING,
            error = null,
            updatedAt = now(),
        ) ?: TransferEntity(
            id = transferId,
            serverId = connection.serverId,
            messageId = "",
            direction = TransferDirection.UPLOAD,
            fileName = picked.name,
            totalBytes = picked.size,
            transferredBytes = 0,
            state = TransferState.QUEUED,
            localUri = uri.toString(),
            uploadId = null,
            remoteFileId = null,
            error = null,
            updatedAt = now(),
        )
        dao.upsert(entity)

        try {
            // 先问服务端「你收了多少」——这是上传侧唯一的权威进度
            val resumed = entity.uploadId?.let { sessionOffset(connection, it, picked.size) }

            val uploadId: String
            var offset: Long
            val chunkSize: Long

            if (resumed != null) {
                uploadId = resumed.uploadId
                offset = resumed.offset
                chunkSize = resumed.chunkSize
            } else {
                // 会话被回收/中止，或本地文件换了内容：开个新会话从 0 开始
                val session = api.createUpload(connection, picked.name, picked.size, mime ?: picked.mime)
                uploadId = session.uploadId
                offset = session.receivedBytes
                chunkSize = session.chunkSize
            }

            entity = entity.copy(
                uploadId = uploadId,
                transferredBytes = offset,
                state = TransferState.RUNNING,
                updatedAt = now(),
            )
            dao.upsert(entity)

            var stream = openSource(uri)
            // 整文件 sha256：边传边算，收尾时随 complete 自证（服务端 422 兜底坏字节）。
            // digestCovered 记录摘要已覆盖到本地文件的哪个字节，与服务端已确认进度同义。
            val digest = MessageDigest.getInstance("SHA-256")
            var digestCovered = 0L
            try {
                if (offset > 0) {
                    // 续传会话：0..offset 早已在服务端落盘，但本进程没有任何摘要记忆
                    // ——只有把本地文件这一段重读一遍补进摘要，收尾时才能交出完整
                    // 指纹。这就是「选项 B」的代价：恢复一次多读一遍前缀。
                    seedDigest(stream, digest, offset)
                    digestCovered = offset
                }
                val buffer = ByteArray(chunkSize.coerceIn(MIN_CHUNK, MAX_CHUNK).toInt())
                var realignments = 0

                while (offset < picked.size) {
                    coroutineContext.ensureActive()

                    val read = stream.readChunk(buffer)
                    if (read <= 0) break

                    val response = try {
                        api.uploadChunk(connection, uploadId, offset, buffer, read)
                    } catch (e: ApiException) {
                        val authoritative = e.errorBody?.receivedBytes
                        if (e.statusCode == 409 && authoritative != null && realignments < MAX_REALIGN) {
                            // 服务端进度与本地不一致：跳回权威位置重发这一片。
                            // 摘要覆盖区间一并对齐：服务端超前的那段从本地文件补读进
                            // 摘要；服务端回退则说明摘要里已混入未接受的字节，从头重算。
                            realignments++
                            offset = authoritative
                            entity = entity.copy(transferredBytes = offset, updatedAt = now())
                            dao.upsert(entity)

                            stream.close()
                            stream = openSource(uri)
                            if (offset > digestCovered) {
                                stream.skipFully(digestCovered)
                                seedDigest(stream, digest, offset - digestCovered)
                            } else {
                                digest.reset()
                                seedDigest(stream, digest, offset)
                            }
                            digestCovered = offset
                            continue
                        }
                        throw e
                    }

                    offset = response.receivedBytes
                    // 摘要只吃「服务端确认落盘」的字节——被 409 拒掉的重发不能进摘要
                    digest.update(buffer, 0, read)
                    digestCovered = offset
                    entity = entity.copy(transferredBytes = offset, updatedAt = now())
                    dao.upsert(entity)
                }
            } finally {
                runCatching { stream.close() }
            }

            if (offset < picked.size) {
                fail(entity, "文件读取中断：已发送 $offset / ${picked.size} 字节")
                return@withContext
            }

            val message = api.completeUpload(connection, uploadId, digest.digest().toHexString())
            messageDao.upsert(message.toEntity(connection.serverId, connection.deviceId))

            dao.upsert(
                entity.copy(
                    messageId = message.id,
                    transferredBytes = picked.size,
                    state = TransferState.COMPLETED,
                    error = null,
                    updatedAt = now(),
                ),
            )
            // 传完了就不再需要这个 URI 的长期读权限，还给系统
            releasePersistableRead(uri)
        } catch (e: CancellationException) {
            // 协程已被取消，普通挂起调用会立刻抛出，因此必须放进 NonCancellable
            withContext(NonCancellable) { settleCancellation(transferId) }
            throw e
        } catch (e: Exception) {
            // 网络抖动不该毁掉已经传了几百 MB 的进度：只要服务端会话还开着，
            // 就停在「已暂停」等用户点一下继续，而不是判死刑。
            val openNow = if (e.isPermanent()) null else {
                entity.uploadId?.let { sessionOffset(connection, it, picked.size) }
            }

            if (openNow != null) {
                dao.upsert(
                    entity.copy(
                        state = TransferState.PAUSED,
                        transferredBytes = openNow.offset,
                        error = "传输中断（${e.toUserMessage()}），可继续",
                        updatedAt = now(),
                    ),
                )
            } else {
                entity.uploadId?.let { runCatching { api.abortUpload(connection, it) } }
                dao.upsert(
                    entity.copy(
                        state = TransferState.FAILED,
                        error = e.toUserMessage(),
                        updatedAt = now(),
                    ),
                )
            }
        }
    }

    /**
     * 询问服务端某个上传会话的权威进度；不可续传时返回 null。
     *
     * 四种情况都要当成「从头来」：会话被 24h TTL 回收（404）、已被中止、
     * 尚未收满但已结束，或本地文件大小与当初声明的不同（文件被改过，
     * 接着传只会得到两个版本拼起来的错文件）。
     */
    private suspend fun sessionOffset(
        connection: Connection,
        uploadId: String,
        localSize: Long,
    ): ResumedSession? {
        val status = runCatching { api.uploadStatus(connection, uploadId) }.getOrNull() ?: return null
        if (!status.resumable) return null
        if (status.size != localSize) return null

        return ResumedSession(
            uploadId = status.uploadId,
            offset = status.receivedBytes.coerceIn(0, localSize),
            chunkSize = status.chunkSize,
        )
    }

    private data class ResumedSession(val uploadId: String, val offset: Long, val chunkSize: Long)

    // ------------------------------------------------------------------ 下载

    /**
     * 把服务端文件下载到系统「下载/LAN-Drop」。
     *
     * [messageId] 仅用于把传输记录关联回消息，可为空。
     */
    fun download(fileId: String, fileName: String, mime: String?, size: Long, messageId: String) {
        startJob(UUID.randomUUID().toString()) { id ->
            runDownload(id, fileId, fileName, mime, size, messageId, existing = null)
        }
    }

    private suspend fun runDownload(
        transferId: String,
        fileId: String,
        fileName: String,
        mime: String?,
        size: Long,
        messageId: String,
        existing: TransferEntity?,
    ) = withContext(Dispatchers.IO) {
        // 恢复任务连「任务自己的」服务端；新任务用当前活动服务端
        val connection = connectionFor(existing?.serverId) ?: return@withContext

        var entity = existing?.copy(
            state = TransferState.RUNNING,
            error = null,
            updatedAt = now(),
        ) ?: TransferEntity(
            id = transferId,
            serverId = connection.serverId,
            messageId = messageId,
            direction = TransferDirection.DOWNLOAD,
            fileName = fileName,
            totalBytes = size,
            transferredBytes = 0,
            state = TransferState.RUNNING,
            localUri = null,
            uploadId = null,
            remoteFileId = fileId,
            error = null,
            updatedAt = now(),
        )
        dao.upsert(entity)

        var target: Uri? = entity.localUri?.let(Uri::parse)

        try {
            if (target == null) {
                target = createDownloadTarget(fileName, mime)
                    ?: throw IllegalStateException("无法在「下载/LAN-Drop」创建文件")

                // 立刻记下目标 URI：进程若在下载途中被杀，启动收尾时才能找到这条
                // MediaStore 的 IS_PENDING 隐藏行——要么删掉，要么接着往里面写。
                entity = entity.copy(localUri = target.toString())
                dao.upsert(entity)
            }

            // 本地这一侧的权威进度 = 文件的真实长度。
            // 记录值可能偏大（记录后文件被外部改动），也可能偏小（上次中断时落盘
            // 的字节还没进库），两种偏差都以真实长度为准。
            var offset = entity.transferredBytes.coerceAtLeast(0)
            if (offset > 0) {
                val actual = probeLength(target)
                offset = if (actual < 0) 0 else minOf(offset, actual)
            }

            val response = api.openDownload(connection, fileId, rangeFrom = offset.takeIf { it > 0 })
            response.use { resp ->
                // 服务端可能不支持 Range（或范围对不上），那就老老实实从头来
                val honored = offset > 0 && resp.code == HTTP_PARTIAL
                val start = if (honored) offset else 0L

                val contentLength = resp.body.contentLength()
                // 206 的 content-length 只是「这一段」的长度，加上起点才是文件总长
                val total = when {
                    contentLength < 0 -> size
                    honored -> contentLength + start
                    else -> contentLength
                }
                entity = entity.copy(totalBytes = total)
                dao.upsert(entity)

                val sink = openTargetSink(target, start)
                sink.use { out ->
                    val source = resp.body.byteStream()
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    var copied = start
                    var reported = start

                    while (true) {
                        coroutineContext.ensureActive()

                        val read = source.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        copied += read

                        if (copied - reported >= PROGRESS_STEP_BYTES) {
                            reported = copied
                            entity = entity.copy(transferredBytes = copied, updatedAt = now())
                            dao.upsert(entity)
                        }
                    }
                    out.flush()

                    entity = entity.copy(transferredBytes = copied)
                    // 服务端把连接截断时 read 返回 -1 而不是抛错，不显式核对就会把
                    // 半截文件当成功落盘——校验大小是最后一道闸。
                    if (total > 0 && copied != total) {
                        throw IllegalStateException("下载不完整：$copied / $total 字节")
                    }
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
                    error = null,
                    updatedAt = now(),
                ),
            )
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                // 暂停要保住半成品文件；取消才清掉
                if (intents[transferId] == CancelIntent.PAUSE) {
                    setStateIfExists(transferId, TransferState.PAUSED, null)
                } else {
                    target?.let { runCatching { resolver.delete(it, null, null) } }
                    discardTransfer(transferId)
                }
            }
            throw e
        } catch (e: Exception) {
            // 与上传同理：目标文件还在、还差一截，就留成「已暂停」等用户继续
            val partial = target?.let { probeLength(it) } ?: -1L
            val recoverable = !e.isPermanent() &&
                partial >= 0 &&
                entity.totalBytes > 0 &&
                partial < entity.totalBytes

            if (recoverable) {
                dao.upsert(
                    entity.copy(
                        state = TransferState.PAUSED,
                        transferredBytes = partial,
                        error = "传输中断（${e.toUserMessage()}），可继续",
                        updatedAt = now(),
                    ),
                )
            } else {
                target?.let { runCatching { resolver.delete(it, null, null) } }
                dao.upsert(
                    entity.copy(
                        state = TransferState.FAILED,
                        error = e.toUserMessage(),
                        updatedAt = now(),
                    ),
                )
            }
        }
    }

    /**
     * 打开下载目标的写入端。
     *
     * `start > 0` 时走可定位写入：把写指针移到 `start` 才落笔，写之前先
     * `ftruncate(start)`。
     *
     * 落笔位置对齐就已经保证了正确性（`"rw"` 打开的 fd 不是 O_APPEND，不会追加到
     * 文件末尾；服务端 `Range` 返回的也正是从 `start` 开始的字节，逐字节覆盖即可），
     * 截断是**兜底的显式不变量**：保证「开始写之前文件长度恒等于 start」。
     * 没有它，一旦将来出现「服务端返回的字节数比文件原有尾巴还短」这类情形
     * （换了下载源、文件被外部改动），旧尾巴就会留在文件里，而大小校验未必发现得了。
     *
     * 对照：服务端上传侧那个截断是**必需的**——那边用的是 `O_APPEND` 追加写，
     * 不截断就会把新数据接在残字节后面（已用负向冒烟用例实测复现过 sha256 不符）。
     *
     * 用 [Os.ftruncate]/[Os.lseek] 直接操作 fd，而不是 FileChannel：channel 的
     * 所有权归 FileOutputStream，两者生命周期纠缠，容易把 fd 关两次（fd 号一旦
     * 被系统回收，第二次关的就是别人的 socket）。
     */
    private fun openTargetSink(target: Uri, start: Long): OutputStream {
        if (start <= 0L) {
            return resolver.openOutputStream(target)
                ?: throw IllegalStateException("无法写入目标文件")
        }

        val pfd = resolver.openFileDescriptor(target, "rw")
            ?: throw IllegalStateException("无法打开目标文件续传")

        try {
            val fd = pfd.fileDescriptor
            Os.ftruncate(fd, start)
            Os.lseek(fd, start, OsConstants.SEEK_SET)
            return SeekableSink(pfd, FileOutputStream(fd))
        } catch (e: Exception) {
            runCatching { pfd.close() }
            throw e
        }
    }

    /** 只关 [ParcelFileDescriptor]，不单关内部流，避免同一个 fd 被关两次。 */
    private class SeekableSink(
        private val pfd: ParcelFileDescriptor,
        private val out: FileOutputStream,
    ) : OutputStream() {
        override fun write(b: Int) = out.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun flush() = out.flush()
        override fun close() {
            pfd.close()
        }
    }

    /**
     * 文件当前的真实长度；拿不到返回 -1（不存在、已被删，或没有该模式的权限）。
     *
     * 上传源只有读权限（`takePersistableUriPermission` 拿的是 READ），用 "rw" 探测
     * 会抛 SecurityException 并被误判成「文件没了」——这正是续传最怕的假阴性。
     */
    private fun probeLength(uri: Uri, mode: String = "rw"): Long =
        runCatching { resolver.openFileDescriptor(uri, mode)?.use { it.statSize } ?: -1L }
            .getOrDefault(-1L)

    // ------------------------------------------------------------------ 控制

    /** 暂停：[transferId] 的记录保留原样，两端的半成品都不动。 */
    fun pause(transferId: String) {
        val job = jobs[transferId]
        if (job == null) {
            // 没有在跑的协程（已暂停/失败）：把状态摆正即可
            scope.launch { setStateIfExists(transferId, TransferState.PAUSED, null) }
            return
        }
        intents[transferId] = CancelIntent.PAUSE
        job.cancel()
    }

    /** 取消：清掉两端的现场（服务端会话 + 本地半成品）。 */
    fun cancel(transferId: String) {
        val job = jobs[transferId]
        if (job == null) {
            scope.launch { discardTransfer(transferId) }
            return
        }
        intents[transferId] = CancelIntent.CANCEL
        job.cancel()
    }

    /**
     * 暂停所有进行中的传输。
     *
     * 目前只有一个调用方：[io.github.illagercpr.landrop.notify.TransferService] 的
     * `onTimeout`——Android 15 起 dataSync 型前台服务有 6 小时/24 小时的配额，
     * 用尽时系统只给我们一次收尾机会，落到「已暂停」才能保住两端的进度。
     */
    fun pauseAll() {
        scope.launch {
            dao.loadByStates(listOf(TransferState.QUEUED, TransferState.RUNNING))
                .forEach { pause(it.id) }
        }
    }

    /** 协程被取消后统一收尾：读意图区分暂停与取消，读完即消费掉。 */
    private suspend fun settleCancellation(transferId: String) {
        when (intents.remove(transferId)) {
            CancelIntent.CANCEL -> discardTransfer(transferId)
            else -> setStateIfExists(transferId, TransferState.PAUSED, null)
        }
    }

    /** 清现场：中止服务端会话、删掉本地半成品、释放 SAF 长期授权，然后标记已取消。 */
    private suspend fun discardTransfer(transferId: String) {
        val transfer = dao.findById(transferId) ?: return
        val connection = connectionFor(transfer.serverId)

        if (transfer.direction == TransferDirection.UPLOAD &&
            transfer.uploadId != null &&
            connection != null
        ) {
            runCatching { api.abortUpload(connection, transfer.uploadId) }
        }

        if (transfer.direction == TransferDirection.DOWNLOAD) {
            // 未完成的下载在 MediaStore 里是 IS_PENDING 的隐藏行，删掉它
            transfer.localUri?.let { runCatching { resolver.delete(Uri.parse(it), null, null) } }
        } else {
            releasePersistableRead(transfer.localUri?.let(Uri::parse))
        }

        dao.upsert(
            transfer.copy(
                state = TransferState.CANCELED,
                error = null,
                updatedAt = now(),
            ),
        )
    }

    // ------------------------------------------------------------------ 启动收尾

    /**
     * 启动时收尾「上次进程被杀而中断的传输」。
     *
     * 没有这一步，被系统杀掉进程后传输记录会永远停在「进行中」：界面上是一条
     * 永不结束的进度条，服务端还留着一个 open 会话和 `.part` 临时文件
     * （要等 24h 的 TTL 才被回收）。
     *
     * 有续传能力之后这里不再一律判失败：**只要两端的现场还在，就落到「已暂停」**，
     * 让用户点一下继续；现场确实没了的才清理并判失败。
     *
     * 刻意不做自动续传：App 启动时 WiFi 往往还没就绪，自动重试会把本来可恢复的
     * 任务直接烧成失败；而且用户也未必希望一开 App 就占满带宽。
     */
    suspend fun reconcileInterruptedTransfers() = withContext(Dispatchers.IO) {
        val active = dao.loadByStates(listOf(TransferState.QUEUED, TransferState.RUNNING))
        if (active.isEmpty()) return@withContext

        for (transfer in active) {
            // 每条传输各连各的服务端（多服务端下「中断」不因切换服务端而改变归属）
            val connection = connectionFor(transfer.serverId)
            if (transfer.direction == TransferDirection.UPLOAD) {
                val uri = transfer.localUri?.let(Uri::parse)
                // 源文件只有读权限，必须用 "r" 探测
                val sourceOk = uri != null && probeLength(uri, "r") > 0
                if (!sourceOk) {
                    discardOrFail(transfer, connection, RESUMABLE_LOST_MESSAGE)
                    continue
                }

                // 顺带把服务端权威进度写回本地，界面上显示的数字才是真的
                val resumed = if (connection != null) {
                    transfer.uploadId?.let { sessionOffset(connection, it, transfer.totalBytes) }
                } else {
                    null
                }

                dao.upsert(
                    transfer.copy(
                        state = TransferState.PAUSED,
                        transferredBytes = resumed?.offset ?: transfer.transferredBytes,
                        error = null,
                        updatedAt = now(),
                    ),
                )
            } else {
                val target = transfer.localUri?.let(Uri::parse)
                val partial = target?.let { probeLength(it) } ?: -1L
                if (partial < 0) {
                    // MediaStore 行已经没了，接着写无从谈起
                    discardOrFail(transfer, connection, RESUMABLE_LOST_MESSAGE)
                    continue
                }
                dao.upsert(
                    transfer.copy(
                        state = TransferState.PAUSED,
                        transferredBytes = minOf(transfer.transferredBytes, partial),
                        error = null,
                        updatedAt = now(),
                    ),
                )
            }
        }
    }

    private suspend fun discardOrFail(
        transfer: TransferEntity,
        connection: Connection?,
        message: String,
    ) {
        if (transfer.direction == TransferDirection.UPLOAD &&
            transfer.uploadId != null &&
            connection != null
        ) {
            // 顺手让服务端删掉半截临时分片，别留孤儿文件
            runCatching { api.abortUpload(connection, transfer.uploadId) }
        }
        if (transfer.direction == TransferDirection.DOWNLOAD) {
            transfer.localUri?.let { runCatching { resolver.delete(Uri.parse(it), null, null) } }
        }
        dao.upsert(
            transfer.copy(
                state = TransferState.FAILED,
                error = message,
                updatedAt = now(),
            ),
        )
    }

    // ------------------------------------------------------------------ 工具

    /** 起一条传输协程并登记（返回 Job 供批量上传串行等待）。 */
    private fun startJob(transferId: String, block: suspend (String) -> Unit): Job? {
        if (jobs.containsKey(transferId)) return null

        // 先叫前台服务再起协程：文件传几分钟，用户几乎必然会锁屏或切走，
        // 没有前台服务进程会被冻结，传输就停在半路。
        foreground.ensureRunning()

        val job = scope.launch { block(transferId) }
        jobs[transferId] = job
        job.invokeOnCompletion { if (jobs[transferId] === job) jobs.remove(transferId) }
        return job
    }

    private suspend fun setStateIfExists(transferId: String, state: String, error: String?) {
        val current = dao.findById(transferId) ?: return
        dao.upsert(current.copy(state = state, error = error, updatedAt = now()))
    }

    private suspend fun fail(entity: TransferEntity, message: String) {
        dao.upsert(
            entity.copy(
                state = TransferState.FAILED,
                error = message,
                updatedAt = now(),
            ),
        )
    }

    /**
     * 连文件都没读出来时的失败收尾。
     *
     * 新任务此刻还没有任何记录，必须补一条占位——否则用户点了发送却什么都没发生，
     * 连失败原因都看不到。
     */
    private suspend fun failBeforeStart(
        transferId: String,
        existing: TransferEntity?,
        message: String,
        serverId: String,
    ) {
        val entity = existing ?: TransferEntity(
            id = transferId,
            serverId = serverId,
            messageId = "",
            direction = TransferDirection.UPLOAD,
            fileName = "未知文件",
            totalBytes = 0,
            transferredBytes = 0,
            state = TransferState.FAILED,
            localUri = null,
            uploadId = null,
            remoteFileId = null,
            error = null,
            updatedAt = now(),
        )
        fail(entity, message)
    }

    /**
     * 永久失败判据：重试也不会有不同的结果。
     *
     * 4xx 基本都是「请求本身有问题」（文件太大、sha256 不符、服务端文件已被删），
     * 唯独 408/429 是明确的「待会儿再来」。其余（IO 异常、5xx、超时）一律当成
     * 可恢复，保住进度等用户继续。
     */
    private fun Throwable.isPermanent(): Boolean = when (this) {
        is ApiException -> statusCode in 400..499 && statusCode != 408 && statusCode != 429
        is SecurityException -> true
        else -> false
    }

    /**
     * 取 SAF 源的长期读权限。
     *
     * `ActivityResultContracts.OpenDocument` 选中的 URI 支持持久化授权，但必须
     * 显式申请——否则权限随进程一起消失，重启 App 后连源文件都打不开。
     */
    private fun takePersistableRead(uri: Uri) {
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun releasePersistableRead(uri: Uri?) {
        if (uri == null) return
        runCatching {
            resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

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

        // `file://` 来源（部分分享方给的就是裸路径）没有 provider，查不到 DISPLAY_NAME，
        // 但路径末段就是文件名——否则界面上只会显示「file-1759…」这种时间戳名。
        if (name == null && uri.scheme == "file") {
            name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
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

    private fun openSource(uri: Uri): InputStream =
        resolver.openInputStream(uri) ?: throw IllegalStateException("无法打开所选文件")

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

        /** 现场已丢失（旧版本记录、授权失效、MediaStore 行被删），只能重新发起。 */
        const val RESUMABLE_LOST_MESSAGE = "传输因应用退出而中断，且无法续传，请重新发起"

        const val DOWNLOAD_BUFFER_SIZE = 64 * 1024

        /** 下载进度写库的步长：1 MiB 一次，避免每 64 KiB 就写一次 SQLite。 */
        const val PROGRESS_STEP_BYTES = 1024L * 1024L

        /** 409 重对齐的最大次数，防止服务端行为异常时死循环。 */
        const val MAX_REALIGN = 3

        /** 分片大小的合理区间：太小则请求过多，太大则中断重传代价高。 */
        const val MIN_CHUNK = 64L * 1024
        const val MAX_CHUNK = 8L * 1024 * 1024

        /** HTTP 206：服务端接受了 Range，从我们要求的位置开始返回。 */
        const val HTTP_PARTIAL = 206

        const val MAX_NAME_ATTEMPTS = 50
        const val MAX_NAME_LENGTH = 120

        fun now(): Long = System.currentTimeMillis()
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

/**
 * 从流当前位置起读恰好 [count] 字节喂进摘要（补齐续传/对齐丢失的覆盖区间）。
 * 文件比预期短（读到 EOF）时抛错，由调用方按可恢复失败处理。
 */
private fun seedDigest(stream: InputStream, digest: MessageDigest, count: Long) {
    val buffer = ByteArray(256 * 1024)
    var remaining = count
    while (remaining > 0) {
        val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (read < 0) throw IllegalStateException("文件比预期短，无法计算完整摘要")
        digest.update(buffer, 0, read)
        remaining -= read
    }
}
