package io.github.illagercpr.landrop.notify

import io.github.illagercpr.landrop.data.local.TransferDirection
import io.github.illagercpr.landrop.data.local.TransferEntity
import io.github.illagercpr.landrop.data.local.TransferState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 传输通知的纯逻辑测试（JVM，无需设备与服务端）。
 *
 * 这两处逻辑在真机上极难反复验证——要复现「两个传输同时在跑」「网络中断落到已暂停」
 * 都得真的传一遍文件；而算错了又不会崩，只会让锁屏上的数字骗人。所以把判断依据
 * 抽成纯函数在这里钉死。
 */
class TransferNoticeTest {

    // ---------------------------------------------------------------- 进行中的合并

    @Test
    fun `单个上传：标题是正在发送，进度是千分比`() {
        val content = TransferNotice.describe(
            transfers = listOf(upload(done = 10 * MB, total = 40 * MB)),
            speedBytesPerSec = 1 * MB,
        )

        assertNotNull(content)
        assertEquals("正在发送", content!!.title)
        assertTrue(content.text.startsWith("video.mp4 · 10.0 MB / 40.0 MB"))
        assertEquals("1.0 MB/s", content.subText)
        assertEquals(250, content.progress)
        assertEquals(TransferNotice.MAX_PROGRESS, content.max)
    }

    @Test
    fun `单个下载：标题是正在接收`() {
        val content = TransferNotice.describe(
            listOf(download(done = MB, total = 2 * MB)),
            speedBytesPerSec = null,
        )

        assertEquals("正在接收", content!!.title)
        assertNull("测不出速率时不应显示速率", content.subText)
    }

    @Test
    fun `多个并发：合并成一条并按总量算进度`() {
        val content = TransferNotice.describe(
            listOf(
                upload(id = "a", done = 5 * MB, total = 10 * MB),
                download(id = "b", done = 20 * MB, total = 40 * MB),
            ),
            speedBytesPerSec = 2 * MB,
        )

        assertEquals("正在传输 2 个文件", content!!.title)
        assertEquals("25.0 MB / 50.0 MB", content.text)
        assertEquals(500, content.progress)
    }

    @Test
    fun `没有进行中的传输时返回 null`() {
        // 这是调用方收掉前台服务的信号，判据必须与 isActive 完全一致
        assertNull(TransferNotice.describe(listOf(upload(state = TransferState.PAUSED)), null))
        assertNull(TransferNotice.describe(listOf(upload(state = TransferState.COMPLETED)), null))
        assertNull(TransferNotice.describe(emptyList(), null))
    }

    @Test
    fun `已暂停的数量会附加在正文里`() {
        val content = TransferNotice.describe(
            listOf(
                upload(id = "a", done = MB, total = 2 * MB),
                upload(id = "b", state = TransferState.PAUSED, done = MB, total = 2 * MB),
            ),
            speedBytesPerSec = null,
        )

        // 只有一个在跑，所以正文仍带文件名；暂停数量追加在后面
        assertEquals("video.mp4 · 1.0 MB / 2.0 MB（另有 1 个已暂停）", content!!.text)
    }

    @Test
    fun `总量未知时退回不确定进度条`() {
        val content = TransferNotice.describe(listOf(upload(done = 0, total = 0)), null)

        assertEquals(0, content!!.max)
        assertEquals(0, content.progress)
    }

    @Test
    fun `本地进度超过总大小时进度条不会溢出`() {
        // 记录值可能偏大（例如下载途中文件被外部改动），千分比必须夹住
        val content = TransferNotice.describe(listOf(upload(done = 100 * MB, total = 10 * MB)), null)

        assertEquals(TransferNotice.MAX_PROGRESS, content!!.progress)
    }

    // ---------------------------------------------------------------- 结束时的提醒

    @Test
    fun `上传完成提醒已送达，下载完成提醒已收到`() {
        val sent = TransferNotice.result(
            upload(state = TransferState.COMPLETED, total = 40 * MB),
        )
        val received = TransferNotice.result(
            download(state = TransferState.COMPLETED, total = 40 * MB),
        )

        assertEquals("文件已送达", sent!!.title)
        assertTrue(sent.text.endsWith("40.0 MB"))
        assertEquals("文件已收到", received!!.title)
    }

