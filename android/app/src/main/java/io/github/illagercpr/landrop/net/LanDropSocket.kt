package io.github.illagercpr.landrop.net

import io.github.illagercpr.landrop.protocol.ApiPath
import io.github.illagercpr.landrop.protocol.MessageDto
import io.github.illagercpr.landrop.protocol.WsHelloPayloadDto
import java.net.URLEncoder
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
}

/** 服务端推来的事件（已解析）。未知类型被静默忽略，便于协议向前兼容。 */
sealed interface WsEvent {
    data object Connected : WsEvent

    data class Disconnected(val reason: String) : WsEvent

    data class Hello(val payload: WsHelloPayloadDto) : WsEvent

    data class MessageNew(val message: MessageDto) : WsEvent

    /** 会话被清空（服务端只允许宿主自己清，这里仅同步本地缓存）。 */
    data object MessagesCleared : WsEvent

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
    private val client: OkHttpClient,
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
    private var target: Pair<String, String>? = null

    /** 主动断开标志：区分「用户断开」与「网络掉线」，前者不触发重连。 */
    @Volatile
    private var manualClose = false

    fun connect(baseUrl: String, token: String) {
        target = baseUrl to token
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

    // ------------------------------------------------------------------ 内部

    private fun open() {
        val (baseUrl, token) = target ?: return
        _state.value = if (attempt == 0) SocketState.CONNECTING else SocketState.RECONNECTING

        val url = buildString {
            append(baseUrl.toWebSocketBase())
            append(ApiPath.WS)
            append("?token=")
            append(URLEncoder.encode(token, "UTF-8"))
        }

        socket = client.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt = 0
            _state.value = SocketState.ONLINE
            emit(WsEvent.Connected)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            parseWsEnvelope(text, json)?.let(::emit)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // 服务端要求关闭（如凭据失效 4401）：回一个关闭帧完成握手
            webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            socket = null
            if (!manualClose) {
                emit(WsEvent.Disconnected(reason.ifBlank { "连接已关闭（$code）" }))
                scheduleReconnect()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            socket = null
            if (!manualClose) {
                emit(WsEvent.Disconnected(t.message ?: "网络异常"))
                scheduleReconnect()
            }
        }
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
