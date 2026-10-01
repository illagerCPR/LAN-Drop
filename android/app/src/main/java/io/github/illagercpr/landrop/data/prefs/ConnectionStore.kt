package io.github.illagercpr.landrop.data.prefs

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 一台已配对服务器的连接信息。
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
 * 连接凭据与服务器地址的本地存储——**多服务端**。
 *
 * 每台服务端一组凭据（按 serverId 存一行），另有一个「当前服务端」指针。
 * 消息缓存与传输记录在 Room 里按 serverId 隔离，切换服务端不清缓存、
 * 不重配对；凭据只对各自的服务端有效，泄露面没有变大。
 *
 * 用 SharedPreferences 而非 DataStore：数据量极小、需要同步读取
 * （OkHttp 拦截器取 token 是同步路径），且首版不加密——token 只对这一台
 * 局域网服务端有效，泄露面有限，服务端删除设备行即可撤销。
 *
 * 0.1.x 的单服务端格式在首次读取时自动迁移（旧键 → `server.<id>.*`）。
 */
class ConnectionStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _connections = MutableStateFlow(readAll())
    private val _activeServerId = MutableStateFlow(readActiveServerId())
    private val _connection = MutableStateFlow(activeConnection())

    /** 当前连接；未配对时为 null，界面据此在配对页与聊天页之间切换。 */
    val connection: StateFlow<Connection?> = _connection.asStateFlow()

    /** 已保存的全部服务端（最近使用的在前），供服务端切换器展示。 */
    val connections: StateFlow<List<Connection>> = _connections.asStateFlow()

    /** 当前活动服务端 ID；未配对时为空。 */
    val activeServerId: String
        get() = _activeServerId.value.orEmpty()

    /** 上一次使用的服务器地址，配对页预填，省得每次重敲。 */
    val lastBaseUrl: String
        get() = prefs.getString(KEY_LAST_BASE_URL, null).orEmpty()

    /**
     * 当前服务端 ID（= [activeServerId] 的旧名，保留既有调用点语义）。
     */
    val lastServerId: String
        get() = activeServerId

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

    /**
     * 保存（新增或更新）一台服务端的凭据，并把它设为当前服务端。
     *
     * 更新已有条目（改名、换 IP、刷新指纹）不会动排序；新条目排到最前。
     */
    fun save(connection: Connection) {
        val existing = _connections.value.filterNot { it.serverId == connection.serverId }
        val knownBefore = existing.size < _connections.value.size
        val next = if (knownBefore) {
            // 已有条目保持原位（不因改名跳位）
            val index = _connections.value.indexOfFirst { it.serverId == connection.serverId }
            _connections.value.toMutableList().also { it[index] = connection }
        } else {
            listOf(connection) + existing
        }
        writeAll(next, connection.serverId)
    }

    /** 切换当前服务端；凭据、缓存都不动，连接与时间线由各 StateFlow 自然跟上。 */
    fun switchTo(serverId: String): Boolean {
        if (_connections.value.none { it.serverId == serverId }) return false
        if (_activeServerId.value == serverId) return true
        writeAll(_connections.value, serverId)
        return true
    }

    /**
     * 解除一台服务端的配对：删除凭据行。删的是当前服务端时连接变为 null，
     * 界面回到配对页。消息缓存刻意保留（纯缓存行，无法再被看到也不碍事，
     * 重新配对同一台服务端后立即恢复显示）。
     */
    fun remove(serverId: String) {
        val remaining = _connections.value.filterNot { it.serverId == serverId }
        val removed = _connections.value.firstOrNull { it.serverId == serverId }
        // 保留地址给配对页预填：删错了还想加回来是高频动作
        if (removed != null) {
            prefs.edit { putString(KEY_LAST_BASE_URL, removed.baseUrl) }
        }
        val nextActive = when {
            _activeServerId.value != serverId -> _activeServerId.value
            remaining.isNotEmpty() -> remaining.first().serverId
            else -> null
        }
        writeAll(remaining, nextActive)
    }

    /**
     * 服务端换了地址（换 IP、换端口）后原地更新，凭据保持不变。
     *
     * `connection` 是 StateFlow，新值会直接触发 [MessageRepository] 的重连；
     * 典型来源是断线后按 serverId 的自动找回（[PairingRepository.recoverIfServerMoved]）。
     */
    fun updateBaseUrl(baseUrl: String) {
        val current = _connection.value ?: return
        updateEntry(current.copy(baseUrl = baseUrl))
    }

    /** 服务端改名后同步本地显示，不重新配对。 */
    fun updateServerName(name: String) {
        val current = _connection.value ?: return
        updateEntry(current.copy(serverName = name))
    }

    /** 按 serverId 找连接（传输恢复用：任务属于哪台服务端就连哪台）。 */
    fun byServerId(serverId: String): Connection? =
        _connections.value.firstOrNull { it.serverId == serverId }

    /**
     * 解除当前服务端的配对：清空凭据（保留设备名与地址预填）。
     * 多服务端下等价于 [remove] 当前活动条目，保留旧名是为了既有调用点。
     */
    fun clear() {
        val active = _activeServerId.value ?: return
        remove(active)
    }

    // ------------------------------------------------------------------ 内部

    private fun updateEntry(connection: Connection) {
        val next = _connections.value.map { if (it.serverId == connection.serverId) connection else it }
        writeAll(next, _activeServerId.value)
    }

    private fun activeConnection(): Connection? {
        val id = _activeServerId.value ?: return null
        return _connections.value.firstOrNull { it.serverId == id }
    }

    private fun readAll(): List<Connection> {
        val ids = prefs.getString(KEY_SERVER_IDS, null)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val servers = ids.mapNotNull(::readOne)
        if (servers.isEmpty()) return migrateLegacy()

        // ids 列表里可能出现已损坏的条目，读不出来就剔除
        if (servers.size != ids.size) writeAll(servers, readActiveServerId())
        return servers
    }

    private fun readActiveServerId(): String? =
        prefs.getString(KEY_ACTIVE_SERVER_ID, null)?.takeIf { it.isNotBlank() }

    private fun readOne(serverId: String): Connection? {
        val baseUrl = prefs.getString(serverKey(serverId, "baseUrl"), null)
            ?.takeIf { it.isNotBlank() } ?: return null
        val deviceId = prefs.getString(serverKey(serverId, "deviceId"), null)
            ?.takeIf { it.isNotBlank() } ?: return null
        val token = prefs.getString(serverKey(serverId, "deviceToken"), null)
            ?.takeIf { it.isNotBlank() } ?: return null

        return Connection(
            baseUrl = baseUrl,
            deviceId = deviceId,
            deviceToken = token,
            serverId = serverId,
            serverName = prefs.getString(serverKey(serverId, "serverName"), null).orEmpty(),
            tlsFingerprint = prefs.getString(serverKey(serverId, "tlsFingerprint"), null)
                ?.takeIf { it.isNotBlank() },
        )
    }

    /** 把一台服务端的全部字段写进 prefs（不含 ids 列表与活动指针）。 */
    private fun writeOne(connection: Connection) {
        val prefix = serverKey(connection.serverId, "")
        prefs.edit {
            putString("${prefix}baseUrl", connection.baseUrl)
            putString("${prefix}deviceId", connection.deviceId)
            putString("${prefix}deviceToken", connection.deviceToken)
            putString("${prefix}serverName", connection.serverName)
            if (connection.tlsFingerprint != null) {
                putString("${prefix}tlsFingerprint", connection.tlsFingerprint)
            } else {
                remove("${prefix}tlsFingerprint")
            }
        }
    }

    private fun eraseOne(serverId: String) {
        prefs.edit {
            remove(serverKey(serverId, "baseUrl"))
            remove(serverKey(serverId, "deviceId"))
            remove(serverKey(serverId, "deviceToken"))
            remove(serverKey(serverId, "serverName"))
            remove(serverKey(serverId, "tlsFingerprint"))
        }
    }

    /** 一次性把整份状态（列表 + 活动指针）落盘并推送各 StateFlow。 */
    private fun writeAll(servers: List<Connection>, activeServerId: String?) {
        prefs.edit {
            putString(KEY_SERVER_IDS, servers.joinToString(",") { it.serverId })
            if (activeServerId != null && servers.any { it.serverId == activeServerId }) {
                putString(KEY_ACTIVE_SERVER_ID, activeServerId)
            } else {
                remove(KEY_ACTIVE_SERVER_ID)
            }
        }
        val knownIds = servers.map { it.serverId }.toSet()
        // 物理删除已不在列表里的条目（解除配对后不留僵尸行）
        readStoredIdsSnapshot().filterNot { it in knownIds }.forEach(::eraseOne)
        servers.forEach(::writeOne)

        _connections.value = servers
        _activeServerId.value = activeServerId
        _connection.value = activeServerId?.let { id -> servers.firstOrNull { it.serverId == id } }
    }

    /** writeAll 前的旧 ids 快照，用于找出需要物理删除的条目。 */
    private fun readStoredIdsSnapshot(): List<String> =
        prefs.getString(KEY_SERVER_IDS, null)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    /**
     * 0.1.x 单服务端格式迁移：旧平铺键 → 新 `server.<id>.*` 格式。
     * 没有旧数据时是空操作。迁移走的是同一条 [writeAll]，StateFlow 因此有初值。
     */
    private fun migrateLegacy(): List<Connection> {
        val baseUrl = prefs.getString("baseUrl", null)?.takeIf { it.isNotBlank() }
        val deviceId = prefs.getString("deviceId", null)?.takeIf { it.isNotBlank() }
        val token = prefs.getString("deviceToken", null)?.takeIf { it.isNotBlank() }
        if (baseUrl == null || deviceId == null || token == null) return emptyList()

        val legacy = Connection(
            baseUrl = baseUrl,
            deviceId = deviceId,
            deviceToken = token,
            serverId = prefs.getString("serverId", null).orEmpty().ifBlank { "legacy" },
            serverName = prefs.getString("serverName", null).orEmpty(),
            tlsFingerprint = prefs.getString("tlsFingerprint", null)?.takeIf { it.isNotBlank() },
        )
        writeAll(listOf(legacy), legacy.serverId)
        // 旧键清掉，防止下次迁移重复叠加
        prefs.edit {
            remove("baseUrl")
            remove("deviceId")
            remove("deviceToken")
            remove("serverId")
            remove("serverName")
            remove("tlsFingerprint")
        }
        return listOf(legacy)
    }

    companion object {
        private const val PREFS_NAME = "lan-drop.connection"
        private const val KEY_SERVER_IDS = "serverIds"
        private const val KEY_ACTIVE_SERVER_ID = "activeServerId"
        private const val KEY_LAST_BASE_URL = "lastBaseUrl"
        private const val KEY_DEVICE_NAME = "deviceName"

        private fun serverKey(serverId: String, field: String): String = "server.$serverId.$field"

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
