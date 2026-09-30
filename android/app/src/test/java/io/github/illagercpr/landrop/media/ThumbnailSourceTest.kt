package io.github.illagercpr.landrop.media

import io.github.illagercpr.landrop.data.local.MessageDirection
import io.github.illagercpr.landrop.data.local.MessageEntity
import io.github.illagercpr.landrop.data.local.MessageLocalState
import io.github.illagercpr.landrop.data.local.TransferDirection
import io.github.illagercpr.landrop.data.local.TransferEntity
import io.github.illagercpr.landrop.data.local.TransferState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缩略图取图顺序。
 *
 * 这几条规则的价值在于**代价与失效**：本机副本最便宜但可能已被用户删掉，
 * 服务端那份一定有但要花带宽。顺序错了的后果不是崩溃，而是「明明就在手机里，
 * 却为看一眼把原图又传了一遍」——只有单测钉得住。
 */
class ThumbnailSourceTest {

    @Test
    fun `没有本地副本时回服务端取`() {
        val sources = thumbnailSourcesOf(message(), emptyList())

        assertEquals(listOf(ThumbnailSource.Remote("file-1")), sources)
    }

    @Test
    fun `下载完成后优先读本地副本`() {
        val transfers = listOf(
            transfer(TransferDirection.DOWNLOAD, TransferState.COMPLETED, "content://media/external/downloads/7"),
        )

        val sources = thumbnailSourcesOf(message(), transfers)

        assertEquals(
            listOf(
                ThumbnailSource.Local("file-1", "content://media/external/downloads/7"),
                ThumbnailSource.Remote("file-1"),
            ),
            sources,
        )
    }

    @Test
    fun `本机发出时先读手上的源文件`() {
        // 分享进来的照片在进程存续期内仍然可读，不必为了预览再传一遍回来
        val transfers = listOf(
            transfer(TransferDirection.UPLOAD, TransferState.COMPLETED, "content://media/external/images/media/1000102703"),
        )

        val sources = thumbnailSourcesOf(message(), transfers)

        assertEquals(
            listOf(
                ThumbnailSource.Local("file-1", "content://media/external/images/media/1000102703"),
                ThumbnailSource.Remote("file-1"),
            ),
            sources,
        )
    }

    @Test
    fun `下载副本优先于上传源文件`() {
        // 同一条消息两边都有记录（先发出、又被别人改了再传回来）时，
        // 下载副本是我们真正会打开的那一份
        val transfers = listOf(
            transfer(TransferDirection.UPLOAD, TransferState.COMPLETED, "content://source/1", updatedAt = 200),
            transfer(TransferDirection.DOWNLOAD, TransferState.COMPLETED, "content://download/1", updatedAt = 100),
        )

        val sources = thumbnailSourcesOf(message(), transfers)

        assertEquals(ThumbnailSource.Local("file-1", "content://download/1"), sources.first())
    }

    @Test
    fun `同方向有多条记录时取最新的一条`() {
        val transfers = listOf(
            transfer(TransferDirection.DOWNLOAD, TransferState.COMPLETED, "content://download/old", updatedAt = 100),
            transfer(TransferDirection.DOWNLOAD, TransferState.COMPLETED, "content://download/new", updatedAt = 300),
        )

        val sources = thumbnailSourcesOf(message(), transfers)

        assertEquals(ThumbnailSource.Local("file-1", "content://download/new"), sources.first())
    }

    @Test
    fun `正在下载时不回服务端拉原图`() {
        // 自动接收刚把这条消息排上队，时间线同时在组合——不挡一下就是同一份字节传两遍
        val transfers = listOf(transfer(TransferDirection.DOWNLOAD, TransferState.RUNNING, null))

        assertTrue(thumbnailSourcesOf(message(), transfers).isEmpty())
    }

    @Test
    fun `排队中的下载同样挡掉服务端取图`() {
        val transfers = listOf(transfer(TransferDirection.DOWNLOAD, TransferState.QUEUED, null))

        assertTrue(thumbnailSourcesOf(message(), transfers).isEmpty())
    }

