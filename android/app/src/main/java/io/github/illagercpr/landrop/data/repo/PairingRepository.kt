package io.github.illagercpr.landrop.data.repo

import android.os.Build
import android.os.SystemClock
import io.github.illagercpr.landrop.data.local.LanDropDatabase
import io.github.illagercpr.landrop.data.prefs.Connection
import io.github.illagercpr.landrop.data.prefs.ConnectionStore
import io.github.illagercpr.landrop.net.DiscoveredServer
import io.github.illagercpr.landrop.net.LanDropApi
import io.github.illagercpr.landrop.net.LanDropSocket
import io.github.illagercpr.landrop.net.ServerDiscovery
import io.github.illagercpr.landrop.net.SocketState
import io.github.illagercpr.landrop.net.toUserMessage
import io.github.illagercpr.landrop.protocol.ProtocolVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 配对阶段可能出现的、需要给用户不同指引的结果。 */
sealed interface PairResult {
    data class Success(val connection: Connection) : PairResult

    data class Failure(val message: String) : PairResult
}

/**
 * 配对流程：校验地址可达 → 用一次性配对码换长期凭据 → 落盘。
 *
 * 分两步而不是直接 POST /pair，是为了把「地址不通」和「配对码不对」区分开——
 * 局域网里这两种错误用户看到的提示必须不一样，否则只能瞎试。
 */
