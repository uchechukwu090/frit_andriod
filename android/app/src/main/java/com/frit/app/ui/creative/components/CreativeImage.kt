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
import coil.decode.SvgDecoder
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
 * The first bytes of a base64 sample, decoded WITHOUT the Android framework.
 *
 * `android.util.Base64` is stubbed under `unitTests.isReturnDefaultValues`:
 * its decode() returns null there, which silently disabled byte sniffing in
 * every JVM test (the three mime tests failed for exactly this reason).
 * `java.util.Base64` exists on the JVM and on Android since API 26 — this
 * app's minSdk — so the sniff behaves identically in a test and on a device.
 *
 * Tolerates a truncated tail (a sample is a prefix of a longer blob), line
 * breaks, and the URL-safe alphabet, because a sniff must never depend on the
 * blob being well-formed.
 */
internal fun base64Head(sample: String, chars: Int = 64): ByteArray? {
    val clean = buildString {
        for (ch in sample) {
            when {
                ch.isLetterOrDigit() || ch == '+' || ch == '/' -> append(ch)
                ch == '-' -> append('+')
                ch == '_' -> append('/')
                else -> Unit // padding, whitespace, quotes: irrelevant to a sniff
            }
        }
    }
    val take = (minOf(chars, clean.length) / 4) * 4
    if (take < 4) return null
    return try {
        java.util.Base64.getDecoder().decode(clean.take(take))
    } catch (_: Exception) {
        null
    }
}

/**
 * Best-guess mime for a bare base64 blob by sniffing the first bytes.
 * Returns null when the blob is too short or not valid base64.
 */
fun sniffBareBase64Mime(raw: String?): String? {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty() || s.equals("null", ignoreCase = true)) return null
    if (s.startsWith("data:") || s.startsWith("http") || s.startsWith("file:") || s.startsWith("content:")) return null
    if (s.length < 16) return null
    return try {
        val head = base64Head(s) ?: return null
        val txt = String(head, Charsets.ISO_8859_1)
        when {
            head.size > 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> "image/jpeg"
            head.size > 8 && head[0] == 0x89.toByte() && txt.substring(1, 4) == "PNG" -> "image/png"
            txt.startsWith("RIFF") && txt.contains("WEBP") -> "image/webp"
            txt.contains("<svg") || txt.contains("<?xml") -> "image/svg+xml"
            else -> null
        }
    } catch (_: Exception) { null }
}

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
/**
 * What is actually inside a data: URI? Looks at the first bytes, not the
 * declared mime (a vector model hands back SVG while the app wraps it as
 * image/png). Used only to make a failed preview explain itself.
 */
internal fun sniffFormat(url: String): String {
    if (!url.startsWith("data:")) return "remote"
    val b64 = url.substringAfter(",", "")
    if (b64.isEmpty()) return "empty"
    return try {
        val head = base64Head(b64) ?: return "not valid base64"
        val txt = String(head, Charsets.ISO_8859_1)
        when {
            head.size > 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> "JPEG"
            head.size > 8 && head[0] == 0x89.toByte() && txt.substring(1, 4) == "PNG" -> "PNG"
            txt.startsWith("RIFF") && txt.contains("WEBP") -> "WEBP"
            txt.contains("<svg") || txt.contains("<?xml") -> "SVG"
            txt.contains("ftypavif") -> "AVIF"
            else -> "unknown format (starts '" + txt.take(10).filter { it.isLetterOrDigit() } + "')"
        }
    } catch (_: Exception) { "not valid base64" }
}

fun normalizeMediaUrl(raw: String?, mime: String = MIME_IMAGE): String? {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty()) return null
    // org.json's optString() on a JSON null returns the literal "null".
    if (s.equals("null", ignoreCase = true)) return null
    return when {
        s.startsWith("data:") -> rewriteDataUriIfMislabelled(s)
        s.startsWith("http://") || s.startsWith("https://") -> s
        s.startsWith("file://") || s.startsWith("content://") -> s
        else -> {
            // Bare base64 blob.
            val sniffed = sniffBareBase64Mime(s)
            val usedMime = sniffed ?: mime
            "data:$usedMime;base64,$s"
        }
    }
}

/**
 * If a `data:` URI is wrapped in the wrong mime, rewrite the prefix to match
 * the first bytes; otherwise return it unchanged.
 */
fun rewriteDataUriIfMislabelled(url: String): String {
    val comma = url.indexOf(',')
    if (comma < 0) return url
    // `comma` is an INDEX, not a delimiter: taking it as a delimiter matched no
    // substringAfter overload and failed the build. substring(comma + 1) is what
    // was meant — everything after the comma of a data: URI.
    val body = url.substring(comma + 1)
    if (body.isEmpty()) return url
    val real = try {
        // sniffBareBase64Mime ignores a data: prefix and inspects raw base64.
        sniffBareBase64Mime(body)
    } catch (_: Exception) { null }
    if (real == null) return url
    val declared = url.substringBefore(",").substringAfter("data:").substringBefore(";")
    if (!declared.equals(real, ignoreCase = true)) {
        return "data:$real;base64,$body"
    }
    return url
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
                else {
                    val fmt = sniffFormat(url)
                    val kb = url.length / 1024
                    when {
                        fmt == "PNG" && kb < 2 -> "Preview is a tiny/empty PNG — the render may have failed."
                        fmt == "WEBP" -> "Preview is a WebP image. If it still looks blank, the previewer does not support WebP — save or re-run and it may come back as PNG."
                        fmt == "SVG" -> "Preview is an SVG vector. The image box cannot show SVG directly — save the file to view it."
                        else -> "Preview failed to load — got ${fmt}, ${kb} KB."
                    }
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        return
    }
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(url)
            // SVG (vector/logo models) is not a bitmap: without this decoder
            // the preview fails whatever mime the data was wrapped in.
            .decoderFactory(SvgDecoder.Factory())
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
                    .decoderFactory(SvgDecoder.Factory())
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
