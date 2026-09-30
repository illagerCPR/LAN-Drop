package io.github.illagercpr.landrop.di

import android.content.Context
import io.github.illagercpr.landrop.data.local.LanDropDatabase
import io.github.illagercpr.landrop.data.prefs.ConnectionStore
import io.github.illagercpr.landrop.data.repo.MessageRepository
import io.github.illagercpr.landrop.data.repo.PairingRepository
import io.github.illagercpr.landrop.data.repo.TransferRepository
import io.github.illagercpr.landrop.net.HttpClientProvider
import io.github.illagercpr.landrop.net.LanDropApi
import io.github.illagercpr.landrop.net.LanDropSocket
import io.github.illagercpr.landrop.net.ProtocolJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * 手写依赖容器。
 *
 * 首版依赖图只有十来个对象、且全是单例，引入 Hilt/Koin 的收益小于它带来的
 * 构建复杂度与注解处理开销；等对象数量或作用域真正变复杂了再换。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /**
     * 协议 JSON（配置见 [ProtocolJson]，与单元测试共用同一份，避免两端行为漂移）。
     */
    val json: Json = ProtocolJson

    /** 应用级作用域：长连接重试、文件传输都挂在这里，跨界面存活。 */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val httpClient = HttpClientProvider.create()

    val connectionStore = ConnectionStore(appContext)

    private val database = LanDropDatabase.get(appContext)

    private val api = LanDropApi(httpClient, json)

    private val socket = LanDropSocket(httpClient, json, appScope)

    val messages = MessageRepository(connectionStore, database, api, socket, appScope)

    val transfers = TransferRepository(appContext, connectionStore, database, api, appScope)

    val pairing = PairingRepository(connectionStore, api, database)

    /** 启动会话（幂等）；在 Application.onCreate 里调一次。 */
    fun start() {
        messages.start()

        // 上次进程若被系统杀掉，传输记录会停在「进行中」，这里统一收尾
        appScope.launch { transfers.reconcileInterruptedTransfers() }
    }
}
