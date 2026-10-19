package com.frit.app.ui.creative

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.frit.app.core.Diag
import androidx.compose.ui.unit.dp
import com.frit.app.data.NetworkClient
import com.frit.app.features.creative.CreativeSuiteEngine
import com.frit.app.ui.creative.components.CreativeTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class BrandKit(val id: String, val name: String, val colors: List<String>, val font: String, val palette: String, val tone: String)

@Composable
fun BrandKitScreen(onClose: () -> Unit = {}, onApply: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var kits by remember { mutableStateOf<List<BrandKit>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var colors by remember { mutableStateOf("") }
    var font by remember { mutableStateOf("") }
    var palette by remember { mutableStateOf("") }
    var tone by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var logo by remember { mutableStateOf<String?>(null) }
    var colorError by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    val logoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                logo = uriToDataUrl(ctx, uri, "image/*")
            }
        }
    }

    fun validateColors(): Boolean {
        val parts = colors.split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (parts.isEmpty()) { colorError = ""; return true }
        val bad = parts.filter { !it.matches(Regex("^#?[0-9A-Fa-f]{6}$")) }
        return if (bad.isEmpty()) { colorError = ""; true }
        else { colorError = "Invalid hex: ${bad.joinToString(", ")}"; false }
    }

    fun reload() {
        scope.launch(Dispatchers.IO) {
            try {
                val r = JSONObject(NetworkClient.getBrandKits())
                val arr = r.optJSONArray("brandkits") ?: JSONArray()
                kits = (0 until arr.length()).map { i ->
                    val k = arr.getJSONObject(i)
                    val ca = k.optJSONArray("colors")
                    BrandKit(
                        k.optString("id"), k.optString("name"),
                        if (ca != null) (0 until ca.length()).map { ca.optString(it) } else emptyList(),
                        k.optString("font"), k.optString("palette"), k.optString("tone")
                    )
                }
            } catch (e: Exception) { Diag.warn("Creative", "brandkit_parse", e) }
        }
    }
    LaunchedEffect(Unit) { reload() }

    Scaffold(topBar = { CreativeTopBar(title = "Brand Kit", onBack = onClose, compact = true) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Lock colors, font & mood — every generation stays on-brand.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Brand name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = colors, onValueChange = { colors = it; colorError = "" }, label = { Text("Colors (hex, comma separated)") }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("#EBE8E0, #362F2F") })
            if (colorError.isNotBlank()) Text(colorError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { logoPicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Image, null); Spacer(Modifier.width(6.dp))
                Text(if (logo == null) "Brand logo (optional)" else "Logo attached ✓")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = font, onValueChange = { font = it }, label = { Text("Font") }, modifier = Modifier.weight(1f))
                OutlinedTextField(value = palette, onValueChange = { palette = it }, label = { Text("Grade") }, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(value = tone, onValueChange = { tone = it }, label = { Text("Mood / tone") }, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        busy = true
                        try {
                            if (!validateColors()) return@launch
                            val arr = JSONArray()
                            colors.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { arr.put(it) }
                            NetworkClient.createBrandKit(name, arr, font.ifBlank { null }, logo, palette.ifBlank { null }, tone.ifBlank { null })
                            name = ""; colors = ""; font = ""; palette = ""; tone = ""; logo = null
                            reload()
                        } catch (e: Exception) {
                            // F-16: saving a brand kit is a money-adjacent write
                            // (it drives every later generation) — a silent
                            // failure here looked like "the button did nothing".
                            Diag.warn("Creative", "brandkit_create", e)
                            status = "Could not save the Brand Kit: ${e.message}"
                        } finally { busy = false }
                    }
                },
                modifier = Modifier.fillMaxWidth(), enabled = name.isNotBlank() && !busy
            ) { Text("Save Brand Kit") }
            if (status.isNotBlank()) {
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Text("Saved kits", style = MaterialTheme.typography.titleSmall)
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(kits, key = { it.id }) { k ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(k.name, style = MaterialTheme.typography.bodyMedium)
                                Text(listOf(k.colors.joinToString(" "), k.font, k.palette, k.tone).filter { it.isNotBlank() }.joinToString(" • "), style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = {
                                // F-13: the brand lock is now applied SERVER-SIDE.
                                // The client only hands over the kit id — it no
                                // longer splices a brand prompt into the brief,
                                // which used to apply to Design Studio only and
                                // was lost on any retry or non-Design route.
                                CreativeSuiteEngine.pendingBrandKitId = k.id
                                onApply()
                            }) { Text("Apply in Design Studio") }
                            IconButton(onClick = { scope.launch(Dispatchers.IO) { NetworkClient.deleteBrandKit(k.id); reload() } }) {
                                Icon(Icons.Default.Delete, "Delete")
                            }
                        }
                    }
                }
            }
        }
    }
}
