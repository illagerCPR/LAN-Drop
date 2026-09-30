package io.github.illagercpr.landrop.ui.pair

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 配对页。
 *
 * 手输地址 + 配对码是主路径；「粘贴配对链接」可以直接消化从 PC 复制来的
 * 整条 `http://ip:port/#pair=CODE`，与扫码走同一套解析逻辑（P3 接摄像头即可）。
 */
@Composable
fun PairingScreen(
    viewModel: PairingViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val feedback by viewModel.feedback.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val discovered by viewModel.discovered.collectAsStateWithLifecycle()

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Text("连接到 PC", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "在 PC 上启动 LAN-Drop 服务端，用浏览器打开它，页面上会显示配对码。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = viewModel.address,
                onValueChange = viewModel::onAddressChange,
                label = { Text("服务器地址") },
                placeholder = { Text("192.168.1.100:8787") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = viewModel.code,
                onValueChange = viewModel::onCodeChange,
                label = { Text("配对码") },
                placeholder = { Text("PC 页面显示的 6 位配对码") },
                singleLine = true,
                enabled = !busy,
                // 必须允许字母：配对码字母表是「大写字母 + 数字」，
                // 用数字键盘会让用户根本敲不出字母（实测踩到过）。
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = viewModel::pair,
                    enabled = !busy && viewModel.address.isNotBlank() && viewModel.code.isNotBlank(),
                ) {
                    Text("配对")
                }

                Spacer(Modifier.width(12.dp))

                OutlinedButton(
                    onClick = viewModel::testConnection,
                    enabled = !busy && viewModel.address.isNotBlank(),
                ) {
                    Text("测试连接")
                }

                Spacer(Modifier.width(12.dp))

                TextButton(
                    onClick = { readPairingLink(context)?.let(viewModel::applyScanned) },
                    enabled = !busy,
                ) {
                    Text("粘贴链接")
                }
            }

            if (busy) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.width(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("正在连接…", style = MaterialTheme.typography.bodyMedium)
                }
            }

            Spacer(Modifier.height(24.dp))

            // ---- 局域网自动发现 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = viewModel::scan, enabled = !busy && !scanning) {
                    Text(if (scanning) "扫描中…" else "扫描局域网")
                }
                if (scanning) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(modifier = Modifier.width(16.dp), strokeWidth = 2.dp)
                }
            }

            discovered.forEach { server ->
                Spacer(Modifier.height(8.dp))
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.useDiscovered(server) }
                            .padding(16.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(server.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                server.baseUrl.removePrefix("http://"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "使用",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            feedback?.let { result ->
                Spacer(Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (result.isError) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                    ),
                ) {
                    Text(
                        text = result.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (result.isError) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            OutlinedCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("本机将以「${viewModel.deviceName}」的身份出现在 PC 上。", style = MaterialTheme.typography.bodySmall)
                    Text("手机需与 PC 处于同一局域网（同一 WiFi）。", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "配对码是一次性的：配对成功后 PC 会立即换一张新码。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/** 读剪贴板首条文本（读剪贴板在 Android 13+ 会由系统弹出提示，属预期行为）。 */
private fun readPairingLink(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = clipboard?.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
}
