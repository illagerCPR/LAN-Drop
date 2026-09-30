package io.github.illagercpr.landrop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.illagercpr.landrop.LanDropApp
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
                ChatViewModel(container.messages, container.transfers, container.pairing, container.settings)
            }
        },
    )

    val current = connection
    if (current == null) {
        PairingScreen(viewModel = pairingViewModel)
    } else {
        ChatScreen(
            viewModel = chatViewModel,
            serverName = current.serverName,
        )
    }
}
