package io.github.illagercpr.landrop

import android.app.Application
import io.github.illagercpr.landrop.di.AppContainer
import io.github.illagercpr.landrop.notify.Notifications

/**
 * 应用入口：建立依赖容器并启动会话。
 *
 * 会话（WebSocket + 增量同步）挂在应用级作用域上，因此它不随 Activity 重建而中断，
 * 前台服务（[io.github.illagercpr.landrop.notify.TransferService]）也直接复用同一个容器
 * ——服务与界面共享同一份传输状态，不存在「服务工作副本」这种需要同步的第二真相。
 */
class LanDropApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // 通知渠道必须先于任何通知建立；渠道一旦创建，重要性就只有用户能改了
        Notifications.ensureChannels(this)

        container = AppContainer(this)
        container.start()
    }
}
