package io.github.illagercpr.landrop.ui.pair

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.illagercpr.landrop.data.prefs.ConnectionStore
import io.github.illagercpr.landrop.data.repo.PairResult
import io.github.illagercpr.landrop.data.repo.PairingRepository
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

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 操作结果提示：成功为绿色语气，失败为错误语气，由界面区分。 */
    private val _feedback = MutableStateFlow<Feedback?>(null)
    val feedback: StateFlow<Feedback?> = _feedback.asStateFlow()

    val deviceName: String = store.deviceName

    fun onAddressChange(value: String) {
        address = value
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
            val result = pairing.probe(address)
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
            when (val result = pairing.pair(address, code)) {
                is PairResult.Success ->
                    // 凭据落盘后 ConnectionStore 会推送新值，界面自动切到会话页
                    _feedback.value = Feedback("已连接到「${result.connection.serverName}」", isError = false)

                is PairResult.Failure -> _feedback.value = Feedback(result.message, isError = true)
            }
            _busy.value = false
        }
    }

    /**
     * 处理扫码结果。
     *
     * 二维码内容形如 `http://192.168.1.100:8787/#pair=123456`，
     * 一次把地址与配对码都填好，用户只需点「配对」。
     */
    fun applyScanned(content: String) {
        val normalized = ConnectionStore.normalizeBaseUrl(content)
        val scannedCode = ConnectionStore.extractPairingCode(content)

        if (normalized.isNotEmpty()) address = normalized
        if (scannedCode != null) code = scannedCode

        _feedback.value = when {
            normalized.isEmpty() -> Feedback("二维码里没有可用的服务器地址", isError = true)
            scannedCode == null -> Feedback("已填入服务器地址，请手动输入配对码", isError = false)
            else -> Feedback("已从二维码填入地址与配对码", isError = false)
        }
    }

    data class Feedback(val message: String, val isError: Boolean)

    private companion object {
        const val MAX_CODE_LENGTH = 32
    }
}
