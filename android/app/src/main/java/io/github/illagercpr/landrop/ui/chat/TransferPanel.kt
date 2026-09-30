package io.github.illagercpr.landrop.ui.chat

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.illagercpr.landrop.data.local.TransferDirection
import io.github.illagercpr.landrop.data.local.TransferEntity
import io.github.illagercpr.landrop.data.local.TransferState
import io.github.illagercpr.landrop.ui.common.formatBytes
import io.github.illagercpr.landrop.ui.common.formatTimestamp
import io.github.illagercpr.landrop.ui.common.progressFraction

/** 传输记录页：上传/下载的完整流水，进行中的可取消，完成的下载可打开。 */
@Composable
fun TransferPanel(
    transfers: List<TransferEntity>,
    onCancel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (transfers.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("还没有传输记录", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "在这里可以查看上传与下载的进度、失败原因。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(transfers, key = { it.id }) { transfer ->
            TransferCard(transfer, onCancel)
        }
    }
}

@Composable
private fun TransferCard(transfer: TransferEntity, onCancel: (String) -> Unit) {
    val context = LocalContext.current
    val uploading = transfer.direction == TransferDirection.UPLOAD
    val fraction = progressFraction(transfer.transferredBytes, transfer.totalBytes)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (uploading) "↑ 发送" else "↓ 接收",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = transfer.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = "${formatBytes(transfer.transferredBytes)} / ${formatBytes(transfer.totalBytes)}" +
                    " · ${stateLabel(transfer.state)} · ${formatTimestamp(transfer.updatedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (transfer.state == TransferState.RUNNING || transfer.state == TransferState.QUEUED) {
                Spacer(Modifier.height(6.dp))
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            transfer.error?.let { message ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (transfer.state == TransferState.RUNNING || transfer.state == TransferState.QUEUED) {
                    TextButton(onClick = { onCancel(transfer.id) }) { Text("取消") }
                }

                if (transfer.state == TransferState.COMPLETED && !uploading) {
                    val uri = transfer.localUri
                    if (uri != null) {
                        TextButton(
                            onClick = {
                                // 用 */* 交给系统按内容挑选应用：我们没在传输记录里存 MIME，
                                // 而 MediaStore 的 content uri 自带类型信息，系统能自己判断。
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(Uri.parse(uri), "*/*")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runCatching { context.startActivity(intent) }
                            },
                        ) {
                            Text("打开")
                        }
                    }
                }
            }
        }
    }
}

private fun stateLabel(state: String): String = when (state) {
    TransferState.QUEUED -> "排队中"
    TransferState.RUNNING -> "进行中"
    TransferState.PAUSED -> "已暂停"
    TransferState.COMPLETED -> "已完成"
    TransferState.FAILED -> "失败"
    TransferState.CANCELED -> "已取消"
    else -> state
}
