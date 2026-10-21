package com.frit.app.ui.creative

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.frit.app.data.NetworkClient
import com.frit.app.ui.creative.components.CreativeTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import com.frit.app.ui.creative.components.normalizeMediaUrl

@Composable
fun InfluencerScreen(onClose: () -> Unit = {}, onHandoff: (String) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val engine = com.frit.app.features.creative.CreativeSuiteEngine
    var status by remember { mutableStateOf("") }
    var niche by remember { mutableStateOf("") }
    var style by remember { mutableStateOf("") }
    var personality by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var posts by remember { mutableStateOf("3") }
    var busy by remember { mutableStateOf(false) }
    var bio by remember { mutableStateOf("") }
    var sheetUrl by remember { mutableStateOf<String?>(null) }
    var ideas by remember { mutableStateOf<List<String>>(emptyList()) }
    var postImages by remember { mutableStateOf<List<Pair<String, String?>>>(emptyList()) }

    Scaffold(
        topBar = {
            CreativeTopBar(
                title = "AI Influencer", onBack = onClose,
                saveEnabled = sheetUrl != null,
                onSave = {
                    val u = sheetUrl
                    if (u != null) scope.launch {
                        status = "Saving…"
                        val res = engine.saveToLibrary(u, "image", "Influencer sheet")
                        status = res.getOrElse { "Save failed: ${it.message}" }.toString()
                    }
                },
                downloadEnabled = sheetUrl != null,
                onDownload = {
                    val u = sheetUrl
                    if (u != null) scope.launch {
                        try {
                            status = "Downloaded → " + com.frit.app.ui.creative.components.saveMediaToGallery(ctx, u, "image", "frit-influencer")
                        } catch (e: Exception) {
                            status = "Download failed: ${e.message}"
                        }
                    }
                },
                exportEnabled = sheetUrl != null,
                onExport = {
                    val u = sheetUrl
                    if (u != null) { engine.sendHandoff(u, "image"); onHandoff("export") }
                },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Persona builder — every attribute keeps the same face.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = niche, onValueChange = { niche = it }, label = { Text("Niche (e.g. fitness, fashion, tech)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = style, onValueChange = { style = it }, label = { Text("Look & style") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = personality, onValueChange = { personality = it }, label = { Text("Personality") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = age, onValueChange = { age = it }, label = { Text("Age") }, modifier = Modifier.weight(1f))
                OutlinedTextField(value = posts, onValueChange = { posts = it.filter(Char::isDigit).take(1) }, label = { Text("Posts") }, modifier = Modifier.weight(1f))
            }
            Button(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        busy = true
                        try {
                            val attrs = JSONObject().put("niche", niche).put("style", style).put("personality", personality).put("age", age)
                            val r = JSONObject(NetworkClient.influencer(attrs, posts.toIntOrNull() ?: 3))
                            val persona = r.optJSONObject("persona")
                            bio = listOf(
                                persona?.optString("name")?.let { "Name: $it (${persona.optString("handle")})" } ?: "",
                                persona?.optString("bio") ?: "",
                                "Tone: ${persona?.optString("voice_tone")}"
                            ).filter { it.isNotBlank() }.joinToString("\n")
                            val cs = r.optJSONObject("character_sheet")
                            sheetUrl = cs?.let { p ->
                                p.strOrNull("image_url")
                                    ?: p.strOrNull("image_b64")?.let { normalizeMediaUrl(it) }
                            }
                            val arr = r.optJSONArray("posts")
                            if (arr != null) {
                                postImages = (0 until arr.length()).map { i ->
                                    val p = arr.getJSONObject(i)
                                    val u = p.strOrNull("image_url")
                                        ?: p.strOrNull("image_b64")?.let { normalizeMediaUrl(it) }
                                    p.optString("caption") to u
                                }
                                ideas = postImages.map { it.first }
                            } else {
                                val legacy = persona?.optJSONArray("post_ideas")
                                ideas = if (legacy != null) (0 until legacy.length()).map { legacy.getJSONObject(it).optString("caption") } else emptyList()
                            }
                            // Remaining ideas the server queued as ready prompts (beyond the rendered batch).
                            val queued = r.optJSONArray("queued_prompts")
                            if (queued != null) {
                                val extra = (0 until queued.length()).mapNotNull { i ->
                                    queued.getJSONObject(i).optString("caption").ifBlank { null }
                                }
                                ideas = ideas + extra
                            }
                            val eid = r.optString("element_id")
                            if (eid.isNotBlank()) status = "Persona saved to Characters ($eid) ✓"
                        } catch (e: Exception) { bio = e.message ?: "Failed" }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(), enabled = niche.isNotBlank() && !busy
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp))
                else { Icon(Icons.Default.Person, null); Spacer(Modifier.width(6.dp)); Text("Build Influencer") }
            }
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
            if (sheetUrl != null) {
                Card(Modifier.fillMaxWidth()) {
                    GeneratedImage(sheetUrl!!, Modifier.fillMaxWidth().height(240.dp))
                    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                        com.frit.app.ui.creative.components.DownloadIconButton(url = sheetUrl, kind = "image", onStatus = { status = it })
                    }
                }
                com.frit.app.ui.creative.components.HandoffRow(media = sheetUrl, kind = "image", onHandoff = onHandoff)
            }
            if (bio.isNotBlank()) Card(Modifier.fillMaxWidth()) { Text(bio, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
            postImages.forEach { (caption, url) ->
                Card(Modifier.fillMaxWidth()) {
                    Column {
                        if (url != null) GeneratedImage(url, Modifier.fillMaxWidth().height(220.dp))
                        Text(caption, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                        if (url != null) {
                            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                                com.frit.app.ui.creative.components.DownloadIconButton(url = url, kind = "image", onStatus = { status = it })
                            }
                        }
                    }
                }
            }
            if (postImages.isEmpty()) ideas.forEach { idea -> Card(Modifier.fillMaxWidth()) { Text(idea, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) } }
        }
    }
}
