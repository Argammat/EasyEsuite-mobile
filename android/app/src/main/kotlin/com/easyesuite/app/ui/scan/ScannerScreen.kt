package com.easyesuite.app.ui.scan

import android.Manifest
import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen barcode scanner (CameraX + ML Kit). Reports the first stable code via [onCode].
 * `mode` is passed through by the caller: "any" (resolve item/shipment) or "return" (give the raw code back).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun ScannerScreen(mode: String, onBack: () -> Unit, onCode: (String) -> Unit) {
    val camera = rememberPermissionState(Manifest.permission.CAMERA)
    var manual by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    val latestOnCode by rememberUpdatedState(onCode)

    LaunchedEffect(Unit) { if (!camera.status.isGranted) camera.launchPermissionRequest() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (mode == "return") "Scan barcode" else "Scan item or label") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { IconButton(onClick = { manual = !manual }) { Icon(Icons.Default.Keyboard, contentDescription = "Type code") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    camera.status.isGranted -> CameraPreview(onCode = { code -> latestOnCode(code) })
                    else -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (camera.status.shouldShowRationale) "Camera access is needed to scan barcodes." else "Allow camera access to scan barcodes, or type the code below.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { camera.launchPermissionRequest() }) { Text("Allow camera") }
                        TextButton(onClick = { manual = true }) { Text("Type the code instead") }
                    }
                }
                // Reticle
                Box(
                    Modifier.align(Alignment.Center).fillMaxWidth(0.8f).height(160.dp)
                        .padding(2.dp),
                ) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        val stroke = 4.dp.toPx(); val len = 28.dp.toPx(); val c = Color.White
                        drawLine(c, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(len, 0f), stroke)
                        drawLine(c, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(0f, len), stroke)
                        drawLine(c, androidx.compose.ui.geometry.Offset(size.width, 0f), androidx.compose.ui.geometry.Offset(size.width - len, 0f), stroke)
                        drawLine(c, androidx.compose.ui.geometry.Offset(size.width, 0f), androidx.compose.ui.geometry.Offset(size.width, len), stroke)
                        drawLine(c, androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(len, size.height), stroke)
                        drawLine(c, androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(0f, size.height - len), stroke)
                        drawLine(c, androidx.compose.ui.geometry.Offset(size.width, size.height), androidx.compose.ui.geometry.Offset(size.width - len, size.height), stroke)
                        drawLine(c, androidx.compose.ui.geometry.Offset(size.width, size.height), androidx.compose.ui.geometry.Offset(size.width, size.height - len), stroke)
                    }
                }
            }
            if (manual || !camera.status.isGranted) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = typed, onValueChange = { typed = it.trim() }, label = { Text("UPC, SKU, order # or tracking #") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { if (typed.isNotBlank()) onCode(typed) }),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { if (typed.isNotBlank()) onCode(typed) }, enabled = typed.isNotBlank()) { Text("Go") }
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(onCode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val delivered = remember { AtomicBoolean(false) }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(
                Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E, Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39, Barcode.FORMAT_ITF, Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_PDF417,
            ).build(),
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            scanner.close()
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                bindCamera(ctx, lifecycleOwner, this, executor, scanner, delivered, onCode)
            }
        },
    )
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
private fun bindCamera(
    context: Context,
    owner: LifecycleOwner,
    previewView: PreviewView,
    executor: java.util.concurrent.ExecutorService,
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    delivered: AtomicBoolean,
    onCode: (String) -> Unit,
) {
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener({
        val provider = future.get()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(executor) { proxy: ImageProxy ->
            val media = proxy.image
            if (media == null || delivered.get()) { proxy.close(); return@setAnalyzer }
            val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
            scanner.process(image)
                .addOnSuccessListener { codes ->
                    val value = codes.firstNotNullOfOrNull { it.rawValue?.trim()?.takeIf { v -> v.isNotEmpty() } }
                    if (value != null && delivered.compareAndSet(false, true)) {
                        previewView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        analysis.clearAnalyzer()
                        previewView.post { onCode(normalize(value)) }
                    }
                }
                .addOnCompleteListener { proxy.close() }
        }
        try {
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        } catch (e: Exception) {
            // Camera in use / not available — the manual entry field remains usable.
        }
    }, ContextCompat.getMainExecutor(context))
}

/** UPC-E → UPC-A expansion is left to the backend; we only strip whitespace and FNC1 noise. */
private fun normalize(raw: String): String = raw.replace("\u001d", "").trim()
