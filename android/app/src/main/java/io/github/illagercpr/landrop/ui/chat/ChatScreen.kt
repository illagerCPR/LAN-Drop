package io.github.illagercpr.landrop.ui.chat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.illagercpr.landrop.data.local.MessageEntity
import io.github.illagercpr.landrop.data.local.TransferDirection
import io.github.illagercpr.landrop.data.local.TransferEntity
import io.github.illagercpr.landrop.data.local.TransferState
import io.github.illagercpr.landrop.data.repo.SyncState
import io.github.illagercpr.landrop.media.ThumbnailSource
import io.github.illagercpr.landrop.media.thumbnailSourcesOf
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
    val autoReceiveFiles by viewModel.autoReceiveFiles.collectAsStateWithLifecycle()

    val context = LocalContext.current

    var showTransfers by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    // 通知权限只在「真的要传东西」时申请：此刻它才有具体含义（你会离开这个页面，
    // 传完了通知你），比一进应用就弹一个没有上下文的系统弹窗更容易被允许。
    // minSdk 33，POST_NOTIFICATIONS 必然存在，不需要版本判断。
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    fun ensureNotificationPermission() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

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
                                text = { Text("自动接收文件") },
                                onClick = { viewModel.toggleAutoReceiveFiles() },
                                trailingIcon = {
                                    Checkbox(
                                        checked = autoReceiveFiles,
                                        onCheckedChange = { viewModel.toggleAutoReceiveFiles() },
                                    )
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
                    onPickFiles = { uris ->
                        ensureNotificationPermission()
                        viewModel.sendFiles(uris)
                    },
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
                    transfers = transfers,
                    listState = listState,
                    downloads = downloads,
                    viewModel = viewModel,
                    loadThumbnail = viewModel::thumbnailFor,
                    onDownload = { message ->
                        ensureNotificationPermission()
                        viewModel.download(message)
                    },
                    onOpenImage = { uri -> openLocalImage(context, uri) },
                )
            }
        }
    }
}

@Composable
private fun Timeline(
    timeline: List<MessageEntity>,
    outbox: List<OutboxItem>,
    transfers: List<TransferEntity>,
    listState: LazyListState,
    downloads: Map<String, DownloadState>,
    viewModel: ChatViewModel,
    loadThumbnail: suspend (List<ThumbnailSource>, Int) -> Bitmap?,
    onDownload: (MessageEntity) -> Unit,
    onOpenImage: (String) -> Unit,
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
            val download = downloads[message.id]
            MessageRow(
                message = message,
                downloadState = download,
                thumbnail = rememberThumbnail(
                    message = message,
                    sources = thumbnailSourcesOf(message, transfers),
                    load = loadThumbnail,
                ),
                onDownload = onDownload,
                onOpenImage = onOpenImage,
            )
        }
    }
}

/**
 * 一行的缩略图。
 *
 * 只在行真的被组合时才去取（LazyColumn 天生如此），滑走时 [produceState] 连带取消
 * 取图协程——原图可能有几十 MB，不能让它继续在后台跑。
 *
 * 键直接取 [sources]：它们是数据类，按结构比较，所以传输进度每跳一次都不会重取；
 * 只有「下载完成了、本机副本出现了」或「下载开始了、服务端那份先别取了」这类
 * 真变化才会重跑一次（那时命中的其实是同一张缓存，代价可以忽略）。
 */
@Composable
private fun rememberThumbnail(
    message: MessageEntity,
    sources: List<ThumbnailSource>,
    load: suspend (List<ThumbnailSource>, Int) -> Bitmap?,
): Bitmap? {
    if (sources.isEmpty()) return null

    val targetPx = with(LocalDensity.current) { previewTargetPx() }
    return produceState<Bitmap?>(null, message.id, sources, targetPx) {
        value = load(sources, targetPx)
    }.value
}

/** 解码目标：预览盒子的长边。降采样到「刚好装进盒子」的清晰度，不多解一像素。 */
private fun Density.previewTargetPx(): Int =
    maxOf(PREVIEW_MAX_WIDTH, PREVIEW_MAX_HEIGHT).roundToPx()

/**
 * 打开已下载的图片。
 *
 * 本应用不做全屏查看器：查看、分享、设为壁纸这些事系统图库做得更好，
 * 这里只把本地副本（MediaStore 行）连同读授权交给它。
 */
private fun openLocalImage(context: Context, uri: String) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(uri), "image/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    val opened = runCatching { context.startActivity(intent) }.isSuccess
    if (!opened) Toast.makeText(context, "没有可以打开这张图片的应用", Toast.LENGTH_SHORT).show()
}

@Composable
private fun ChatInputBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onPickFiles: (List<android.net.Uri>) -> Unit,
) {
    // 多选：`OpenMultipleDocuments` 与单选版一样带持久化读授权（SAF 授予），
    // 所以批量发送的文件在进程被杀后仍然可以续传；系统分享进来的 URI 则不行（临时授权）。
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) onPickFiles(uris)
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
    // 服务端明确拒绝了凭据，重试没有意义；把出路直接告诉用户
    state == SocketState.CREDENTIALS_INVALID -> "配对已失效，请在「更多」里解除配对后重新配对"
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
            TransferState.COMPLETED -> DownloadState(
                fraction = 1f,
                completed = true,
                localUri = transfer.localUri,
            )

            TransferState.FAILED -> DownloadState(fraction = null, failed = transfer.error ?: "下载失败")

            else -> DownloadState(
                fraction = progressFraction(transfer.transferredBytes, transfer.totalBytes),
            )
        }
    }
    return result
}
