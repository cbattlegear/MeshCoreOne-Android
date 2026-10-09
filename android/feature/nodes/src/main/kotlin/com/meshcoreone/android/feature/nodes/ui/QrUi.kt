// PortedFrom: MC1/Utilities/QRCodeGenerator.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Contacts/ScanContactQRView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.nodes.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.feature.nodes.add.ScanContactStateHolder
import com.meshcoreone.android.feature.nodes.share.ContactQrSpec
import java.util.concurrent.Executors

@Composable
internal fun ContactQrCode(
    spec: ContactQrSpec,
    description: String,
    modifier: Modifier = Modifier,
) {
    val matrix = remember(spec.payload, spec.correctionLevel) { contactQrMatrix(spec.payload) }
    val foreground = Color.Black
    Canvas(modifier.size(240.dp).semantics { contentDescription = description }) {
        val module = minOf(size.width, size.height) / matrix.width
        val left = (size.width - matrix.width * module) / 2f
        val top = (size.height - matrix.height * module) / 2f
        drawRect(Color.White, style = Fill)
        for (row in 0 until matrix.height) {
            for (column in 0 until matrix.width) {
                if (matrix[column, row]) {
                    drawRect(
                        color = foreground,
                        topLeft = Offset(left + column * module, top + row * module),
                        size = androidx.compose.ui.geometry.Size(module, module),
                    )
                }
            }
        }
    }
}

internal fun contactQrMatrix(payload: String): BitMatrix {
    require(payload.isNotBlank()) { "QR payload must not be blank" }
    return QRCodeWriter().encode(
        payload,
        BarcodeFormat.QR_CODE,
        1,
        1,
        mapOf(com.google.zxing.EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
    )
}

@Composable
internal fun ContactQrScanner(
    holder: ScanContactStateHolder,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var manual by remember { mutableStateOf("") }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var requested by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { accepted ->
        requested = true
        granted = accepted
        if (!accepted) holder.onCameraPermissionDenied()
    }

    Column(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.l10n_app_contacts_contacts_scan_title), style = MaterialTheme.typography.headlineSmall)
        if (granted) {
            CameraQrPreview(
                onDecoded = holder::handleScanResult,
                modifier = Modifier.fillMaxWidth().height(320.dp),
            )
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (requested) stringResource(R.string.l10n_app_contacts_contacts_scan_unavailable_description)
                        else stringResource(R.string.l10n_app_contacts_contacts_scan_permission_description),
                    )
                    Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) {
                        Text(stringResource(R.string.l10n_app_contacts_contacts_add_scanqr))
                    }
                    if (requested) {
                        TextButton(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(Uri.fromParts("package", context.packageName, null)),
                            )
                        }) {
                            Text(stringResource(R.string.l10n_app_contacts_contacts_list_opensettings))
                        }
                    }
                }
            }
        }
        Text(stringResource(R.string.l10n_app_contacts_contacts_add_pasteurlfooter))
        OutlinedTextField(
            value = manual,
            onValueChange = { manual = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.l10n_app_contacts_contacts_add_pasteurl)) },
            minLines = 2,
        )
        Button(
            onClick = { holder.handleScanResult(manual) },
            enabled = manual.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.l10n_app_contacts_contacts_add_view))
        }
        TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.l10n_app_contacts_contacts_common_cancel))
        }
    }
}

@Composable
private fun CameraQrPreview(
    onDecoded: (String) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var cameraReady by remember { mutableStateOf(false) }

    DisposableEffect(owner, previewView, executor) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false
        providerFuture.addListener({
            val resolved = providerFuture.get()
            provider = resolved
            if (disposed) {
                resolved.unbindAll()
                return@addListener
            }
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(executor, QrAnalyzer(onDecoded)) }
            runCatching {
                resolved.unbindAll()
                resolved.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                cameraReady = true
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            provider?.unbindAll()
            executor.shutdown()
        }
    }

    Box(modifier) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        if (!cameraReady) CircularProgressIndicator(Modifier.align(Alignment.Center))
    }
}

private class QrAnalyzer(private val onDecoded: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes.firstOrNull() ?: return
            val buffer = plane.buffer
            val crop = image.cropRect
            val data = ByteArray(crop.width() * crop.height())
            var output = 0
            for (row in crop.top until crop.bottom) {
                var input = row * plane.rowStride + crop.left * plane.pixelStride
                for (column in crop.left until crop.right) {
                    data[output++] = buffer.get(input)
                    input += plane.pixelStride
                }
            }
            val source = PlanarYUVLuminanceSource(
                data,
                crop.width(),
                crop.height(),
                0,
                0,
                crop.width(),
                crop.height(),
                false,
            )
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = runCatching { reader.decodeWithState(bitmap) }.getOrNull()
                ?: runCatching { reader.decodeWithState(bitmap.rotateCounterClockwise()) }.getOrNull()
            result?.text?.let(onDecoded)
        } finally {
            reader.reset()
            image.close()
        }
    }
}
