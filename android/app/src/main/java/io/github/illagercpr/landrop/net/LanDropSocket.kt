package io.github.illagercpr.landrop.net

import io.github.illagercpr.landrop.data.prefs.Connection
import io.github.illagercpr.landrop.protocol.ApiPath
import io.github.illagercpr.landrop.protocol.MessageDto
import io.github.illagercpr.landrop.protocol.WsHelloPayloadDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** 连接状态，供界面显示「在线/重连中」。 */
enum class SocketState {
    /** 未配对或已主动断开 */
    IDLE,
    CONNECTING,
    ONLINE,

    /** 掉线后正在按退避重试 */
    RECONNECTING,

    /**
     * 服务端明确拒绝凭据（WS 关闭码 4401 / HTTP 401）。
     *
     * 与 RECONNECTING 必须区分：这种情况下重试一万次也是 401，
     * 继续重连只会让界面永远显示「正在重连…」误导用户（实测踩过：
     * 服务端设备行丢失后，横幅一直显示「连接断开，正在重连…」）。
     * 唯一出路是解除配对重新配对。
     */
    CREDENTIALS_INVALID,

    /**
     * 服务端已启用 TLS（自签证书），但本地凭据没有指纹（0.1.0 升级上来的老配对）。
     * 指纹只能来自配对二维码，重试与找回扫描都无法补上——横幅必须直接给出
     * 「解除配对后重新扫码」的出路，而不是假装能重连。
     */
    TLS_UNTRUSTED,
}

/** 服务端推来的事件（已解析）。未知类型被静默忽略，便于协议向前兼容。 */
sealed interface WsEvent {
    data object Connected : WsEvent

    data class Disconnected(val reason: String) : WsEvent

    /** 服务端拒绝凭据：连接层已停止重连，等待用户解除配对后重新配对。 */
    data object CredentialInvalid : WsEvent

    data class Hello(val payload: WsHelloPayloadDto) : WsEvent

    data class MessageNew(val message: MessageDto) : WsEvent

    /** 会话被清空（服务端只允许宿主自己清，这里仅同步本地缓存）。 */
    data object MessagesCleared : WsEvent

    /** 保留策略清掉了 `seq <= uptoSeq` 的旧消息（严格前缀删除，游标无需回退）。 */
    data class MessagesPurged(val uptoSeq: Long) : WsEvent

    data class Presence(
        val online: Boolean,
        val deviceName: String,
        val onlineCount: Int?,
    ) : WsEvent

    data class Typing(val deviceName: String) : WsEvent
}

/**
 * 控制面长连接。
 *
 * 数据（文件字节）永远不走这里——WebSocket 只承载消息事件、在线状态与心跳，
 * 大文件经 HTTP 分片传输，避免与聊天消息互相队头阻塞。
 *
 * 断线自动重连：指数退避 1s→2s→4s→8s→10s 封顶。OkHttp 客户端级
 * `pingInterval` 已在 [HttpClientProvider] 里设为 20s，因此这里不再自己发应用层心跳
 * （TCP 半开连接由协议层 ping 帧探活）。
 */
