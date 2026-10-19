package com.frit.app.features.veil

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import java.util.concurrent.Executors

/**
 * Shared front-camera face feed (enroll + veil verify). Shows a live preview
 * and streams the largest detected face + full frame bitmap to [onFace].
 * Set [enabled]=false to stop analysis (preview freezes on last frame).
 */
@Composable
fun VeilCameraPreview(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onFace: (face: Face, bmp: Bitmap, imgW: Int) -> Unit
) {
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose {
            try {
                executor.shutdown()
            } catch (_: Exception) { }
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { c ->
            val pv = PreviewView(c)
            val future = ProcessCameraProvider.getInstance(c)
            future.addListener({
                try {
                    val provider = future.get()
                    val preview = Preview.Builder().build()
                    preview.surfaceProvider = pv.surfaceProvider
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    var busy = false
                    analysis.setAnalyzer(executor) { proxy: ImageProxy ->
                        if (busy || !enabled) {
                            proxy.close()
                            return@setAnalyzer
                        }
                        busy = true
                        try {
                            val media = proxy.image
                            if (media == null) {
                                proxy.close()
                                busy = false
                                return@setAnalyzer
                            }
                            val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                            FaceVerifier.detector.process(input)
                                .addOnSuccessListener { faces ->
                                    try {
                                        val face = faces.maxByOrNull {
                                            it.boundingBox.width() * it.boundingBox.height()
                                        }
                                        if (face != null && enabled) {
                                            imageProxyToBitmap(proxy)?.let { bmp ->
                                                try {
                                                    onFace(face, bmp, proxy.width)
                                                } finally {
                                                    try {
                                                        bmp.recycle()
                                                    } catch (_: Exception) { }
                                                }
                                            }
                                        }
                                    } catch (_: Exception) { }
                                }
                                .addOnCompleteListener {
                                    try {
                                        proxy.close()
                                    } catch (_: Exception) { }
                                    busy = false
                                }
                        } catch (_: Exception) {
                            try {
                                proxy.close()
                            } catch (_: Exception) { }
                            busy = false
                        }
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        c as ComponentActivity,
                        CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis
                    )
                } catch (_: Exception) { }
            }, ContextCompat.getMainExecutor(c))
            pv
        }
    )
}
