package io.github.illagercpr.landrop.net

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.X509TrustManager

/**
 * SPKI 指纹的归一化与比较。
 *
 * 指纹统一形态是 **base64url 无填充的 sha256(SPKI DER)，43 字符**；比较前先归一化，
 * 让 `+`/`/`/`=` 与 `-`/`_`/缺省填充视为同一指纹，避免两端编码习惯差异造成假性失配。
 */
object FingerprintPins {

    /** 归一化到 base64url 无填充形态。 */
    fun normalize(value: String): String = value.trim()
        .replace('+', '-')
        .replace('/', '_')
        .trimEnd('=')

    fun matches(expected: String, actual: String): Boolean =
        normalize(expected) == normalize(actual) && normalize(expected).isNotEmpty()
}

/**
 * 按 SPKI 指纹校验服务端证书的 TrustManager——LAN-Drop 的信任边界。
 *
 * 模型与 SSH 首连相同：证书不经 CA 背书（裸 IP 拿不到 CA 证书），客户端只认
 * 「配对时见过的公钥指纹」。指纹来自配对二维码（相机是攻击者插不进的视觉信道），
 * 因此**指纹即身份**：
 *  - 指纹匹配 → 信任该服务端（主机名/IP 不参与决策，DHCP 换址、SAN 过时都无影响）；
 *  - 不匹配 → 连接拒绝，上层按「网络可能被劫持」处理。
 *
 * 中间人没有服务端私钥，无法出示相同 SPKI 的证书，冒充必然失败——这正是
 * 自签 TLS 必须配指纹固定才有意义的原因。
 */
class FingerprintTrustManager(
    private val expectedFingerprintUrlSafe: String,
) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        // 本应用只作 TLS 客户端，不做客户端证书
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("服务端未提供证书")
        // X509 证书的 PublicKey.encoded 即 SubjectPublicKeyInfo DER（JDK 规范行为），
        // 对整个 SPKI 取摘要（而不是整张证书）：指纹随公钥走，证书重签不影响固定关系
        val spki = leaf.publicKey?.encoded
            ?: throw CertificateException("无法读取服务端证书公钥")
        val digest = MessageDigest.getInstance("SHA-256").digest(spki)
        val actual = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        if (!FingerprintPins.matches(expectedFingerprintUrlSafe, actual)) {
            throw CertificateException(
                "服务端证书指纹不匹配（期望 ${expectedFingerprintUrlSafe.take(8)}…，实际 ${actual.take(8)}…）",
            )
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