class LanDropSocket(
    /** 按连接的 TLS 指纹挑客户端（pinned/默认），见 [HttpClientProvider.createForTls]。 */
    private val clientFor: (String?) -> OkHttpClient,
    private val json: Json,
    private val scope: CoroutineScope,
) {
    private val _events = MutableSharedFlow<WsEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<WsEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow(SocketState.IDLE)
    val state: StateFlow<SocketState> = _state.asStateFlow()

    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var attempt = 0

    /** 当前连接目标；断开重连时复用，不需要调用方再传一次。 */
    private var target: Connection? = null

    /** 主动断开标志：区分「用户断开」与「网络掉线」，前者不触发重连。 */
    @Volatile
    private var manualClose = false

    fun connect(connection: Connection) {
        target = connection
        manualClose = false
        attempt = 0
        reconnectJob?.cancel()
        open()
    }

    fun disconnect() {
        manualClose = true
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.close(NORMAL_CLOSURE, "client closing")
        socket = null
        _state.value = SocketState.IDLE
    }

    /**
     * 标记「服务端已启用加密但本地没有指纹」并停掉重连（见 [SocketState.TLS_UNTRUSTED]）。
     * 由配对层的找回扫描发现这一情形时调用。
     */
    fun enterTlsUntrusted() {
        manualClose = true
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.close(NORMAL_CLOSURE, "client closing")
        socket = null
        _state.value = SocketState.TLS_UNTRUSTED
    }

    // ------------------------------------------------------------------ 内部

    private fun open() {
        val connection = target ?: return
        _state.value = if (attempt == 0) SocketState.CONNECTING else SocketState.RECONNECTING

        // 凭据走 Authorization 头而不是 ?token=：URL 会原样进服务端访问日志与各中间层。
        // OkHttp 的 WebSocket 握手完全可以带自定义请求头，只有浏览器才被迫用查询参数
        // （服务端对 WS 保留查询参数通道正是为了浏览器）。
        val request = Request.Builder()
            .url(connection.baseUrl.toWebSocketBase() + ApiPath.WS)
            .header("Authorization", "Bearer ${connection.deviceToken}")
            .build()

        socket = clientFor(connection.tlsFingerprint).newWebSocket(request, listenerFor(connection))
    }

    /**
     * 监听器按「发起连接时的连接实例」把关：切换服务端后，旧 socket 的迟到回调
     * 一律忽略——否则旧服务端的消息会写进新服务端的缓存行（serverId 标错、
     * seq 冲突），状态条也会被旧连接的关闭事件拖着乱跳。
     */
    private fun listenerFor(connection: Connection): WebSocketListener = object : WebSocketListener() {
        private fun isCurrent(): Boolean = target === connection

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrent()) {
                webSocket.close(NORMAL_CLOSURE, "switched")
                return
            }
            attempt = 0
            _state.value = SocketState.ONLINE
            emit(WsEvent.Connected)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent()) return
            parseWsEnvelope(text, json)?.let(::emit)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // 服务端要求关闭（如凭据失效 4401）：回一个关闭帧完成握手
            webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrent()) return
            socket = null
            if (handleCredentialRejection(code, null)) return
            if (!manualClose) {
                emit(WsEvent.Disconnected(reason.ifBlank { "连接已关闭（$code）" }))
                scheduleReconnect()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!isCurrent()) return
            socket = null
            // 握手阶段就被拒时（HTTP 层 401）走这里；升级成功后被服务端踢走走 onClosed
            if (handleCredentialRejection(response?.code ?: 0, t)) return
            if (!manualClose) {
                emit(WsEvent.Disconnected(t.message ?: "网络异常"))
                scheduleReconnect()
            }
        }
    }

    /**
     * 服务端明确拒绝凭据（HTTP 401 或 WS 关闭码 4401）时：进入
     * [SocketState.CREDENTIALS_INVALID] 并停止重连，返回 true 表示已按凭据失效处理。
     */
    private fun handleCredentialRejection(statusOrCode: Int, cause: Throwable?): Boolean {
        if (statusOrCode != HTTP_UNAUTHORIZED && statusOrCode != WS_CLOSE_UNAUTHORIZED) return false
        _state.value = SocketState.CREDENTIALS_INVALID
        emit(WsEvent.CredentialInvalid)
        emit(WsEvent.Disconnected(cause?.message ?: "登录凭据已失效"))
        return true
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        _state.value = SocketState.RECONNECTING

        val delayMs = BACKOFF_MS[attempt.coerceAtMost(BACKOFF_MS.lastIndex)]
        attempt++

        reconnectJob = scope.launch {
            delay(delayMs)
            if (!manualClose) open()
        }
    }

    private fun emit(event: WsEvent) {
        // 监听器运行在 OkHttp 线程，用 tryEmit 避免阻塞网络回调
        _events.tryEmit(event)
    }

    companion object {
        private const val NORMAL_CLOSURE = 1000

        /** HTTP 401：握手阶段就被服务端拒绝。 */
        private const val HTTP_UNAUTHORIZED = 401

        /** 自定义 WS 关闭码：鉴权失败（与服务端 `app.ts` 的 `WS_CLOSE_UNAUTHORIZED` 一致）。 */
        private const val WS_CLOSE_UNAUTHORIZED = 4401

        /** 重连退避序列（毫秒）；超出长度后一直用最后一个值。 */
        private val BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 10_000)

        /** `http(s)://` → `ws(s)://`。 */
        fun String.toWebSocketBase(): String = when {
            startsWith("https://") -> "wss://" + removePrefix("https://")
            startsWith("http://") -> "ws://" + removePrefix("http://")
            else -> this
        }
    }
}
