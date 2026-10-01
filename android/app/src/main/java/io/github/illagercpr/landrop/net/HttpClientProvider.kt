package io.github.illagercpr.landrop.net

import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

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

    /**
     * 配对/TLS 连接专用客户端。
     *
     *  - [fingerprint] 非 null：按 [FingerprintTrustManager] 固定校验 SPKI 指纹。
     *    主机名校验同时放行——指纹即身份（SSH 模型），裸 IP + 自签证书下主机名
     *    校验本来就不可能通过，而指纹匹配已经涵盖了它想保证的一切。
     *  - [fingerprint] 为 null：**仅用于配对引导**（连 /info 拿服务端指纹的第一次
     *    接触）——接受任意自签证书的 TLS 信道，配合服务端 /info 下发的指纹形成
     *    TOFU（首次信任）。之后的业务连接一律用带指纹的 pinned 客户端。
     */
    fun createForTls(fingerprint: String?): OkHttpClient {
        val trustManager: X509TrustManager = if (fingerprint != null) {
            FingerprintTrustManager(fingerprint)
        } else {
            LenientTrustManager
        }
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        return create().newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier(LENIENT_HOSTNAME_VERIFIER)
            .build()
    }

    /** 接受任意证书的信任管理器。**只允许配对引导使用**，见 [createForTls]。 */
    private object LenientTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val LENIENT_HOSTNAME_VERIFIER = HostnameVerifier { _, _ -> true }
}
