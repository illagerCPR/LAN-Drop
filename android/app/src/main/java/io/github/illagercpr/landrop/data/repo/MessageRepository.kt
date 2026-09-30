package io.github.illagercpr.landrop.data.repo

import io.github.illagercpr.landrop.data.local.LanDropDatabase
import io.github.illagercpr.landrop.data.local.MessageEntity
import io.github.illagercpr.landrop.data.local.toEntity
import io.github.illagercpr.landrop.data.prefs.ConnectionStore
import io.github.illagercpr.landrop.net.LanDropApi
import io.github.illagercpr.landrop.net.LanDropSocket
import io.github.illagercpr.landrop.net.SocketState
import io.github.illagercpr.landrop.net.WsEvent
import io.github.illagercpr.landrop.net.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 与服务端的同步状态，供界面顶部提示条展示。 */
sealed interface SyncState {
    data object Idle : SyncState

    data object Syncing : SyncState

    data class Failed(val message: String) : SyncState
}

/**
 * 会话消息的权威副本在服务端，本类负责：
 *  1. 首次全量 / 断线后增量地把消息同步进 Room（游标是本地最大 `seq`）；
 *  2. 把 WebSocket 推来的实时消息落库，实现「PC 发一条，手机立刻出现」；
 *  3. 发送文字消息（文件走 [TransferRepository]）。
 *
 * 界面永远只读 Room——服务端推送、增量拉取、本机发送三条路径最终都写同一张表，
 * 因此不需要在 UI 层做多来源合并（发送中的乐观气泡除外，那是纯内存状态）。
 */
class MessageRepository(
    private val store: ConnectionStore,
    private val db: LanDropDatabase,
    private val api: LanDropApi,
    private val socket: LanDropSocket,
    private val scope: CoroutineScope,
) {
    private val dao = db.messageDao()
    private val syncMutex = Mutex()

    /** 时间线（新 → 旧）。界面用 `reverseLayout = true` 的 LazyColumn 渲染，天然贴底。 */
    val timeline: Flow<List<MessageEntity>> = dao.observeRecent(TIMELINE_LIMIT)

    val socketState: StateFlow<SocketState> = socket.state

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    /** 当前在线设备数（含本机），来自 WS hello / presence 事件。 */
    private val _onlineCount = MutableStateFlow(0)
    val onlineCount: StateFlow<Int> = _onlineCount.asStateFlow()

    /** 对端（PC）是否在线；影响「发出去有没有人收」的心理预期，值得显示。 */
    private val _peerOnline = MutableStateFlow(false)
    val peerOnline: StateFlow<Boolean> = _peerOnline.asStateFlow()

    /**
     * 启动会话：连接凭据变化时重建长连接，并订阅其事件。
     *
     * 幂等——重复调用只会多一个收集协程，不会重复建连（[LanDropSocket.connect] 会先取消旧连接）。
     */
    fun start() {
        scope.launch {
            store.connection.collect { connection ->
                if (connection == null) {
                    socket.disconnect()
                    _peerOnline.value = false
                    _onlineCount.value = 0
                } else {
                    socket.connect(connection.baseUrl, connection.deviceToken)
                }
            }
        }

        scope.launch {
            socket.events.collect(::handleEvent)
        }
    }

    /** 手动触发一次增量同步（下拉刷新、从后台回到前台时调用）。 */
    suspend fun syncNow() {
        val connection = store.connection.value ?: return

        syncMutex.withLock {
            _syncState.value = SyncState.Syncing
            try {
                while (true) {
                    val since = dao.latestSeq()
                    val page = api.listMessages(connection, since, PAGE_SIZE)

                    if (page.items.isEmpty()) break

                    dao.upsertAll(page.items.map { it.toEntity(connection.deviceId) })

                    // 服务端说还有、但这一页已经追平 latestSeq 时收手，避免空转
                    if (!page.hasMore || page.latestSeq <= since) break
                }
                _syncState.value = SyncState.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _syncState.value = SyncState.Failed(e.toUserMessage())
            }
        }
    }

    /** 发送文字消息，返回服务端落库后的权威副本。失败时抛出异常由调用方展示。 */
    suspend fun sendText(text: String): MessageEntity {
        val connection = store.connection.value ?: error("尚未配对服务器")
        val dto = api.sendText(connection, text)
        val entity = dto.toEntity(connection.deviceId)
        dao.upsert(entity)
        return entity
    }

    suspend fun clearLocal() {
        dao.clear()
    }

    private suspend fun handleEvent(event: WsEvent) {
        val connection = store.connection.value ?: return

        when (event) {
            is WsEvent.Connected -> syncNow()

            is WsEvent.Disconnected -> _peerOnline.value = false

            is WsEvent.Hello -> {
                _onlineCount.value = event.payload.onlineCount
                // 服务端水位比本地游标高 → 离线期间错过消息，立刻补拉
                if (event.payload.latestSeq > dao.latestSeq()) syncNow()
            }

            is WsEvent.MessageNew -> {
                dao.upsert(event.message.toEntity(connection.deviceId))
            }

            is WsEvent.MessagesCleared -> dao.clear()

            is WsEvent.Presence -> {
                event.onlineCount?.let { _onlineCount.value = it }
                _peerOnline.value = event.online
            }

            is WsEvent.Typing -> Unit
        }
    }

    private companion object {
        /** 单页拉取条数，与服务端默认值一致。 */
        const val PAGE_SIZE = 200

        /** 本地保留的消息条数上限；更早的历史按需再向服务端翻页，首版不做。 */
        const val TIMELINE_LIMIT = 500
    }
}
