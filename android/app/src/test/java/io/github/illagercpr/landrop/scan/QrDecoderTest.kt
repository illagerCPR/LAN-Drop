package io.github.illagercpr.landrop.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [QrDecoder] 的纯 JVM 验证：用 zxing 自己的编码器生成亮度矩阵再喂回解码器，
 * 把「合成二维码 → 亮度数组 → 解码」整条管线钉住。相机取流本身只能真机验证。
 */
class QrDecoderTest {

    /** 服务端 routes/pair.ts 实际编码进二维码的内容形态。 */
    private val pairingUrl = "http://192.168.1.5:8787/#pair=7F3K9Q"

    /**
     * 编码成方阵亮度数组：bit=true 是黑色（暗、低亮度），bit=false 是白色。
     * 返回「紧凑行主序数组 to 边长」。
     *
     * 目标边长取 300：相机分析帧 640×480 上，占画面约三分之二的二维码就是
     * 每"模块"好几个像素的尺度。实测 33×33（1px/模块）的极小合成图连
     * binarizer 都推不出阈值，那不是相机里会出现的输入，别拿它当用例。
     */
    private fun encodeToLuminance(content: String): Pair<ByteArray, Int> {
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            /* width = */ 300,
            /* height = */ 300,
            mapOf(EncodeHintType.MARGIN to 2),
        )
        val side = matrix.width
        val data = ByteArray(side * side)
        for (y in 0 until side) {
            for (x in 0 until side) {
                data[y * side + x] = if (matrix[x, y]) 0 else 0xFF.toByte()
            }
        }
        return data to side
    }

    @Test
    fun `服务端配对 URL 可解出原文`() {
        val (data, side) = encodeToLuminance(pairingUrl)
        assertEquals(pairingUrl, QrDecoder.decode(data, side, side))
    }

    @Test
    fun `全白画面没有码`() {
        val side = 64
        val blank = ByteArray(side * side) { 0xFF.toByte() }
        assertNull(QrDecoder.decode(blank, side, side))
    }

    @Test
    fun `空帧与非法尺寸返回 null 而不是抛异常`() {
        assertNull(QrDecoder.decode(ByteArray(0), 0, 0))
        assertNull(QrDecoder.decode(ByteArray(4), 2, 0))
        assertNull(QrDecoder.decode(ByteArray(4), 0, 2))
    }

    @Test
    fun `rowStride 大于宽度时按行打包后可解`() {
        val (packed, side) = encodeToLuminance(pairingUrl)
        val rowStride = side + 7
        val padded = ByteArray(rowStride * side) { 0x55 } // 行尾垫字节模拟真实缓冲
        for (row in 0 until side) {
            System.arraycopy(packed, row * side, padded, row * rowStride, side)
        }
        assertEquals(pairingUrl, QrDecoder.decode(padded, side, side, rowStride = rowStride))
    }

    @Test
    fun `pixelStride 为 2 的交错亮度被正确抽取`() {
        // 半平面 YUV 的 Y 平面就是这种排布：亮度与色度交错（pixelStride=2）
        val (packed, side) = encodeToLuminance(pairingUrl)
        val rowStride = side * 2
        val interleaved = ByteArray(rowStride * side) { 0x7F } // 奇数位是垫色度
        for (row in 0 until side) {
            for (x in 0 until side) {
                interleaved[row * rowStride + x * 2] = packed[row * side + x]
            }
        }
        assertEquals(
            pairingUrl,
            QrDecoder.decode(interleaved, side, side, rowStride = rowStride, pixelStride = 2),
        )
    }
}
