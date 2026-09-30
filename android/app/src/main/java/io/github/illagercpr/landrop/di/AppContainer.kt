package io.github.illagercpr.landrop.di

import android.content.Context
import io.github.illagercpr.landrop.data.local.LanDropDatabase
import io.github.illagercpr.landrop.data.prefs.ConnectionStore
import io.github.illagercpr.landrop.data.prefs.SettingsStore
import io.github.illagercpr.landrop.data.repo.AutoReceiveStarter
import io.github.illagercpr.landrop.data.repo.MessageRepository
import io.github.illagercpr.landrop.data.repo.PairingRepository
import io.github.illagercpr.landrop.data.repo.TransferRepository
import io.github.illagercpr.landrop.data.repo.TransferServiceLauncher
import io.github.illagercpr.landrop.net.HttpClientProvider
import io.github.illagercpr.landrop.net.LanDropApi
import io.github.illagercpr.landrop.net.LanDropSocket
import io.github.illagercpr.landrop.net.ProtocolJson
import io.github.illagercpr.landrop.net.ServerDiscovery
import io.github.illagercpr.landrop.net.WsEvent
import io.github.illagercpr.landrop.notify.MessageNotifier
import io.github.illagercpr.landrop.notify.TransferService
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

    val settings = SettingsStore(appContext)

    private val database = LanDropDatabase.get(appContext)

    private val api = LanDropApi(httpClient, json)

    private val socket = LanDropSocket(httpClient, json, appScope)

    /** UDP 局域网发现：配对页扫描与断线找回共用同一个客户端。 */
    private val discovery = ServerDiscovery(json)

    val messages = MessageRepository(
        store = connectionStore,
        db = database,
        api = api,
        socket = socket,
        scope = appScope,
        notifier = MessageNotifier(appContext),
        settings = settings,
        autoReceive = AutoReceiveStarter { message ->
            val fileId = message.fileId ?: return@AutoReceiveStarter
            transfers.download(
                fileId = fileId,
                fileName = message.fileName ?: "file",
                mime = message.fileMime,
                size = message.fileSize ?: 0L,
                messageId = message.id,
            )
        },
    )

    val transfers = TransferRepository(
        context = appContext,
        store = connectionStore,
        db = database,
        api = api,
        scope = appScope,
        // 前台服务与界面共用这个容器，因此服务里看到的传输状态就是界面上的那一份
        foreground = TransferServiceLauncher { TransferService.start(appContext) },
    )

    val pairing = PairingRepository(connectionStore, api, database, socket, discovery, appScope)

    /** 启动会话（幂等）；在 Application.onCreate 里调一次。 */
    fun start() {
        messages.start()

        // 断线时给配对层一个按 serverId 找回服务端的机会（PC 换 IP 场景）
        appScope.launch {
            socket.events.collect { event ->
                if (event is WsEvent.Disconnected) pairing.onSocketDisconnected()
            }
        }

        // 上次进程若被系统杀掉，传输记录会停在「进行中」，这里统一收尾
        appScope.launch { transfers.reconcileInterruptedTransfers() }
    }
}
