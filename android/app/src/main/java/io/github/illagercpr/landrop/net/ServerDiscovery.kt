package io.github.illagercpr.landrop.net

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** UDP 发现应答。与服务端 `apps/server/src/discovery.ts` 的 `DiscoveryAnnounce` 逐字段对齐。 */
@Serializable
data class DiscoveryAnnounceDto(
    val service: String,
    val v: Int,
    val id: String,
    val name: String,
    val port: Int,
)

/** 扫描到的一台服务端。 */
data class DiscoveredServer(
    val id: String,
    val name: String,
    val baseUrl: String,
)

/** 解析发现应答；任何不合规（非本服务、版本不符、字段缺失）都返回 null。 */
internal fun parseAnnounce(json: Json, text: String): DiscoveryAnnounceDto? {
    val announce = runCatching { json.decodeFromString(DiscoveryAnnounceDto.serializer(), text) }
        .getOrNull()
        ?: return null
    if (announce.service != "lan-drop") return null
    if (announce.v != 1) return null
    if (announce.id.isBlank()) return null
    if (announce.port !in 1..65535) return null
    return announce
}

/**
 * UDP 局域网自动发现（与服务端 `discovery.ts` 成对实现）。
 *
 * 向广播地址发探测报文，服务端以**单播**应答——应答不是广播/组播，
 * 因此不申请 MulticastLock 也收得到。整个扫描占用约一秒的接收窗口，
 * 窗口内来几台答几台，按来源地址去重。
 *
 * 发送目标同时覆盖 255.255.255.255 与各网卡的定向广播地址：
 * 部分 AP/ROM 组合会吞掉其中一种，两种都发不增加用户可感知的延迟。
 */
class ServerDiscovery(
    private val json: Json,
    private val port: Int = DISCOVERY_PORT,
) {
    suspend fun discover(timeoutMs: Long = DISCOVERY_TIMEOUT_MS): List<DiscoveredServer> =
        withContext(Dispatchers.IO) { scan(timeoutMs) }

    private fun scan(timeoutMs: Long): List<DiscoveredServer> {
        val found = LinkedHashMap<String, DiscoveredServer>()
        val payload = DISCOVERY_MAGIC.toByteArray(Charsets.UTF_8)
        val targets = broadcastTargets()
        if (targets.isEmpty()) return emptyList()

        DatagramSocket().use { socket ->
            socket.broadcast = true
            // receive 靠超时分片轮询：整个窗口内都能收应答，而不是一收不到就放弃
            socket.soTimeout = RECEIVE_SLICE_MS.toInt()

            for (target in targets) {
                runCatching {
                    socket.send(DatagramPacket(payload, payload.size, target, port))
                }
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(MAX_PACKET_BYTES)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: IOException) {
                    break // 网络接口没了，本轮扫描作废
                }

                val host = packet.address?.hostAddress ?: continue
                val announce = parseAnnounce(json, String(packet.data, 0, packet.length, Charsets.UTF_8))
                    ?: continue
                found[host] = DiscoveredServer(
                    id = announce.id,
                    name = announce.name.ifBlank { "LAN-Drop" },
                    baseUrl = "http://$host:${announce.port}",
                )
            }
        }
        return found.values.toList()
    }

    private fun broadcastTargets(): List<InetAddress> {
        val targets = LinkedHashSet<InetAddress>()
        runCatching {
            targets += InetAddress.getByName("255.255.255.255")
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            for (nif in interfaces.asSequence()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (address in nif.interfaceAddresses) {
                    address.broadcast?.let(targets::add)
                }
            }
        }
        return targets.toList()
    }

    companion object {
        /** 与服务端 `config.ts` 的 `discoveryPort` 默认值一致；改这里必须两边同步。 */
        const val DISCOVERY_PORT = 8788

        private const val DISCOVERY_MAGIC = "LANDROP-DISCOVER-v1"

        /** 单次扫描的接收窗口。 */
        private const val DISCOVERY_TIMEOUT_MS = 1_000L

        /** receive 的单次等待：窗口被切成小片轮询。 */
        private const val RECEIVE_SLICE_MS = 50L

        private const val MAX_PACKET_BYTES = 1024
    }
}
