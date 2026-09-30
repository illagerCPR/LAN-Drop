package io.github.illagercpr.landrop.net

import io.github.illagercpr.landrop.protocol.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WS 事件信封解析测试。
 *
 * 「清空会话」这条链路（`message.deleted` → [WsEvent.MessagesCleared] → 本地库清空）
 * 自 P2 就存在，但一度被误判为「Android 未处理」——在源码里搜字面量 `message.deleted`
 * 搜不到（Kotlin 用常量 `MESSAGE_DELETED`）。这组用例把解析钉住，防止重构时悄悄丢掉。
 */
class WsEnvelopeParserTest {

    @Test
    fun `message_deleted 解析为 MessagesCleared`() {
        val event = parseWsEnvelope("""{"type":"message.deleted","payload":{"cleared":true}}""")

        assertEquals(WsEvent.MessagesCleared, event)
    }

    @Test
    fun `messages_purged 解析出 uptoSeq`() {
        val event = parseWsEnvelope("""{"type":"messages.purged","payload":{"uptoSeq":96}}""")

        assertEquals(WsEvent.MessagesPurged(96), event)
    }

    @Test
    fun `messages_purged 缺 payload 时返回 null`() {
        val event = parseWsEnvelope("""{"type":"messages.purged"}""")

        assertNull(event)
    }

    @Test
    fun `message_new 的 payload 按 type 二次解码`() {
        // 服务端 POST /messages 响应的真实样本（WS 广播把它包进 payload）
        val raw = """
            {"type":"message.new","payload":{"seq":96,"id":"d76fd99b-03a5-483a-8ed7-3092fe4bd94d",
             "kind":"text","senderId":"a17ee8b4-ba7a-40dc-b120-515d819af300",
             "senderName":"clear-verify","createdAt":1790770551078,"text":"seq-behavior-probe"}}
        """.trimIndent().replace("\n", "")

        val event = parseWsEnvelope(raw)

        assertTrue(event is WsEvent.MessageNew)
        val message = (event as WsEvent.MessageNew).message
        assertEquals(96, message.seq)
        assertEquals(MessageKind.TEXT, message.kind)
        assertEquals("seq-behavior-probe", message.text)
    }

    @Test
    fun `未知 type 返回 null 而不是崩溃`() {
        val event = parseWsEnvelope("""{"type":"message.recalled.v9","payload":{}}""")

        assertNull(event)
    }

    @Test
    fun `畸形 JSON 返回 null`() {
        assertNull(parseWsEnvelope("not json at all"))
        assertNull(parseWsEnvelope("""[1,2,3]"""))
        assertNull(parseWsEnvelope("""{"payload":{"cleared":true}}"""))
    }
}
