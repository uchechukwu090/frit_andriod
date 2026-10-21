package com.frit.app.ui.creative

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import com.frit.app.features.creative.CreativeTemplate
import com.frit.app.ui.creative.components.CreativeTopBar
import com.frit.app.ui.creative.components.TemplatePreviewCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import com.frit.app.ui.creative.components.normalizeMediaUrl

@Composable
fun TemplateGalleryScreen(
    onClose: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var templates by remember { mutableStateOf<List<CreativeTemplate>>(emptyList()) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var selectedPlatform by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    // Apply flow: pick a template, optionally attach an image, resolve the
    // layout zones against the real backend instead of dropping the tap.
    var applyTarget by remember { mutableStateOf<CreativeTemplate?>(null) }
    var applyAsset by remember { mutableStateOf<String?>(null) }
    var applyBusy by remember { mutableStateOf(false) }
    var applyResult by remember { mutableStateOf("") }
    var applyRender by remember { mutableStateOf<String?>(null) }

    val applyPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            uriToDataUrl(ctx, uri, "image/*")?.let { applyAsset = it }
        }
    }

    fun runApply(t: CreativeTemplate) {
        scope.launch(Dispatchers.IO) {
            applyBusy = true
            applyResult = ""
            try {
                val assets = JSONArray()
                if (applyAsset != null) assets.put(applyAsset)
                val r = JSONObject(NetworkClient.applyTemplate(t.id, assets))
                val rendered = r.strOrNull("image_url")
                    ?: r.strOrNull("image_b64")?.let { normalizeMediaUrl(it) }
                applyRender = rendered
                applyResult = if (rendered != null) {
                    "Rendered “${r.optString("name", t.name)}” • ${r.optInt("width")}x${r.optInt("height")}"
                } else r.optString("error", "Apply failed")
            } catch (e: Exception) {
                applyResult = e.message ?: "Apply failed"
            }
            applyBusy = false
        }
    }

    val categories = listOf("all", "social_post", "ad", "reel", "thumbnail", "poster")
    val platforms = listOf("all", "tiktok", "instagram", "youtube", "twitter", "facebook")

    LaunchedEffect(selectedCategory, selectedPlatform) {
        isLoading = true
        try {
            val cat = if (selectedCategory == "all") null else selectedCategory
            val plat = if (selectedPlatform == "all") null else selectedPlatform
            val response = com.frit.app.data.NetworkClient.getTemplates(cat, plat)
            val json = org.json.JSONObject(response)
            val arr = json.optJSONArray("templates")
            if (arr != null) {
                templates = (0 until arr.length()).map { i ->
                    CreativeTemplate.fromJson(arr.getJSONObject(i))
                }
            }
        } catch (e: Exception) { Diag.warn("Creative", "templates_load", e) }
        isLoading = false
    }

    Scaffold(
        topBar = {
            CreativeTopBar(
                title = "Templates",
                onBack = onClose,
                compact = true
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("Category", style = MaterialTheme.typography.labelSmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        categories.forEach { cat ->
                            FilterChip(
                                selected = (selectedCategory ?: "all") == cat,
                                onClick = { selectedCategory = if (cat == "all") null else cat },
                                label = { Text(cat.replace("_", " ").replaceFirstChar { it.uppercase() }) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Platform", style = MaterialTheme.typography.labelSmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        platforms.forEach { plat ->
                            FilterChip(
                                selected = (selectedPlatform ?: "all") == plat,
                                onClick = { selectedPlatform = if (plat == "all") null else plat },
                                label = { Text(plat.replaceFirstChar { it.uppercase() }) }
                            )
                        }
                    }
                }
            }

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (templates.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.GridView, contentDescription = null, modifier = Modifier.size(64.dp))
                        Text("No templates found", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(templates) { template ->
                        TemplatePreviewCard(
                            template = template,
                            onUse = { applyTarget = template; applyAsset = null; applyResult = ""; applyRender = null }
                        )
                    }
                }
            }
        }

        val target = applyTarget
        if (target != null) {
            AlertDialog(
                onDismissRequest = { applyTarget = null },
                title = { Text(target.name) },
                text = {
                    Column {
                        Text("${target.category} • ${target.platform} • ${target.aspectRatio}", style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = { applyPicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Upload, null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (applyAsset == null) "Attach image (optional)" else "Image attached ✓ — replace")
                        }
                        if (applyResult.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(applyResult, style = MaterialTheme.typography.bodySmall)
                        }
                        if (applyRender != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            GeneratedImage(applyRender!!, Modifier.fillMaxWidth().height(220.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        val u = applyRender ?: return@OutlinedButton
                                        scope.launch {
                                            try {
                                                applyResult = "Saved → " + com.frit.app.ui.creative.components.saveMediaToGallery(ctx, u, "image", "frit-template")
                                            } catch (e: Exception) { applyResult = "Download failed: ${e.message}" }
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Download") }
                                Button(
                                    onClick = {
                                        val u = applyRender ?: return@Button
                                        scope.launch(Dispatchers.IO) {
                                            applyResult = com.frit.app.features.creative.CreativeSuiteEngine.saveToLibrary(u, "image", target.name)
                                                .getOrElse { "Save failed: ${it.message}" }.toString()
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Save") }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { runApply(target) }, enabled = !applyBusy) {
                        if (applyBusy) CircularProgressIndicator(Modifier.size(18.dp)) else Text("Apply layout")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { applyTarget = null }) { Text("Close") }
                }
            )
        }
    }
}
