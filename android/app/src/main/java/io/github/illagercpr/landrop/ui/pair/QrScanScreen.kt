package io.github.illagercpr.landrop.ui.pair

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import io.github.illagercpr.landrop.scan.QrDecoder
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 扫码配对页（P3 收尾）：相机预览 + 连续解码。
 *
 * 二维码内容是 PC 配对页上那张码的同一串 URL（`http://ip:port/#pair=CODE`），
 * 扫到后回到配对页把地址与配对码一起填好——「配对」仍由用户点下，
 * 与手输/粘贴路径共用 [PairingViewModel.applyScanned] 的同一套解析。
 *
 * 权限只在进入本页时申请：配对页本身不弹相机授权，
 * 不扫码的用户永远看不到这道弹窗。
 */
@Composable
fun QrScanScreen(
    onBack: () -> Unit,
    onScanned: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (!granted) permissionDenied = true
    }

    // 解码结果经状态流转回主线程：分析跑在独立单线程池，回调线程不是组合线程。
    // 解出第一张码即收手——对准同一张码时连续触发没有意义。
    val decoded = remember { MutableStateFlow<String?>(null) }
    LaunchedEffect(onScanned, onBack) {
        decoded.collect { content ->
            if (content != null) {
                onScanned(content)
                onBack()
                return@collect
            }
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = Color.Black) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            TextButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) {
                Text("返回", color = Color.White)
            }

            if (hasPermission) {
                CameraScanArea(
                    onResult = { content -> decoded.value = content },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else {
                CameraPermissionRationale(
                    denied = permissionDenied,
                    onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun CameraScanArea(
    onResult: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    // 相机绑定跟随本组合函数存活：离开本页（返回、或已配对自动切到会话页）时解绑，
    // 否则相机指示灯长亮，别的应用也打不开相机。
    LaunchedEffect(previewView) {
        val provider = ProcessCameraProvider.getInstance(context).await()
        try {
            provider.unbindAll()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                // 只留最新帧：扫码要的是「现在的画面」，积压旧帧只浪费算力
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(analysisExecutor) { frame ->
                        frame.use { proxy ->
                            val plane = proxy.planes[0]
                            val buffer = plane.buffer
                            val luminance = ByteArray(buffer.remaining())
                            buffer.get(luminance)
                            QrDecoder.decode(
                                luminance = luminance,
                                width = proxy.width,
                                height = proxy.height,
                                rowStride = plane.rowStride,
                                pixelStride = plane.pixelStride,
                            )?.let(onResult)
                        }
                    }
                }
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
            awaitCancellation()
        } finally {
            runCatching { provider.unbindAll() }
        }
    }

    Box(modifier = modifier) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // 取景框：暗幕围出中央方形，提示用户把码放进这个区域
        Canvas(modifier = Modifier.fillMaxSize()) {
            val side = size.minDimension * 0.68f
            val left = (size.width - side) / 2f
            val top = (size.height - side) / 2f
            val window = Rect(left, top, left + side, top + side)

            val scrim = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                addRect(window)
            }
            drawPath(scrim, Color.Black.copy(alpha = 0.45f))
            drawRoundRect(
                color = Color.White,
                topLeft = window.topLeft,
                size = window.size,
                cornerRadius = CornerRadius(16f, 16f),
                style = Stroke(width = 2.dp.toPx()),
            )
        }

        Text(
            text = "对准 PC 配对页上的二维码",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .background(Color.Black.copy(alpha = 0.35f), shape = MaterialTheme.shapes.small)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun CameraPermissionRationale(
    denied: Boolean,
    onRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "扫码配对需要相机权限",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "二维码里只有服务器地址与配对码，不会拍摄或存档任何画面。" +
                "也可以回到上一页手动输入配对码。",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.75f),
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) {
            Text(if (denied) "再次授权相机" else "授权相机")
        }
        if (denied) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "如果点了没有反应，说明已被设为「不再询问」，请在系统设置里手动允许相机。",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
            )
        }
    }
}

/**
 * `ProcessCameraProvider.getInstance()` 返回 ListenableFuture；
 * 为了一个小小的 await 不引 guava / coroutines-guava 依赖，手写挂起桥接。
 * 执行器用直接执行即可：跨线程 resume 的安全由协程机制保证。
 */
private suspend fun <T> ListenableFuture<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addListener(
            {
                try {
                    continuation.resume(get())
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            },
            { it.run() },
        )
    }
