package io.github.illagercpr.landrop.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
import io.github.illagercpr.landrop.data.prefs.ConnectionStore
import io.github.illagercpr.landrop.net.LanDropApi
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 时间线图片预览的加载器：内存 LRU → 磁盘缓存 → 按来源现取。
 *
 * 服务端没有缩略图接口（便携包是自包含单文件，塞不进原生图像库），所以「服务端有」
 * 的那一条路是**拉原图再本地降采样**。这决定了三件事：
 *  1. 只在行真的被组合时才加载（LazyColumn 天然如此），滑走即取消；
 *  2. 结果必须落盘缓存，同一张图只拉一次（缓存的是**降采样后的图**，几十 KB，
 *     不会把手机相册级的大图留在缓存里）；
 *  3. 并发要有上限，别和用户正在传的文件抢带宽。
 *
 * 缓存键是 `fileId@档位`：与「这一次从哪儿取」无关，所以「先看服务端原图、下载完成
 * 后读本地副本」命中的是同一张缓存。
 */
class ThumbnailLoader(
    context: Context,
    private val api: LanDropApi,
    private val store: ConnectionStore,
) {
    private val resolver = context.applicationContext.contentResolver
    private val cacheDir = File(context.applicationContext.cacheDir, CACHE_DIR_NAME)

    /** 内存缓存按字节计：`LruCache` 的 `sizeOf` 返回字节数，淘汰才按体积而不是条数。 */
    private val memory = object : LruCache<String, Bitmap>(memoryBudgetBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 同时最多两张：预览是后台补充，不该和用户正在传的文件抢带宽。 */
    private val gate = Semaphore(permits = 2)

    /**
     * 取一条消息的预览；[sources] 由 [thumbnailSourcesOf] 给出（按代价排序）。
     *
     * 返回 null 只表示「这一张没有预览」——消息本身、文件名、大小、下载入口都不受影响。
     */
    suspend fun load(sources: List<ThumbnailSource>, targetPx: Int): Bitmap? {
        val fileId = sources.firstOrNull()?.fileId ?: return null
        val bucket = cacheBucketOf(targetPx)
        val key = "$fileId@$bucket"

        memory.get(key)?.let { return it }

        return withContext(Dispatchers.IO) {
            val cached = readDisk(fileId, bucket)
            if (cached != null) {
                memory.put(key, cached)
                return@withContext cached
            }

            for (source in sources) {
                currentCoroutineContext().ensureActive()

                // 单个来源失败要顺延到下一个：本地副本可能已被用户删掉、临时授权可能已
                // 随进程失效、服务端可能连不上——都只是「这一张预览没有」，不是错误。
                val bitmap = try {
                    decode(source, bucket)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                } ?: continue

                writeDisk(fileId, bucket, bitmap)
                memory.put(key, bitmap)
                return@withContext bitmap
            }

            null
        }
    }

    // ------------------------------------------------------------------ 取字节

    private suspend fun decode(source: ThumbnailSource, targetPx: Int): Bitmap? = when (source) {
        is ThumbnailSource.Local ->
            decodeBitmap(ImageDecoder.createSource(resolver, Uri.parse(source.uri)), targetPx)

        is ThumbnailSource.Remote -> gate.withPermit { decodeRemote(source.fileId, targetPx) }
    }

    /**
     * 服务端原图 → 本地临时文件 → 解码。
     *
     * 先落临时文件而不是读进内存：`ImageDecoder` 要能来回寻址，而把几十 MB 的原图
     * 整个塞进堆里，在低端机上就是 OOM。
     */
    private suspend fun decodeRemote(fileId: String, targetPx: Int): Bitmap? {
        val connection = store.connection.value ?: return null

        cacheDir.mkdirs()
        val temp = File.createTempFile("preview-", ".part", cacheDir)
        return try {
            api.openDownload(connection, fileId, rangeFrom = null).use { response ->
                FileOutputStream(temp).use { output -> copy(response.body.byteStream(), output) }
            }
            decodeBitmap(ImageDecoder.createSource(temp), targetPx)
        } finally {
            runCatching { temp.delete() }
        }
    }

    /**
     * 解码并缩到目标长边。
     *
     * 用 [ImageDecoder] 而不是 `BitmapFactory`：手机竖拍的照片把方向写在 EXIF 里，
     * `BitmapFactory` 不理会它，直接解出来是躺倒的；`ImageDecoder` 按 EXIF 摆正。
     * minSdk 33 早已覆盖 `ImageDecoder`（API 28+），没有兼容代价。
     */
    private fun decodeBitmap(source: ImageDecoder.Source, targetPx: Int): Bitmap? {
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // 必须是软件位图：下面要按目标尺寸缩放，而硬件位图不能作为缩放的像素源
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(sampleSizeFor(info.size.width, info.size.height, targetPx))
        }
        return scaleToLongEdge(decoded, targetPx)
    }

    /**
     * 缩到长边等于 [targetPx]（只缩不放）。
     *
     * 解码出来就缩到位，而不是留给界面缩放：缓存里放的是「显示用的那一份」，
     * 内存与磁盘都只需按预览体积算账（一张 4000×3000 的位图是 48 MB 堆）。
     */
    private fun scaleToLongEdge(bitmap: Bitmap, targetPx: Int): Bitmap {
        val size = fitInside(bitmap.width, bitmap.height, targetPx, targetPx)
        if (size.width == bitmap.width && size.height == bitmap.height) return bitmap

        val scaled = Bitmap.createScaledBitmap(bitmap, size.width, size.height, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private suspend fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            // 拉一张大图可能要好几秒；用户滑走就立刻放手，别把带宽占着
            currentCoroutineContext().ensureActive()

            val read = input.read(buffer)
            if (read < 0) return
            output.write(buffer, 0, read)
        }
    }

    // ------------------------------------------------------------------ 磁盘缓存

    private fun diskFile(fileId: String, bucket: Int): File = File(cacheDir, "$fileId-$bucket.webp")

    private fun readDisk(fileId: String, bucket: Int): Bitmap? {
        val file = diskFile(fileId, bucket)
        if (!file.isFile) return null

        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
        // 解不出来说明这一份坏了（上次写到一半被杀），删掉重下，别一直读它
        if (bitmap == null) runCatching { file.delete() }
        return bitmap
    }

    private fun writeDisk(fileId: String, bucket: Int, bitmap: Bitmap) {
        runCatching {
            cacheDir.mkdirs()
            val target = diskFile(fileId, bucket)
            // 先写临时文件再改名：半张图不会冒充完整缓存被读走
            val temp = File(cacheDir, "${target.name}.part")
            FileOutputStream(temp).use {
                // WebP 有损：照片缩略图用 PNG 会大十倍；它同时支持透明通道，
                // 所以带 alpha 的图也不会变成黑底
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, WEBP_QUALITY, it)
            }
            if (!temp.renameTo(target)) temp.delete()
        }
    }

    private companion object {
        const val CACHE_DIR_NAME = "thumbnails"
        const val WEBP_QUALITY = 80

        /** 内存预算：堆的 1/16，夹在 4~24 MB——一张缩略图几十 KB，够几百张。 */
        fun memoryBudgetBytes(): Int =
            (Runtime.getRuntime().maxMemory() / 16).coerceIn(4L * 1024 * 1024, 24L * 1024 * 1024).toInt()
    }
}
