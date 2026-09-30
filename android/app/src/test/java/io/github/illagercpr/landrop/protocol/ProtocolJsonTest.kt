package io.github.illagercpr.landrop.protocol

import io.github.illagercpr.landrop.net.ProtocolJson
import io.github.illagercpr.landrop.net.WsEvent
import io.github.illagercpr.landrop.net.parseWsEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议一致性测试：拿**真实服务端响应的固定样本**验证 Kotlin 侧模型能正确解析。
 *
 * 为什么值得单独测：本项目的协议事实源在 TypeScript，Kotlin 侧是手写镜像，
 * 两端漂移（字段改名、枚举取值、必填/可空判断）不会在编译期暴露，只会在真机上
 * 表现为「消息空白」「文件大小为 0」这类难查的运行时现象。样本取自
 * `apps/server` 实际输出（2026-09-30），改动协议时同步更新即可。
 *
 * 解析一律使用运行时同一份 [ProtocolJson] 配置，避免「测试通过但运行时配置不同」。
 */
class ProtocolJsonTest {

    // ---------------------------------------------------------------- HTTP 响应

    @Test
    fun `解析 info 响应`() {
        val info = ProtocolJson.decodeFromString(
            ServerInfoDto.serializer(),
            """{"protocolVersion":1,"serverId":"7446d3d5-888f-4cef-aaed-532cf92c86e0",
               "serverName":"LAN-Drop","tls":false,"pairingRequired":true}""",
        )

        assertEquals(1, info.protocolVersion)
        assertEquals("LAN-Drop", info.serverName)
        assertEquals(false, info.tls)
        assertTrue(info.pairingRequired)
    }

