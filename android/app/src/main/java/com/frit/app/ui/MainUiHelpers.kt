package com.frit.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.frit.app.R
import com.frit.app.core.Diag
import org.json.JSONObject

/**
 * Small helpers shared by the main screen, split out of MainActivity.kt
 * (audit F-14).
 *
 * TopoBackground is the wallpaper; normalizeAssistantReplyForUi unwraps the
 * assistant_text envelope; downscaleForUpload shrinks images for upload;
 * shareExportAsset shares an exported creative asset.
 */
fun normalizeAssistantReplyForUi(reply: String): String {
    if (reply.contains("assistant_text")) {
        try {
            val json = JSONObject(reply)
            return json.optString("assistant_text", reply)
        } catch (e: Exception) { Diag.warn("MainUi", "normalize_reply", e) }
    }
    return reply
}

/**
 * Format naira from kobo.
 *
 * F-14: this used to exist TWICE — a private copy in the wallet package and
 * a second copy in the creator package — so a currency-format change had to
 * be made twice and the two could silently drift. One shared helper now.
 */
fun naira(kobo: Long): String = "₦${"%,.0f".format(kobo / 100.0)}"

/**
 * Downscale a bitmap for brain upload: full-resolution camera shots and
 * screenshots are several MB as base64 and make vision calls slow or fail.
 * Keeps the aspect ratio, caps the long edge, re-encodes as JPEG.
 */
internal fun downscaleForUpload(
    src: android.graphics.Bitmap,
    quality: Int = 78,
    maxDim: Int = 1568
): ByteArray {
    val w = src.width; val h = src.height
    if (w <= 0 || h <= 0) return ByteArray(0)
    val scale = (maxDim / maxOf(w, h).toFloat()).coerceAtMost(1f)
    val bmp = if (scale < 1f) {
        android.graphics.Bitmap.createScaledBitmap(src, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), true)
    } else src
    val out = java.io.ByteArrayOutputStream()
    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
    if (bmp !== src) bmp.recycle()
    return out.toByteArray()
}

/**
 * Share an exported creative asset. Remote (http) assets go out through the
 * system share sheet; in-app data: URLs can't leave as text, so confirm
 * with a toast instead of firing a multi-MB intent that would fail.
 * Safe to call from any thread.
 */
internal fun shareExportAsset(ctx: android.content.Context, url: String) {
    android.os.Handler(ctx.mainLooper).post {
        try {
            if (url.startsWith("http")) {
                val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, url)
                }
                ctx.startActivity(android.content.Intent.createChooser(share, "Share export"))
            } else if (url.startsWith("data:")) {
                android.widget.Toast.makeText(ctx, "Export ready — use Save for a file copy", android.widget.Toast.LENGTH_LONG).show()
            } else {
                android.widget.Toast.makeText(ctx, "Nothing to share yet", android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            android.widget.Toast.makeText(ctx, "Share failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
fun TopoBackground(isDark: Boolean, modifier: Modifier = Modifier.fillMaxSize()) {
    val bgRes = if (isDark) R.drawable.chat_background_white else R.drawable.chat_background_dark
    Image(
        painter = painterResource(id = bgRes),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .alpha(if (isDark) 0.12f else 0.18f)
    )
}
