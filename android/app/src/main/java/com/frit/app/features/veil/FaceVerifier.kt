package com.frit.app.features.veil

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.frit.app.core.Diag
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * FaceVerifier — fully on-device owner verification for the veil.
 *
 * No cloud, no downloads beyond what ML Kit ships: identity = enrolled
 * reference captures (face geometry + small grayscale thumbnails) compared
 * against a live probe. Liveness (blink / head-turn) is evaluated by the
 * caller from ML Kit classification/pose — a still photo cannot pass an
 * interactive challenge. This is a convenience guard for the pickup case,
 * NOT bank-grade biometrics (see VeilManager honesty note).
 */
data class FaceRef(val geo: List<Double>, val thumbB64: String, val pose: String)

object FaceVerifier {
    private const val TAG = "FaceVerifier"
    private const val PREFS = "frit_veil_face"
    private const val KEY_REFS = "refs"
    const val THUMB_PX = 64

    /** Pass bars: geometry distance below, thumbnail correlation above. */
    const val GEO_PASS = 0.22
    const val NCC_PASS = 0.45

    val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.15f)
                .enableTracking()
                .build()
        )
    }

    // ---------------- geometry (scale-invariant landmark ratios) ----------------
    /** 8-D vector normalized by inter-eye distance. Null = landmarks unusable. */
    fun geoVector(face: Face): List<Double>? {
        fun pt(t: Int) = face.getLandmark(t)?.position
        val le = pt(FaceLandmark.LEFT_EYE) ?: return null
        val re = pt(FaceLandmark.RIGHT_EYE) ?: return null
        val nose = pt(FaceLandmark.NOSE_BASE) ?: return null
        val ml = pt(FaceLandmark.MOUTH_LEFT) ?: return null
        val mr = pt(FaceLandmark.MOUTH_RIGHT) ?: return null
        val mb = pt(FaceLandmark.MOUTH_BOTTOM) ?: return null
        fun d(ax: Float, ay: Float, bx: Float, by: Float): Double {
            val dx = (ax - bx).toDouble()
            val dy = (ay - by).toDouble()
            return sqrt(dx * dx + dy * dy)
        }
        val eye = d(le.x, le.y, re.x, re.y)
        if (eye < 1.0) return null
        val midX = (le.x + re.x) / 2f
        val midY = (le.y + re.y) / 2f
        val h = face.boundingBox.height().coerceAtLeast(1).toDouble()
        return listOf(
            1.0,
            d(nose.x, nose.y, midX, midY) / eye,
            d(ml.x, ml.y, mr.x, mr.y) / eye,
            d(nose.x, nose.y, mb.x, mb.y) / eye,
            d(le.x, le.y, nose.x, nose.y) / eye,
            d(re.x, re.y, nose.x, nose.y) / eye,
            d(midX, midY, mb.x, mb.y) / eye,
            face.boundingBox.width().toDouble() / h
        )
    }

    fun geoDistance(a: List<Double>, b: List<Double>): Double {
        if (a.size != b.size) return Double.MAX_VALUE
        var s = 0.0
        for (i in a.indices) {
            val dd = a[i] - b[i]
            s += dd * dd
        }
        return sqrt(s)
    }

    // ---------------- thumbnails (64x64 grayscale, prefs-safe) ----------------
    /** Face crop (20% margin) scaled to THUMB_PX grayscale bytes. Null on failure. */
    fun cropThumb(src: Bitmap, face: Face): ByteArray? {
        return try {
            val box = face.boundingBox
            val padX = (box.width() * 0.2f).toInt()
            val padY = (box.height() * 0.2f).toInt()
            val l = (box.left - padX).coerceAtLeast(0)
            val t = (box.top - padY).coerceAtLeast(0)
            val r = (box.right + padX).coerceAtMost(src.width)
            val b = (box.bottom + padY).coerceAtMost(src.height)
            if (r - l < 16 || b - t < 16) return null
            val crop = Bitmap.createBitmap(src, l, t, r - l, b - t)
            val small = Bitmap.createScaledBitmap(crop, THUMB_PX, THUMB_PX, true)
            if (crop !== small) crop.recycle()
            val px = IntArray(THUMB_PX * THUMB_PX)
            small.getPixels(px, 0, THUMB_PX, 0, 0, THUMB_PX, THUMB_PX)
            small.recycle()
            ByteArray(px.size) { i ->
                val c = px[i]
                val lum = (0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)).toInt()
                lum.coerceIn(0, 255).toByte()
            }
        } catch (e: Exception) {
            Log.w(TAG, "cropThumb", e)
            null
        }
    }

    fun thumbToJpegB64(thumb: ByteArray): String? {
        return try {
            val bmp = Bitmap.createBitmap(THUMB_PX, THUMB_PX, Bitmap.Config.ARGB_8888)
            val px = IntArray(thumb.size) { i ->
                val g = thumb[i].toInt() and 0xFF
                (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
            bmp.setPixels(px, 0, THUMB_PX, 0, 0, THUMB_PX, THUMB_PX)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            bmp.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "thumbToJpegB64", e)
            null
        }
    }

    fun thumbFromJpegB64(b64: String, w: Int = THUMB_PX, h: Int = THUMB_PX): ByteArray? {
        return try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val small = Bitmap.createScaledBitmap(bmp, w, h, true)
            if (bmp !== small) bmp.recycle()
            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            small.recycle()
            ByteArray(px.size) { i ->
                val c = px[i]
                val lum = (0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)).toInt()
                lum.coerceIn(0, 255).toByte()
            }
        } catch (e: Exception) {
            Log.w(TAG, "thumbFromJpegB64", e)
            null
        }
    }

    /** Mean-normalized cross-correlation in [-1, 1]. 0 on degenerate input. */
    fun ncc(a: ByteArray, b: ByteArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var ma = 0.0
        var mb = 0.0
        for (i in a.indices) {
            ma += (a[i].toInt() and 0xFF)
            mb += (b[i].toInt() and 0xFF)
        }
        ma /= a.size
        mb /= a.size
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in a.indices) {
            val xa = (a[i].toInt() and 0xFF) - ma
            val xb = (b[i].toInt() and 0xFF) - mb
            num += xa * xb
            da += xa * xa
            db += xb * xb
        }
        if (da <= 0.0 || db <= 0.0) return 0.0
        return (num / sqrt(da * db)).coerceIn(-1.0, 1.0)
    }

    // ---------------- storage ----------------
    fun loadRefs(ctx: Context): List<FaceRef> = try {
        val raw = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REFS, null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val geo = o.optJSONArray("geo") ?: return@mapNotNull null
            val g = (0 until geo.length()).map { k -> geo.optDouble(k, Double.NaN) }
            if (g.any { !it.isFinite() }) return@mapNotNull null
            val thumb = o.optString("thumb", "")
            if (thumb.isBlank()) return@mapNotNull null
            FaceRef(g, thumb, o.optString("pose", ""))
        }
    } catch (e: Exception) {
        Log.w(TAG, "loadRefs", e)
        emptyList()
    }

    fun saveRefs(ctx: Context, refs: List<FaceRef>) {
        try {
            val arr = JSONArray()
            refs.forEach { r ->
                arr.put(JSONObject()
                    .put("geo", JSONArray(r.geo))
                    .put("thumb", r.thumbB64)
                    .put("pose", r.pose))
            }
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_REFS, arr.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "saveRefs", e)
        }
    }

    fun clearRefs(ctx: Context) {
        try {
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_REFS).apply()
        } catch (e: Exception) { Diag.warn("FaceVerifier", "clearRefs", e) }
    }

    fun isEnrolled(ctx: Context): Boolean = loadRefs(ctx).isNotEmpty()

    // ---------------- decision ----------------
    /**
     * Best fused match over all enrolled references. True only when the
     * closest reference passes BOTH the geometry and thumbnail bars.
     */
    fun verify(probeGeo: List<Double>, probeThumb: ByteArray, refs: List<FaceRef>): Boolean {
        var bestGeo = Double.MAX_VALUE
        var bestNcc = -1.0
        for (r in refs) {
            val gd = geoDistance(probeGeo, r.geo)
            val rt = thumbFromJpegB64(r.thumbB64) ?: continue
            val nc = ncc(probeThumb, rt)
            if (gd < bestGeo) bestGeo = gd
            if (nc > bestNcc) bestNcc = nc
        }
        Log.i(TAG, "verify bestGeo=$bestGeo bestNcc=$bestNcc refs=${refs.size}")
        if (refs.isEmpty()) return false
        return bestGeo < GEO_PASS && bestNcc > NCC_PASS
    }

    /**
     * Enrollment quality: big-enough face, eyes visibly open (frontal only —
     * eye classification degrades at yaw, so turned poses skip that check).
     * Head pose is NOT checked here: the caller's slot owns the pose demand,
     * otherwise the two gates contradict each other ("face the camera" vs
     * "turn left") and enrollment loops forever.
     */
    fun enrollQuality(face: Face, imageWidth: Int, requireEyesOpen: Boolean = true): String? {
        if (imageWidth > 0 && face.boundingBox.width() < imageWidth * 0.15f)
            return "Move closer"
        if (requireEyesOpen) {
            val le = face.leftEyeOpenProbability
            val re = face.rightEyeOpenProbability
            if (le == null || re == null) return "Hold still…"
            if (le < 0.5f || re < 0.5f) return "Open both eyes"
        }
        return null
    }
}
