package com.frit.app.ui.creative

import com.frit.app.ui.creative.components.MIME_IMAGE
import com.frit.app.ui.creative.components.normalizeMediaUrl
import com.frit.app.ui.creative.components.sniffBareBase64Mime
import com.frit.app.features.creative.strList
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

data class GfxTemplate(val id: String, val name: String, val aspect: String, val zones: List<String> = emptyList())
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
    // Week 6 — Design Agent: research -> brief -> render -> verify.
    var agentMode by remember { mutableStateOf(false) }
    var briefLines by remember { mutableStateOf("") }
    var verifyLine by remember { mutableStateOf("") }
    // Week 7 — the trail: which references were studied, the style route the
    // agent took, and how far it pushed the brief away from the references.
    var studiedRefs by remember { mutableStateOf<List<String>>(emptyList()) }
    var agentTrail by remember { mutableStateOf("") }
    // The design model decides which aspect ratios can actually be rendered.
    var caps by remember { mutableStateOf(engine.cachedCapabilities() ?: com.frit.app.features.creative.CreativeCapabilities.FALLBACK) }

    LaunchedEffect(Unit) {
        caps = engine.capabilities()
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
                GfxTemplate(
                    o.optString("id"),
                    o.optString("name"),
                    o.optString("aspect_ratio"),
                    strList(o.optJSONArray("zones"))
                )
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
            // Fonts are NOT a fixed list: type any font name (e.g. "Space Mono",
            // "Playfair Display", "Bebas Neue"). The chips are only shortcuts.
            Text("Font — type any font name, or tap a shortcut", style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(
                value = selectedFont, onValueChange = { selectedFont = it },
                label = { Text("Font name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Sans Serif", "Serif", "Script", "Display", "Mono",
                    "Space Mono", "Playfair Display", "Bebas Neue", "Syne", "DM Serif Display", "Archivo Black").forEach { f ->
                    FilterChip(selected = selectedFont.equals(f, ignoreCase = true), onClick = { selectedFont = f }, label = { Text(f) })
                }
            }
            // Colours are NOT a fixed list either: type any hex codes (comma
            // separated, first = main colour). Swatches add/remove a colour.
            Text("Colours — type hex codes, or tap swatches (first = main)", style = MaterialTheme.typography.labelMedium)
            val pickedColors = selectedColor.split(",", " ").map { it.trim() }.filter { it.isNotEmpty() }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("#362F2F", "#EBE8E0", "#4A7C59", "#9C3A2F", "#1A1A2E", "#D4A574").forEach { c ->
                    val on = pickedColors.any { it.equals(c, ignoreCase = true) }
                    Surface(
                        modifier = Modifier.size(if (on) 38.dp else 32.dp),
                        color = androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(c)),
                        shape = MaterialTheme.shapes.small,
                        border = if (on) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                        onClick = {
                            val next = if (on) pickedColors.filter { !it.equals(c, ignoreCase = true) } else (pickedColors + c).takeLast(5)
                            selectedColor = next.joinToString(", ")
                        }
                    ) {}
                }
            }
            OutlinedTextField(
                value = selectedColor, onValueChange = { selectedColor = it },
                label = { Text("Hex colours, e.g. #0E3B2E, #F4EFE4, #D4A574") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { moodPicker.launch("image/*") }, enabled = caps.design.reference, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Image, null); Spacer(Modifier.width(6.dp)); Text("Mood-board (${styleRefs.size}/4)")
                }
                OutlinedTextField(value = variations, onValueChange = { variations = it.filter(Char::isDigit).take(1) }, label = { Text("Routes") }, modifier = Modifier.weight(1f), placeholder = { Text("1-3") })
            }
            if (!caps.design.reference) {
                Text(
                    "${caps.design.label.ifBlank { caps.design.model }} can't take image inputs, so Mood-board and Logo are switched off.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (styleRefs.isNotEmpty()) {
                Text(
                    "Mood-board: ${styleRefs.size}/4 images. The agent studies these for layout, colour and typography ideas — it does NOT copy them. Pinterest/Behance images can still be skipped if their host blocks server access.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { styleRefs = emptyList() }) { Text("Clear mood-board") }
            }
            Text("Template", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                templates.forEach { t ->
                    FilterChip(selected = template == t.id, onClick = { template = if (template == t.id) null else t.id }, label = { Text("${t.name} • ${t.aspect}") })
                }
            }
            // Be explicit when the chosen template's ratio is not one the active
            // model renders: say what it WILL render instead of letting the
            // server snap it invisibly.
            val selTpl = templates.find { it.id == template }
            val renderedAspect = caps.design.pickAspect(selTpl?.aspect ?: "1:1") ?: "1:1"
            if (selTpl != null && !caps.design.supportsAspect(selTpl.aspect)) {
                Text(
                    "${caps.design.label.ifBlank { caps.design.model }} renders $renderedAspect — " +
                        "this template's ${selTpl.aspect} will come out at $renderedAspect.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (caps.design.summary.isNotBlank()) {
                Text(
                    "Model: ${caps.design.label.ifBlank { caps.design.model }} — ${caps.design.summary}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                OutlinedButton(onClick = { logoPicker.launch("image/*") }, enabled = caps.design.reference, modifier = Modifier.weight(1f)) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = agentMode, onCheckedChange = { agentMode = it })
                Text(
                    "Agent mode — research references, write a detailed brief, verify the render",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (agentMode) Text(
                "Slower (10–90s). Charges one image credit per delivered design, never on failure.",
                style = MaterialTheme.typography.labelSmall
            )
            if (briefLines.isNotBlank()) Text(briefLines, style = MaterialTheme.typography.labelSmall)
            if (agentTrail.isNotBlank()) Text(agentTrail, style = MaterialTheme.typography.labelSmall)
            if (studiedRefs.isNotEmpty()) {
                Text("References studied", style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    studiedRefs.forEach { u ->
                        coil.compose.AsyncImage(
                            model = u,
                            contentDescription = "inspiration reference",
                            modifier = Modifier.size(56.dp),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    }
                }
            }
            if (verifyLine.isNotBlank()) Text(verifyLine, style = MaterialTheme.typography.labelSmall)
            Button(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        busy = true; results = emptyList(); briefLines = ""; verifyLine = ""
                        studiedRefs = emptyList(); agentTrail = ""
                        if (agentMode) {
                            try {
                                val goalText = (if (useCustomBrief) customBrief else headline).ifBlank { "graphic design" }
                                val selTpl = templates.find { it.id == template }
                                val selAspect = caps.design.pickAspect(selTpl?.aspect ?: "1:1") ?: "1:1"
                                // When using the free brief, send it as the agent's headline
                                // so the brief writer can MUST-USE it rather than guessing from
                                // the goal text alone. When using helper fields, send headline
                                // normally.
                                val agentHeadline = if (useCustomBrief) customBrief else headline.ifBlank { null }
                                // The user's own images go to the agent as references too —
                                // but only when the model takes image input at all.
                                val agentRefs = JSONArray()
                                if (caps.design.reference) styleRefs.forEach { agentRefs.put(it) }
                                val r = JSONObject(NetworkClient.designAgent(
                                    goal = goalText,
                                    headline = agentHeadline,
                                    body = if (useCustomBrief) null else body.ifBlank { null },
                                    cta = if (useCustomBrief) null else cta.ifBlank { null },
                                    palette = if (palette.isNotBlank()) palette else selectedColor,
                                    brandkitId = kitId,
                                    aspectRatio = selAspect,
                                    font = selectedFont,
                                    inspiration = true,
                                    verify = true,
                                    template = selTpl?.id,
                                    styleRefs = agentRefs.takeIf { it.length() > 0 },
                                    logoUrl = if (caps.design.reference) logo else null,
                                    // The free-brief text is a DESCRIPTION of the piece,
                                    // not the copy to typeset — say so, or the agent sets
                                    // the whole paragraph as the headline.
                                    freeBrief = useCustomBrief
                                ))
                                val bref = r.optJSONObject("brief")
                                val lay = bref?.optJSONObject("layout")
                                val pal = bref?.optJSONArray("palette")
                                val typo = bref?.optJSONArray("typography")
                                val refsRead = r.optJSONObject("inspiration")?.optInt("read") ?: 0
                                val refsStudied = r.optJSONObject("inspiration")?.optJSONArray("studied")
                                val refsDropped = r.optJSONObject("inspiration")?.optJSONArray("dropped")
                                val studiedDetail = buildString {
                                    append("References read: $refsRead")
                                    appendLine()
                                    if (refsStudied != null && refsStudied.length() > 0) {
                                        append("Studied refs: ")
                                        appendLine((0 until refsStudied.length()).joinToString(", ") {
                                            val o = refsStudied.getJSONObject(it)
                                            o.optString("ref", o.optString("image_url", "—"))
                                        })
                                    }
                                    if (refsDropped != null && refsDropped.length() > 0) {
                                        append("Dropped: ")
                                        appendLine((0 until refsDropped.length()).joinToString(", ") {
                                            val o = refsDropped.getJSONObject(it)
                                            o.optString("ref", o.optString("image_url", "—"))
                                        })
                                    }
                                }.trim()
                                briefLines = buildString {
                                    if (selTpl != null) {
                                        append("Template: ${selTpl.name}")
                                        if (selTpl.zones.isNotEmpty()) {
                                            appendLine()
                                            append("Template zones: ${selTpl.zones.joinToString(" • ")}")
                                        } else {
                                            appendLine()
                                            append("Template zones: none declared")
                                        }
                                    }
                                    if (lay != null) {
                                        appendLine()
                                        append("Layout: ${lay.optString("grid")} • ${lay.optString("alignment")} • focal ${lay.optString("focal")}")
                                    }
                                    if (pal != null) {
                                        appendLine()
                                        append("Palette: ")
                                        for (i in 0 until pal.length()) {
                                            val p = pal.optJSONObject(i) ?: continue
                                            append("${p.optString("hex")} ${p.optInt("share_pct")}%  ")
                                        }
                                    }
                                    if (typo != null) {
                                        appendLine()
                                        for (i in 0 until typo.length()) {
                                            val t = typo.optJSONObject(i) ?: continue
                                            append("${t.optString("role")}: ${t.optString("font")} ${t.optInt("size_px")}px")
                                            appendLine()
                                        }
                                    }
                                    appendLine()
                                    append(studiedDetail)
                                }.trim()
                                // Week 7 trail: the style route, the divergence
                                // from the references and the reference images.
                                val insp = r.optJSONObject("inspiration")
                                val studied = insp?.optJSONArray("studied")
                                studiedRefs = (0 until (studied?.length() ?: 0)).mapNotNull { i ->
                                    studied?.optJSONObject(i)?.strOrNull("image_url")
                                }
                                val div = r.optJSONObject("divergence")?.optJSONArray("changed")
                                val divText = (0 until (div?.length() ?: 0)).joinToString(", ") { div?.optString(it).orEmpty() }
                                agentTrail = buildString {
                                    append("Style route: ${r.optString("style_token").ifBlank { "—" }}")
                                    append("  •  Seed ${r.optInt("seed")}")
                                    if (r.optJSONObject("render")?.optBoolean("type_layer") == true) {
                                        append("\nType layer: real typography set from the brief")
                                    }
                                    if (divText.isNotBlank()) append("\nPushed away from the references: $divText")
                                }.trim()
                                val rr = r.optJSONObject("render")
                                val src = rr?.strOrNull("image_url") ?: rr?.strOrNull("image_b64")
                                // The server now returns the real media_type and the client
                                // sniffs bare base64 bytes, so webp/png/svg renders no longer
                                // get wrapped as image/png and show blank.
                                val mime = rr?.optString("media_type")?.takeIf { it.isNotBlank() }
                                    ?: sniffBareBase64Mime(src)
                                    ?: MIME_IMAGE
                                val url: String? = normalizeMediaUrl(src, mime)
                                val v = r.optJSONObject("verification")
                                val issues = v?.optJSONArray("issues")
                                verifyLine = when {
                                    v == null || !v.optBoolean("checked", false) -> "Review: not run — this render is unverified"
                                    v.optBoolean("pass") -> "Review: PASS ${(v.optDouble("score", 0.0) * 100).toInt()}%"
                                    else -> "Review: ${(v.optDouble("score", 0.0) * 100).toInt()}% — " +
                                        (0 until (issues?.length() ?: 0)).joinToString("; ") { issues?.optString(it).orEmpty() }
                                }
                                results = listOf(url to (rr?.optString("model_used").orEmpty().ifBlank { "design agent" }))
                                status = if (url != null) "Agent finished — ${r.optInt("attempts", 1)} render attempt(s)"
                                else "Agent could not render: " + r.optString("error")
                            } catch (e: Exception) { status = e.message ?: "Agent failed" }
                            busy = false
                            return@launch
                        }
                        try {
                            val refs = JSONArray(); if (caps.design.reference) styleRefs.forEach { refs.put(it) }
                            val effHeadline = if (useCustomBrief) customBrief else headline
                            val selTemplate = templates.find { it.id == template }
                            val effAspect = caps.design.pickAspect(selTemplate?.aspect ?: "1:1") ?: "1:1"
                            val effPalette = if (palette.isNotBlank()) palette else selectedColor
                            val r = JSONObject(NetworkClient.design(template, effHeadline, if (useCustomBrief) null else body.ifBlank { null }, if (useCustomBrief) null else cta.ifBlank { null }, effPalette, kitId, if (caps.design.reference) logo else null, craft, refs, variations.toIntOrNull() ?: 2, effAspect, selectedFont))
                            craftUsed = r.optString("craft")
                            val arr = r.optJSONArray("designs") ?: JSONArray()
                            results = (0 until arr.length()).map { i ->
                                val d = arr.getJSONObject(i)
                                val url = d.strOrNull("image_url")
                                    ?: d.strOrNull("image_b64")?.let { normalizeMediaUrl(it) }
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
                else { Icon(Icons.Default.Brush, null); Spacer(Modifier.width(6.dp)); Text(if (agentMode) "Run Design Agent" else "Design It") }
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
