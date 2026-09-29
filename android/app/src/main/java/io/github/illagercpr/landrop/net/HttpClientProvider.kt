package io.github.illagercpr.landrop.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * OkHttp 客户端工厂。
 *
 * 超时策略针对「局域网 + 大文件」场景：
 *  - 连接超时短（10s）：局域网连不上就是连不上，快速失败好过让用户干等；
 *  - 读写超时不限：GB 级文件经 WiFi 传输可能长时间没有新字节，
 *    用固定读超时会把正常传输误判为失败；保活交给 WebSocket 的 ping 与上层进度心跳。
 */
object HttpClientProvider {

    fun create(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}
