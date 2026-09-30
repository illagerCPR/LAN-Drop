package io.github.illagercpr.landrop.media

import io.github.illagercpr.landrop.data.local.MessageEntity
import io.github.illagercpr.landrop.data.local.TransferDirection
import io.github.illagercpr.landrop.data.local.TransferEntity
import io.github.illagercpr.landrop.data.local.TransferState

/**
 * 时间线里一张图片预览的取字节来源。
 *
 * 两种来源都带上 [fileId]：缓存键要跟着「服务端那一个文件」走，而不是跟着
 * 这一次从哪儿取——同一条消息先显示服务端原图、下载完成后再读本地副本，
 * 必须是同一张缓存，否则白解一次码。
 */
sealed interface ThumbnailSource {
    val fileId: String

    /** 本机已有副本：下载落盘的 MediaStore 行，或本机发出时的源文件。 */
    data class Local(override val fileId: String, val uri: String) : ThumbnailSource

    /** 只有服务端有：按 fileId 取原图，本地降采样。 */
    data class Remote(override val fileId: String) : ThumbnailSource
}

/** 与 [MessageEntity.kind] 对应；时间线只把文件消息渲染成文件卡片。 */
const val MESSAGE_KIND_FILE = "file"

/**
 * 超过这个大小的图片不自动取原图做预览。
 *
 * 服务端没有缩略图接口（出包是自包含单文件，塞不进原生图像库），预览只能拉原图
 * 再本地降采样。手机随手拍的照片在几 MB 量级，局域网里等一两秒可以接受；
 * 但几十 MB 的全景图/扫描件为了一张小图拉一遍就不划算了——那种情况下直接
 * 显示文件卡片，用户真要就点「下载」。
 */
const val MAX_REMOTE_PREVIEW_BYTES = 24L * 1024 * 1024

/**
 * 是否值得尝试渲染预览：只有 `image/` 开头的 mime 有缩略图。
 *
 * 注意 Kotlin 的块注释可以嵌套：文档里写 `image/` + 通配符会开一个嵌套注释，
 * 把整个文件后半截吞掉（编译报 Unclosed comment）。
 */
fun isImageMime(mime: String?): Boolean = mime != null && mime.startsWith("image/")

/**
 * 一条消息的预览候选来源，按优先级排列；没有可用的就返回空列表。
 *
 * 顺序即代价顺序：先本机已有副本（一次解码，不联网），再本机发送时的源文件，
 * 最后才回服务端拉原图。**失败要能顺延到下一个**，因为前两个都可能失效：
 * 用户可能刚从图库删掉了下载的文件，而分享进来的源 URI 只有临时授权
 * （见 AGENTS.md：`ACTION_SEND` 的 URI 活不过本进程），上传完成后
 * `releasePersistableRead` 又会把 SAF 的长期授权还回去。
 *
 * [transfers] 是本地传输记录（新→旧或乱序都可，这里自己按时间排序）。
 */
fun thumbnailSourcesOf(
    message: MessageEntity,
    transfers: List<TransferEntity>,
): List<ThumbnailSource> {
    if (message.kind != MESSAGE_KIND_FILE) return emptyList()
    if (!isImageMime(message.fileMime)) return emptyList()

    val fileId = message.fileId?.takeIf { it.isNotBlank() } ?: return emptyList()
    val ordered = transfers.sortedByDescending { it.updatedAt }

    val downloaded = ordered.completedLocalUri(message.id, TransferDirection.DOWNLOAD)
    val uploaded = ordered.completedLocalUri(message.id, TransferDirection.UPLOAD)

    // 正在下载这条消息时不要再拉一遍原图：预览是「顺手看一眼」，而下载是用户
    // 明确要的东西，让预览把同一份字节再传一次会白白占掉一半带宽（自动接收开启时
    // 尤其明显——消息一到就开始下载，时间线这时正好在组合）。
    val downloading = ordered.any {
        it.messageId == message.id &&
            it.direction == TransferDirection.DOWNLOAD &&
            (it.state == TransferState.QUEUED || it.state == TransferState.RUNNING)
    }

    return buildList {
        downloaded?.let { add(ThumbnailSource.Local(fileId, it)) }
        uploaded?.let { add(ThumbnailSource.Local(fileId, it)) }

        // 大小未知（理论上文件消息都有）时按「可以试」处理：宁可多试一次，
        // 也不要因为缺一个字段就让所有预览都不显示。
        val size = message.fileSize
        val withinBudget = size == null || size <= MAX_REMOTE_PREVIEW_BYTES
        if (!downloading && withinBudget) add(ThumbnailSource.Remote(fileId))
    }
}

private fun List<TransferEntity>.completedLocalUri(messageId: String, direction: String): String? =
    firstOrNull {
        it.messageId == messageId &&
            it.direction == direction &&
            it.state == TransferState.COMPLETED
    }?.localUri?.takeIf { it.isNotBlank() }
