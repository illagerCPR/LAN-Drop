package io.github.illagercpr.landrop.data.repo

import android.os.Build
import android.os.SystemClock
import io.github.illagercpr.landrop.data.prefs.Connection
import io.github.illagercpr.landrop.data.prefs.ConnectionStore
import io.github.illagercpr.landrop.net.DiscoveredServer
import io.github.illagercpr.landrop.net.FingerprintPins
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
    private val socket: LanDropSocket,
    private val discovery: ServerDiscovery,
    private val scope: CoroutineScope,
) {
    private var recoveryJob: Job? = null

    /** 上次找回扫描的时间（单调时钟），防止长时间断线期间高频 UDP 扫描。 */
    @Volatile
    private var lastRecoveryAtMs = 0L
    /**
     * 探测服务端，成功时返回它的展示名（配对页的「测试连接」按钮用）。
     *
     * [scannedFingerprint] 来自配对二维码（`#fp=` 参数）：有它就走 pinned 客户端，
     * 证书不符直接握手失败——这是最强的首次接触；没有（手输地址）就走配对引导的
     * 宽松 TLS，信任边界退化为 TOFU（见 [pair]）。
     */
    suspend fun probe(rawAddress: String, scannedFingerprint: String? = null): Result<String> {
        val baseUrl = ConnectionStore.normalizeBaseUrl(rawAddress)
        if (baseUrl.isEmpty()) {
            return Result.failure(IllegalArgumentException("请输入服务器地址"))
        }

        return try {
            val info = api.info(baseUrl, scannedFingerprint)
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

    /**
     * 执行配对；成功后凭据已写入 [ConnectionStore]，界面会自动切到会话页。
     *
     * TLS 信任决策（自签证书 + SPKI 指纹固定）：
     *  1. 服务端 `tls=true` 却没下发指纹 → 协议不完整，拒绝配对；
     *  2. 二维码带了指纹（[scannedFingerprint]）→ 必须与服务端自报一致，
     *     不一致就是中间人，**立即中止**（相机信道 vs 网络信道对不上的唯一解释）；
     *  3. 没有二维码指纹（手输地址）→ 采纳服务端自报指纹（TOFU，与 SSH 首连
     *     同一信任边界），固化后业务连接全部按它固定校验。
     */
    suspend fun pair(
        rawAddress: String,
        code: String,
        scannedFingerprint: String? = null,
    ): PairResult {
        val baseUrl = ConnectionStore.normalizeBaseUrl(rawAddress)
        if (baseUrl.isEmpty()) {
            return PairResult.Failure("请输入服务器地址")
        }
        if (code.isBlank()) {
            return PairResult.Failure("请输入配对码")
        }

        return try {
            val info = api.info(baseUrl, scannedFingerprint)
            if (info.protocolVersion != ProtocolVersion.CURRENT) {
                return PairResult.Failure(
                    "协议版本不匹配：服务端 v${info.protocolVersion}，客户端 v${ProtocolVersion.CURRENT}",
                )
            }

            val serverFingerprint = info.tlsFingerprint?.takeIf { it.isNotBlank() }
            if (info.tls && serverFingerprint == null) {
                return PairResult.Failure("服务端已启用加密但未下发证书指纹，请把服务端升级到配套版本")
            }
            if (scannedFingerprint != null && !FingerprintPins.matches(scannedFingerprint, serverFingerprint!!)) {
                return PairResult.Failure("服务端证书指纹与二维码不一致，当前网络可能被劫持，已中止配对")
            }
            val effectiveFingerprint = serverFingerprint

            val response = api.pair(
                baseUrl = baseUrl,
                code = code.trim(),
                deviceName = store.deviceName,
                platform = platformName(),
                tlsFingerprint = effectiveFingerprint,
            )

            // 配对开始与结束时服务端各报了一次指纹：两头都有却不一致 = 信道中途被换，
            // 这份凭据不能要
            val responseFingerprint = response.tlsFingerprint?.takeIf { it.isNotBlank() }
            if (
                effectiveFingerprint != null &&
                responseFingerprint != null &&
                !FingerprintPins.matches(effectiveFingerprint, responseFingerprint)
            ) {
                return PairResult.Failure("配对过程中服务端证书发生变化，请重新扫码配对")
            }

            val connection = Connection(
                baseUrl = baseUrl,
                deviceId = response.deviceId,
                deviceToken = response.deviceToken,
                serverId = response.serverId,
                serverName = response.serverName.ifBlank { info.serverName },
                tlsFingerprint = effectiveFingerprint,
            )

            // 多服务端下缓存按 serverId 隔离、游标各自单调，换服务端不再需要清缓存
            store.save(connection)

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
        val info = runCatching { api.info(connection.baseUrl, connection.tlsFingerprint) }.getOrNull() ?: return
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
            // TLS_UNTRUSTED 同理：指纹只能来自重新扫码，扫描补不上
            if (socket.state.value == SocketState.TLS_UNTRUSTED) return@launch
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
     *
     * 特例：扫描发现「还是那台服务端」但它已启用 TLS（升级场景），而本地凭据
     * 是升级前的老配对（没有指纹）——指纹只能来自重新扫码，此时进入
     * [SocketState.TLS_UNTRUSTED] 停止无意义的重连循环，横幅直接给出重新配对的出路。
     */
    suspend fun recoverIfServerMoved(): Boolean {
        val connection = store.connection.value ?: return false
        if (connection.serverId.isBlank()) return false

        val now = SystemClock.elapsedRealtime()
        if (now - lastRecoveryAtMs < RECOVERY_MIN_INTERVAL_MS) return false
        lastRecoveryAtMs = now

        val servers = runCatching { discovery.discover(RECOVERY_SCAN_TIMEOUT_MS) }.getOrNull().orEmpty()
        val match = servers.firstOrNull { it.id == connection.serverId } ?: return false
        if (match.tls && connection.tlsFingerprint.isNullOrBlank()) {
            socket.enterTlsUntrusted()
            return false
        }
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
