package io.github.illagercpr.landrop.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.illagercpr.landrop.BuildConfig
import io.github.illagercpr.landrop.R
import io.github.illagercpr.landrop.protocol.ProtocolVersion
import io.github.illagercpr.landrop.ui.theme.LanDropTheme

/**
 * P0 占位首页：用于验证工具链打通（Compose + Material 3 + Room/KSP + 序列化）。
 * P2 会替换为「配对 → 会话时间线 → 传输列表」的真实界面。
 */
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .safeDrawingPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
            )
            Text(
                text = "局域网文件与文字传输",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            Card(modifier = Modifier.padding(top = 32.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    StatusLine("构建类型", BuildConfig.BUILD_TYPE)
                    StatusLine("版本", BuildConfig.VERSION_NAME)
                    StatusLine("协议版本", "v${ProtocolVersion.CURRENT}")
                    Text(
                        text = "客户端骨架已就绪，等待接入配对与会话同步。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Text(
        text = "$label：$value",
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    LanDropTheme(dynamicColor = false) {
        HomeScreen()
    }
}
