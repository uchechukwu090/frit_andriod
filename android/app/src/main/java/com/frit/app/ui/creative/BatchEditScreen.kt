package com.frit.app.ui.creative

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.frit.app.features.creative.BatchJob
import com.frit.app.features.creative.CreativeSuiteEngine
import com.frit.app.ui.creative.components.BatchProgressBar
import com.frit.app.ui.creative.components.CreativeTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun BatchEditScreen(
    onClose: () -> Unit = {},
    onComplete: () -> Unit = {},
    onHandoff: (String) -> Unit = {},
) {
    val ctx = LocalContext.current
    var selectedImages by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedOperation by remember { mutableStateOf("resize") }
    var currentJob by remember { mutableStateOf<BatchJob?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var doneMessage by remember { mutableStateOf("") }
    var batchResults by remember { mutableStateOf<List<Pair<String?, String>>>(emptyList()) }
    val scope = rememberCoroutineScope()

    val operations = listOf("resize", "remove_bg", "upscale", "style_transfer", "filter", "convert")

    val imgPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (!uris.isNullOrEmpty()) scope.launch(Dispatchers.IO) {
            val fresh = uris.take(6 - selectedImages.size).mapNotNull { uriToDataUrl(ctx, it, "image/*") }
            if (fresh.isNotEmpty()) selectedImages = (selectedImages + fresh).take(6)
        }
    }

    val firstResult = batchResults.firstOrNull { it.first != null }?.first
    fun saveAll() {
        val urls = batchResults.mapNotNull { it.first }
        if (urls.isEmpty()) { doneMessage = "Nothing to save yet"; return }
        scope.launch {
            doneMessage = "Saving ${urls.size}…"
            var ok = 0
            for ((i, u) in urls.withIndex()) {
                val res = CreativeSuiteEngine.saveToLibrary(u, "image", "Batch $selectedOperation #$i")
                if (res.isSuccess) ok++
            }
            doneMessage = "Saved $ok/${urls.size} to My Creations"
        }
    }
    Scaffold(
        topBar = {
            CreativeTopBar(
                title = "Batch Edit",
                onBack = onClose,
                saveEnabled = firstResult != null, onSave = { saveAll() },
                downloadEnabled = false,
                exportEnabled = firstResult != null,
                onExport = {
                    val u = firstResult
                    if (u != null) {
                        CreativeSuiteEngine.sendHandoff(u, "image")
                        onHandoff("export")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.medium
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Select Operation", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    operations.forEach { op ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedOperation == op,
                                onClick = { selectedOperation = op }
                            )
                            Text(op.replace("_", " ").replaceFirstChar { it.uppercase() })
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { imgPicker.launch("image/*") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = selectedImages.size < 6 && !isProcessing
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (selectedImages.isEmpty()) "Add images (up to 6)" else "Add more (${selectedImages.size}/6)")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                isProcessing = true
                                doneMessage = ""
                                batchResults = emptyList()
                                try {
                                    val params = JSONObject().apply {
                                        if (selectedOperation == "resize") { put("width", 1080); put("height", 1080); put("fit", "cover") }
                                        if (selectedOperation == "upscale") put("scale", 2)
                                        if (selectedOperation == "style_transfer") put("style", "realistic")
                                        if (selectedOperation == "filter") put("filter", "grayscale")
                                        if (selectedOperation == "convert") put("format", "png")
                                    }
                                    // The server processes the batch synchronously and
                                    // returns per-image results inline — no polling.
                                    val out = CreativeSuiteEngine.runBatch(selectedImages, selectedOperation, params)
                                    val ok = out.count { it.first != null }
                                    batchResults = out
                                    currentJob = BatchJob("batch", selectedOperation, selectedImages.size, ok, "completed")
                                    doneMessage = "Batch complete — $ok/${selectedImages.size} done"
                                    if (ok == selectedImages.size) scope.launch(Dispatchers.Main) { onComplete() }
                                } catch (e: Exception) {
                                    doneMessage = e.message ?: "Failed"
                                }
                                isProcessing = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = selectedImages.isNotEmpty() && !isProcessing
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text("Process ${selectedImages.size} Images")
                    }
                    if (doneMessage.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(doneMessage, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            BatchProgressBar(job = currentJob)

            if (batchResults.any { it.first != null }) {
                Text(
                    "Results — tap ⬇ on any image to download",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(batchResults) { index, (url, err) ->
                        Card(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                if (url != null) {
                                    GeneratedImage(
                                        src = url,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    com.frit.app.ui.creative.components.DownloadIconButton(
                                        url = url,
                                        kind = "image",
                                        onStatus = { doneMessage = it }
                                    )
                                } else {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(err.ifBlank { "failed" }, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (selectedImages.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(64.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Select images to batch edit", style = MaterialTheme.typography.bodyMedium)
                        Text("Use the image picker to add images", style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(selectedImages) { index, imageUrl ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                androidx.compose.foundation.Image(
                                    painter = coil.compose.rememberAsyncImagePainter(imageUrl),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Surface(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = MaterialTheme.shapes.small,
                                    onClick = { selectedImages = selectedImages.filterIndexed { i, _ -> i != index } }
                                ) {
                                    Text(
                                        "${index + 1}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
