package io.github.illagercpr.landrop.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * UDP 发现应答的解析测试。
 *
 * 应答来自局域网里任意设备的 UDP 包，内容不可信：解析必须「严格到假的进不来」，
 * 这组用例锁住这一点（service/v 校验、坏 JSON 拒绝）。
 */
class ServerDiscoveryTest {

    @Test
    fun `标准应答可以解析出全部字段`() {
        val announce = parseAnnounce(
            ProtocolJson,
            """{"service":"lan-drop","v":1,"id":"abc-123","name":"SW-ShittimLight","port":8787}""",
        )

        // junit 的 assertNotNull 不做智能转换，用 !! 明示「上面刚断言过非空」
        assertNotNull(announce)
        assertEquals("lan-drop", announce!!.service)
        assertEquals(1, announce.v)
        assertEquals("abc-123", announce.id)
        assertEquals("SW-ShittimLight", announce.name)
        assertEquals(8787, announce.port)
    }

    @Test
    fun `未来版本的应答被拒绝`() {
        val announce = parseAnnounce(
            ProtocolJson,
            """{"service":"lan-drop","v":2,"id":"abc","name":"x","port":8787}""",
        )

        assertNull(announce)
    }

    @Test
    fun `非本服务的应答被拒绝`() {
        val announce = parseAnnounce(
            ProtocolJson,
            """{"service":"other-tool","v":1,"id":"abc","name":"x","port":8787}""",
        )

        assertNull(announce)
    }

    @Test
    fun `缺字段的应答被拒绝`() {
        assertNull(parseAnnounce(ProtocolJson, """{"service":"lan-drop","v":1}"""))
    }

    @Test
    fun `非 JSON 的应答被拒绝`() {
        assertNull(parseAnnounce(ProtocolJson, "LANDROP-DISCOVER-v1"))
    }

    @Test
    fun `端口越界被拒绝`() {
        assertNull(
            parseAnnounce(
                ProtocolJson,
                """{"service":"lan-drop","v":1,"id":"abc","name":"x","port":70000}""",
            ),
        )
    }

    @Test
    fun `tls 位解析：新服务端为 true，老服务端缺字段默认 false（向前兼容）`() {
        val tls = parseAnnounce(
            ProtocolJson,
            """{"service":"lan-drop","v":1,"id":"abc","name":"x","port":8787,"tls":true}""",
        )
        val plain = parseAnnounce(
            ProtocolJson,
            """{"service":"lan-drop","v":1,"id":"abc","name":"x","port":8787}""",
        )

        assertEquals(true, tls!!.tls)
        assertEquals(false, plain!!.tls)
    }
}