    @Test
    fun `解析增量消息页，区分 text 与 link 枚举`() {
        val page = ProtocolJson.decodeFromString(
            MessagePageDto.serializer(),
            """
            {
              "latestSeq": 22,
              "hasMore": false,
              "items": [
                {"seq":16,"id":"cb1954f1-5a81-4929-8175-163e26b9050f","kind":"text",
                 "senderId":"d8685e6c-f111-4ef2-b6ee-62bb691c609d","senderName":"冒烟测试设备",
                 "createdAt":1790745535044,"text":"冒烟测试 2026-09-30T05:18:55.041Z"},
                {"seq":17,"id":"a1b2c3","kind":"link","senderId":"d8685e6c","senderName":"PC",
                 "createdAt":1790745535100,"text":"https://example.com"}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(22, page.latestSeq)
        assertEquals(2, page.items.size)
        assertEquals(MessageKind.TEXT, page.items[0].kind)
        assertEquals(MessageKind.LINK, page.items[1].kind)
        assertNull(page.items[0].file)
    }

    @Test
    fun `解析文件消息，嵌套 file 字段完整`() {
        val message = ProtocolJson.decodeFromString(
            MessageDto.serializer(),
            """
            {"seq":22,"id":"0701a2c6-6785-4ee7-896c-06757c8f9ddc","kind":"file",
             "senderId":"1dbcb0bb-0e13-4e90-b60b-0732dea16553","senderName":"样本采集",
             "createdAt":1790745562764,
             "file":{"id":"f52fd498-e64a-4225-8c86-7a37d7538273","name":"报告 v2.pdf",
                     "size":2048,"mime":"application/pdf",
                     "sha256":"f8f32f68c7ab74ce932713f801e0499f45e1721b69fca6801b97f2ffec39d7f2"}}
            """.trimIndent(),
        )

        assertEquals(MessageKind.FILE, message.kind)
        assertEquals("报告 v2.pdf", message.file?.name)
        assertEquals(2048L, message.file?.size)
        assertEquals("application/pdf", message.file?.mime)
        assertNull(message.text)
    }

    @Test
    fun `解析上传会话与分片响应`() {
        val session = ProtocolJson.decodeFromString(
            CreateUploadResponseDto.serializer(),
            """{"uploadId":"7c1e","receivedBytes":0,"chunkSize":4194304}""",
        )
        assertEquals(0L, session.receivedBytes)
        assertEquals(4L * 1024 * 1024, session.chunkSize)

        val patch = ProtocolJson.decodeFromString(
            UploadPatchResponseDto.serializer(),
            """{"uploadId":"7c1e","receivedBytes":2048,"size":2048}""",
        )
        assertEquals(2048L, patch.receivedBytes)
        assertEquals(2048L, patch.size)
    }

    @Test
    fun `解析 409 冲突体，拿到服务端权威 offset（续传对齐的依据）`() {
        val error = ProtocolJson.decodeFromString(
            ApiErrorDto.serializer(),
            """{"error":"offset_mismatch","receivedBytes":8388608}""",
        )

        assertEquals("offset_mismatch", error.error)
        assertEquals(8L * 1024 * 1024, error.receivedBytes)
        assertNull(error.size)
    }

    @Test
    fun `服务端新增未知字段不影响解析`() {
        val info = ProtocolJson.decodeFromString(
            ServerInfoDto.serializer(),
            """{"protocolVersion":1,"serverId":"x","serverName":"n","tls":false,
               "pairingRequired":true,"futureField":{"deep":[1,2,3]}}""",
        )
        assertEquals("n", info.serverName)
    }

    // ---------------------------------------------------------------- WebSocket 信封

    @Test
    fun `解析 hello 信封`() {
        val event = parseWsEnvelope(
            """{"type":"hello","payload":{"deviceId":"1dbcb0bb","serverId":"7446d3d5",
               "protocolVersion":1,"latestSeq":22,"onlineCount":1}}""",
        )

        assertTrue(event is WsEvent.Hello)
        val hello = event as WsEvent.Hello
        assertEquals(22L, hello.payload.latestSeq)
        assertEquals(1, hello.payload.onlineCount)
    }

    @Test
    fun `解析 device_online 信封`() {
        val event = parseWsEnvelope(
            """{"type":"device.online","payload":{"deviceId":"1dbcb0bb","deviceName":"样本采集",
               "onlineCount":1}}""",
        )

        assertTrue(event is WsEvent.Presence)
        val presence = event as WsEvent.Presence
        assertTrue(presence.online)
        assertEquals("样本采集", presence.deviceName)
        assertEquals(1, presence.onlineCount)
    }

    @Test
    fun `解析 message_new 信封（文件消息）`() {
        val event = parseWsEnvelope(
            """{"type":"message.new","payload":{"seq":22,"id":"0701a2c6","kind":"file",
               "senderId":"1dbcb0bb","senderName":"样本采集","createdAt":1790745562764,
               "file":{"id":"f52fd498","name":"报告 v2.pdf","size":2048,"mime":"application/pdf"}}}""",
        )

        assertTrue(event is WsEvent.MessageNew)
        val message = (event as WsEvent.MessageNew).message
        assertEquals(MessageKind.FILE, message.kind)
        assertEquals("报告 v2.pdf", message.file?.name)
    }

    @Test
    fun `未知事件类型与畸形 JSON 一律静默忽略`() {
        assertNull(parseWsEnvelope("""{"type":"brand.new.event","payload":{"x":1}}"""))
        assertNull(parseWsEnvelope("""{"type":"hello"}"""))
        assertNull(parseWsEnvelope("not json at all"))
        assertNull(parseWsEnvelope("""{"payload":{}}"""))
    }

    // ---------------------------------------------------------------- 工具

    @Test
    fun `发送文字请求体的字段名与服务端约定一致`() {
        val encoded = ProtocolJson.encodeToString(
            SendMessageRequestDto.serializer(),
            SendMessageRequestDto(kind = "text", text = "你好"),
        )
        assertEquals("""{"kind":"text","text":"你好"}""", encoded)
    }
}
