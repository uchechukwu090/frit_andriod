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
import androidx.compose.ui.unit.dp
import com.frit.app.core.Diag
import com.frit.app.data.NetworkClient
import com.frit.app.ui.creative.components.CreativeTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class SoulElement(val id: String, val name: String, val type: String, val refs: Int, val refUrls: List<String> = emptyList())

@Composable
fun CharactersScreen(onClose: () -> Unit = {}, onUseElement: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var elements by remember { mutableStateOf<List<SoulElement>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("character") }
    var refs by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch(Dispatchers.IO) {
            try {
                val r = JSONObject(NetworkClient.getElements(null))
                val arr = r.optJSONArray("elements") ?: JSONArray()
                elements = (0 until arr.length()).map { i ->
                    val e = arr.getJSONObject(i)
                    val refArr = e.optJSONArray("reference_urls")
                    val urls = if (refArr != null) (0 until refArr.length()).map { refArr.optString(it) }.filter { it.isNotBlank() } else emptyList()
                    SoulElement(e.optString("id"), e.optString("name"), e.optString("type"), urls.size, urls)
                }
            } catch (e: Exception) { Diag.warn("Creative", "elements_load", e) }
        }
    }
    var notice by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        reload()
        com.frit.app.features.creative.CreativeSuiteEngine.takeHandoff()?.let { (m, k) ->
            if (k == "image" && m.isNotBlank()) {
                refs = listOf(m)
                name = "Pushed look"
                notice = "Image arrived from another studio — name it and Save Element"
            }
        }
    }
    val imgPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        scope.launch(Dispatchers.IO) {
            val added = uris.take(30 - refs.size).mapNotNull { uriToDataUrl(ctx, it, "image/*") }
            refs = (refs + added).take(30)
        }
    }

    Scaffold(topBar = { CreativeTopBar(title = "Characters — Soul ID", onBack = onClose, compact = true) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Save a face once — reuse it in every shot.", style = MaterialTheme.typography.bodySmall)
            if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Element name") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("character", "location", "prop").forEach { t ->
                    FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.replaceFirstChar { it.uppercase() }) })
                }
            }
            OutlinedButton(onClick = { imgPicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Image, null); Spacer(Modifier.width(6.dp)); Text("Add references (${refs.size}/30)")
            }
            Button(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        busy = true
                        try {
                            val arr = JSONArray(); refs.forEach { arr.put(it) }
                            JSONObject(NetworkClient.createElement(name, type, arr, null))
                            name = ""; refs = emptyList(); reload()
                        } catch (e: Exception) { Diag.warn("Creative", "create_element", e) }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(), enabled = name.isNotBlank() && refs.isNotEmpty() && !busy
            ) { Text("Save Element") }
            Text("Saved Elements", style = MaterialTheme.typography.titleSmall)
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(elements, key = { it.id }) { e ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(e.name, style = MaterialTheme.typography.bodyMedium)
                                Text("${e.type} • ${e.refs} refs", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = {
                                com.frit.app.features.creative.CreativeSuiteEngine.pendingCinemaRefs = e.refUrls
                                onUseElement(e.id)
                            }) { Text("Use") }
                            IconButton(onClick = { scope.launch(Dispatchers.IO) { NetworkClient.deleteElement(e.id); reload() } }) {
                                Icon(Icons.Default.Delete, "Delete")
                            }
                        }
                    }
                }
            }
        }
    }
}
