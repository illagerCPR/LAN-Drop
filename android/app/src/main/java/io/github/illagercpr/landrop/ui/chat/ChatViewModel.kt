package io.github.illagercpr.landrop.ui.chat

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.illagercpr.landrop.data.local.MessageEntity
import io.github.illagercpr.landrop.data.prefs.ConnectionStore
import io.github.illagercpr.landrop.data.repo.MessageRepository
import io.github.illagercpr.landrop.data.repo.PairingRepository
import io.github.illagercpr.landrop.data.repo.TransferRepository
import io.github.illagercpr.landrop.data.prefs.SettingsStore
import io.github.illagercpr.landrop.media.ThumbnailLoader
import io.github.illagercpr.landrop.media.ThumbnailSource
import io.github.illagercpr.landrop.net.toUserMessage
import io.github.illagercpr.landrop.share.SharedPayload
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 发送中的消息在界面上是「乐观气泡」，尚未落库（服务端确认后才进 Room）。 */
data class OutboxItem(
    val id: String,
    val text: String,
    val error: String? = null,
) {
    val failed: Boolean get() = error != null
}

/** 一次系统分享被处理的结果，调用方据此决定提示什么。 */
data class ShareResult(
    val textPrefilled: Boolean,
    val filesQueued: Int,
    val filesDropped: Int,
)

/**
 * 会话页状态。
 *
 * 消息本体不在这里——它来自 Room（[MessageRepository.timeline]），因为服务端推送、
 * 增量拉取、本机发送三条路径都写同一张表。这里只维护两类「还没有权威副本」的状态：
 * 发送中的文字（[outbox]）与一次性提示（[notice]）。
 */
