
package com.frit.app.features.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Base64
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.frit.app.core.LocalInferenceEngine
import com.frit.app.data.NetworkClient
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object VisionEngine {
    private var isInitialized = false
    private lateinit var cameraExecutor: ExecutorService
    private var imageAnalyzer: ImageAnalysis? = null
    private var analysisScope: CoroutineScope? = null
    private var openCvReady = false

    /** Continuous analysis debounce: only hit the cloud when the scene has truly
     *  changed AND at most every [ANALYSIS_MIN_INTERVAL_MS]. The OpenCV pipeline
     *  already drops identical frames, so this is the last safety gate. */
    private const val ANALYSIS_MIN_INTERVAL_MS = 4000L
    @Volatile private var lastAnalysisTime = 0L
    @Volatile private var lastAnalyzedB64: String? = null

    fun init(context: Context) {
        if (isInitialized) return
        // OpenCV native lib must be loaded before any Mat/bitmapToMat call.
        openCvReady = try {
            org.opencv.android.OpenCVLoader.initLocal()
        } catch (e: Throwable) {
            android.util.Log.e("VisionEngine", "OpenCV init failed", e)
            false
        }
        if (!openCvReady) {
            android.util.Log.w("VisionEngine", "OpenCV not available — vision frame pipeline disabled")
        }
        cameraExecutor = Executors.newSingleThreadExecutor()
        analysisScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        ContinuousVisionPipeline.enabled = openCvReady
        isInitialized = true
    }

    fun isOpenCvReady(): Boolean = openCvReady

    fun status(): String =
        if (isInitialized) (if (openCvReady) "Vision Active" else "Vision Ready (OpenCV missing)") else "Vision Ready"

    fun startLiveFeed(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        onAnalysisResult: (String) -> Unit
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = surfaceProvider
            }

            imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImageProxy(imageProxy, onAnalysisResult)
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner, cameraSelector, preview, imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e("VisionEngine", "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(context))
    }

    private fun processImageProxy(imageProxy: ImageProxy, onAnalysisResult: (String) -> Unit) {
        try {
            if (!openCvReady) {
                // OpenCV unavailable — fall back to a plain JPEG (no change
                // detection) so camera vision still works at the throttle gate.
                val nv21 = yuv420ToNv21(imageProxy)
                val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
                val jpegOut = ByteArrayOutputStream()
                yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 80, jpegOut)
                val now = System.currentTimeMillis()
                if (now - lastAnalysisTime >= ANALYSIS_MIN_INTERVAL_MS) {
                    lastAnalysisTime = now
                    val b64 = Base64.encodeToString(jpegOut.toByteArray(), Base64.NO_WRAP)
                    analysisScope?.launch { analyzeBase64Image(b64, "camera", onAnalysisResult) }
                }
                return
            }

            // 1. Continuous: convert this frame to NV21 and feed the OpenCV
            //    pipeline (which does change detection + keeps the latest frame).
            val nv21 = yuv420ToNv21(imageProxy)
            val bitmap = ContinuousVisionPipeline.nv21ToBitmap(nv21, imageProxy.width, imageProxy.height)
            ContinuousVisionPipeline.submit(bitmap, "camera")
            bitmap.recycle()

            // 2. Throttled: analyze the latest changed frame only if it differs
            //    from the last one we sent to the cloud.
            val current = ContinuousVisionPipeline.getLatestBase64() ?: return
            val now = System.currentTimeMillis()
            if (now - lastAnalysisTime >= ANALYSIS_MIN_INTERVAL_MS && current != lastAnalyzedB64) {
                lastAnalysisTime = now
                lastAnalyzedB64 = current
                analysisScope?.launch {
                    analyzeBase64Image(current, "camera", onAnalysisResult)
                }
            }
        } catch (e: Exception) {
            Log.e("VisionEngine", "Frame processing failed", e)
        } finally {
            imageProxy.close()
        }
    }

    fun yuv420ToNv21(proxy: ImageProxy): ByteArray {
        val planes = proxy.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val ySize = yBuf.remaining()
        val uvSize = uBuf.remaining() + vBuf.remaining()
        val nv21 = ByteArray(ySize + uvSize)

        yBuf.get(nv21, 0, ySize)

        val uvWidth = proxy.width / 2
        val uvHeight = proxy.height / 2
        val uPixelStride = uPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val vRowStride = vPlane.rowStride

        var idx = ySize
        for (row in 0 until uvHeight) {
            for (col in 0 until uvWidth) {
                val uPos = row * uRowStride + col * uPixelStride
                val vPos = row * vRowStride + col * vPixelStride
                nv21[idx++] = vBuf.get(vPos.coerceIn(0, vBuf.capacity() - 1))
                nv21[idx++] = uBuf.get(uPos.coerceIn(0, uBuf.capacity() - 1))
            }
        }
        return nv21
    }

    suspend fun analyzeBase64Image(base64Image: String, source: String, onAnalysisResult: (String) -> Unit) {
        analysisScope?.launch {
            try {
                // Try local analysis first
                val localResult = LocalInferenceEngine.analyzeVisionInput(base64Image, source)
                if (localResult != "Could not analyze this image locally. Try again with an internet connection.") {
                    withContext(Dispatchers.Main) { onAnalysisResult(localResult) }
                    return@launch
                }
                
                // Fallback to cloud analysis
                val payload = JSONObject().apply {
                    put("image_base64", base64Image)
                    put("source", source)
                    put("prompt", if (source == "camera") "Describe this image in detail." else "Describe the UI elements and any actionable items on this screen.")
                }
                val response = NetworkClient.analyzeScreenFrame(payload)
                val json = JSONObject(response)
                val description = json.optString("description", json.optString("result", "Image analyzed"))
                withContext(Dispatchers.Main) { onAnalysisResult(description) }
            } catch (e: Exception) {
                Log.e("VisionEngine", "Image analysis failed", e)
                withContext(Dispatchers.Main) {
                    onAnalysisResult("Error analyzing image: ${e.message}")
                }
            }
        }
    }

    fun shutdown() {
        analysisScope?.cancel()
        ContinuousVisionPipeline.clear()
        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }
    }
}
