package com.frit.app.ui.creative

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.frit.app.data.NetworkClient
import com.frit.app.ui.creative.components.CreativeTopBar
import com.frit.app.ui.creative.components.ExportPlatformSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun ExportScreen(
    assetUrl: String = "",
    onClose: () -> Unit = {},
    onExported: (String) -> Unit = {}
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = com.frit.app.features.creative.CreativeSuiteEngine
    var asset by remember(assetUrl) { mutableStateOf(assetUrl) }
    var selectedPlatform by remember { mutableStateOf<String?>(null) }
    var isExporting by remember { mutableStateOf(false) }
    var exportInfo by remember { mutableStateOf("") }
    var exportError by remember { mutableStateOf("") }
    var exported by remember { mutableStateOf(false) }
    var fileStatus by remember { mutableStateOf("") }
    var resultAsset by remember { mutableStateOf<String?>(null) }

    fun effective(): String = resultAsset ?: asset

    // Media pushed from any studio lands here automatically.
    LaunchedEffect(Unit) {
        if (asset.isBlank()) {
            engine.takeHandoff()?.let { (m, _) ->
                if (m.isNotBlank()) { asset = m; exported = false }
            }
        }
    }

    fun assetKind(): String = if (asset.startsWith("data:video") || asset.endsWith(".mp4")) "video" else "image"

    fun downloadAsset() {
        val a = effective()
        if (a.isBlank()) { fileStatus = "Import an asset first"; return }
        scope.launch {
            try {
                fileStatus = "Downloaded → " + com.frit.app.ui.creative.components.saveMediaToGallery(ctx, a, assetKind(), "frit-export")
            } catch (e: Exception) {
                fileStatus = "Download failed: ${e.message}"
            }
        }
    }

    fun saveAsset() {
        val a = effective()
        if (a.isBlank()) { fileStatus = "Import an asset first"; return }
        scope.launch {
            fileStatus = "Saving…"
            val res = engine.saveToLibrary(a, assetKind(), "Export ${selectedPlatform ?: ""}".trim())
            fileStatus = res.getOrElse { "Save failed: ${it.message}" }.toString()
        }
    }

    val assetPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            val url = uriToDataUrl(ctx, uri, "*/*")
            if (url != null && url.length > 12_000_000) {
                exportError = "File too large for export (over ~9MB)"
            } else if (url != null) {
                asset = url
                resultAsset = null
                exportError = ""
                exported = false
            }
        }
    }

    Scaffold(
        topBar = {
            CreativeTopBar(
                title = "Export",
                onBack = onClose,
                saveEnabled = asset.isNotBlank(), onSave = { saveAsset() },
                downloadEnabled = asset.isNotBlank(), onDownload = { downloadAsset() },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Select Platform", style = MaterialTheme.typography.titleMedium)

            ExportPlatformSelector(
                selectedPlatform = selectedPlatform,
                onPlatformSelected = { selectedPlatform = it }
            )

            OutlinedButton(onClick = { assetPicker.launch("*/*") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Upload, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (asset.isBlank()) "Import asset to export" else "Asset attached ✓ — replace")
            }
            if (fileStatus.isNotBlank()) {
                Text(fileStatus, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { downloadAsset() }, modifier = Modifier.weight(1f), enabled = asset.isNotBlank()) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Download file")
                }
                OutlinedButton(onClick = { saveAsset() }, modifier = Modifier.weight(1f), enabled = asset.isNotBlank()) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Save to library")
                }
            }

            if (selectedPlatform != null) {
                val preset = when (selectedPlatform) {
                    "tiktok" -> "1080x1920, 9:16, MP4, max 60s"
                    "instagram" -> "1080x1080, 1:1, MP4, max 60s"
                    "instagram_story" -> "1080x1920, 9:16, MP4, max 15s"
                    "youtube" -> "1920x1080, 16:9, MP4, max 600s"
                    "youtube_short" -> "1080x1920, 9:16, MP4, max 60s"
                    "twitter" -> "1920x1080, 16:9, MP4, max 140s"
                    "facebook" -> "1920x1080, 16:9, MP4, max 240s"
                    else -> ""
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Export Settings", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Resolution: $preset", style = MaterialTheme.typography.bodySmall)
                        Text("Platform: ${selectedPlatform?.replaceFirstChar { it.uppercase() }}", style = MaterialTheme.typography.bodySmall)
                    }
                }

                Button(
                    onClick = {
                        val platform = selectedPlatform ?: return@Button
                        scope.launch(Dispatchers.IO) {
                            isExporting = true
                            exportError = ""
                            exportInfo = ""
                            try {
                                val r = JSONObject(NetworkClient.exportAsset(asset, platform, null))
                                val locked = parsePlanGate(r)
                                if (locked != null) {
                                    exportError = "${locked.message} — open Wallet to subscribe."
                                } else if (r.optBoolean("ok", false)) {
                                    val preset = r.optJSONObject("preset")
                                    // F-13: video exports now come back re-encoded to the
                                    // platform canvas as video_base64 (not a data URL), so
                                    // build one here for the share/save flow.
                                    val videoB64 = r.optString("video_base64")
                                    val result = when {
                                        videoB64.isNotBlank() -> "data:video/mp4;base64,$videoB64"
                                        else -> r.optString("result")
                                    }
                                    if (result.isNotBlank()) resultAsset = result
                                    exportInfo = buildString {
                                        append("Platform: $selectedPlatform")
                                        if (r.optBoolean("transformed", false)) {
                                            val w = r.optInt("width")
                                            val h = r.optInt("height")
                                            if (w > 0 && h > 0) append("\nResized to ${w}x$h ✓")
                                            else append("\nRe-encoded ✓")
                                        }
                                        if (preset != null) append("\nPreset: ${preset.toString().take(300)}")
                                        val note = r.optString("note")
                                        if (note.isNotBlank()) append("\n$note")
                                    }
                                    exported = true
                                    onExported(result.ifBlank { asset })
                                } else {
                                    exportError = r.optString("error", "Export failed")
                                }
                            } catch (e: Exception) {
                                exportError = e.message ?: "Export failed"
                            }
                            isExporting = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isExporting && asset.isNotBlank()
                ) {
                    if (isExporting) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Export for ${selectedPlatform?.replaceFirstChar { it.uppercase() }}")
                }
                if (asset.isBlank()) {
                    Text("Import an asset above first", style = MaterialTheme.typography.bodySmall)
                }
                if (exportError.isNotBlank()) {
                    Text(exportError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                if (exported && exportInfo.isNotBlank()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Export Complete!", style = MaterialTheme.typography.titleSmall)
                            Text(exportInfo, style = MaterialTheme.typography.bodySmall)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onExported(effective()) }) {
                                    Text("Share")
                                }
                                Button(onClick = { exported = false; exportInfo = "" }) {
                                    Text("Done")
                                }
                            }
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(64.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Select a platform to export", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