class ChatViewModel(
    private val messages: MessageRepository,
    private val transfers: TransferRepository,
    private val pairing: PairingRepository,
    private val connectionStore: ConnectionStore,
    private val settings: SettingsStore,
    private val thumbnails: ThumbnailLoader,
) : ViewModel() {

    val timeline = messages.timeline
    val transferList = transfers.transfers
    val socketState = messages.socketState
    val syncState = messages.syncState
    val onlineCount = messages.onlineCount
    val peerOnline = messages.peerOnline

    /** 已保存的服务端列表（服务端切换器的数据源）。 */
    val servers = connectionStore.connections

    /** 当前服务端（切换器里标记「使用中」）。 */
    val activeConnection = connectionStore.connection

    val autoReceiveFiles = settings.autoReceiveFiles

    var draft by mutableStateOf("")
        private set

    private val _outbox = MutableStateFlow<List<OutboxItem>>(emptyList())
    val outbox: StateFlow<List<OutboxItem>> = _outbox.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun onDraftChange(value: String) {
        draft = value
    }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty()) return

        draft = ""
        val item = OutboxItem(id = UUID.randomUUID().toString(), text = text)
        _outbox.update { it + item }

        viewModelScope.launch {
            try {
                messages.sendText(text)
                _outbox.update { list -> list.filterNot { it.id == item.id } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _outbox.update { list ->
                    list.map { if (it.id == item.id) it.copy(error = e.toUserMessage()) else it }
                }
            }
        }
    }

    fun retry(item: OutboxItem) {
        _outbox.update { list -> list.filterNot { it.id == item.id } }
        val replacement = item.copy(error = null)
        _outbox.update { it + replacement }

        viewModelScope.launch {
            try {
                messages.sendText(item.text)
                _outbox.update { list -> list.filterNot { it.id == replacement.id } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _outbox.update { list ->
                    list.map {
                        if (it.id == replacement.id) it.copy(error = e.toUserMessage()) else it
                    }
                }
            }
        }
    }

    fun discard(item: OutboxItem) {
        _outbox.update { list -> list.filterNot { it.id == item.id } }
    }

    /** 发送文件；传输状态由传输记录页展示，这里只负责启动。 */
    fun sendFile(uri: Uri, mime: String?) {
        transfers.upload(uri, mime)
    }

    /** 多选批量发送：顺序上传，接收端看到的顺序与选择顺序一致。 */
    fun sendFiles(uris: List<Uri>) {
        transfers.uploadAll(uris)
    }

    /**
     * 接收系统分享进来的内容（见 [io.github.illagercpr.landrop.share.ShareInbox]）。
     *
     * 文字**只预填不自动发送**：分享过来的往往还需要改一改，而自动发送是不可撤销的
     * （服务端已经落库并推给对方了）。文件则必须当场开工——`ACTION_SEND` 的 URI
     * 只有临时授权，拖到下一轮就没法读了。
     *
     * [canSendFiles] 为 false（尚未配对）时不排队上传，如实把它们计入 `filesDropped`：
     * 那批 URI 反正留不住，与其排个注定失败的队，不如直接告诉用户先配对。
     */
    fun acceptShare(payload: SharedPayload, canSendFiles: Boolean): ShareResult {
        var prefilled = false
        payload.text?.let { text ->
            draft = if (draft.isBlank()) text else "${draft.trimEnd()}\n$text"
            prefilled = true
        }

        val sendable = if (canSendFiles) payload.uris else emptyList()
        if (sendable.isNotEmpty()) transfers.uploadAll(sendable)

        return ShareResult(
            textPrefilled = prefilled,
            filesQueued = sendable.size,
            filesDropped = payload.uris.size - sendable.size,
        )
    }

    fun download(message: MessageEntity) {
        val fileId = message.fileId ?: return
        val fileName = message.fileName ?: "file"
        transfers.download(
            fileId = fileId,
            fileName = fileName,
            mime = message.fileMime,
            size = message.fileSize ?: 0,
            messageId = message.id,
        )
    }

    /**
     * 时间线里一条消息的图片预览（见 [io.github.illagercpr.landrop.media.ThumbnailLoader]）。
     *
     * 取哪一份字节由 [thumbnailSourcesOf] 在界面侧算好（它依赖传输记录，而那正是界面
     * 已经在观察的数据）；这里只管把候选来源依次试一遍。返回 null 只是「没有预览」。
     */
    suspend fun thumbnailFor(sources: List<ThumbnailSource>, targetPx: Int): Bitmap? =
        thumbnails.load(sources, targetPx)

    fun cancelTransfer(transferId: String) {
        transfers.cancel(transferId)
    }

    /** 暂停：保住两端的进度，记录停在「已暂停」。 */
    fun pauseTransfer(transferId: String) {
        transfers.pause(transferId)
    }

    /** 继续：上传按服务端权威进度、下载按本地文件长度重新对齐后接着传。 */
    fun resumeTransfer(transferId: String) {
        transfers.resume(transferId)
    }

    /** 回到前台 / 手动「立即同步」：补拉增量消息，并顺带核对服务端展示名。 */
    fun refresh() {
        // 两条独立协程：改名只是展示信息，它失败不该影响同步状态的显示
        viewModelScope.launch { pairing.refreshServerName() }
        viewModelScope.launch { messages.syncNow() }
    }

    fun showNotice(message: String) {
        _notice.value = message
    }

    fun consumeNotice() {
        _notice.value = null
    }

    fun unpair() {
        pairing.unpair()
    }

    /**
     * 切换到另一台已配对的服务端。
     *
     * 凭据与缓存都不动：连接层会自动断开旧长连接、按新服务端重连并增量同步，
     * 时间线随之换成那台服务端的缓存（各服务端 seq 各自单调，互不掺和）。
     */
    fun switchServer(serverId: String) {
        if (!connectionStore.switchTo(serverId)) {
            _notice.value = "切换失败：找不到这台服务端的凭据"
        }
    }

    /** 解除一台服务端的配对（可再重新配对回来；缓存保留，重新配对后立即恢复）。 */
    fun removeServer(serverId: String) {
        connectionStore.remove(serverId)
    }

    /**
     * 清除本地消息缓存（[MessageRepository.clearLocal]）。
     *
     * 服务端记录不受影响：清完立刻补一次同步，时间线会从服务端重新拉回——
     * 这个入口是「本地缓存修复」，不是「删除聊天记录」，提示文案要说清这层。
     */
    fun clearLocalMessages() {
        viewModelScope.launch {
            messages.clearLocal()
            _notice.value = "本地消息缓存已清除，正在重新同步…"
            messages.syncNow()
        }
    }

    /** 切换「自动接收文件」；默认关，开启时刻即自动接收的基准点。 */
    fun toggleAutoReceiveFiles() {
        settings.setAutoReceiveFiles(!settings.autoReceiveFiles.value)
    }
}
