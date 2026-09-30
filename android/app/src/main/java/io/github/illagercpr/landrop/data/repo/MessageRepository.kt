package io.github.illagercpr.landrop.data.repo

import io.github.illagercpr.landrop.data.local.LanDropDatabase
import io.github.illagercpr.landrop.data.local.MessageDirection
import io.github.illagercpr.landrop.data.local.MessageEntity
import io.github.illagercpr.landrop.data.local.toEntity
import io.github.illagercpr.landrop.data.prefs.ConnectionStore
import io.github.illagercpr.landrop.data.prefs.SettingsStore
import io.github.illagercpr.landrop.net.LanDropApi
import io.github.illagercpr.landrop.net.LanDropSocket
import io.github.illagercpr.landrop.net.SocketState
import io.github.illagercpr.landrop.net.WsEvent
import io.github.illagercpr.landrop.net.toUserMessage
import io.github.illagercpr.landrop.protocol.MESSAGE_KIND_LINK
import io.github.illagercpr.landrop.protocol.MESSAGE_KIND_TEXT
import io.github.illagercpr.landrop.protocol.isHttpUrl
import java.util.Collections
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
 * 收到对端消息时的提醒入口。
 *
 * 抽成接口是为了让仓储层只管「消息到了」，不掺和「要不要响、怎么响」——
 * 应用在前台时不提醒这条策略属于展示层，放在这里会让数据层多一个
 * 它无法验证的判断（前台与否）。
 */
fun interface NewMessageNotifier {
    fun onMessage(message: MessageEntity)
}

/**
 * 自动接收的落点：把一条入站文件消息转成下载任务。
 *
 * 抽成接口与 [NewMessageNotifier] 同理——消息层只判断「该不该自动收」，
 * 下载怎么跑是传输层的事，两层不直接依赖。
 */
fun interface AutoReceiveStarter {
    fun startDownload(message: MessageEntity)
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
    private val notifier: NewMessageNotifier,
    private val settings: SettingsStore,
    private val autoReceive: AutoReceiveStarter,
) {
    private val dao = db.messageDao()
    private val syncMutex = Mutex()

    /**
     * 本进程内已自动接收过的消息 ID。WS 推送与增量同步可能先后到达同一条消息，
     * Room 的 upsert 不去重传输，这道内存闸负责并发窗口内的防重；
     * 进程重启后的防重由传输表的 `message_id` 查询兜底。
     */
    private val autoReceivedIds: MutableSet<String> = Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

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

                    // 补上离线期间错过的保留清理：服务端删到哪，本地缓存同步删到哪
                    // （幂等，每页重复执行无副作用；值为 0 时是空区间）
                    dao.deleteUpTo(page.purgedUpto)

                    if (page.items.isEmpty()) break

                    val entities = page.items.map { it.toEntity(connection.deviceId) }
                    dao.upsertAll(entities)
                    // 离线期间的入站文件同样参与自动接收，否则开关形同虚设
                    entities.forEach { maybeAutoReceive(it) }

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
        // 整段就是一个 http(s) 链接时按 link 类型发，两端才会都渲染成可点链接
        // （与 Web 端同一条规则，见 [isHttpUrl]）
        val kind = if (isHttpUrl(text)) MESSAGE_KIND_LINK else MESSAGE_KIND_TEXT
        val dto = api.sendText(connection, text, kind)
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

            // 连接层已停止重连，界面靠 socketState 展示「请重新配对」；这里无事可做
            is WsEvent.CredentialInvalid -> Unit

            is WsEvent.Hello -> {
                _onlineCount.value = event.payload.onlineCount
                // 服务端水位比本地游标高 → 离线期间错过消息，立刻补拉
                if (event.payload.latestSeq > dao.latestSeq()) syncNow()
            }

            is WsEvent.MessageNew -> {
                val entity = event.message.toEntity(connection.deviceId)
                dao.upsert(entity)
                // 只提醒对端发来的：服务端会把消息广播给所有客户端，包括发送者自己
                if (entity.direction == MessageDirection.INBOUND) {
                    notifier.onMessage(entity)
                    maybeAutoReceive(entity)
                }
            }

            is WsEvent.MessagesCleared -> dao.clear()

            // 保留策略的前缀删除：删到 seq X 为止，本地缓存同步删到 X；
            // 游标不用动（seq 单调递增，新消息照常增量同步）
            is WsEvent.MessagesPurged -> dao.deleteUpTo(event.uptoSeq)

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

        /** 与 [io.github.illagercpr.landrop.ui.chat] 的 KIND_FILE 一致：kind 落库存小写。 */
        const val KIND_FILE = "file"
    }

    /**
     * 自动接收的判定与防重。
     *
     * 三个条件同时满足才收：开关打开、入站文件消息、晚于开启基准点
     * （防止开启后第一次全量同步把服务端历史里的文件全拉下来）。
     * 同一条消息无论手动还是自动，发起过一次传输就不再重复——
     * 内存闸挡住 WS 推送与增量同步的并发窗口，传输表的 `message_id` 查询兜底进程重启。
     */
    private suspend fun maybeAutoReceive(entity: MessageEntity) {
        if (!settings.autoReceiveFiles.value) return
        if (entity.direction != MessageDirection.INBOUND) return
        if (entity.kind != KIND_FILE) return
        if (entity.createdAt < settings.autoReceiveSince) return
        if (entity.fileId == null) return
        if (!autoReceivedIds.add(entity.id)) return
        if (db.transferDao().findByMessageId(entity.id) != null) return

        autoReceive.startDownload(entity)
    }
}
