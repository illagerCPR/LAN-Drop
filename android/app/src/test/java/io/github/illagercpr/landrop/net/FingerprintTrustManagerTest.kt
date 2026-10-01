package io.github.illagercpr.landrop.net

import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SPKI 指纹固定（自签 TLS 的信任边界）的单测。
 *
 * 指纹固定是整个「自签 TLS + 指纹固定」方案里真正拦中间人的那一环：二维码带来
 * 期望指纹，TrustManager 对服务端证书的 SPKI 现算指纹并比较。这里用一张**真实
 * 生成的自签证书**（node selfsigned 产出，2026-10-01，与 verify-all 的 TLS 实例
 * 同一实现）钉住三件事：归一化比较语义、正确指纹放行、错误指纹拒绝。
 */
class FingerprintTrustManagerTest {

    /** 测试夹具证书的 SPKI sha256（base64url 无填充，43 字符）。 */
    private val expectedFingerprint = "UqhHXFAcag6e8JWlDXZ48gEOFVsOG7zlz1N4inqWM6I"

    /** 与 [expectedFingerprint] 对应的自签证书（CN=LAN-Drop，RSA 2048）。 */
    private val certificatePem = """
        -----BEGIN CERTIFICATE-----
        MIICxzCCAa+gAwIBAgIJOUwRnQ4D5386MA0GCSqGSIb3DQEBBQUAMBMxETAPBgNV
        BAMTCExBTi1Ecm9wMB4XDTI2MTAwMTEwNDU1NloXDTI3MTAwMTEwNDU1NlowEzER
        MA8GA1UEAxMITEFOLURyb3AwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIB
        AQCd0lSmXaY2mCZh2LGCJIw554r0LNYrv3jE4oRoR7uEdgB42BJd4zrVqVZ9cCgo
        zHDJm0TRhX2b+vLuauqysL2g4R0DcxqCLztiaP7s6IOtM7xOpF5YRrYTqgzbmeOs
        AEhqjXvR1sKaMCVVQXHCKiAuL4hvO3rg/sg52Od+1ASXqgw2k+DvclOsu+7mYvYJ
        D+BLGBJ7z4g2AegNZhwbHEmPFEEruyIRYGUW73kNJnZ5Gyav5mkIVAgFmcGdqhx3
        Ytg6HvMk5OHUZChimpvB2ha4p9hWl3IqHunG6tOO8ccjOxDx7gkG6tudphowgdMI
        l5c1ykdrqIEBdwtH+OfnMk5rAgMBAAGjHjAcMBoGA1UdEQQTMBGCCWxvY2FsaG9z
        dIcEfwAAATANBgkqhkiG9w0BAQUFAAOCAQEAEJpsq+4YEPgM0BjuSe5gI6JAS3M4
        86Hia8Oyac0r+8gxXLyCycoaech3eoFS9ksjlkT//k9mss21EH8hVkUnmjv2qcXy
        Hz0rcKmyHTDMV8THABdkYJ6HC34PS/6+8C3/YcBT0E38UvjfbdMyQZB6Y2cg4DeU
        SvE7xQX9j0Qv0jcd9+ArHCxxPojI5xlW6rRDgpVV5X63ouJ9SJwmM3meMpRcmULy
        ki4d0cxw3xMUT4EmK3WVaV7/j7GDwIQb7fuHBqf+Kcg23rnpym3MKFq5AHDe5b6n
        uvr/0adJ9MOFQwPghdKBNhFk7tPK0VsctLXQE/Spgw9nwFuda5mEw68Dbw==
        -----END CERTIFICATE-----
    """.trimIndent()

    private fun certificate(): X509Certificate {
        val der = certificatePem
            .replace("-----BEGIN CERTIFICATE-----", "")
            .replace("-----END CERTIFICATE-----", "")
            .filter { !it.isWhitespace() }
            .let { Base64.getDecoder().decode(it) }
        return CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    }

    @Test
    fun `指纹归一化让 base64 与 base64url 视为同一指纹`() {
        // 同一摘要的 base64 形态（含 +/= ）与 base64url 形态（含 -_ 无填充）
        assertEquals(
            FingerprintPins.normalize("a+b/cd=="),
            FingerprintPins.normalize("a-b_cd"),
        )
        // 空指纹不是合法身份
        assertFalse(FingerprintPins.matches("", ""))
        assertFalse(FingerprintPins.matches("   ", ""))
    }

    @Test
    fun `正确的指纹放行服务端证书`() {
        val trustManager = FingerprintTrustManager(expectedFingerprint)
        // 不抛异常即通过
        trustManager.checkServerTrusted(arrayOf(certificate()), "RSA")
    }

    @Test
    fun `错误的指纹拒绝连接（中间人出示的证书在这里被拦下）`() {
        val trustManager = FingerprintTrustManager("0000000000000000000000000000000000000000000")
        try {
            trustManager.checkServerTrusted(arrayOf(certificate()), "RSA")
            throw AssertionError("错误指纹竟然通过了校验")
        } catch (expected: CertificateException) {
            assertTrue(expected.message!!.contains("不匹配"))
        }
    }

    @Test
    fun `空证书链被拒绝`() {
        val trustManager = FingerprintTrustManager(expectedFingerprint)
        try {
            trustManager.checkServerTrusted(emptyArray(), "RSA")
            throw AssertionError("空证书链竟然通过了校验")
        } catch (expected: CertificateException) {
            assertTrue(expected.message!!.contains("未提供证书"))
        }
    }

    @Test
    fun `指纹由 SPKI 现算而与期望形态一致`() {
        // 用证书里的公钥按服务端同一算法现算一遍，应与夹具指纹一致——
        // 守住「指纹 = sha256(SPKI DER)」这个跨端约定
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(certificate().publicKey.encoded)
        val actual = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)

        assertEquals(expectedFingerprint, actual)
        assertNotEquals(expectedFingerprint, actual.reversed())
    }
}
