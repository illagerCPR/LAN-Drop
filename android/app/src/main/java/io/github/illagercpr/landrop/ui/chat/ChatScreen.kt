package io.github.illagercpr.landrop.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.illagercpr.landrop.data.local.MessageEntity
import io.github.illagercpr.landrop.data.local.TransferDirection
import io.github.illagercpr.landrop.data.local.TransferEntity
import io.github.illagercpr.landrop.data.local.TransferState
import io.github.illagercpr.landrop.data.repo.SyncState
import io.github.illagercpr.landrop.net.SocketState
import io.github.illagercpr.landrop.ui.common.progressFraction

/**
 * 会话页：时间线 + 输入栏 + 传输记录切换。
 *
 * 时间线用 `reverseLayout = true` 渲染——Room 的查询是 `seq DESC`（新→旧），
 * 配合反向布局就是「新的在底部、打开即贴底」，省掉手动计算滚动位置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    serverName: String,
    modifier: Modifier = Modifier,
) {
    val timeline by viewModel.timeline.collectAsStateWithLifecycle(initialValue = emptyList())
    val outbox by viewModel.outbox.collectAsStateWithLifecycle()
    val transfers by viewModel.transferList.collectAsStateWithLifecycle(initialValue = emptyList())
    val socketState by viewModel.socketState.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val onlineCount by viewModel.onlineCount.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()

    var showTransfers by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    // 新消息到达且用户本来就在底部附近时自动贴底；用户翻历史时不打扰
    LaunchedEffect(timeline.firstOrNull()?.seq, outbox.size) {
        if ((timeline.isNotEmpty() || outbox.isNotEmpty()) && listState.firstVisibleItemIndex <= 2) {
            listState.animateScrollToItem(0)
        }
    }

    // 回到前台补一次增量同步：后台期间进程可能被冻结，长连接未必能及时感知
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        viewModel.refresh()
    }

    notice?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeNotice()
        }
    }

    val downloads = remember(transfers) { transfers.toDownloadStates() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = serverName.ifBlank { "LAN-Drop" },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = statusLine(socketState, syncState, onlineCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { showTransfers = !showTransfers }) {
                        Text(if (showTransfers) "聊天" else "传输")
                    }
                    Box {
                        TextButton(onClick = { showMenu = true }) { Text("更多") }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("立即同步") },
                                onClick = {
                                    showMenu = false
                                    viewModel.refresh()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("解除配对") },
                                onClick = {
                                    showMenu = false
                                    viewModel.unpair()
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (!showTransfers) {
                ChatInputBar(
                    draft = viewModel.draft,
                    onDraftChange = viewModel::onDraftChange,
                    onSend = viewModel::send,
                    onPickFile = viewModel::sendFile,
                )
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            if (showTransfers) {
                TransferPanel(
                    transfers = transfers,
                    onPause = viewModel::pauseTransfer,
                    onResume = viewModel::resumeTransfer,
                    onCancel = viewModel::cancelTransfer,
                )
            } else {
                Timeline(
                    timeline = timeline,
                    outbox = outbox,
                    listState = listState,
                    downloads = downloads,
                    viewModel = viewModel,
                )
            }
        }
    }
}

@Composable
private fun Timeline(
    timeline: List<MessageEntity>,
    outbox: List<OutboxItem>,
    listState: LazyListState,
    downloads: Map<String, DownloadState>,
    viewModel: ChatViewModel,
) {
    if (timeline.isEmpty() && outbox.isEmpty()) {
        EmptyTimeline(modifier = Modifier.fillMaxSize())
        return
    }

    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        // reverseLayout 下 index 0 在最底部，所以先渲染发送中的气泡
        items(outbox, key = { it.id }) { item ->
            OutboxRow(
                item = item,
                onRetry = viewModel::retry,
                onDiscard = viewModel::discard,
            )
        }

        items(timeline, key = { it.id }) { message ->
            MessageRow(
                message = message,
                downloadState = downloads[message.id],
                onDownload = viewModel::download,
            )
        }
    }
}

@Composable
private fun ChatInputBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onPickFile: (android.net.Uri, String?) -> Unit,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onPickFile(it, null) }
    }

    Surface(tonalElevation = 3.dp) {
        Column {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TextButton(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    modifier = Modifier.height(56.dp),
                ) {
                    Text("文件")
                }

                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    placeholder = { Text("发消息…") },
                    maxLines = 4,
                    modifier = Modifier.weight(1f),
                )

                Button(
                    onClick = onSend,
                    enabled = draft.isNotBlank(),
                    modifier = Modifier.height(56.dp),
                ) {
                    Text("发送")
                }
            }
        }
    }
}

/** 顶栏副标题：连接状态优先，其次同步状态与在线设备数。 */
private fun statusLine(state: SocketState, sync: SyncState, onlineCount: Int): String = when {
    state == SocketState.ONLINE && sync is SyncState.Syncing -> "在线 · 正在同步…"
    state == SocketState.ONLINE -> "在线 · $onlineCount 台设备"
    state == SocketState.CONNECTING -> "正在连接…"
    state == SocketState.RECONNECTING -> "连接断开，正在重连…"
    sync is SyncState.Failed -> sync.message
    else -> "未连接"
}

/**
 * 把传输记录投影成「按消息 ID 索引的下载状态」。
 *
 * 只取每个文件的最新一条记录：用户重复点下载时，界面应该反映最后一次的结果。
 */
private fun List<TransferEntity>.toDownloadStates(): Map<String, DownloadState> {
    val result = mutableMapOf<String, DownloadState>()

    for (transfer in this) {
        if (transfer.direction != TransferDirection.DOWNLOAD || transfer.messageId.isEmpty()) continue
        if (result.containsKey(transfer.messageId)) continue

        result[transfer.messageId] = when (transfer.state) {
            TransferState.COMPLETED -> DownloadState(fraction = 1f, completed = true)

            TransferState.FAILED -> DownloadState(fraction = null, failed = transfer.error ?: "下载失败")

            else -> DownloadState(
                fraction = progressFraction(transfer.transferredBytes, transfer.totalBytes),
            )
        }
    }
    return result
}
