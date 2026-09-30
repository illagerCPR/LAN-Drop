package io.github.illagercpr.landrop.ui.chat

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.illagercpr.landrop.data.local.MessageEntity
import io.github.illagercpr.landrop.data.repo.MessageRepository
import io.github.illagercpr.landrop.data.repo.PairingRepository
import io.github.illagercpr.landrop.data.repo.TransferRepository
import io.github.illagercpr.landrop.net.toUserMessage
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
) : ViewModel() {

    val timeline = messages.timeline
    val transferList = transfers.transfers
    val socketState = messages.socketState
    val syncState = messages.syncState
    val onlineCount = messages.onlineCount
    val peerOnline = messages.peerOnline

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

    fun refresh() {
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
}
