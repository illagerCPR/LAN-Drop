package io.github.illagercpr.landrop.data.prefs

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 已配对服务器的连接信息。
 *
 * [baseUrl] 是归一化后的形态（形如 `http://192.168.1.100:8787`，无尾斜杠），
 * 拼接路径时直接用字符串相加即可。
 *
 * [tlsFingerprint] 是服务端证书的 SPKI sha256（base64url 无填充）：非 null 时
 * 所有 TLS 连接都按它固定校验（SSH TOFU 模型）；null 表示明文服务端或升级前
 * 配对的老凭据——后者连上启用 TLS 的服务端会进「请重新配对」状态。
 */
data class Connection(
    val baseUrl: String,
    val deviceId: String,
    val deviceToken: String,
    val serverId: String,
    val serverName: String,
    val tlsFingerprint: String? = null,
)

/**
 * 连接凭据与服务器地址的本地存储。
 *
 * 用 SharedPreferences 而非 DataStore：数据量极小、需要同步读取
 * （OkHttp 拦截器取 token 是同步路径），且首版不加密——token 只对这一台
 * 局域网服务端有效，泄露面有限，服务端删除设备行即可撤销。
 */
class ConnectionStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _connection = MutableStateFlow(read())

    /** 当前连接；未配对时为 null，界面据此在配对页与聊天页之间切换。 */
    val connection: StateFlow<Connection?> = _connection.asStateFlow()

    /** 上一次使用的服务器地址，配对页预填，省得每次重敲。 */
    val lastBaseUrl: String
        get() = prefs.getString(KEY_BASE_URL, null).orEmpty()

    /**
     * 最近一次配对的服务器 ID。
     *
     * 解除配对后依然保留：消息缓存的增量游标（本地最大 `seq`）只对同一台服务端有效，
     * 换一台服务端必须清缓存，否则新服务端 seq 从 1 开始会被旧游标整段跳过。
     */
    val lastServerId: String
        get() = prefs.getString(KEY_SERVER_ID, null).orEmpty()

    /**
     * 本机设备名：配对时上报服务端，也是聊天里显示的名字。
     * 缺省取手机型号（如 `2201122C`），PC 端一眼能认出来是哪台机器。
     */
    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, null)?.takeIf { it.isNotBlank() }
            ?: defaultDeviceName()
        set(value) {
            prefs.edit { putString(KEY_DEVICE_NAME, value.trim()) }
        }

    fun save(connection: Connection) {
        prefs.edit {
            putString(KEY_BASE_URL, connection.baseUrl)
            putString(KEY_DEVICE_ID, connection.deviceId)
            putString(KEY_DEVICE_TOKEN, connection.deviceToken)
            putString(KEY_SERVER_ID, connection.serverId)
            putString(KEY_SERVER_NAME, connection.serverName)
            if (connection.tlsFingerprint != null) {
                putString(KEY_TLS_FINGERPRINT, connection.tlsFingerprint)
            } else {
                remove(KEY_TLS_FINGERPRINT)
            }
        }
        _connection.value = connection
    }

    /** 服务端改名后同步本地显示，不重新配对。 */
    fun updateServerName(name: String) {
        val current = _connection.value ?: return
        prefs.edit { putString(KEY_SERVER_NAME, name) }
        _connection.value = current.copy(serverName = name)
    }

    /**
     * 服务端换了地址（换 IP、换端口）后原地更新，凭据保持不变。
     *
     * `connection` 是 StateFlow，新值会直接触发 [MessageRepository] 的重连；
     * 典型来源是断线后按 serverId 的自动找回（[PairingRepository.recoverIfServerMoved]）。
     */
    fun updateBaseUrl(baseUrl: String) {
        val current = _connection.value ?: return
        prefs.edit { putString(KEY_BASE_URL, baseUrl) }
        _connection.value = current.copy(baseUrl = baseUrl)
    }

    /**
     * 解除配对：清空凭据（保留设备名、上次地址与 serverId，供重新配对与
     * 「是否换了服务端」的判断使用）。
     */
    fun clear() {
        val name = deviceName
        val address = lastBaseUrl
        prefs.edit {
            remove(KEY_DEVICE_ID)
            remove(KEY_DEVICE_TOKEN)
            remove(KEY_SERVER_NAME)
            putString(KEY_DEVICE_NAME, name)
            putString(KEY_BASE_URL, address)
        }
        _connection.value = null
    }

    private fun read(): Connection? {
        val baseUrl = prefs.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        val deviceId = prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val token = prefs.getString(KEY_DEVICE_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null

        return Connection(
            baseUrl = baseUrl,
            deviceId = deviceId,
            deviceToken = token,
            serverId = prefs.getString(KEY_SERVER_ID, null).orEmpty(),
            serverName = prefs.getString(KEY_SERVER_NAME, null).orEmpty(),
            tlsFingerprint = prefs.getString(KEY_TLS_FINGERPRINT, null)?.takeIf { it.isNotBlank() },
        )
    }

    companion object {
        private const val PREFS_NAME = "lan-drop.connection"
        private const val KEY_BASE_URL = "baseUrl"
        private const val KEY_DEVICE_ID = "deviceId"
        private const val KEY_DEVICE_TOKEN = "deviceToken"
        private const val KEY_SERVER_ID = "serverId"
        private const val KEY_SERVER_NAME = "serverName"
        private const val KEY_DEVICE_NAME = "deviceName"
        private const val KEY_TLS_FINGERPRINT = "tlsFingerprint"

        /** 服务端默认端口，与 `apps/server/src/config.ts` 保持一致。 */
        const val DEFAULT_PORT = 8787

        fun defaultDeviceName(): String =
            Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android 设备"

        /**
         * 把用户输入归一化成基地址。
         *
         * 容忍这几种写法：`192.168.1.100`、`192.168.1.100:8787`、
         * `http://192.168.1.100:8787/`、以及扫码得到的完整 URL（会截掉路径与 hash）。
         * 没写端口时补默认 8787——局域网服务端端口固定，省得用户再敲一遍。
         */
        fun normalizeBaseUrl(input: String): String {
            var text = input.trim()
            if (text.isEmpty()) return ""

            // 扫码/粘贴可能带路径与 hash（如 http://ip:8787/#pair=123456），只取 origin
            text = text.substringBefore('#').substringBefore('?')

            if (!text.startsWith("http://") && !text.startsWith("https://")) {
                text = "http://$text"
            }

            val schemeEnd = text.indexOf("://") + 3
            val authority = text.substring(schemeEnd).substringBefore('/')
            if (authority.isEmpty()) return ""

            val port = authority.substringAfterLast(':', "").toIntOrNull()
            val normalized = text.substring(0, schemeEnd) + authority
            return if (port != null) normalized else "$normalized:$DEFAULT_PORT"
        }

        /** 从扫码内容里摘出配对码（`#pair=CODE`）；没有则返回 null。 */
        fun extractPairingCode(scanned: String): String? {
            val marker = "pair="
            val index = scanned.indexOf(marker)
            if (index < 0) return null
            return scanned.substring(index + marker.length)
                .takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
                .takeIf { it.isNotEmpty() }
        }

        /**
         * 从扫码内容里摘出 TLS 指纹（`#fp=<base64url sha256>`）。
         *
         * 指纹随二维码走（相机是攻击者插不进的视觉信道），配对时与服务端自报的
         * 指纹核对一致才固化——不符即中间人，立即中止。没有 fp 参数返回 null
         * （老服务端二维码 / 手输地址路径走 TOFU 信任边界）。
         */
        fun extractPairingFingerprint(scanned: String): String? {
            val marker = "fp="
            val index = scanned.indexOf(marker)
            if (index < 0) return null
            val value = scanned.substring(index + marker.length)
                .takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
            // sha256 base64url 无填充恒为 43 字符；其余形态一律不采信
            return value.takeIf { it.length == 43 }
        }
    }
}
