package io.github.illagercpr.landrop.media

import kotlin.math.roundToInt

/** 按比例装入盒子后的尺寸（像素）。 */
data class PreviewSize(val width: Int, val height: Int)

/**
 * 把 [width]×[height] 按比例装进 [maxWidth]×[maxHeight]，**不放大**。
 *
 * 之所以要在位图尺寸上算一遍、而不是交给 `ContentScale.Fit`：Fit 只把图像缩放着画进
 * 布局盒子，布局本身仍占满约束——竖图会被塞进一个横着多出空白的大框里，气泡里
 * 看着就是「图没对齐」。这里直接给出布局尺寸，气泡贴合图像本身。
 *
 * 不放大是为了小图（表情、图标）不被拉糊——本来多大就画多大。
 * 任一维非正时返回 0×0，调用方据此不渲染。
 */
fun fitInside(width: Int, height: Int, maxWidth: Int, maxHeight: Int): PreviewSize {
    if (width <= 0 || height <= 0 || maxWidth <= 0 || maxHeight <= 0) return PreviewSize(0, 0)

    val scale = minOf(1f, maxWidth.toFloat() / width, maxHeight.toFloat() / height)
    return PreviewSize(
        width = (width * scale).roundToInt().coerceAtLeast(1),
        height = (height * scale).roundToInt().coerceAtLeast(1),
    )
}

/**
 * 解码降采样因子：`BitmapFactory.Options.inSampleSize` 语义（`ImageDecoder` 的
 * `setTargetSampleSize` 同义）。
 *
 * 判据是**长边**不小于 [targetPx]：预览盒子按长边给目标，短边随之等比缩小。
 * 若按「两条边都不小于目标」来判，16:9 的图会被当成方图处理——短边要 800 像素，
 * 于是 2560×1440 一点不降地整张解进内存（14 MB），而实际上 1280×720 就够清楚了。
 *
 * 尺寸未知（解码前拿不到）时返回 1，即原样解码。
 */
fun sampleSizeFor(width: Int, height: Int, targetPx: Int): Int {
    if (width <= 0 || height <= 0 || targetPx <= 0) return 1

    val longest = maxOf(width, height)
    var sample = 1
    while (longest / (sample * 2) >= targetPx) {
        sample *= 2
    }
    return sample
}

/**
 * 缓存档位：把目标像素尺寸归一到 [stepPx] 的整数倍。
 *
 * 目标尺寸来自 `dp × density`，折叠屏展开、外接屏或密度改变时都会变；不定档位
 * 就会为同一张图反复重下重存。归到粗档位后，同一台设备上基本只有一个档。
 */
fun cacheBucketOf(targetPx: Int, stepPx: Int = 128): Int {
    if (targetPx <= 0 || stepPx <= 0) return stepPx.coerceAtLeast(1)
    return ((targetPx + stepPx - 1) / stepPx) * stepPx
}
