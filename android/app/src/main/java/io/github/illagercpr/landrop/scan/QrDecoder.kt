package io.github.illagercpr.landrop.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * 相机帧里的二维码解码（扫码配对，P3 收尾）。
 *
 * 只喂 Y 平面（亮度）数据：二维码是黑白图案，亮度通道就够了，不必整帧转 RGB。
 * [pixelStride] / [rowStride] 必须如实传入——相机 HAL 常用半平面格式
 * （pixelStride=2，亮度与色度交错），当成紧凑数组直接喂会解出乱码或找不到码。
 *
 * 刻意不做帧内旋转：QR 的三个定位图案决定解码与 90° 旋转无关，
 * zxing 的 Detector 会自行尝试四个方向；竖屏持机时传感器帧是横向的，照样可解。
 *
 * 纯 JVM 依赖（只有 zxing core），单测用 zxing 自己的编码器生成亮度数据来验证。
 */
object QrDecoder {

    /**
     * 解一帧；没有码返回 null。绝大多数帧都没有码，所以「没码」用 null 表达
     * 而不是异常——[NotFoundException] 等解码失败在这里属于正常路径。
     */
    fun decode(
        luminance: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int = width,
        pixelStride: Int = 1,
    ): String? {
        if (width <= 0 || height <= 0 || rowStride <= 0 || pixelStride <= 0) return null

        return runCatching {
            val source = PlanarYUVLuminanceSource(
                extractPackedRows(luminance, width, height, rowStride, pixelStride),
                /* dataWidth = */ width,
                /* dataHeight = */ height,
                /* left = */ 0,
                /* top = */ 0,
                width,
                height,
                /* reverseHorizontal = */ false,
            )
            val reader = MultiFormatReader()
            reader.setHints(
                mapOf(
                    // 只认 QR：配对码只通过二维码发；顺带避免把环境里的条形码当输入
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                ),
            )
            reader.decode(BinaryBitmap(HybridBinarizer(source))).text
        }.getOrNull()
    }

    /**
     * 把带 stride 的 Y 平面折叠成紧凑的 width×height 行主序数组。
     * 紧凑排布（pixelStride==1 且 rowStride==width）时原样返回，零拷贝。
     */
    private fun extractPackedRows(
        luminance: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
    ): ByteArray {
        if (pixelStride == 1 && rowStride == width) return luminance

        val packed = ByteArray(width * height)
        for (row in 0 until height) {
            val sourceStart = row * rowStride
            if (pixelStride == 1) {
                System.arraycopy(luminance, sourceStart, packed, row * width, width)
            } else {
                var source = sourceStart
                var target = row * width
                repeat(width) {
                    packed[target++] = luminance[source]
                    source += pixelStride
                }
            }
        }
        return packed
    }
}
