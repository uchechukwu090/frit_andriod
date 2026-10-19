package com.frit.app.features.vision

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Base64
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * ContinuousVisionPipeline — consumes a stream of raw frames (camera or screen)
 * and turns it into a clean, throttled, change-detected JPEG/bitmap feed.
 *
 * OpenCV is used for:
 *   - YUV(NV21) -> downscale (much cheaper than full-res JPEG encode)
 *   - grayscale diff for CHANGE DETECTION (skip frames where nothing moved)
 *     so we don't re-encode / re-upload identical content to the cloud
 *   - compressed JPEG encoding of the latest captured frame
 *
 * Usage: feed every raw frame via [submit]; the pipeline keeps only the latest
 * frame that represents a meaningful change. Callers read [latestBase64] or
 * register [onNewFrame].
 *
 * Thread-safe: [submit] may be called from the CameraX analyzer thread or the
 * MediaProjection ImageReader thread; the latest frame is held atomically.
 */
object ContinuousVisionPipeline {

    private const val TAG = "ContVision"

    /** Optional callback invoked with a NEW (changed) frame's JPEG + base64. */
    var onNewFrame: ((base64: String) -> Unit)? = null

    /** Latest frame's JPEG bytes, base64 encoded (or null until a frame lands). */
    private val latestBase64 = AtomicReference<String?>(null)

    /** Frame-to-frame change threshold (lower = tighter change detection). */
    private const val CHANGE_MEAN_THRESHOLD = 14.0

    /** Max processed width. Browsing the screen at 1/4 and camera at ~HD keeps
     *  encoding/cloud costs low while retaining enough detail for a VLM. */
    private const val MAX_PROCESS_WIDTH = 640

    // Kept for grayscale diff between consecutive frames.
    private var prevGray: Mat? = null

    /** Set to true once OpenCV native lib is loaded (see VisionEngine.init). */
    @Volatile
    var enabled: Boolean = false

    /**
     * Submit one raw frame. source is only used for logging.
     * Converted to OpenCV Mat, resized, change-checked, and (if changed) encoded
     * to JPEG and stored + notified.
     */
    fun submit(bitmap: Bitmap, source: String) {
        if (!enabled) return
        try {
            val src = Mat()
            Utils.bitmapToMat(bitmap, src)

            val resized = resizeKeepingAspect(src)
            src.release()

            val gray = Mat()
            Imgproc.cvtColor(resized, gray, Imgproc.COLOR_RGBA2GRAY)

            val prev = prevGray
            var changed = true
            if (prev != null && prev.size().equals(gray.size())) {
                val diff = Mat()
                Core.absdiff(prev, gray, diff)
                val mean = Core.mean(diff)
                diff.release()
                changed = mean.`val`[0] > CHANGE_MEAN_THRESHOLD
            }
            prevGray?.release()
            prevGray = gray.clone()
            gray.release()

            if (!changed) {
                resized.release()
                return
            }

            val bmp = Bitmap.createBitmap(resized.cols(), resized.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(resized, bmp)
            resized.release()

            val stream = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, stream)
            val b64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)

            latestBase64.set(b64)
            onNewFrame?.invoke(b64)
            Log.d(TAG, "Captured changed $source frame (${bmp.width}x${bmp.height}, ${stream.size() / 1024}KB)")
        } catch (e: Exception) {
            Log.e(TAG, "submit failed ($source)", e)
        }
    }

    private fun resizeKeepingAspect(src: Mat): Mat {
        val w = src.cols()
        val h = src.rows()
        if (w <= MAX_PROCESS_WIDTH) return src.clone()
        val scale = MAX_PROCESS_WIDTH.toDouble() / w
        val nw = MAX_PROCESS_WIDTH
        val nh = (h * scale).toInt()
        val dst = Mat()
        Imgproc.resize(src, dst, org.opencv.core.Size(nw.toDouble(), nh.toDouble()))
        return dst
    }

    /** Encode an NV21 byte array (from CameraX YUV planes) to a Bitmap via OpenCV. */
    fun nv21ToBitmap(nv21: ByteArray, width: Int, height: Int): Bitmap {
        val yuv = org.opencv.core.Mat(height + height / 2, width, CvType.CV_8UC1)
        yuv.put(0, 0, nv21)
        val rgb = Mat()
        // NV21 -> RGBA
        Imgproc.cvtColor(yuv, rgb, Imgproc.COLOR_YUV2RGBA_NV21)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgb, bmp)
        rgb.release()
        yuv.release()
        return bmp
    }

    fun getLatestBase64(): String? = latestBase64.get()

    fun clear() {
        latestBase64.set(null)
        prevGray?.release()
        prevGray = null
    }
}
