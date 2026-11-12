package dev.pideck.nativeapp.ui.pairing

import dev.pideck.nativeapp.data.attempt
import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/**
 * 扫码配对：扫桌面端「连接面板」里的二维码。
 *
 * 二维码内容是 `pideck://pair?host=…&port=…&token=…`（也认 `http://ip:8765/?token=…`），
 * 解出来的原始字符串直接丢回设备页，由 `PairingLink.parse` 统一解析 —— 扫码和粘贴走同一条路。
 *
 * 解码用 CameraX 的 ImageAnalysis 拿 YUV 的 Y 平面（灰度）喂 zxing，不引 ML Kit：
 * 只认一种码，少 20MB 依赖，也不用到云端。
 */
@Composable
fun ScanScreen(
    onBack: () -> Unit,
    onScanned: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var error by remember { mutableStateOf<String?>(null) }
    var handled by remember { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) error = "需要相机权限才能扫码；也可以回上一页手动粘地址。"
    }
    LaunchedEffect(Unit) {
        if (!granted) permission.launch(Manifest.permission.CAMERA)
    }

    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                    val providerFuture = ProcessCameraProvider.getInstance(ctx)
                    providerFuture.addListener({
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setTargetResolution(Size(1280, 720))
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        val reader = MultiFormatReader()
                        analysis.setAnalyzer(executor) { image ->
                            if (handled) {
                                image.close()
                                return@setAnalyzer
                            }
                            val text = attempt { decodeQr(reader, image) }.getOrNull()
                            image.close()
                            if (!text.isNullOrBlank()) {
                                handled = true
                                previewView.post { onScanned(text) }
                            }
                        }
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White,
                    )
                }
                Column(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "把桌面端「设置 → 连接面板」里的二维码放进取景框",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    error?.let {
                        Box(
                            modifier = Modifier.background(
                                MaterialTheme.colorScheme.error.copy(alpha = 0.9f),
                                RoundedCornerShape(10.dp),
                            ),
                        ) {
                            Text(
                                it,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                color = MaterialTheme.colorScheme.onError,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (!granted) {
                        Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) {
                            Text("允许使用相机")
                        }
                    }
                }
            }
        }
    }
}

/** YUV_420_888 的 Y 平面 → zxing 灰度源。rowStride 可能大于宽度，按行拷紧。 */
private fun decodeQr(reader: MultiFormatReader, image: androidx.camera.core.ImageProxy): String? {
    val plane = image.planes[0]
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val width = image.width
    val height = image.height
    if (width <= 0 || height <= 0) return null
    val data: ByteArray = if (rowStride == width) {
        ByteArray(buffer.remaining()).also { buffer.get(it) }
    } else {
        ByteArray(width * height).also { out ->
            val row = ByteArray(rowStride)
            var offset = 0
            for (y in 0 until height) {
                val available = buffer.remaining().coerceAtMost(rowStride)
                buffer.get(row, 0, available)
                val copy = minOf(width, available)
                System.arraycopy(row, 0, out, offset, copy)
                offset += width
            }
        }
    }
    val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
    val bitmap = BinaryBitmap(HybridBinarizer(source))
    return attempt { reader.decodeWithState(bitmap).text }.getOrNull()
}
