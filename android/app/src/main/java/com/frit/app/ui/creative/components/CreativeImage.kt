package com.frit.app.ui.creative.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * Shared, robust image rendering for the creative suite.
 *
 * WHY THIS EXISTS (audit F-13 follow-up, "the editor/manage side is broken and
 * does not preview images"):
 *
 * 1. **Full-resolution decode → OOM → a blank preview.** The old
 *    `GeneratedImage` did `BitmapFactory.decodeByteArray(bytes, 0, bytes.size)`
 *    on a multi-megabyte `data:` URL with no `inSampleSize`. A 2048x2048 PNG
 *    is 16 MB of ARGB_8888; a 12 MP camera photo is ~48 MB. A grid of 6-8
 *    previews is 100-400 MB → the app is OOM-killed and the preview silently
 *    shows nothing. Coil downsamples to the layout size for us, so the
 *    decode is bounded by the target box, not by the source file.
 *
 * 2. **A raw base64 string is not a loadable URL.** The server returns
 *    `image_b64` WITHOUT a `data:` prefix, and Coil cannot load a bare
 *    base64 blob — it needs the prefix. [normalizeMediaUrl] adds it, and also
 *    treats the literal string "null" (what org.json's `optString` gives you
 *    for a JSON null) as ABSENT rather than as a URL to load.
 *
 * 3. **A failed load used to be invisible.** There is now an explicit error
 *    line instead of an empty box, so "it is broken" is distinguishable from
 *    "it is still loading".
 */

/** Media kind → the mime subtype used when a raw base64 blob needs a prefix. */
const val MIME_IMAGE = "image/png"
const val MIME_VIDEO = "video/mp4"

/**
 * Turn whatever the server handed us into something loadable.
 *
 * Accepts: a `data:` URI, a raw base64 blob, an http(s) URL, a file:// or
 * content:// URI. Returns null for the empty string and for the literal
 * "null" that org.json produces for a JSON null.
 *
 * @param raw the value straight out of the JSON
 * @param mime the mime to use when [raw] is a bare base64 blob
 */
fun normalizeMediaUrl(raw: String?, mime: String = MIME_IMAGE): String? {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty()) return null
    // org.json's optString() on a JSON null returns the literal "null".
    if (s.equals("null", ignoreCase = true)) return null
    if (s.startsWith("data:")) return s
    if (s.startsWith("http://") || s.startsWith("https://")) return s
    if (s.startsWith("file://") || s.startsWith("content://")) return s
    // Anything else is a bare base64 payload.
    return "data:$mime;base64,$s"
}

/**
 * Minimum length for a value to be treated as a bare base64 blob. Below this
 * it is far more likely to be a stray identifier than an encoded payload.
 */
private const val MIN_BARE_B64 = 16

/** True when the value looks like a bare (prefix-less) base64 blob. */
fun isBareBase64(raw: String?): Boolean {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty() || s.equals("null", ignoreCase = true)) return false
    if (s.startsWith("data:") || s.startsWith("http") || s.startsWith("file:") || s.startsWith("content:")) return false
    return s.length >= MIN_BARE_B64 && s.all { it.isLetterOrDigit() || it in "+/=_-" }
}

/**
 * Render an image from any of the shapes the server can return.
 *
 * @param source the raw value (data: URI, raw base64, or http URL)
 * @param modifier layout modifier
 * @param contentScale how the image fills its box
 * @param label text shown when the image cannot be loaded
 */
@Composable
fun CreativeImage(
    source: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    label: String = "The image came back but couldn't be shown.",
) {
    val url = remember(source) { normalizeMediaUrl(source) }
    if (url == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    var failed by remember(url) { mutableStateOf(false) }
    if (failed) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                if (url.startsWith("http")) "Preview failed to load — the image host may be blocking the request."
                else "Preview failed to load — the image data is incomplete.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        return
    }
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(url)
            // Bounded decode: Coil samples the source down to the target size
            // instead of materialising the full-resolution bitmap.
            .crossfade(true)
            .build(),
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier.fillMaxSize(),
        onError = { failed = true },
    )
}

/**
 * Render an image plus a small "loading / failed" affordance, for places that
 * want a spinner while the bytes arrive.
 */
@Composable
fun CreativeImageWithState(
    source: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val url = remember(source) { normalizeMediaUrl(source) }
    var failed by remember(url) { mutableStateOf(false) }
    var loading by remember(url) { mutableStateOf(true) }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (url != null && !failed) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                onError = { failed = true },
                onSuccess = { loading = false },
                onLoading = { loading = true },
            )
        }
        if (failed) {
            Text("Preview failed to load.", style = MaterialTheme.typography.bodySmall)
        } else if (loading && url != null) {
            CircularProgressIndicator()
        } else if (url == null) {
            Text("No image.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