    @Test
    fun `用户主动暂停不提醒`() {
        // 主动暂停走的是 error = null 那条路：按按钮的人自己清楚，再响一声是噪声
        assertNull(TransferNotice.result(upload(state = TransferState.PAUSED, error = null)))
    }

    @Test
    fun `网络中断落到暂停要提醒`() {
        // 中断路径会带上原因；屏幕关着的人必须被告知，否则文件就是静悄悄地不传了
        val content = TransferNotice.result(
            upload(state = TransferState.PAUSED, error = "传输中断（网络连接中断），可继续"),
        )

        assertEquals("传输已暂停", content!!.title)
        assertTrue(content.text.contains("网络连接中断"))
    }

    @Test
    fun `空白原因不算中断`() {
        assertNull(TransferNotice.result(upload(state = TransferState.PAUSED, error = "   ")))
    }

    @Test
    fun `失败要提醒，取消不提醒`() {
        val failed = TransferNotice.result(upload(state = TransferState.FAILED, error = "文件校验失败"))
        assertEquals("发送失败", failed!!.title)
        assertTrue(failed.text.contains("文件校验失败"))

        // 取消是用户自己的动作
        assertNull(TransferNotice.result(upload(state = TransferState.CANCELED, error = null)))
    }

    @Test
    fun `进行中与排队中都不属于结束`() {
        assertNull(TransferNotice.result(upload(state = TransferState.RUNNING)))
        assertNull(TransferNotice.result(upload(state = TransferState.QUEUED)))
    }

    // ---------------------------------------------------------------- 速率估算

    @Test
    fun `第一次采样只建基准不出速率`() {
        val meter = SpeedMeter()
        meter.sample(0, now = 0)

        assertNull(meter.rate)
    }

    @Test
    fun `采样间隔不足时不更新速率`() {
        val meter = SpeedMeter(minIntervalMs = 1_000)
        meter.sample(0, now = 0)
        meter.sample(100 * 1024, now = 500)

        assertNull("间隔 500ms 短于下限，不该按此推算速率", meter.rate)
    }

    @Test
    fun `稳定推进时按指数滑动平均输出速率`() {
        val meter = SpeedMeter(minIntervalMs = 1_000)
        meter.sample(bytes = 0, now = 0)
        meter.sample(bytes = 1_000_000, now = 1_000)
        assertEquals(1_000_000L, meter.rate)

        // 第二段瞬时 2 MB/s，平滑后 (3×1MB + 2MB) / 4
        meter.sample(bytes = 3_000_000, now = 2_000)
        assertEquals(1_250_000L, meter.rate)
    }

    @Test
    fun `进度回退视为换任务，速率清零`() {
        val meter = SpeedMeter(minIntervalMs = 1_000)
        meter.sample(0, now = 0)
        meter.sample(2_000_000, now = 1_000)

        // 续传重新对齐、或换了另一个文件，字节数会倒退
        meter.sample(1_000, now = 2_000)

        assertNull(meter.rate)
        // 重置后重新起算，不该把回退前后的字节数相减
        meter.sample(1_001_000, now = 3_000)
        assertEquals(1_000_000L, meter.rate)
    }

    private companion object {
        const val MB = 1024L * 1024L

        fun upload(
            id: String = "t1",
            state: String = TransferState.RUNNING,
            name: String = "video.mp4",
            done: Long = 0,
            total: Long = 0,
            error: String? = null,
        ): TransferEntity = transfer(id, TransferDirection.UPLOAD, state, name, done, total, error)

        fun download(
            id: String = "t1",
            state: String = TransferState.RUNNING,
            name: String = "video.mp4",
            done: Long = 0,
            total: Long = 0,
            error: String? = null,
        ): TransferEntity = transfer(id, TransferDirection.DOWNLOAD, state, name, done, total, error)

        fun transfer(
            id: String,
            direction: String,
            state: String,
            name: String,
            done: Long,
            total: Long,
            error: String?,
        ) = TransferEntity(
            id = id,
            messageId = "",
            direction = direction,
            fileName = name,
            totalBytes = total,
            transferredBytes = done,
            state = state,
            localUri = null,
            uploadId = null,
            remoteFileId = null,
            error = error,
            updatedAt = 0,
        )
    }
}
