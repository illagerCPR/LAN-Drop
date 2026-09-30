package io.github.illagercpr.landrop.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.illagercpr.landrop.data.local.MessageDirection
import io.github.illagercpr.landrop.data.local.MessageEntity
import io.github.illagercpr.landrop.media.fitInside
import io.github.illagercpr.landrop.ui.common.formatBytes
import io.github.illagercpr.landrop.ui.common.formatTimestamp

private const val KIND_FILE = "file"

/**
 * 图片预览的尺寸上限。
 *
 * 宽度小于气泡上限（300dp）是有意的：图片顶到气泡两边会让「这是谁发的、
 * 从哪儿开始」变得难以分辨；留一圈底色，气泡的方向感才保得住。
 */
internal val PREVIEW_MAX_WIDTH = 240.dp
internal val PREVIEW_MAX_HEIGHT = 260.dp

/**
 * 一条消息。
 *
 * 方向决定对齐方式与配色：本机发出的靠右用主色，收到的靠左用表面色——
 * 这是聊天界面的通用语汇，不需要额外标签说明。
 */
@Composable
fun MessageRow(
    message: MessageEntity,
    downloadState: DownloadState?,
    thumbnail: Bitmap?,
    onDownload: (MessageEntity) -> Unit,
    onOpenImage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val outbound = message.direction == MessageDirection.OUTBOUND

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = if (outbound) Alignment.End else Alignment.Start,
    ) {
        if (!outbound) {
            Text(
                text = message.senderName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
            )
        }

        Surface(
            color = if (outbound) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (outbound) 16.dp else 4.dp,
                bottomEnd = if (outbound) 4.dp else 16.dp,
            ),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (message.kind == KIND_FILE) {
                    FileBody(message, downloadState, thumbnail, onDownload, onOpenImage)
                } else {
                    Text(
                        text = message.text.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }

                Text(
                    text = formatTimestamp(message.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * 图片消息的缩略图。
 *
 * 点击行为分三种：收到的图已下载 → 交给系统查看器打开本地副本；还没下载 → 等同于
 * 「下载」按钮（点图就是想看它，先下下来最直接）；自己发出的 → 不可点，气泡下面
 * 已经写了「已发送到 PC」，再点开自己刚发的那张没有意义。
 */
@Composable
private fun ImagePreview(
    bitmap: Bitmap,
    message: MessageEntity,
    downloadState: DownloadState?,
    onDownload: (MessageEntity) -> Unit,
    onOpenImage: (String) -> Unit,
) {
    val density = LocalDensity.current
    val size = with(density) {
        fitInside(
            width = bitmap.width,
            height = bitmap.height,
            maxWidth = PREVIEW_MAX_WIDTH.roundToPx(),
            maxHeight = PREVIEW_MAX_HEIGHT.roundToPx(),
        )
    }
    if (size.width <= 0 || size.height <= 0) return

    val onClick: (() -> Unit)? = when {
        message.direction == MessageDirection.OUTBOUND -> null

        downloadState?.completed == true -> downloadState.localUri?.let { uri -> { onOpenImage(uri) } }

        else -> ({ onDownload(message) })
    }

    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = message.fileName,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .padding(bottom = 6.dp)
            .size(
                width = with(density) { size.width.toDp() },
                height = with(density) { size.height.toDp() },
            )
            .clip(RoundedCornerShape(10.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    )
}

/** 文件消息正文：缩略图（图片才有）+ 文件名 + 大小 + 下载入口（或下载进度）。 */
@Composable
private fun FileBody(
    message: MessageEntity,
    downloadState: DownloadState?,
    thumbnail: Bitmap?,
    onDownload: (MessageEntity) -> Unit,
    onOpenImage: (String) -> Unit,
) {
    Column {
        thumbnail?.let { ImagePreview(it, message, downloadState, onDownload, onOpenImage) }

        Text(
            text = message.fileName ?: "文件",
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = formatBytes(message.fileSize ?: 0),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(6.dp))

        when {
            downloadState?.completed == true -> Text(
                text = "已保存到「下载/LAN-Drop」",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            downloadState?.failed != null -> Column {
                Text(
                    text = downloadState.failed,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = { onDownload(message) }) { Text("重试") }
            }

            downloadState != null -> DownloadProgress(downloadState)

            message.direction == MessageDirection.OUTBOUND -> Text(
                text = "已发送到 PC",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> OutlinedButton(onClick = { onDownload(message) }) { Text("下载") }
        }
    }
}

@Composable
private fun DownloadProgress(state: DownloadState) {
    Column {
        val fraction = state.fraction
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = state.fraction?.let { "接收中 ${(it * 100).toInt()}%" } ?: "接收中…",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 发送中的乐观气泡：服务端确认前只存在于内存，失败可重试或丢弃。 */
@Composable
fun OutboxRow(
    item: OutboxItem,
    onRetry: (OutboxItem) -> Unit,
    onDiscard: (OutboxItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Surface(
            color = if (item.failed) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(item.text, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (item.failed) item.error.orEmpty() else "发送中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.failed) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        if (item.failed) {
            Row {
                TextButton(onClick = { onDiscard(item) }) { Text("丢弃") }
                TextButton(onClick = { onRetry(item) }) { Text("重试") }
            }
        }
    }
}

/** 一次下载在界面上的投影；由传输记录换算而来。 */
data class DownloadState(
    val fraction: Float?,
    val completed: Boolean = false,
    val failed: String? = null,
    /** 下载完成后的本地副本（MediaStore 行），预览与「打开」都用它。 */
    val localUri: String? = null,
)

/** 空会话时的引导文案。 */
@Composable
fun EmptyTimeline(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("还没有消息", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "在 PC 的网页里发一条，或者用下面输入框发一条试试。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
