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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.frit.app.core.Diag
import com.frit.app.data.NetworkClient
import com.frit.app.ui.creative.components.CreativeTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class GfxTemplate(val id: String, val name: String, val aspect: String)
data class BrandKitRef(val id: String, val name: String)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DesignStudioScreen(onClose: () -> Unit = {}, onHandoff: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = com.frit.app.features.creative.CreativeSuiteEngine
    var customBrief by remember { mutableStateOf("") }
    var useCustomBrief by remember { mutableStateOf(false) }
    var selectedFont by remember { mutableStateOf("Sans Serif") }
    var selectedColor by remember { mutableStateOf("#362F2F") }
    var undoStack = remember { mutableStateListOf<List<Pair<String?, String?>>>() }
    var redoStack = remember { mutableStateListOf<List<Pair<String?, String?>>>() }
    var templates by remember { mutableStateOf<List<GfxTemplate>>(emptyList()) }
    var template by remember { mutableStateOf<String?>(null) }
    var headline by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var cta by remember { mutableStateOf("") }
    var palette by remember { mutableStateOf("") }
    var kits by remember { mutableStateOf<List<BrandKitRef>>(emptyList()) }
    var kitId by remember { mutableStateOf<String?>(null) }
    var kitOpen by remember { mutableStateOf(false) }
    var logo by remember { mutableStateOf<String?>(null) }
    var styleRefs by remember { mutableStateOf<List<String>>(emptyList()) }
    var craft by remember { mutableStateOf("auto") }
    var variations by remember { mutableStateOf("2") }
    var busy by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Pair<String?, String?>>>(emptyList()) }
    var craftUsed by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        engine.takeHandoff()?.let { (m, k) ->
            if (k == "image" && m.isNotBlank() && logo == null) {
                logo = m
                status = "Received image from another studio — using it as the logo reference"
            }
        }
        // Brand Kit → Design: the kit id is enough. F-13: the server resolves
        // the kit and applies the lock to the prompt, so the client no longer
        // splices a brand fragment into the brief (which only worked here).
        val legacyBrand = com.frit.app.features.creative.CreativeSuiteEngine.pendingBrandPrompt
        val kitForBrand = com.frit.app.features.creative.CreativeSuiteEngine.pendingBrandKitId
        if (!kitForBrand.isNullOrBlank()) {
            kitId = kitForBrand
            com.frit.app.features.creative.CreativeSuiteEngine.pendingBrandKitId = ""
            status = "Brand lock applied from Brand Kit"
        }
        if (legacyBrand.isNotBlank()) {
            // Legacy client-built fragment: keep it working, but only when no
            // kit id was supplied (older server / older handoff).
            if (kitForBrand.isNullOrBlank()) {
                customBrief = if (customBrief.isBlank()) legacyBrand else "$legacyBrand\n$customBrief"
                useCustomBrief = true
            }
            com.frit.app.features.creative.CreativeSuiteEngine.pendingBrandPrompt = ""
        }
        try {
            val t = JSONObject(NetworkClient.getDesignTemplates())
            val arr = t.optJSONArray("templates") ?: JSONArray()
            templates = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                GfxTemplate(o.optString("id"), o.optString("name"), o.optString("aspect_ratio"))
            }
            val k = JSONObject(NetworkClient.getBrandKits())
            val karr = k.optJSONArray("brandkits") ?: JSONArray()
            kits = (0 until karr.length()).map { i ->
                val o = karr.getJSONObject(i)
                BrandKitRef(o.optString("id"), o.optString("name"))
            }
        } catch (e: Exception) { Diag.warn("Creative", "design_bootstrap", e) }
    }
    val logoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) { logo = uriToDataUrl(ctx, uri, "image/*") }
    }
    val moodPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        scope.launch(Dispatchers.IO) {
            val added = uris.take(4 - styleRefs.size).mapNotNull { uriToDataUrl(ctx, it, "image/*") }
            styleRefs = (styleRefs + added).take(4)
        }
    }

    fun doUndo() {
        if (undoStack.isNotEmpty()) {
            redoStack.add(results)
            results = undoStack.removeAt(undoStack.lastIndex)
            status = "Undone"
        }
    }
    fun doRedo() {
        if (redoStack.isNotEmpty()) {
            undoStack.add(results)
            results = redoStack.removeAt(redoStack.lastIndex)
            status = "Redone"
        }
    }
    val firstResult = results.firstOrNull { it.first != null }?.first
    fun saveFirst() {
        if (firstResult.isNullOrBlank()) { status = "Nothing to save yet"; return }
        scope.launch {
            status = "Saving…"
            val res = engine.saveToLibrary(firstResult, "image", headline.take(60).ifBlank { "Design" })
            status = res.getOrElse { "Save failed: ${it.message}" }.toString()
        }
    }
    fun downloadFirst() {
        if (firstResult.isNullOrBlank()) { status = "Nothing to download yet"; return }
        scope.launch {
            try {
                status = "Downloaded → " + com.frit.app.ui.creative.components.saveMediaToGallery(ctx, firstResult, "image", "frit-design")
            } catch (e: Exception) {
                status = "Download failed: ${e.message}"
            }
        }
    }
    Scaffold(
        topBar = {
            CreativeTopBar(
                title = "Design Studio", onBack = onClose,
                canUndo = undoStack.isNotEmpty(), onUndo = { doUndo() },
                canRedo = redoStack.isNotEmpty(), onRedo = { doRedo() },
                saveEnabled = firstResult != null, onSave = { saveFirst() },
                downloadEnabled = firstResult != null, onDownload = { downloadFirst() },
                exportEnabled = firstResult != null,
                onExport = {
                    if (firstResult != null) { engine.sendHandoff(firstResult!!, "image"); onHandoff("export") }
                },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Graphics with real text — flyers, posters, thumbnails.", style = MaterialTheme.typography.bodySmall)
            Text("Craft — each job routes to the model built for it", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("auto" to "Auto", "type" to "Type (text)", "vector" to "Vector (logos)", "photo" to "Photo plate").forEach { (id, label) ->
                    FilterChip(selected = craft == id, onClick = { craft = id }, label = { Text(label) })
                }
            }
            Text("Font", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Sans Serif", "Serif", "Script", "Display", "Mono").forEach { f ->
                    FilterChip(selected = selectedFont == f, onClick = { selectedFont = f }, label = { Text(f) })
                }
            }
            Text("Brand color", style = MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("#362F2F", "#EBE8E0", "#4A7C59", "#9C3A2F", "#1A1A2E", "#D4A574").forEach { c ->
                    Surface(
                        modifier = Modifier.size(32.dp),
                        color = androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(c)),
                        shape = MaterialTheme.shapes.small,
                        onClick = { selectedColor = c }
                    ) {}
                }
                OutlinedTextField(
                    value = selectedColor, onValueChange = { selectedColor = it },
                    label = { Text("Hex") }, modifier = Modifier.width(120.dp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { moodPicker.launch("image/*") }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Image, null); Spacer(Modifier.width(6.dp)); Text("Mood-board (${styleRefs.size}/4)")
                }
                OutlinedTextField(value = variations, onValueChange = { variations = it.filter(Char::isDigit).take(1) }, label = { Text("Routes") }, modifier = Modifier.weight(1f), placeholder = { Text("1-3") })
            }
            if (styleRefs.isNotEmpty()) TextButton(onClick = { styleRefs = emptyList() }) { Text("Clear mood-board") }
            Text("Template", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                templates.forEach { t ->
                    FilterChip(selected = template == t.id, onClick = { template = if (template == t.id) null else t.id }, label = { Text("${t.name} • ${t.aspect}") })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = useCustomBrief, onCheckedChange = { useCustomBrief = it })
                Text("Free brief — skip the helper fields", style = MaterialTheme.typography.bodySmall)
            }
            if (useCustomBrief) {
                OutlinedTextField(
                    value = customBrief, onValueChange = { customBrief = it },
                    label = { Text("Describe ANY graphic in your own words…") },
                    modifier = Modifier.fillMaxWidth(), minLines = 3
                )
            }
            OutlinedTextField(value = headline, onValueChange = { headline = it }, label = { Text("Headline (spelled exactly)") }, modifier = Modifier.fillMaxWidth(), enabled = !useCustomBrief)
            OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text("Body copy (optional)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = cta, onValueChange = { cta = it }, label = { Text("CTA") }, modifier = Modifier.weight(1f))
                OutlinedTextField(value = palette, onValueChange = { palette = it }, label = { Text("Grade") }, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { logoPicker.launch("image/*") }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Image, null); Spacer(Modifier.width(6.dp))
                    Text(if (logo == null) "Logo (optional)" else "Logo ✓")
                }
                Box(Modifier.weight(1f)) {
                    ExposedDropdownMenuBox(expanded = kitOpen, onExpandedChange = { kitOpen = !kitOpen }) {
                        OutlinedTextField(
                            value = kits.find { it.id == kitId }?.name ?: "Brand kit",
                            onValueChange = {}, readOnly = true, label = { Text("Brand") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = kitOpen) },
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(expanded = kitOpen, onDismissRequest = { kitOpen = false }) {
                            DropdownMenuItem(text = { Text("None") }, onClick = { kitId = null; kitOpen = false })
                            kits.forEach { k ->
                                DropdownMenuItem(text = { Text(k.name) }, onClick = { kitId = k.id; kitOpen = false })
                            }
                        }
                    }
                }
            }
            Button(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        busy = true; results = emptyList()
                        try {
                            val refs = JSONArray(); styleRefs.forEach { refs.put(it) }
                            val effHeadline = if (useCustomBrief) customBrief else headline
                            val selTemplate = templates.find { it.id == template }
                            val effAspect = selTemplate?.aspect ?: "1:1"
                            val effPalette = if (palette.isNotBlank()) palette else selectedColor
                            val r = JSONObject(NetworkClient.design(template, effHeadline, if (useCustomBrief) null else body.ifBlank { null }, if (useCustomBrief) null else cta.ifBlank { null }, effPalette, kitId, logo, craft, refs, variations.toIntOrNull() ?: 2, effAspect, selectedFont))
                            craftUsed = r.optString("craft")
                            val arr = r.optJSONArray("designs") ?: JSONArray()
                            results = (0 until arr.length()).map { i ->
                                val d = arr.getJSONObject(i)
                                val url = d.optString("image_url").ifBlank { null }
                                    ?: d.optString("image_b64").ifBlank { null }?.let { "data:image/png;base64,$it" }
                                url to (if (d.optBoolean("ok")) d.optString("model_used") else "failed: " + d.optString("error"))
                            }
                            val okCount = results.count { it.first != null }
                            status = when {
                                okCount == 0 && results.isNotEmpty() -> "All routes failed — check your plan or try a different craft."
                                okCount < results.size -> "$okCount/${results.size} routes ready — some failed."
                                else -> "$okCount route(s) ready — pick, don't settle."
                            }
                        } catch (e: Exception) { status = e.message ?: "Failed" }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(), enabled = (if (useCustomBrief) customBrief else headline).isNotBlank() && !busy
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp))
                else { Icon(Icons.Default.Brush, null); Spacer(Modifier.width(6.dp)); Text("Design It") }
            }
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
            if (craftUsed.isNotBlank()) Text("Craft: $craftUsed", style = MaterialTheme.typography.labelSmall)
            results.forEach { (url, note) ->
                Card(Modifier.fillMaxWidth()) {
                    Column {
                        if (url != null) GeneratedImage(src = url, modifier = Modifier.fillMaxWidth().height(300.dp))
                        Text(note ?: "", modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall)
                        if (url != null) {
                            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                                com.frit.app.ui.creative.components.DownloadIconButton(url = url, kind = "image", onStatus = { status = it })
                            }
                        }
                    }
                }
            }
            if (firstResult != null) {
                com.frit.app.ui.creative.components.HandoffRow(media = firstResult, kind = "image", onHandoff = onHandoff)
            }
        }
    }
}
