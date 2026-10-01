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
    fun `info 携带 TLS 指纹，老服务端缺字段时默认 null`() {
        val tlsInfo = ProtocolJson.decodeFromString(
            ServerInfoDto.serializer(),
            """{"protocolVersion":1,"serverId":"sid","serverName":"LAN-Drop","tls":true,
               "tlsFingerprint":"UqhHXFAcag6e8JWlDXZ48gEOFVsOG7zlz1N4inqWM6I","pairingRequired":true}""",
        )
        val plainInfo = ProtocolJson.decodeFromString(
            ServerInfoDto.serializer(),
            """{"protocolVersion":1,"serverId":"sid","serverName":"LAN-Drop","tls":false,"pairingRequired":true}""",
        )

        assertEquals("UqhHXFAcag6e8JWlDXZ48gEOFVsOG7zlz1N4inqWM6I", tlsInfo.tlsFingerprint)
        assertNull(plainInfo.tlsFingerprint)
    }

    @Test
    fun `配对响应的指纹字段名与服务端约定一致，null 时省略键`() {
        val withFp = ProtocolJson.encodeToString(
            PairResponseDto.serializer(),
            PairResponseDto(
                deviceId = "d1",
                deviceToken = "tok",
                serverId = "sid",
                serverName = "LAN-Drop",
                tlsFingerprint = "UqhHXFAcag6e8JWlDXZ48gEOFVsOG7zlz1N4inqWM6I",
            ),
        )
        val withoutFp = ProtocolJson.encodeToString(
            PairResponseDto.serializer(),
            PairResponseDto(deviceId = "d1", deviceToken = "tok", serverId = "sid", serverName = "LAN-Drop"),
        )

        // explicitNulls=false：明文服务端的配对响应不能出现 "tlsFingerprint":null
        assertTrue(withFp.contains("\"tlsFingerprint\":\"UqhHXFAcag6e8JWlDXZ48gEOFVsOG7zlz1N4inqWM6I\""))
        assertTrue(!withoutFp.contains("tlsFingerprint"))
    }

    @Test
    fun `解析增量消息页，区分 text 与 link 枚举`() {
        val page = ProtocolJson.decodeFromString(
            MessagePageDto.serializer(),
            """
            {
              "latestSeq": 22,
              "hasMore": false,
              "purgedUpto": 15,
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
        assertEquals(15, page.purgedUpto)
        assertEquals(2, page.items.size)
        assertEquals(MessageKind.TEXT, page.items[0].kind)
        assertEquals(MessageKind.LINK, page.items[1].kind)
        assertNull(page.items[0].file)
    }

    @Test
    fun `旧服务端缺 purgedUpto 字段时默认 0（向前兼容）`() {
        val page = ProtocolJson.decodeFromString(
            MessagePageDto.serializer(),
            """{"latestSeq":5,"hasMore":false,"items":[]}""",
        )

        assertEquals(0, page.purgedUpto)
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

    // ---------------------------------------------------------------- 断点续传

    @Test
    fun `解析上传会话的可续传视图`() {
        // 样本取自实跑的服务端：声明 9 字节、已收 4 字节、会话仍在 open
        val status = ProtocolJson.decodeFromString(
            UploadStatusDto.serializer(),
            """{"uploadId":"0193b0f0-6f2e-7c31-9a55-2f0b7c1d4e88","name":"样本.bin","size":9,
               "receivedBytes":4,"state":"open","resumable":true,
               "createdAt":1790746000000,"updatedAt":1790746001234,
               "mime":"application/octet-stream"}""",
        )

        assertEquals("样本.bin", status.name)
        assertEquals(9L, status.size)
        assertEquals(4L, status.receivedBytes)
        assertEquals(UploadState.OPEN, status.state)
        assertTrue(status.resumable)
        assertEquals("application/octet-stream", status.mime)
    }

    @Test
    fun `上传会话列表解析，空列表不报错`() {
        val list = ProtocolJson.decodeFromString(
            UploadListDto.serializer(),
            """{"items":[{"uploadId":"a","name":"x","size":100,"receivedBytes":0,
                "state":"open","resumable":true,"createdAt":1,"updatedAt":1}]}""",
        )
        assertEquals(1, list.items.size)
        assertNull(list.items[0].mime)

        val empty = ProtocolJson.decodeFromString(UploadListDto.serializer(), """{"items":[]}""")
        assertTrue(empty.items.isEmpty())

        // 服务端将来加字段不应把老客户端打挂（ProtocolJson 已开 ignoreUnknownKeys）
        val tolerant = ProtocolJson.decodeFromString(
            UploadListDto.serializer(),
            """{"items":[],"nextCursor":"abc"}""",
        )
        assertTrue(tolerant.items.isEmpty())
    }

    @Test
    fun `会话已结束时 resumable 为 false`() {
        val aborted = ProtocolJson.decodeFromString(
            UploadStatusDto.serializer(),
            """{"uploadId":"a","name":"x","size":10,"receivedBytes":4,
                "state":"aborted","resumable":false,"createdAt":1,"updatedAt":2}""",
        )
        assertEquals(UploadState.ABORTED, aborted.state)
        assertEquals(false, aborted.resumable)
    }

    @Test
    fun `409 错误体能带回权威 offset`() {
        // 客户端断点续传完全依赖这个字段重新对齐
        val error = ProtocolJson.decodeFromString(
            ApiErrorDto.serializer(),
            """{"error":"offset_mismatch","receivedBytes":2097152}""",
        )
        assertEquals("offset_mismatch", error.error)
        assertEquals(2097152L, error.receivedBytes)
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

    @Test
    fun `收尾摘要请求体省略空字段且字段名与服务端约定一致`() {
        // Android 收尾必带 sha256；省略 null 字段，避免服务端看到 "sha256":null
        val encoded = ProtocolJson.encodeToString(
            CompleteUploadRequestDto.serializer(),
            CompleteUploadRequestDto(sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
        )
        assertEquals(
            """{"sha256":"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"}""",
            encoded,
        )
        // 不带摘要的收尾是合法的空对象（服务端只算并存档摘要）
        assertEquals("{}", ProtocolJson.encodeToString(CompleteUploadRequestDto.serializer(), CompleteUploadRequestDto(null)))
    }
}
