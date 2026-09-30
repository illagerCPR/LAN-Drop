package io.github.illagercpr.landrop.share

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 系统分享内容的归一化测试。
 *
 * 这里锁住的是「分享面板送进来的东西五花八门」这一现实：
 *   - 同一批 URI 往往同时出现在 `EXTRA_STREAM` 与 `ClipData` 里（相册、文件管理器都这么发），
 *     不去重就会把同一张照片发两遍；
 *   - 有的应用只填其中一边；
 *   - 文字可能是空白，应用也可能只发了个 action 什么都不带。
 *
 * 只测纯逻辑（[normalizeShare]）不测 Intent 解析：`Intent`/`Uri` 来自 Android 框架，
 * 纯 JVM 单测里拿不到实例，硬测只会写出测框架的假用例。
 */
class ShareIntentTest {

    private fun raw(
        action: String? = Intent.ACTION_SEND,
        text: String? = null,
        streamUris: List<String> = emptyList(),
        clipUris: List<String> = emptyList(),
        dataUri: String? = null,
    ) = RawShare(
        action = action,
        text = text,
        streamUris = streamUris,
        clipUris = clipUris,
        dataUri = dataUri,
    )

    @Test
    fun `分享文字只有文字没有文件`() {
        val share = normalizeShare(raw(text = "这是要发过去的文字"))

        assertEquals("这是要发过去的文字", share?.text)
        assertEquals(emptyList<String>(), share?.uris)
    }

    @Test
    fun `空白文字不算内容`() {
        assertNull(normalizeShare(raw(text = "   \n  ")))
        // 只有空白文字、没有文件 → 整条分享没有可用内容
        assertNull(normalizeShare(raw(text = " ", streamUris = emptyList())))
    }

    @Test
    fun `单选文件走 EXTRA_STREAM`() {
        val share = normalizeShare(raw(streamUris = listOf("content://media/1")))

        assertEquals(listOf("content://media/1"), share?.uris)
        assertNull(share?.text)
    }

    @Test
    fun `多选文件按顺序保留`() {
        val share = normalizeShare(
            raw(
                action = Intent.ACTION_SEND_MULTIPLE,
                streamUris = listOf("content://media/1", "content://media/2", "content://media/3"),
            ),
        )

        assertEquals(
            listOf("content://media/1", "content://media/2", "content://media/3"),
            share?.uris,
        )
    }

    @Test
    fun `同一个 URI 同时出现在 EXTRA_STREAM 与 ClipData 时只保留一份`() {
        // 系统相册分享单张照片就是这个形态：不去重会发两遍
        val share = normalizeShare(
            raw(
                streamUris = listOf("content://media/photo/42"),
                clipUris = listOf("content://media/photo/42"),
            ),
        )

        assertEquals(listOf("content://media/photo/42"), share?.uris)
    }

    @Test
    fun `只在 ClipData 里的文件也算数`() {
        // 部分应用（含系统分享的「复制到」路径）只填 ClipData
        val share = normalizeShare(raw(clipUris = listOf("content://downloads/7")))

        assertEquals(listOf("content://downloads/7"), share?.uris)
    }

    @Test
    fun `两边都有且不同时按 EXTRA_STREAM 在前合并`() {
        val share = normalizeShare(
            raw(
                streamUris = listOf("content://media/1"),
                clipUris = listOf("content://media/1", "content://media/2"),
            ),
        )

        assertEquals(listOf("content://media/1", "content://media/2"), share?.uris)
    }

    @Test
    fun `文字与文件可以同时存在`() {
        val share = normalizeShare(
            raw(text = "顺便看下这个", streamUris = listOf("content://media/9")),
        )

        assertEquals("顺便看下这个", share?.text)
        assertEquals(listOf("content://media/9"), share?.uris)
    }

    @Test
    fun `不是分享的 action 一律忽略`() {
        assertNull(normalizeShare(raw(action = Intent.ACTION_MAIN, text = "不该出现")))
        assertNull(normalizeShare(raw(action = null, text = "不该出现")))
        assertNull(normalizeShare(raw(action = Intent.ACTION_VIEW, streamUris = listOf("content://x"))))
    }

    @Test
    fun `什么都没有的分享返回空`() {
        assertNull(normalizeShare(raw()))
    }

    @Test
    fun `EXTRA_STREAM 与 ClipData 都为空时用 data 兜底`() {
        val share = normalizeShare(raw(dataUri = "content://media/external/file/9"))

        assertEquals(listOf("content://media/external/file/9"), share?.uris)
    }

    @Test
    fun `有 EXTRA_STREAM 时 data 不参与合并`() {
        // data 里放的可能根本不是一个文件，只在没有别的来源时才采用
        val share = normalizeShare(
            raw(streamUris = listOf("content://media/1"), dataUri = "content://other/2"),
        )

        assertEquals(listOf("content://media/1"), share?.uris)
    }

    @Test
    fun `空白 URI 被忽略`() {
        val share = normalizeShare(raw(streamUris = listOf("", "  ", "content://ok")))

        assertEquals(listOf("content://ok"), share?.uris)
    }
}
