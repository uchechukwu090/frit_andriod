package com.frit.app.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frit.app.data.Artifact
import com.frit.app.ui.theme.Montserrat
import java.io.File
import java.io.FileOutputStream

/**
 * Renders server-generated artifacts inside a chat bubble:
 *  - images (png/jpg) are shown inline
 *  - everything else (csv, txt, etc.) gets a Download + Share action
 * Both the full app and the overlay chat share this composable.
 */
@Composable
fun ArtifactSection(
    artifacts: List<Artifact>,
    fg: Color,
    surf: Color,
    stroke: Color,
    modifier: Modifier = Modifier
) {
    if (artifacts.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        artifacts.forEach { artifact ->
            ArtifactRow(artifact, fg, surf, stroke)
        }
    }
}

@Composable
fun ArtifactRow(
    artifact: Artifact,
    fg: Color,
    surf: Color,
    stroke: Color,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    SideEffect { rememberAppContext(context) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(surf)
            .border(1.dp, stroke.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "Generated file: ${artifact.name}",
            color = fg,
            fontFamily = Montserrat,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2
        )
        if (artifact.isImage && artifact.base64 != null) {
            val bitmap = remember(artifact.base64) {
                try {
                    val bytes = Base64.decode(artifact.base64, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (e: Exception) { null }
            }
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = artifact.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(6.dp))
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DownloadChip("Download", Icons.Default.Download, fg, surf, stroke) {
                Thread {
                    val location = saveArtifactToDownloads(artifact, context)
                    android.os.Handler(context.mainLooper).post {
                        val msg = if (location.isBlank()) "Save failed — storage not writable"
                        else "Saved ${artifact.name} → $location"
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
            DownloadChip("Open", Icons.Outlined.OpenInNew, fg, surf, stroke) {
                openArtifact(artifact)
            }
        }
    }
}

@Composable
private fun DownloadChip(
    label: String,
    icon: ImageVector = Icons.Default.Download,
    fg: Color,
    surf: Color,
    stroke: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(fg.copy(alpha = 0.12f))
            .border(1.dp, stroke, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(14.dp))
        Text(label, color = fg, fontFamily = Montserrat, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/* ---------- Save / open helpers ---------- */

private var appContextRef: Context? = null

fun rememberAppContext(context: Context) {
    appContextRef = appContextRef ?: context.applicationContext
}

/**
 * Persist an artifact to the OS Downloads folder, returning a human-readable
 * location label ("", on failure). Call from a background thread — base64
 * decode + disk write can block.
 */
fun saveArtifactToDownloads(artifact: Artifact, context: Context? = null): String {
    val ctx = context?.applicationContext ?: appContextRef ?: return ""
    appContextRef = ctx
    return runCatching {
        val bytes = decodeArtifactBytes(artifact) ?: run {
            Log.e("ArtifactSave", "decodeArtifactBytes returned null for ${artifact.name}")
            return ""
        }
        val rawName = sanitizeFileName(artifact.name.ifBlank { "frit_file" })
        val mime = if (artifact.mime.isNotBlank() && artifact.mime != "application/octet-stream") {
            artifact.mime
        } else {
            getMimeTypeFromFileName(rawName)
        }
        val fileName = ensureFileNameExtension(rawName, mime)
        persistBytesToDownloads(ctx, fileName, mime, bytes)
    }.onFailure { Log.e("ArtifactSave", "save failed for ${artifact.name}", it) }.getOrDefault("")
}

private fun ensureFileNameExtension(name: String, mime: String): String {
    val hasExt = name.contains('.') && name.substringAfterLast('.').length in 1..5
    if (hasExt) return name
    val ext = when (mime.lowercase()) {
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/svg+xml" -> "svg"
        "text/csv" -> "csv"
        "text/plain" -> "txt"
        "application/pdf" -> "pdf"
        "application/json" -> "json"
        "text/html", "text/htm" -> "html"
        "audio/mpeg", "audio/mp3" -> "mp3"
        "video/mp4" -> "mp4"
        else -> ""
    }
    return if (ext.isNotBlank()) "$name.$ext" else name
}

fun openArtifact(artifact: Artifact, context: Context? = null) {
    val ctx = context?.applicationContext ?: appContextRef ?: return
    appContextRef = ctx
    runCatching {
        val file = writeArtifactToCache(ctx, artifact) ?: run {
            Toast.makeText(ctx, "File content is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            ctx, "${ctx.packageName}.fileprovider", file
        )
        val mimeType = if (artifact.mime.isNotBlank() && artifact.mime != "application/octet-stream") {
            artifact.mime
        } else {
            getMimeTypeFromFileName(artifact.name)
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }.onFailure { e ->
        Log.e("ArtifactSave", "open failed for ${artifact.name}", e)
        Toast.makeText(ctx, "No app available to open this file", Toast.LENGTH_SHORT).show()
    }
}

private fun decodeArtifactBytes(artifact: Artifact): ByteArray? {
    artifact.base64?.takeIf { it.isNotBlank() }?.let { b64 ->
        return try {
            val clean = b64.substringAfter(',').replace("\\s".toRegex(), "")
            Base64.decode(clean, Base64.DEFAULT)
        } catch (e: Exception) {
            Log.e("ArtifactSave", "Base64 decode failed for ${artifact.name}", e)
            null
        }
    }
    artifact.content?.takeIf { it.isNotBlank() }?.let { text ->
        return text.toByteArray(Charsets.UTF_8)
    }
    return null
}

private fun writeArtifactToCache(ctx: Context, artifact: Artifact): File? {
    val bytes = decodeArtifactBytes(artifact) ?: return null
    val rawName = sanitizeFileName(artifact.name.ifBlank { "frit_file" })
    val mime = if (artifact.mime.isNotBlank()) artifact.mime else getMimeTypeFromFileName(rawName)
    val fileName = ensureFileNameExtension(rawName, mime)
    val dir = File(ctx.cacheDir, "artifacts").apply { mkdirs() }
    val file = File(dir, fileName)
    FileOutputStream(file).use { it.write(bytes) }
    return file
}

@Suppress("DEPRECATION")
private fun persistBytesToDownloads(ctx: Context, fileName: String, mime: String, bytes: ByteArray): String {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val collectionsToTry = mutableListOf(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        )
        if (mime.startsWith("image/")) {
            collectionsToTry.add(0, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        }
        
        for (collection in collectionsToTry) {
            try {
                val resolver = ctx.contentResolver
                var targetName = fileName
                var uri: android.net.Uri? = null
                
                for (attempt in 0..2) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, targetName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    uri = resolver.insert(collection, values)
                    if (uri != null) break
                    
                    val dot = fileName.lastIndexOf('.')
                    targetName = if (dot > 0) {
                        "${fileName.substring(0, dot)}_${System.currentTimeMillis()}${fileName.substring(dot)}"
                    } else {
                        "${fileName}_${System.currentTimeMillis()}"
                    }
                }

                if (uri != null) {
                    val os = resolver.openOutputStream(uri)
                    if (os != null) {
                        os.use { it.write(bytes) }
                        val updateValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                        }
                        resolver.update(uri, updateValues, null, null)
                        Log.i("ArtifactSave", "Saved $targetName to MediaStore ($uri)")
                        return "Downloads"
                    } else {
                        // Some OEM providers return a row but refuse the write
                        // stream — previously this still set success=true and
                        // showed "Saved" with nothing on disk (and often a
                        // ghost file stuck at IS_PENDING=1, invisible to file
                        // managers). Drop the row and try the next location.
                        try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                        Log.w("ArtifactSave", "openOutputStream null for $collection — trying next location")
                    }
                }
            } catch (e: Exception) {
                Log.e("ArtifactSave", "MediaStore insert failed for collection $collection", e)
            }
        }
    }

    // Shared-storage MediaStore failed: try the legacy public Downloads folder.
    try {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        var file = File(dir, fileName)
        if (file.exists()) {
            val dot = fileName.lastIndexOf('.')
            val nameWithTime = if (dot > 0) {
                "${fileName.substring(0, dot)}_${System.currentTimeMillis()}${fileName.substring(dot)}"
            } else {
                "${fileName}_${System.currentTimeMillis()}"
            }
            file = File(dir, nameWithTime)
        }
        FileOutputStream(file).use { it.write(bytes) }
        MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), arrayOf(mime), null)
        Log.i("ArtifactSave", "Saved ${file.name} directly to Public Downloads folder")
        return "Downloads"
    } catch (e: Exception) {
        Log.e("ArtifactSave", "Public downloads write failed, trying app external dir", e)
        // Last resort: app-scoped storage needs no permission — always works.
        try {
            val extDir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
            val file = File(extDir, fileName)
            FileOutputStream(file).use { it.write(bytes) }
            MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), arrayOf(mime), null)
            Log.i("ArtifactSave", "Saved ${file.name} to app external files dir")
            return "FRIT app files/Download (Files app → Android/data/com.frit.app)"
        } catch (e2: Exception) {
            Log.e("ArtifactSave", "All save locations failed", e2)
        }
    }
    return ""
}

private fun getMimeTypeFromFileName(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "csv" -> "text/csv"
        "pdf" -> "application/pdf"
        "txt", "log" -> "text/plain"
        "json" -> "application/json"
        "html", "htm" -> "text/html"
        "mp3" -> "audio/mpeg"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }
}

private fun sanitizeFileName(name: String): String {
    val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    return cleaned.take(120).ifBlank { "frit_file" }
}