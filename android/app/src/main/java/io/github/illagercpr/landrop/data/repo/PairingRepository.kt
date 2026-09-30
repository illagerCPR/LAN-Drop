package io.github.illagercpr.landrop.data.repo

import android.os.Build
import io.github.illagercpr.landrop.data.local.LanDropDatabase
import io.github.illagercpr.landrop.data.prefs.Connection
import io.github.illagercpr.landrop.data.prefs.ConnectionStore
import io.github.illagercpr.landrop.net.LanDropApi
import io.github.illagercpr.landrop.net.toUserMessage
import io.github.illagercpr.landrop.protocol.ProtocolVersion

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
) {
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
                Result.success(info.serverName.ifBlank { "LAN-Drop 服务端" })
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

    fun unpair() = store.clear()

    private fun platformName(): String = "Android ${Build.VERSION.RELEASE}"
}
