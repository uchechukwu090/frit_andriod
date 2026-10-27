package com.frit.app.ui.creative

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import com.frit.app.data.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.frit.app.ui.creative.components.sniffBareBase64Mime
import com.frit.app.ui.creative.components.rewriteDataUriIfMislabelled

/**
 * Shared plan-gate parsing for every Creative/Trading surface.
 * Server returns HTTP 402 -> NetworkClient.errorJson passes through:
 * { ok:false?, code:"feature_locked"|"insufficient_credits", error/message, http:402 }
 */
data class PlanGate(
    val locked: Boolean,
    val outOfCredits: Boolean,
    val message: String,
)

fun parsePlanGate(r: JSONObject): PlanGate? {
    val code = r.optString("code", "")
    val http = r.optInt("http", 0)
    val msg = r.optString("error").ifBlank { r.optString("message") }
    val isLocked = code == "feature_locked" || http == 402 && (
        msg.contains("isn't in your", ignoreCase = true) ||
            msg.contains("upgrade", ignoreCase = true) ||
            msg.contains("plan", ignoreCase = true)
        )
    val isCredits = code == "insufficient_credits" ||
        msg.contains("Not enough credits", ignoreCase = true)
    if (!isLocked && !isCredits) return null
    return PlanGate(locked = isLocked, outOfCredits = isCredits, message = msg.ifBlank {
        if (isCredits) "Out of credits — top up in Wallet to continue."
        else "This feature isn't part of your current plan — subscribe to unlock it."
    })
}

/** Never show a bare "Done" with no output; surface the real server error. */
fun creativeErrorText(r: JSONObject, fallback: String = "Failed"): String {
    val e = r.optString("error").ifBlank { r.optString("message") }
    return e.ifBlank { fallback }
}

/** org.json returns the literal string "null" for JSON null — treat that as missing. */
fun JSONObject.strOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
}

/**
 * Extracts a displayable image source (http URL or data: URI); null if the
 * server sent no pixels.
 *
 * Prefers the server-declared media_type when present, but falls back to a
 * sniff of the bare base64 bytes so a webp/png/svg render is never wrongly
 * wrapped as image/png (which is what made some AI renders show blank).
 */
fun extractImageUrl(r: JSONObject): String? {
    r.strOrNull("image_url")?.takeIf { it.length > 8 }?.let { return it }
    val b64 = r.strOrNull("image_b64")
    if (!b64.isNullOrBlank() && b64.length > 16) {
        val sniffed = sniffBareBase64Mime(b64)
        val mime = r.strOrNull("media_type")?.takeIf { it.isNotBlank() }
            ?: sniffed
            ?: "image/png"
        return if (b64.startsWith("data:")) {
            rewriteDataUriIfMislabelled(b64)
        } else {
            "data:$mime;base64,$b64"
        }
    }
    r.strOrNull("url")?.takeIf { it.length > 8 }?.let { return it }
    return null
}

/** Provider-side video job id (what /videos/status and /videos/content expect). */
fun providerJobId(r: JSONObject): String? {
    val pj = r.optJSONObject("provider_job") ?: return null
    return pj.strOrNull("id") ?: pj.optJSONObject("data")?.strOrNull("id") ?: pj.strOrNull("job_id")
}

/**
 * Polls the provider job until the render finishes, then downloads the mp4 to cache.
 * Returns the file, or null with [onStatus] carrying the reason.
 */
suspend fun awaitVideoFile(ctx: android.content.Context, providerId: String, onStatus: (String) -> Unit): java.io.File? {
    var softErrors = 0
    repeat(72) { attempt -> // ~6 minutes at 5s
        val o = try { JSONObject(NetworkClient.videoStatus(providerId)) } catch (_: Exception) { JSONObject() }
        val http = o.optInt("http", 0)
        val st = (o.optJSONObject("data")?.strOrNull("status") ?: o.strOrNull("status") ?: "").lowercase()
        when {
            st == "completed" || st == "succeeded" || st == "success" -> {
                onStatus("Downloading video…")
                val f = NetworkClient.downloadVideoToCache(ctx, providerId)
                if (f == null) onStatus("Video finished but the download failed — try again in a moment.")
                return f
            }
            st == "failed" || st == "cancelled" || st == "expired" || http in 400..499 -> {
                onStatus(creativeErrorText(o, "Video generation failed.")); return null
            }
            st == "error" -> {
                if (++softErrors >= 4) { onStatus(creativeErrorText(o, "Lost contact with the server while rendering.")); return null }
                onStatus("Rendering… (retrying connection)")
            }
            else -> { softErrors = 0; onStatus("Rendering video… ${((attempt + 1) * 5)}s") }
        }
        delay(5_000)
    }
    onStatus("Still rendering — check again shortly (job $providerId).")
    return null
}

/** Renders http URLs via Coil and data: URLs by decoding the bytes directly (no blank squares). */
/**
 * Image preview for the whole creative suite.
 *
 * F-13 follow-up: this used to decode the `data:` PNG at FULL RESOLUTION
 * (`BitmapFactory.decodeByteArray(bytes, 0, bytes.size)`, no `inSampleSize`)
 * and fall back to Coil only for http URLs. A 2048x2048 image is 16 MB of
 * ARGB_8888 and a 12 MP photo ~48 MB; a grid of 6-8 previews is 100-400 MB,
 * so the app was OOM-killed and the preview silently showed nothing. It also
 * treated the literal string "null" (org.json's optString on a JSON null) as a
 * URL and tried to load it.
 *
 * Now both shapes go through the shared, downsampling loader.
 */
@Composable
fun GeneratedImage(src: String, modifier: Modifier = Modifier) {
    com.frit.app.ui.creative.components.CreativeImage(source = src, modifier = modifier)
}

/** Inline mp4 player with transport controls. */
@Composable
fun GeneratedVideo(file: java.io.File, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { c ->
            android.widget.VideoView(c).apply {
                val mc = android.widget.MediaController(c)
                mc.setAnchorView(this)
                setMediaController(mc)
                setVideoPath(file.absolutePath)
                setOnPreparedListener { it.isLooping = true; start() }
            }
        }
    )
}

@Composable
fun PlanLockedCard(
    message: String,
    outOfCredits: Boolean,
    onSubscribe: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.onSurface)
                Text(
                    if (outOfCredits) "Out of credits" else "Not in your plan",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onSubscribe, modifier = Modifier.fillMaxWidth()) {
                Text(if (outOfCredits) "Top up in Wallet" else "View plans & subscribe")
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Outlined.ArrowForward, null, modifier = Modifier.size(16.dp))
            }
        }
    }
}
