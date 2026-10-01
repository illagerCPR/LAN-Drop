package io.github.illagercpr.landrop.ui.pair

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.illagercpr.landrop.data.prefs.ConnectionStore
import io.github.illagercpr.landrop.data.repo.PairResult
import io.github.illagercpr.landrop.data.repo.PairingRepository
import io.github.illagercpr.landrop.net.DiscoveredServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 配对页状态机。 */
class PairingViewModel(
    private val pairing: PairingRepository,
    store: ConnectionStore,
) : ViewModel() {

    var address by mutableStateOf(store.lastBaseUrl)
        private set

    var code by mutableStateOf("")
        private set

    /**
     * 扫码得到的 TLS 指纹（`#fp=` 参数），随下次配对/探活交给仓库核对。
     * 用户手动改地址即视为重新声明目标，指纹随之作废（避免拿 A 服务端的
     * 指纹去配 B 服务端）；扫到新码时会覆盖。
     */
    private var scannedFingerprint: String? = null

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 操作结果提示：成功为绿色语气，失败为错误语气，由界面区分。 */
    private val _feedback = MutableStateFlow<Feedback?>(null)
    val feedback: StateFlow<Feedback?> = _feedback.asStateFlow()

    /**
     * 配对成功的一次性信号：AppRoot 的「添加服务端」覆盖层据此自动退出。
     * 不能复用 [feedback]——「测试连接」成功也是成功语气，不该把用户踢出配对页。
     */
    private val _pairingCompleted = MutableStateFlow(false)
    val pairingCompleted: StateFlow<Boolean> = _pairingCompleted.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _discovered = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val discovered: StateFlow<List<DiscoveredServer>> = _discovered.asStateFlow()

    val deviceName: String = store.deviceName

    fun onAddressChange(value: String) {
        address = value
        scannedFingerprint = null
        _feedback.value = null
    }

    fun onCodeChange(value: String) {
        // 服务端的配对码字母表是大写字母+数字（刻意排除 0/O、1/I/L 等易混字符），
        // 因此这里统一过滤并转大写，用户用小写键盘输入也能配对成功。
        code = value.filter { it.isLetterOrDigit() }.uppercase().take(MAX_CODE_LENGTH)
        _feedback.value = null
    }

    /** 「测试连接」：只探活，不消耗配对码（配对码是一次性的，试错成本高）。 */
    fun testConnection() {
        if (_busy.value) return

        viewModelScope.launch {
            _busy.value = true
            val result = pairing.probe(address, scannedFingerprint)
            _feedback.value = result.fold(
                onSuccess = { name -> Feedback("已连接到「$name」，可以输入配对码了", isError = false) },
                onFailure = { Feedback(it.message ?: "连接失败", isError = true) },
            )
            _busy.value = false
        }
    }

    fun pair() {
        if (_busy.value) return

        viewModelScope.launch {
            _busy.value = true
            when (val result = pairing.pair(address, code, scannedFingerprint)) {
                is PairResult.Success -> {
                    // 凭据落盘后 ConnectionStore 会推送新值，界面自动切到会话页；
                    // 「添加服务端」覆盖层据此信号自动关闭
                    _pairingCompleted.value = true
                    _feedback.value = Feedback("已连接到「${result.connection.serverName}」", isError = false)
                }

                is PairResult.Failure -> _feedback.value = Feedback(result.message, isError = true)
            }
            _busy.value = false
        }
    }

    /**
     * 扫描局域网里的 LAN-Drop 服务端（UDP 自动发现）。
     *
     * 扫描不到不算错误状态以外的异常：服务端可能没开机、也可能在另一个网段，
     * 提示文案要把「下一步可以做什么」说清楚。
     */
    fun scan() {
        if (_scanning.value) return

        viewModelScope.launch {
            _scanning.value = true
            _discovered.value = runCatching { pairing.discoverServers() }.getOrDefault(emptyList())
            _scanning.value = false
            _feedback.value = if (_discovered.value.isEmpty()) {
                Feedback("没有扫描到服务端：请确认 PC 已启动并与手机在同一 WiFi，或手动输入地址", isError = true)
            } else {
                Feedback("扫描到 ${_discovered.value.size} 台服务端，点击即可填入地址", isError = false)
            }
        }
    }

    /** 点选一台扫描到的服务端：只填地址，配对码仍需用户在 PC 页面上读。 */
    fun useDiscovered(server: DiscoveredServer) {
        address = server.baseUrl
        // 发现应答没有指纹（UDP 是可伪造信道，指纹不能从那里来）：
        // 这条路走 TOFU 信任边界，扫码才是带强校验的路径，提示里说清楚
        scannedFingerprint = null
        _feedback.value = Feedback(
            "已填入「${server.name}」的地址，输入配对码即可配对（扫码配对可获得加密校验）",
            isError = false,
        )
    }

    /**
     * 处理扫码结果。
     *
     * 二维码内容形如 `https://192.168.1.100:8787/#pair=123456&fp=<指纹>`，
     * 一次把地址、配对码与 TLS 指纹都填好，用户只需点「配对」。
     */
    fun applyScanned(content: String) {
        val normalized = ConnectionStore.normalizeBaseUrl(content)
        val scannedCode = ConnectionStore.extractPairingCode(content)
        val fingerprint = ConnectionStore.extractPairingFingerprint(content)

        if (normalized.isNotEmpty()) address = normalized
        if (scannedCode != null) code = scannedCode
        scannedFingerprint = fingerprint

        _feedback.value = when {
            normalized.isEmpty() -> Feedback("二维码里没有可用的服务器地址", isError = true)
            scannedCode == null -> Feedback("已填入服务器地址，请手动输入配对码", isError = false)
            else -> Feedback("已从二维码填入地址与配对码", isError = false)
        }
    }

    data class Feedback(val message: String, val isError: Boolean)

    /** 消费配对完成信号（AppRoot 关闭覆盖层后调用，避免下次进入配对页立即被弹回）。 */
    fun consumePairingCompleted() {
        _pairingCompleted.value = false
    }

    private companion object {
        const val MAX_CODE_LENGTH = 32
    }
}
