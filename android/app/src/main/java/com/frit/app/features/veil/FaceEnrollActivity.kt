package com.frit.app.features.veil

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import com.google.mlkit.vision.face.Face
import java.io.ByteArrayOutputStream
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageProxy
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/**
 * Owner face enrollment for veil pickup verification (Phase 2).
 *
 * Captures 3 reference frames automatically — straight, left, right — each
 * gated on ML Kit quality (size, eyes open, frontal-ish). Stores geometry +
 * small grayscale thumbnails in app-private prefs via FaceVerifier. Fully
 * on-device; nothing leaves the phone.
 */
class FaceEnrollActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VeilManager.init(this)
        setContent { EnrollScreen(onDone = { finish() }) }
    }
}

/** YUV_420_888 ImageProxy -> Bitmap (handles row/pixel stride). */
fun imageProxyToBitmap(proxy: ImageProxy): Bitmap? = try {
    val yPlane = proxy.planes[0]
    val uPlane = proxy.planes[1]
    val vPlane = proxy.planes[2]
    val w = proxy.width
    val h = proxy.height
    val nv21 = ByteArray(w * h + 2 * (w / 2) * (h / 2))
    var pos = 0
    // Y
    val yRowStride = yPlane.rowStride
    val yBuf = yPlane.buffer
    yBuf.rewind()
    for (row in 0 until h) {
        yBuf.position(row * yRowStride)
        yBuf.get(nv21, pos, w)
        pos += w
    }
    // VU interleaved (NV21 order)
    val uBuf = uPlane.buffer
    val vBuf = vPlane.buffer
    val uvRowStride = uPlane.rowStride
    val uvPixelStride = uPlane.pixelStride
    uBuf.rewind()
    vBuf.rewind()
    for (row in 0 until h / 2) {
        for (col in 0 until w / 2) {
            val vuPos = row * uvRowStride + col * uvPixelStride
            nv21[pos++] = vBuf.get(vuPos)
            nv21[pos++] = uBuf.get(vuPos)
        }
    }
    val yuv = YuvImage(nv21, ImageFormat.NV21, w, h, null)
    val out = ByteArrayOutputStream()
    yuv.compressToJpeg(Rect(0, 0, w, h), 85, out)
    BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
} catch (_: Exception) { null }

@Composable
private fun EnrollScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var hasCam by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCam = granted }

    val refs = remember { mutableStateListOf<FaceRef>() }
    var status by remember { mutableStateOf("Position your face in view") }
    var lastCaptureMs by remember { mutableStateOf(0L) }
    // Sign of the first turned capture (+1 / -1). The second turn must go
    // the opposite way. Direction-agnostic on purpose: front cameras mirror
    // yaw on some devices, so fixed "left/right" demands can strand users.
    var firstTurnSign by remember { mutableStateOf(0) }

    // Pose demand per capture. The quality gate (size, eyes) deliberately
    // does NOT check yaw — it lives here, in exactly one place, so the two
    // can never contradict each other into an infinite loop.
    fun currentSlot(): Pair<String, (Float) -> Boolean> = when (refs.size) {
        0 -> "Look straight at the camera" to { y -> kotlin.math.abs(y) < 10f }
        1 -> "Turn your head slightly to one side" to { y ->
            kotlin.math.abs(y) in 12f..35f
        }
        else -> "Now turn slightly to the other side" to { y ->
            kotlin.math.abs(y) in 12f..35f && (if (y > 0f) 1 else -1) != firstTurnSign
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCam) permLauncher.launch(Manifest.permission.CAMERA)
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF0E0C0B)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Enroll face", color = Color(0xFFEBE8E0), fontSize = 22.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(4.dp))
        Text(status, color = Color(0xFFEBE8E0).copy(alpha = 0.7f), fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(3) { i ->
                androidx.compose.foundation.layout.Box(
                    Modifier.size(12.dp).clip(CircleShape)
                        .background(if (i < refs.size) Color(0xFF7BC47F) else Color(0xFFEBE8E0).copy(alpha = 0.25f))
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        if (hasCam) {
            VeilCameraPreview(
                modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)),
                onFace = { face, bmp, imgW ->
                    if (refs.size >= 3) return@VeilCameraPreview
                    val now = System.currentTimeMillis()
                    if (now - lastCaptureMs < 900) return@VeilCameraPreview
                    val (label, want) = currentSlot()
                    val quality = FaceVerifier.enrollQuality(face, imgW, requireEyesOpen = refs.size == 0)
                    if (quality != null) {
                        status = quality
                        return@VeilCameraPreview
                    }
                    val yaw = face.headEulerAngleY
                    if (!want(yaw)) {
                        status = label
                        return@VeilCameraPreview
                    }
                    val geo = FaceVerifier.geoVector(face) ?: return@VeilCameraPreview
                    val thumb = FaceVerifier.cropThumb(bmp, face) ?: return@VeilCameraPreview
                    val b64 = FaceVerifier.thumbToJpegB64(thumb) ?: return@VeilCameraPreview
                    if (refs.size == 1) firstTurnSign = if (yaw > 0f) 1 else -1
                    refs.add(FaceRef(geo, b64, label))
                    lastCaptureMs = now
                    if (refs.size >= 3) {
                        FaceVerifier.saveRefs(ctx, refs.toList())
                        status = "Enrolled — you can close this"
                    } else {
                        val (nextLabel, _) = currentSlot()
                        status = "Got it — now: ${nextLabel.lowercase()}"
                    }
                }
            )
        } else {
            Text(
                "Camera permission is needed to enroll your face.",
                color = Color(0xFFEBE8E0), fontSize = 14.sp, modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onDone, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEBE8E0)),
            shape = RoundedCornerShape(12.dp)
        ) { Text(if (refs.size >= 3) "Done" else "Cancel", color = Color(0xFF0E0C0B), fontWeight = FontWeight.Bold) }
    }
}