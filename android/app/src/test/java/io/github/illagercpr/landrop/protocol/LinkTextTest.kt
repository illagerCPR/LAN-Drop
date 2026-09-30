package io.github.illagercpr.landrop.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `link` 判定规则的边界。
 *
 * 这份用例表与事实源 `packages/protocol/test/link.test.ts` **逐条对应**：同一条输入
 * 必须在两端得到同一个结论，否则会出现「手机上是链接、网页上是纯文本」。
 * 改动任何一条都要同时改另一边。
 */
class LinkTextTest {

    private val cases: List<Triple<String, Boolean, String>> = listOf(
        Triple("https://example.com", true, "最常见的形态"),
        Triple("http://192.168.1.5:8787/#pair=ABC", true, "局域网地址带端口与 hash 也算"),
        Triple("  https://example.com/a?b=1#c  ", true, "首尾空白先剪掉"),
        Triple("HTTPS://EXAMPLE.COM", true, "scheme 大小写不敏感"),
        Triple("https://例子.中国/路径", true, "非 ASCII 域名/路径照收"),
        Triple("https://", false, "只有 scheme 与分隔符，没有主机"),
        Triple("example.com", false, "没有 scheme，不当链接"),
        Triple("看这个 https://example.com", false, "夹在句子里的是 text，不是 link kind"),
        Triple("https://exa mple.com", false, "含空格就不可能是一个完整 URL"),
        Triple("javascript:alert(1)", false, "伪协议——渲染成 href 就是注入"),
        Triple("data:text/html,<script>alert(1)</script>", false, "同样是伪协议"),
        Triple("file:///etc/passwd", false, "只允许 http/https"),
        Triple("mailto:someone@example.com", false, "只允许 http/https"),
        Triple("", false, "空串永远不是链接"),
        Triple("   ", false, "只有空白同样不是"),
    )

    @Test
    fun `判定与事实源逐条一致`() {
        for ((text, expected, why) in cases) {
            assertEquals("isHttpUrl(\"$text\") —— $why", expected, isHttpUrl(text))
        }
    }

    @Test
    fun `消息类型常量与协议取值一致`() {
        // 这两个字符串会上行给服务端并落库，改错就是 400 invalid_kind 或渲染错
        assertEquals("text", MESSAGE_KIND_TEXT)
        assertEquals("link", MESSAGE_KIND_LINK)
    }

    @Test
    fun `换行与制表符不算空白以外的内容`() {
        // trim 只处理首尾；内部换行意味着这不是「一整段就是一个链接」
        assertEquals(true, isHttpUrl("\nhttps://example.com\n"))
        assertEquals(false, isHttpUrl("https://example.com\n第二行"))
        assertEquals(false, isHttpUrl("https://exa\tmple.com"))
    }
}
