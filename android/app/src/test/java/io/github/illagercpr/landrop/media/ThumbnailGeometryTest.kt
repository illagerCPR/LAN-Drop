package io.github.illagercpr.landrop.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 预览尺寸与降采样的算术。
 *
 * 这两件事错了都不会崩：一个让气泡里多出空白、一个让图糊。正因为「不算错」
 * 才要靠单测——设备上看一眼很难判断是尺寸算错了还是原图就这样。
 */
class ThumbnailGeometryTest {

    @Test
    fun `横图按宽度贴满盒子`() {
        // 4:3 的图，盒子 720×780：宽是受限的那一边
        assertEquals(PreviewSize(720, 540), fitInside(4000, 3000, 720, 780))
    }

    @Test
    fun `竖图按高度贴满盒子`() {
        assertEquals(PreviewSize(585, 780), fitInside(3000, 4000, 720, 780))
    }

    @Test
    fun `方图取较小的那一边`() {
        assertEquals(PreviewSize(720, 720), fitInside(1000, 1000, 720, 780))
    }

    @Test
    fun `小图不放大`() {
        // 表情、图标本来就只有几十像素，拉大只会糊
        assertEquals(PreviewSize(64, 64), fitInside(64, 64, 720, 780))
        assertEquals(PreviewSize(80, 40), fitInside(80, 40, 720, 780))
    }

    @Test
    fun `尺寸非法时返回零`() {
        assertEquals(PreviewSize(0, 0), fitInside(0, 100, 720, 780))
        assertEquals(PreviewSize(0, 0), fitInside(100, -1, 720, 780))
        assertEquals(PreviewSize(0, 0), fitInside(100, 100, 0, 780))
    }

    @Test
    fun `极端窄图也不会算出零高度`() {
        // 退化输入不该产出 0 尺寸的布局——那会让整行渲染不出来
        val size = fitInside(4000, 3, 720, 780)

        assertEquals(720, size.width)
        assertEquals(1, size.height)
    }

    @Test
    fun `降采样保证长边不小于目标`() {
        // 8000×6000 目标 780：8 倍后长边 1000 仍够用，16 倍就只剩 500 了
        assertEquals(8, sampleSizeFor(8000, 6000, 780))
        assertEquals(4, sampleSizeFor(4000, 3000, 780))
    }

    @Test
    fun `宽屏图不按短边保守降采样`() {
        // 短边只要跟着等比缩就行；若要求短边也不小于目标，这张 16:9 会整张解进内存
        val sample = sampleSizeFor(2560, 1440, 800)

        assertEquals(2, sample)
        assertEquals(1280, 2560 / sample)
    }

    @Test
    fun `原图不大于目标时不降采样`() {
        assertEquals(1, sampleSizeFor(640, 480, 780))
        assertEquals(1, sampleSizeFor(780, 780, 780))
    }

    @Test
    fun `尺寸或目标未知时不降采样`() {
        assertEquals(1, sampleSizeFor(0, 0, 780))
        assertEquals(1, sampleSizeFor(4000, 3000, 0))
    }

    @Test
    fun `缓存档位向上取整到步长`() {
        assertEquals(128, cacheBucketOf(128))
        assertEquals(128, cacheBucketOf(1))
        assertEquals(256, cacheBucketOf(129))
        assertEquals(896, cacheBucketOf(780))
    }

    @Test
    fun `非法的缓存档位回落到一档`() {
        assertEquals(128, cacheBucketOf(0))
        assertEquals(128, cacheBucketOf(-5))
    }
}
