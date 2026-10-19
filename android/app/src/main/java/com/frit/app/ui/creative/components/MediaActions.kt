package com.frit.app.ui.creative.components

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import com.frit.app.features.creative.CreativeSuiteEngine

/** Decode a data: URL or http(s) URL to raw bytes. Null when undecodable. */
fun mediaUrlToBytes(url: String): ByteArray? = try {
    if (url.startsWith("data:")) {
        val b64 = url.substringAfter(",", "")
        if (b64.isBlank()) null else Base64.decode(b64, Base64.DEFAULT)
    } else if (url.startsWith("http")) {
        URL(url).openStream().use { it.readBytes() }
    } else null
} catch (_: Exception) { null }

private fun sniffExt(bytes: ByteArray, kind: String): Pair<String, String> {
    if (kind == "video") return "mp4" to "video/mp4"
    if (bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return "jpg" to "image/jpeg"
    if (bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) return "png" to "image/png"
    if (bytes.size > 12 && bytes[0] == 'R'.code.toByte() && bytes[3] == 'F'.code.toByte()) return "webp" to "image/webp"
    return "png" to "image/png"
}

/**
 * Save image/video bytes to the public gallery (Downloads on Q+, app files
 * below that). Returns a human-readable location. Throws with a message on failure.
 */
suspend fun saveMediaToGallery(ctx: Context, url: String, kind: String, nameHint: String = "frit"): String =
    withContext(Dispatchers.IO) {
        val bytes = mediaUrlToBytes(url) ?: throw Exception("could not read media")
        if (bytes.isEmpty()) throw Exception("empty media")
        val (ext, mime) = sniffExt(bytes, kind)
        val name = "$nameHint-${System.currentTimeMillis()}.$ext"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val coll = if (kind == "video") MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = ctx.contentResolver.insert(coll, values) ?: throw Exception("gallery refused the file")
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw Exception("gallery write failed")
            "Downloads/$name"
        } else {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
            val f = File(dir, name)
            f.writeBytes(bytes)
            f.absolutePath
        }
    }

/** Save an already-downloaded mp4 file to the public gallery. */
suspend fun saveVideoFileToGallery(ctx: Context, file: File, nameHint: String = "frit"): String =
    withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) throw Exception("empty video file")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val name = "$nameHint-${System.currentTimeMillis()}.mp4"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("gallery refused the file")
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: throw Exception("gallery write failed")
            "Downloads/$name"
        } else {
            val dest = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir, "$nameHint-${System.currentTimeMillis()}.mp4")
            file.copyTo(dest, overwrite = true)
            dest.absolutePath
        }
    }

/** Save plain text (e.g. .srt captions) to Downloads. */
suspend fun saveTextToDownloads(ctx: Context, filename: String, text: String): String =
    withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
                ?: throw Exception("gallery refused the file")
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                ?: throw Exception("write failed")
            "Downloads/$filename"
        } else {
            val f = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir, filename)
            f.writeText(text)
            f.absolutePath
        }
    }

/** Share an http(s) URL through the system sheet. Returns false when there is nothing shareable. */
fun shareMediaUrl(ctx: Context, url: String): Boolean {
    if (!url.startsWith("http")) return false
    return try {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        ctx.startActivity(Intent.createChooser(share, "Share media"))
        true
    } catch (_: Exception) { false }
}

@Composable
fun DownloadIconButton(
    url: String?,
    kind: String,
    onStatus: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var busy by remember { mutableStateOf(false) }
    IconButton(
        onClick = {
            val u = url
            if (u.isNullOrBlank()) { onStatus("Nothing to download yet"); return@IconButton }
            scope.launch {
                busy = true
                try {
                    val loc = saveMediaToGallery(ctx, u, kind)
                    onStatus("Downloaded → $loc")
                } catch (e: Exception) {
                    onStatus("Download failed: ${e.message}")
                }
                busy = false
            }
        },
        enabled = !url.isNullOrBlank() && !busy
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp))
        else Icon(Icons.Default.Download, contentDescription = "Download")
    }
}

/**
 * "Continue in…" — push this result into another studio without re-importing.
 * The media is staged on the handoff bus; [onHandoff] navigates.
 */
@Composable
fun HandoffRow(
    media: String?,
    kind: String, // "image" | "video"
    onHandoff: (String) -> Unit,
) {
    if (media.isNullOrBlank()) return
    val chips: List<Pair<String, String>> = if (kind == "video") {
        listOf(
            "Trim & edit" to "video",
            "Katana command" to "katana",
            "Lip-sync dub" to "lipsync",
            "Genjutsu restyle" to "genjutsu",
            "Export" to "export",
        )
    } else {
        listOf(
            "Edit image" to "image",
            "Talking avatar" to "lipsync",
            "Image → video" to "cinema",
            "Design with it" to "design",
            "Export" to "export",
        )
    }
    Text("Continue in…", style = MaterialTheme.typography.labelMedium)
    fun go(target: String) {
        CreativeSuiteEngine.sendHandoff(requireNotNull(media), kind)
        onHandoff(target)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        chips.take(3).forEach { (label, target) ->
            AssistChip(onClick = { go(target) }, label = { Text(label) })
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        chips.drop(3).forEach { (label, target) ->
            AssistChip(onClick = { go(target) }, label = { Text(label) })
        }
        AssistChip(
            onClick = { go("characters") },
            label = { Text(if (kind == "video") "Characters" else "Save as character") }
        )
    }
}