class PairingRepository(
    private val store: ConnectionStore,
    private val api: LanDropApi,
    private val database: LanDropDatabase,
    private val socket: LanDropSocket,
    private val discovery: ServerDiscovery,
    private val scope: CoroutineScope,
) {
    private var recoveryJob: Job? = null

    /** 上次找回扫描的时间（单调时钟），防止长时间断线期间高频 UDP 扫描。 */
    @Volatile
    private var lastRecoveryAtMs = 0L
    /** 探测服务端，成功时返回它的展示名（配对页的「测试连接」按钮用）。 */
    suspend fun probe(rawAddress: String): Result<String> {
        val baseUrl = ConnectionStore.normalizeBaseUrl(rawAddress)
        if (baseUrl.isEmpty()) {
            return Result.failure(IllegalArgumentException("请输入服务器地址"))
        }

        return try {
            val info = api.info(baseUrl)
            if (info.protocolVersion != ProtocolVersion.CURRENT) {
                Result.failure(
                    IllegalStateException(
                        "协议版本不匹配：服务端 v${info.protocolVersion}，客户端 v${ProtocolVersion.CURRENT}",
                    ),
                )
            } else {
                Result.success(info.serverName.ifBlank { "LAN-Drop" })
            }
        } catch (e: Exception) {
            Result.failure(IllegalStateException(e.toUserMessage()))
        }
    }

    /** 执行配对；成功后凭据已写入 [ConnectionStore]，界面会自动切到会话页。 */
    suspend fun pair(rawAddress: String, code: String): PairResult {
        val baseUrl = ConnectionStore.normalizeBaseUrl(rawAddress)
        if (baseUrl.isEmpty()) {
            return PairResult.Failure("请输入服务器地址")
        }
        if (code.isBlank()) {
            return PairResult.Failure("请输入配对码")
        }

        return try {
            val info = api.info(baseUrl)
            if (info.protocolVersion != ProtocolVersion.CURRENT) {
                return PairResult.Failure(
                    "协议版本不匹配：服务端 v${info.protocolVersion}，客户端 v${ProtocolVersion.CURRENT}",
                )
            }

            val response = api.pair(
                baseUrl = baseUrl,
                code = code.trim(),
                deviceName = store.deviceName,
                platform = platformName(),
            )

            val connection = Connection(
                baseUrl = baseUrl,
                deviceId = response.deviceId,
                deviceToken = response.deviceToken,
                serverId = response.serverId,
                serverName = response.serverName.ifBlank { info.serverName },
            )

            val previousServerId = store.lastServerId
            store.save(connection)

            // 换了服务端：本地消息缓存的 seq 游标不再有意义（新服务端 seq 从 1 重新开始），
            // 必须清掉，否则增量同步会把新服务端的前 N 条当成「已见过」整段跳过。
            if (previousServerId.isNotEmpty() && previousServerId != connection.serverId) {
                database.messageDao().clear()
            }

            PairResult.Success(connection)
        } catch (e: Exception) {
            PairResult.Failure(e.toUserMessage())
        }
    }

    /**
     * 重新拉一次服务端展示名，并同步到本地。
     *
     * 配对时取过一次就不再更新的话，PC 改了主机名（或改了 `LAN_DROP_SERVER_NAME`）
     * 手机侧会永远显示旧名字——而聊天页标题正是靠它回答「我在跟哪台机器说话」。
     * 拿不到就静默放弃：这只是展示信息，不值得为它打断会话或弹错误。
     */
    suspend fun refreshServerName() {
        val connection = store.connection.value ?: return
        val info = runCatching { api.info(connection.baseUrl) }.getOrNull() ?: return
        val name = info.serverName.trim()
        if (name.isNotEmpty() && name != connection.serverName) {
            store.updateServerName(name)
        }
    }

    fun unpair() = store.clear()

    /** 扫描局域网里的 LAN-Drop 服务端（配对页「扫描局域网」按钮用）。 */
    suspend fun discoverServers(): List<DiscoveredServer> = discovery.discover()

    /**
     * 断线后按 serverId 找回服务端（典型场景：PC 换了 IP、DHCP 续租变了地址）。
     *
     * 由 [AppContainer][io.github.illagercpr.landrop.di.AppContainer] 在收到断线事件时调用；
     * 内部先等一小段「短暂抖动窗口」——普通抖动交给既有退避重连即可，不值得扫一轮 UDP。
     */
    fun onSocketDisconnected() {
        recoveryJob?.cancel()
        recoveryJob = scope.launch {
            delay(RECOVERY_DELAY_MS)
            // 凭据失效时服务端明明活着，扫描只会得到同一个地址；等用户重新配对
            if (socket.state.value == SocketState.CREDENTIALS_INVALID) return@launch
            if (socket.state.value == SocketState.ONLINE) return@launch
            // 找回成功时地址已写入 ConnectionStore，重连由既有机制自动触发
            runCatching { recoverIfServerMoved() }
        }
    }

    /**
     * 扫一轮局域网，找到与本地记录同 `serverId` 的服务端时更新地址。
     *
     * 返回是否发生了地址变更。找不到（服务端确实关了）返回 false，
     * 交给既有的退避重连继续按旧地址重试——那仍是正确的默认行为。
     */
    suspend fun recoverIfServerMoved(): Boolean {
        val connection = store.connection.value ?: return false
        if (connection.serverId.isBlank()) return false

        val now = SystemClock.elapsedRealtime()
        if (now - lastRecoveryAtMs < RECOVERY_MIN_INTERVAL_MS) return false
        lastRecoveryAtMs = now

        val servers = runCatching { discovery.discover(RECOVERY_SCAN_TIMEOUT_MS) }.getOrNull().orEmpty()
        val match = servers.firstOrNull { it.id == connection.serverId } ?: return false
        if (match.baseUrl == connection.baseUrl) return false

        store.updateBaseUrl(match.baseUrl)
        return true
    }

    private fun platformName(): String = "Android ${Build.VERSION.RELEASE}"

    private companion object {
        /** 断线后等这么久还没重连上，才认为值得扫一轮。 */
        const val RECOVERY_DELAY_MS = 4_000L

        /** 两次找回扫描的最小间隔：长时间断线时事件每 ~10 秒来一次，别跟着全跑。 */
        const val RECOVERY_MIN_INTERVAL_MS = 15_000L

        /** 找回扫描比手动扫描更收着用：窗口短一点，快出结论。 */
        const val RECOVERY_SCAN_TIMEOUT_MS = 800L
    }
}
