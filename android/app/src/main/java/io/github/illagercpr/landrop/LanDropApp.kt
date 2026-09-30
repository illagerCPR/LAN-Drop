package io.github.illagercpr.landrop

import android.app.Application
import io.github.illagercpr.landrop.di.AppContainer

/**
 * 应用入口：只做一件事——建立依赖容器并启动会话。
 *
 * 会话（WebSocket + 增量同步）挂在应用级作用域上，因此它不随 Activity 重建而中断，
 * 也让 P3 的前台服务可以直接复用同一个容器。
 */
class LanDropApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}