    @Test
    fun `暂停的下载不挡预览`() {
        // 用户可能就是暂停了不想等：这时给他看缩略图比什么都不显示有用
        val transfers = listOf(transfer(TransferDirection.DOWNLOAD, TransferState.PAUSED, null))

        assertEquals(listOf(ThumbnailSource.Remote("file-1")), thumbnailSourcesOf(message(), transfers))
    }

    @Test
    fun `下载失败后仍可回服务端取预览`() {
        val transfers = listOf(transfer(TransferDirection.DOWNLOAD, TransferState.FAILED, null))

        assertEquals(listOf(ThumbnailSource.Remote("file-1")), thumbnailSourcesOf(message(), transfers))
    }

    @Test
    fun `超过体积上限的图片不自动取原图`() {
        val big = message(size = MAX_REMOTE_PREVIEW_BYTES + 1)

        assertTrue(thumbnailSourcesOf(big, emptyList()).isEmpty())
    }

    @Test
    fun `恰好等于上限时仍然取`() {
        val atLimit = message(size = MAX_REMOTE_PREVIEW_BYTES)

        assertEquals(listOf(ThumbnailSource.Remote("file-1")), thumbnailSourcesOf(atLimit, emptyList()))
    }

    @Test
    fun `大小未知不放弃预览`() {
        // 缺一个装饰性字段就全都不显示，比偶尔多试一次糟得多
        val unknown = message(size = null)

        assertEquals(listOf(ThumbnailSource.Remote("file-1")), thumbnailSourcesOf(unknown, emptyList()))
    }

    @Test
    fun `空白的本地 URI 不算本地副本`() {
        val transfers = listOf(
            transfer(TransferDirection.DOWNLOAD, TransferState.COMPLETED, "   "),
        )

        assertEquals(listOf(ThumbnailSource.Remote("file-1")), thumbnailSourcesOf(message(), transfers))
    }

    @Test
    fun `其他消息的本地副本不算数`() {
        val transfers = listOf(
            transfer(TransferDirection.DOWNLOAD, TransferState.COMPLETED, "content://download/1", messageId = "other"),
        )

        assertEquals(listOf(ThumbnailSource.Remote("file-1")), thumbnailSourcesOf(message(), transfers))
    }

    @Test
    fun `非图片文件没有预览`() {
        assertTrue(thumbnailSourcesOf(message(mime = "application/pdf"), emptyList()).isEmpty())
    }

    @Test
    fun `文字消息没有预览`() {
        assertTrue(thumbnailSourcesOf(message(kind = "text", mime = null), emptyList()).isEmpty())
    }

    @Test
    fun `缺少文件 ID 时没有预览`() {
        assertTrue(thumbnailSourcesOf(message(fileId = null), emptyList()).isEmpty())
        assertTrue(thumbnailSourcesOf(message(fileId = "  "), emptyList()).isEmpty())
    }

    @Test
    fun `只认 image 开头的 mime`() {
        assertTrue(isImageMime("image/jpeg"))
        assertTrue(isImageMime("image/png"))
        assertFalse(isImageMime("application/pdf"))
        assertFalse(isImageMime("video/mp4"))
        assertFalse(isImageMime(null))
    }
}

private fun message(
    kind: String = MESSAGE_KIND_FILE,
    mime: String? = "image/jpeg",
    fileId: String? = "file-1",
    size: Long? = 43_417,
    id: String = "msg-1",
): MessageEntity = MessageEntity(
    id = id,
    seq = 1,
    kind = kind,
    text = null,
    fileId = fileId,
    fileName = "photo.jpg",
    fileSize = size,
    fileMime = mime,
    senderId = "pc-1",
    senderName = "PC",
    createdAt = 1_790_000_000_000,
    direction = MessageDirection.INBOUND,
    localState = MessageLocalState.SYNCED,
)

private fun transfer(
    direction: String,
    state: String,
    localUri: String?,
    messageId: String = "msg-1",
    updatedAt: Long = 100,
): TransferEntity = TransferEntity(
    id = "$direction-$state-$updatedAt",
    messageId = messageId,
    direction = direction,
    fileName = "photo.jpg",
    totalBytes = 43_417,
    transferredBytes = 43_417,
    state = state,
    localUri = localUri,
    uploadId = null,
    remoteFileId = null,
    error = null,
    updatedAt = updatedAt,
)
