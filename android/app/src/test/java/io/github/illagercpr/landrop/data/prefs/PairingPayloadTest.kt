package io.github.illagercpr.landrop.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 扫码/粘贴配对 payload 的解析契约。
 *
 * 事实源是服务端 `apps/server/src/routes/pair.ts` 的 `urls` 字段：
 * `http://<ip>:<port>/#pair=<code>`。扫码页、粘贴链接与 Web 端 `#pair=`
 * hash 进入共用这里的两个解析函数，改任何一边都要过这份测试。
 */
class PairingPayloadTest {

    @Test
    fun `服务端配对 URL 同时解出地址与配对码`() {
        val url = "http://192.168.1.5:8787/#pair=7F3K9Q"
        assertEquals("http://192.168.1.5:8787", ConnectionStore.normalizeBaseUrl(url))
        assertEquals("7F3K9Q", ConnectionStore.extractPairingCode(url))
    }

    @Test
    fun `没有 pair 标记的 URL 只有地址没有码`() {
        val url = "http://192.168.1.5:8787/"
        assertEquals("http://192.168.1.5:8787", ConnectionStore.normalizeBaseUrl(url))
        assertNull(ConnectionStore.extractPairingCode(url))
    }

    @Test
    fun `配对码在 URL 分隔符处截断`() {
        // 服务端不会生成这种形态，但解析必须容忍码后面还跟着查询串/路径
        assertEquals("7F3K9Q", ConnectionStore.extractPairingCode("http://192.168.1.5:8787/#pair=7F3K9Q?x=1"))
        assertEquals("7F3K9Q", ConnectionStore.extractPairingCode("http://192.168.1.5:8787/#pair=7F3K9Q/next"))
        assertEquals("7F3K9Q", ConnectionStore.extractPairingCode("http://192.168.1.5:8787/#pair=7F3K9Q\n更多文字"))
    }

    @Test
    fun `裸 IP 补默认端口`() {
        assertEquals("http://192.168.1.5:8787", ConnectionStore.normalizeBaseUrl("192.168.1.5"))
        assertEquals("http://192.168.1.5:8787", ConnectionStore.normalizeBaseUrl("192.168.1.5/"))
    }

    @Test
    fun `带端口与路径的输入收敛到 origin`() {
        assertEquals(
            "http://192.168.1.5:9000",
            ConnectionStore.normalizeBaseUrl("http://192.168.1.5:9000/some/path?x=1#pair=AB"),
        )
    }

    @Test
    fun `空输入不是地址`() {
        assertEquals("", ConnectionStore.normalizeBaseUrl(""))
        assertEquals("", ConnectionStore.normalizeBaseUrl("   "))
    }

    // ------------------------------------------------------------------ TLS 指纹（#fp=）

    @Test
    fun `TLS 配对 URL 解出 https 地址、配对码与指纹`() {
        val fp = "UqhHXFAcag6e8JWlDXZ48gEOFVsOG7zlz1N4inqWM6I"
        val url = "https://192.168.1.5:8787/#pair=7F3K9Q&fp=$fp"

        assertEquals("https://192.168.1.5:8787", ConnectionStore.normalizeBaseUrl(url))
        // 配对码在 & 处截断，不会被指纹污染
        assertEquals("7F3K9Q", ConnectionStore.extractPairingCode(url))
        assertEquals(fp, ConnectionStore.extractPairingFingerprint(url))
    }

    @Test
    fun `没有 fp 参数的 URL 返回 null 指纹（明文服务端）`() {
        assertNull(ConnectionStore.extractPairingFingerprint("http://192.168.1.5:8787/#pair=7F3K9Q"))
        assertNull(ConnectionStore.extractPairingFingerprint("https://192.168.1.5:8787/#pair=7F3K9Q&x=1"))
    }

    @Test
    fun `长度不是 43 的指纹一律不采信`() {
        // sha256 base64url 无填充恒为 43 字符；别的形态只可能是篡改或损坏
        assertNull(
            ConnectionStore.extractPairingFingerprint(
                "https://192.168.1.5:8787/#pair=7F3K9Q&fp=shortfp",
            ),
        )
        assertNull(
            ConnectionStore.extractPairingFingerprint(
                "https://192.168.1.5:8787/#pair=7F3K9Q&fp=UqhHXFAcag6e8JWlDXZ48gEOFVsOG7zlz1N4inqWM6IEXTRA",
            ),
        )
    }

    @Test
    fun `明文地址输入不会被误判成 TLS`() {
        val url = "http://192.168.1.5:8787/#pair=7F3K9Q"
        assertEquals("http://192.168.1.5:8787", ConnectionStore.normalizeBaseUrl(url))
    }
}
