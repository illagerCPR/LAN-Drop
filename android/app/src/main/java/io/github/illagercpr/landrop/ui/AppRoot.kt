package io.github.illagercpr.landrop.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.illagercpr.landrop.LanDropApp
import io.github.illagercpr.landrop.share.ShareInbox
import io.github.illagercpr.landrop.ui.chat.ChatScreen
import io.github.illagercpr.landrop.ui.chat.ChatViewModel
import io.github.illagercpr.landrop.ui.pair.PairingScreen
import io.github.illagercpr.landrop.ui.pair.PairingViewModel

/**
 * 应用根：按「有没有配对凭据」在两个界面之间切换。
 *
 * 不做路由库——首版只有这两个目的地，且切换条件就是一条状态流，
 * 引入 Navigation 只会带来回退栈语义上的额外决策。
 */
@Composable
fun AppRoot() {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as LanDropApp).container }
    val connection by container.connectionStore.connection.collectAsStateWithLifecycle()

    val pairingViewModel: PairingViewModel = viewModel(
        factory = viewModelFactory {
            initializer { PairingViewModel(container.pairing, container.connectionStore) }
        },
    )

    val chatViewModel: ChatViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                ChatViewModel(
                    messages = container.messages,
                    transfers = container.transfers,
                    pairing = container.pairing,
                    settings = container.settings,
                    thumbnails = container.thumbnails,
                )
            }
        },
    )

    val current = connection

    // 系统分享的落点（P4-2）。投递方是 ShareTrampolineActivity（无界面中转）；
    // 放在根上而不是聊天页里：分享可能发生在还没配对的时候，
    // 那时聊天页根本没被组合，收件箱里的内容会一直没人取。
    val shared by ShareInbox.payload.collectAsStateWithLifecycle()
    LaunchedEffect(shared) {
        val payload = ShareInbox.consume() ?: return@LaunchedEffect
        val result = chatViewModel.acceptShare(payload, canSendFiles = current != null)
        val message = when {
            result.filesDropped > 0 && result.textPrefilled -> "已填入分享的文字；文件需先完成配对再分享"
            result.filesDropped > 0 -> "请先完成配对，再分享文件"
            result.filesQueued > 0 -> "已加入发送队列：${result.filesQueued} 个文件"
            result.textPrefilled -> "已填入分享的文字，确认后点发送"
            else -> null
        }
        message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    if (current == null) {
        PairingScreen(viewModel = pairingViewModel)
    } else {
        ChatScreen(
            viewModel = chatViewModel,
            serverName = current.serverName,
        )
    }
}
