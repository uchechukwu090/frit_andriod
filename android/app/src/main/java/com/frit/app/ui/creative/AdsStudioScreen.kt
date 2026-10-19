package com.frit.app.ui.creative

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.frit.app.data.NetworkClient
import com.frit.app.features.creative.CreativeSuiteEngine
import com.frit.app.ui.creative.components.CreativeTopBar
import com.frit.app.ui.creative.components.DownloadIconButton
import com.frit.app.ui.creative.components.HandoffRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

private data class AdShot(val kind: String, val prompt: String, val duration: Int, var imageUrl: String? = null, var videoPath: String? = null)

@Composable
fun AdsStudioScreen(
    onClose: () -> Unit = {},
    onHandoff: (String) -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = CreativeSuiteEngine
    var brief by remember { mutableStateOf("") }
    var product by remember { mutableStateOf("") }
    var productImage by remember { mutableStateOf<String?>(null) }
    var productUrl by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var planText by remember { mutableStateOf("") }
    var posterUrl by remember { mutableStateOf<String?>(null) }
    var shots by remember { mutableStateOf<List<AdShot>>(emptyList()) }
    var shotsBusy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    val imgPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) { productImage = uriToDataUrl(ctx, uri, "image/*") }
    }

    fun savePoster() {
        val u = posterUrl
        if (u.isNullOrBlank()) { status = "Nothing to save yet"; return }
        scope.launch {
            status = "Saving…"
            val res = engine.saveToLibrary(u, "image", "Ad poster")
            status = res.getOrElse { "Save failed: ${it.message}" }.toString()
        }
    }

    fun downloadPoster() {
        val u = posterUrl
        if (u.isNullOrBlank()) { status = "Nothing to download yet"; return }
        scope.launch {
            try {
                status = "Downloaded → " + com.frit.app.ui.creative.components.saveMediaToGallery(ctx, u, "image", "frit-ad")
            } catch (e: Exception) {
                status = "Download failed: ${e.message}"
            }
        }
    }

    Scaffold(
        topBar = {
            CreativeTopBar(
                title = "Ads Studio", onBack = onClose,
                saveEnabled = posterUrl != null, onSave = { savePoster() },
                downloadEnabled = posterUrl != null, onDownload = { downloadPoster() },
                exportEnabled = posterUrl != null,
                onExport = {
                    val u = posterUrl
                    if (u != null) { engine.sendHandoff(u, "image"); onHandoff("export") }
                },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = product, onValueChange = { product = it }, label = { Text("Product name / URL") }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { imgPicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Image, null); Spacer(Modifier.width(6.dp))
                Text(if (productImage == null) "Import product image" else "Product image attached ✓")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(value = productUrl, onValueChange = { productUrl = it }, label = { Text("Product image URL") }, modifier = Modifier.weight(1f))
                OutlinedButton(
                    onClick = { if (productUrl.startsWith("http")) productImage = productUrl.trim() },
                    enabled = productUrl.startsWith("http")
                ) { Text("Use URL") }
            }
            OutlinedTextField(value = brief, onValueChange = { brief = it }, label = { Text("Campaign brief…") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
            Button(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        busy = true; shots = emptyList()
                        try {
                            val r = JSONObject(NetworkClient.ads(brief, product.ifBlank { null }, productImage))
                            val plan = r.optJSONObject("plan")
                            planText = listOf(
                                plan?.optString("hook")?.let { "Hook: $it" } ?: "",
                                plan?.optString("script_30s")?.let { "Script: $it" } ?: "",
                                plan?.optString("caption")?.let { "Caption: $it" } ?: "",
                                plan?.optString("cta")?.let { "CTA: $it" } ?: ""
                            ).filter { it.isNotBlank() }.joinToString("\n\n").ifBlank { r.toString().take(600) }
                            posterUrl = r.optJSONObject("poster")?.let { p ->
                                p.optString("image_url").ifBlank { null }
                                    ?: p.optString("image_b64").ifBlank { null }?.let { "data:image/png;base64,$it" }
                            }
                            val arr = plan?.optJSONArray("shots")
                            shots = if (arr != null) (0 until arr.length()).map { i ->
                                val s = arr.getJSONObject(i)
                                AdShot(s.optString("kind", "image"), s.optString("prompt"), s.optInt("duration_s", 4))
                            } else emptyList()
                        } catch (e: Exception) { planText = e.message ?: "Failed" }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(), enabled = brief.isNotBlank() && !busy
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp))
                else { Icon(Icons.Default.Campaign, null); Spacer(Modifier.width(6.dp)); Text("Create Ad") }
            }
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
            if (posterUrl != null) {
                Card(Modifier.fillMaxWidth()) {
                    GeneratedImage(posterUrl!!, Modifier.fillMaxWidth().height(240.dp))
                    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                        DownloadIconButton(url = posterUrl, kind = "image", onStatus = { status = it })
                    }
                }
                HandoffRow(media = posterUrl, kind = "image", onHandoff = onHandoff)
            }
            if (planText.isNotBlank()) Card(Modifier.fillMaxWidth()) { Text(planText, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
            if (shots.isNotEmpty()) {
                Text("Ad shots — generate each visual", style = MaterialTheme.typography.titleSmall)
                shots.forEachIndexed { i, shot ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Shot ${i + 1} • ${shot.kind}", style = MaterialTheme.typography.labelMedium)
                            Text(shot.prompt, style = MaterialTheme.typography.bodySmall)
                            if (shot.videoPath != null) {
                                GeneratedVideo(java.io.File(shot.videoPath!!), Modifier.fillMaxWidth().height(200.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton(onClick = {
                                        scope.launch {
                                            try { status = "Downloaded → " + com.frit.app.ui.creative.components.saveVideoFileToGallery(ctx, java.io.File(shot.videoPath!!), "frit-ad-video") }
                                            catch (e: Exception) { status = "Download failed: ${e.message}" }
                                        }
                                    }) { Text("Download video") }
                                }
                            } else if (shot.imageUrl != null) {
                                GeneratedImage(shot.imageUrl!!, Modifier.fillMaxWidth().height(180.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    DownloadIconButton(url = shot.imageUrl, kind = "image", onStatus = { status = it })
                                }
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            shotsBusy = true
                                            try {
                                                if (shot.kind == "video") {
                                                    val r = JSONObject(NetworkClient.generateVideo(shot.prompt, shot.duration.coerceIn(2, 8), "720p", "9:16"))
                                                    val locker = parsePlanGate(r)
                                                    if (locker != null) {
                                                        status = locker.message
                                                    } else if (r.optBoolean("ok", false)) {
                                                        val pid = providerJobId(r)
                                                        if (pid == null) status = "Shot ${i + 1}: server returned no job id"
                                                        else {
                                                            status = "Shot ${i + 1} rendering video…"
                                                            val f = awaitVideoFile(ctx, pid) { status = it }
                                                            if (f != null) shots = shots.mapIndexed { j, s -> if (j == i) s.copy(videoPath = f.absolutePath) else s }
                                                            else status = "Shot ${i + 1}: video not ready — try again"
                                                        }
                                                    } else status = "Shot ${i + 1} failed: ${creativeErrorText(r)}"
                                                } else {
                                                    val r = JSONObject(NetworkClient.generateCreativeImage(shot.prompt, null, "16:9", 1, productImage))
                                                    val url = extractImageUrl(r)
                                                    if (url != null) {
                                                        shots = shots.mapIndexed { j, s -> if (j == i) s.copy(imageUrl = url) else s }
                                                    } else {
                                                        status = "Shot ${i + 1} failed: ${creativeErrorText(r)}"
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                status = "Shot ${i + 1} failed: ${e.message}"
                                            }
                                            shotsBusy = false
                                        }
                                    },
                                    enabled = !shotsBusy && !busy
                                ) { Text(if (shot.kind == "video") "Generate this video shot" else "Generate this shot") }
                            }
                        }
                    }
                }
            }
        }
    }
}
