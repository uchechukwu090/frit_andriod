package com.frit.app.ui.creative

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.frit.app.core.Diag
import com.frit.app.data.NetworkClient
import com.frit.app.features.creative.*
import com.frit.app.ui.creative.components.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

enum class EditorTool { NONE, CROP, RESIZE, FILTER, ADJUST, TEXT, LAYERS, STYLE_TRANSFER, REMOVE_BG, RETOUCH, GENERATIVE_FILL, UPSCALE }

/** Server-supported style keys (mirrors the style map in aiFeatures.styleTransfer). */
private val AiStylePresets = listOf(
    StylePreset("vangogh", "Van Gogh", "AI", "Swirling post-impressionist oil painting"),
    StylePreset("cyberpunk", "Cyberpunk", "AI", "Neon futuristic high contrast"),
    StylePreset("watercolor", "Watercolor", "AI", "Soft delicate washes"),
    StylePreset("cartoon", "Cartoon", "AI", "Bold outlines, vibrant"),
    StylePreset("abstract", "Abstract", "AI", "Geometric, non-representational"),
    StylePreset("realistic", "Realistic", "AI", "Photorealistic 8k detail"),
    StylePreset("oilpainting", "Oil Painting", "AI", "Classical rich textures"),
    StylePreset("sketch", "Sketch", "AI", "Monochrome hand-drawn linework"),
    StylePreset("pixelart", "Pixel Art", "AI", "16-bit retro game look"),
    StylePreset("anime", "Anime", "AI", "Manga illustration, vibrant")
)

private data class TextLayer(val id: String, val text: String, val size: Int, val visible: Boolean = true)

/** Image URL (data: or http) -> raw bytes, or null. */
private fun editorUrlToBytes(url: String): ByteArray? = try {
    if (url.startsWith("data:")) {
        val b64 = url.substringAfter(",", "")
        if (b64.isBlank()) null else Base64.decode(b64, Base64.DEFAULT)
    } else if (url.startsWith("http")) {
        java.net.URL(url).openStream().use { it.readBytes() }
    } else null
} catch (_: Exception) { null }

private fun bitmapToDataUrl(bmp: Bitmap): String {
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
    return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}

@Composable
fun ImageEditorScreen(
    assetId: String = "",
    imageUrl: String = "",
    onClose: () -> Unit = {},
    onSave: (String) -> Unit = {},
    onHandoff: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var currentImageUrl by remember(imageUrl) { mutableStateOf(imageUrl) }
    var activeTool by remember { mutableStateOf(EditorTool.NONE) }
    var selectedFilter by remember { mutableStateOf("none") }
    var brightness by remember { mutableFloatStateOf(1f) }
    var contrast by remember { mutableFloatStateOf(1f) }
    var saturation by remember { mutableFloatStateOf(1f) }
    var textOverlay by remember { mutableStateOf("") }
    var fontSize by remember { mutableIntStateOf(32) }
    var textColorHex by remember { mutableStateOf("#FFFFFF") }
    var selectedStyle by remember { mutableStateOf<String?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var toolStatus by remember { mutableStateOf("") }
    var textLayers by remember { mutableStateOf(listOf<TextLayer>()) }
    var selectedLayerId by remember { mutableStateOf<String?>(null) }
    var promptIdea by remember { mutableStateOf("") }
    var promptSuggestions by remember { mutableStateOf<List<PromptSuggestion>>(emptyList()) }
    var ideasBusy by remember { mutableStateOf(false) }
    var maskPath by remember { mutableStateOf<androidx.compose.ui.graphics.Path?>(null) }
    var maskCanvasSize by remember { mutableStateOf(IntSize.Zero) }
    val undoStack = remember { mutableStateListOf<String>() }
    val redoStack = remember { mutableStateListOf<String>() }

    // Pick up media pushed from any other studio.
    LaunchedEffect(Unit) {
        CreativeSuiteEngine.takeHandoff()?.let { (m, k) ->
            if (k == "image" && m.isNotBlank()) {
                if (currentImageUrl.isNotBlank()) undoStack.add(currentImageUrl)
                currentImageUrl = m
                toolStatus = "Received from another studio"
            }
        }
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            val url = uriToDataUrl(ctx, uri, "image/*")
            if (url != null) {
                if (currentImageUrl.isNotBlank()) undoStack.add(currentImageUrl)
                redoStack.clear()
                currentImageUrl = url
            }
        }
    }

    fun pushUndo() {
        if (currentImageUrl.isNotBlank()) undoStack.add(currentImageUrl)
        redoStack.clear()
    }

    fun doUndo() {
        if (undoStack.isNotEmpty()) {
            if (currentImageUrl.isNotBlank()) redoStack.add(currentImageUrl)
            currentImageUrl = undoStack.removeAt(undoStack.lastIndex)
            toolStatus = "Undone"
        }
    }

    fun doRedo() {
        if (redoStack.isNotEmpty()) {
            if (currentImageUrl.isNotBlank()) undoStack.add(currentImageUrl)
            currentImageUrl = redoStack.removeAt(redoStack.lastIndex)
            toolStatus = "Redone"
        }
    }

    /** Server-side op chain (filter / adjust / text / style). */
    fun applyServerOps(label: String, ops: JSONArray) {
        scope.launch(Dispatchers.IO) {
            isProcessing = true
            toolStatus = ""
            try {
                if (currentImageUrl.isBlank()) throw Exception("load an image first")
                // Server expects base64 — a raw http URL would decode to garbage.
                val bytes = editorUrlToBytes(currentImageUrl) ?: throw Exception("could not fetch image bytes")
                val b64 = "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                val r = JSONObject(NetworkClient.editCreativeImage(b64, ops))
                val out = r.optString("result").takeIf { it.isNotBlank() }
                    ?: r.optString("result_url").takeIf { it.isNotBlank() }
                if (!r.optBoolean("ok") || out == null) throw Exception(r.optString("error", "edit failed"))
                pushUndo()
                currentImageUrl = out
                toolStatus = "$label done"
            } catch (e: Exception) {
                toolStatus = "$label failed: ${e.message}"
            }
            isProcessing = false
        }
    }

    /** On-device bitmap op (crop / resize / rotate / flip): instant, offline, free. */
    fun applyBitmapOp(label: String, transform: (Bitmap) -> Bitmap) {
        scope.launch(Dispatchers.Default) {
            isProcessing = true
            toolStatus = ""
            try {
                val bytes = editorUrlToBytes(currentImageUrl) ?: throw Exception("load an image first")
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw Exception("could not decode image")
                val out = transform(bmp)
                if (out != bmp) bmp.recycle()
                pushUndo()
                currentImageUrl = bitmapToDataUrl(out)
                if (out != bmp) out.recycle()
                toolStatus = "$label done (on-device)"
            } catch (e: Exception) {
                toolStatus = "$label failed: ${e.message}"
            }
            isProcessing = false
        }
    }

    fun centerCropBmp(bmp: Bitmap, targetRatio: Float): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val cur = w.toFloat() / h
        val (cw, ch) = if (cur > targetRatio) (h * targetRatio).toInt() to h else w to (w / targetRatio).toInt()
        val x = (w - cw) / 2
        val y = (h - ch) / 2
        return Bitmap.createBitmap(bmp, x, y, cw, ch)
    }

    fun scaleBmp(bmp: Bitmap, maxDim: Int): Bitmap {
        val m = maxOf(bmp.width, bmp.height)
        if (m <= maxDim) return bmp
        val s = maxDim.toFloat() / m
        return Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
    }

    fun rotateFlipBmp(bmp: Bitmap, degrees: Float, flipH: Boolean, flipV: Boolean): Bitmap {
        val m = Matrix()
        if (flipH || flipV) m.preScale(if (flipH) -1f else 1f, if (flipV) -1f else 1f)
        if (degrees != 0f) m.postRotate(degrees)
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    /** Pull the AI result URL out of a tool response into the viewer. False = error already in toolStatus. */
    fun applyAiResult(r: JSONObject): Boolean {
        if (!r.optBoolean("ok")) {
            toolStatus = r.optString("error", "AI tool failed")
            return false
        }
        val out = r.optString("image_url").takeIf { it.isNotBlank() }
            ?: r.optString("image_b64").takeIf { it.isNotBlank() }?.let { "data:image/png;base64,$it" }
            ?: r.optString("result").takeIf { it.isNotBlank() }
        if (out == null) {
            toolStatus = "AI tool returned no image"
            return false
        }
        pushUndo()
        currentImageUrl = out
        return true
    }

    fun runAi(label: String, call: suspend (String) -> String) {
        scope.launch(Dispatchers.IO) {
            isProcessing = true
            toolStatus = ""
            try {
                val bytes = editorUrlToBytes(currentImageUrl)
                    ?: throw Exception("load an image first")
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val r = JSONObject(call(b64))
                toolStatus = if (applyAiResult(r)) "$label done" else "$label failed: ${r.optString("error", "unknown error")}"
            } catch (e: Exception) {
                toolStatus = "$label failed: ${e.message}"
            }
            isProcessing = false
        }
    }

    fun saveToLibrary() {
        scope.launch {
            toolStatus = "Saving…"
            val res = CreativeSuiteEngine.saveToLibrary(currentImageUrl, "image", "Edited image")
            toolStatus = res.getOrElse { "Save failed: ${it.message}" }.toString()
        }
    }

    fun downloadFile() {
        scope.launch {
            toolStatus = "Downloading…"
            try {
                val loc = saveMediaToGallery(ctx, currentImageUrl, "image")
                toolStatus = "Downloaded → $loc"
            } catch (e: Exception) {
                toolStatus = "Download failed: ${e.message}"
            }
        }
    }

    Scaffold(
        topBar = {
            CreativeTopBar(
                title = "Image Editor",
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                onUndo = { doUndo() },
                onRedo = { doRedo() },
                saveEnabled = currentImageUrl.isNotBlank(),
                onSave = { saveToLibrary() },
                downloadEnabled = currentImageUrl.isNotBlank(),
                onDownload = { downloadFile() },
                exportEnabled = currentImageUrl.isNotBlank(),
                onExport = {
                    if (currentImageUrl.isNotBlank()) {
                        CreativeSuiteEngine.sendHandoff(currentImageUrl, "image")
                        onHandoff("export")
                    }
                },
                onBack = onClose
            )
        }
    ) { padding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .width(80.dp)
                    .fillMaxHeight()
                    .padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                EditorToolButton(Icons.Default.Crop, "Crop", activeTool == EditorTool.CROP) { activeTool = EditorTool.CROP }
                EditorToolButton(Icons.Default.PhotoSizeSelectActual, "Size", activeTool == EditorTool.RESIZE) { activeTool = EditorTool.RESIZE }
                EditorToolButton(Icons.Default.Filter, "Filter", activeTool == EditorTool.FILTER) { activeTool = EditorTool.FILTER }
                EditorToolButton(Icons.Default.Tune, "Adjust", activeTool == EditorTool.ADJUST) { activeTool = EditorTool.ADJUST }
                EditorToolButton(Icons.Default.TextFields, "Text", activeTool == EditorTool.TEXT) { activeTool = EditorTool.TEXT }
                EditorToolButton(Icons.Default.Layers, "Layers", activeTool == EditorTool.LAYERS) { activeTool = EditorTool.LAYERS }
                EditorToolButton(Icons.Default.AutoFixHigh, "AI", activeTool in listOf(EditorTool.REMOVE_BG, EditorTool.RETOUCH, EditorTool.GENERATIVE_FILL, EditorTool.UPSCALE, EditorTool.STYLE_TRANSFER)) { activeTool = EditorTool.REMOVE_BG }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                if (currentImageUrl.isNotEmpty()) {
                    GestureImageViewer(
                        imageUrl = currentImageUrl,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(64.dp))
                        Text("No image loaded", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { importPicker.launch("image/*") }) {
                            Icon(Icons.Default.Upload, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Import image")
                        }
                    }
                }
                if (isProcessing) {
                    CircularProgressIndicator()
                }
            }

            when (activeTool) {
                EditorTool.NONE -> Surface(modifier = Modifier.fillMaxHeight().width(200.dp), tonalElevation = 2.dp) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Start", style = MaterialTheme.typography.titleSmall)
                        OutlinedButton(onClick = { importPicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (currentImageUrl.isBlank()) "Import image" else "Replace image")
                        }
                        if (currentImageUrl.isNotBlank()) {
                            HandoffRow(media = currentImageUrl, kind = "image", onHandoff = onHandoff)
                        }
                        if (toolStatus.isNotBlank()) Text(toolStatus, style = MaterialTheme.typography.bodySmall)
                    }
                }
                EditorTool.CROP -> Surface(modifier = Modifier.fillMaxHeight().width(200.dp), tonalElevation = 2.dp) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Crop & Flip", style = MaterialTheme.typography.titleSmall)
                        listOf("Square 1:1" to 1f, "Wide 16:9" to (16f / 9f), "Tall 9:16" to (9f / 16f), "Card 4:5" to 0.8f).forEach { (label, ratio) ->
                            OutlinedButton(
                                onClick = { applyBitmapOp("Cropped $label") { centerCropBmp(it, ratio) } },
                                modifier = Modifier.fillMaxWidth(), enabled = currentImageUrl.isNotBlank() && !isProcessing
                            ) { Text(label) }
                        }
                        Button(
                            onClick = { applyBitmapOp("Rotated") { rotateFlipBmp(it, 90f, false, false) } },
                            modifier = Modifier.fillMaxWidth(), enabled = currentImageUrl.isNotBlank() && !isProcessing
                        ) { Text("Rotate 90°") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { applyBitmapOp("Flipped") { rotateFlipBmp(it, 0f, true, false) } },
                                modifier = Modifier.weight(1f), enabled = currentImageUrl.isNotBlank() && !isProcessing
                            ) { Text("Flip ↔") }
                            OutlinedButton(
                                onClick = { applyBitmapOp("Flipped") { rotateFlipBmp(it, 0f, false, true) } },
                                modifier = Modifier.weight(1f), enabled = currentImageUrl.isNotBlank() && !isProcessing
                            ) { Text("Flip ↕") }
                        }
                        if (toolStatus.isNotBlank()) Text(toolStatus, style = MaterialTheme.typography.bodySmall)
                    }
                }
                EditorTool.RESIZE -> Surface(modifier = Modifier.fillMaxHeight().width(200.dp), tonalElevation = 2.dp) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Resize", style = MaterialTheme.typography.titleSmall)
                        Text("On-device, keeps aspect ratio.", style = MaterialTheme.typography.bodySmall)
                        listOf(512, 1024, 2048).forEach { px ->
                            OutlinedButton(
                                onClick = { applyBitmapOp("Resized ${px}px") { scaleBmp(it, px) } },
                                modifier = Modifier.fillMaxWidth(), enabled = currentImageUrl.isNotBlank() && !isProcessing
                            ) { Text("Max $px px") }
                        }
                        if (toolStatus.isNotBlank()) Text(toolStatus, style = MaterialTheme.typography.bodySmall)
                    }
                }
                EditorTool.FILTER -> Surface(modifier = Modifier.fillMaxHeight().width(200.dp), tonalElevation = 2.dp) {
                    Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterCarousel(selectedFilter = selectedFilter, onFilterSelected = { selectedFilter = it })
                        Button(
                            onClick = {
                                val ops = JSONArray().put(JSONObject().put("type", "filter").put("params", JSONObject().put("filter", selectedFilter)))
                                applyServerOps("Filter applied", ops)
                            },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            enabled = selectedFilter != "none" && currentImageUrl.isNotBlank() && !isProcessing
                        ) { Text("Apply filter") }
                        if (toolStatus.isNotBlank()) Text(toolStatus, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp))
                    }
                }
                EditorTool.ADJUST -> Surface(modifier = Modifier.fillMaxHeight().width(200.dp), tonalElevation = 2.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ColorAdjustmentSliders(
                            brightness = brightness, contrast = contrast, saturation = saturation,
                            onBrightnessChange = { brightness = it },
                            onContrastChange = { contrast = it },
                            onSaturationChange = { saturation = it }
                        )
                        Row(modifier = Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { brightness = 1f; contrast = 1f; saturation = 1f },
                                modifier = Modifier.weight(1f)
                            ) { Text("Reset") }
                            Button(
                                onClick = {
                                    val ops = JSONArray().put(
                                        JSONObject().put("type", "adjust_color").put(
                                            "params", JSONObject().put("brightness", brightness).put("contrast", contrast).put("saturation", saturation)
                                        )
                                    )
                                    applyServerOps("Adjustments applied", ops)
                                },
                                modifier = Modifier.weight(1f), enabled = currentImageUrl.isNotBlank() && !isProcessing
                            ) { Text("Apply") }
                        }
                        if (toolStatus.isNotBlank()) Text(toolStatus, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
                EditorTool.TEXT -> Surface(modifier = Modifier.fillMaxHeight().width(200.dp), tonalElevation = 2.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextOverlayEditor(
                            text = textOverlay, fontSize = fontSize, onTextChange = { textOverlay = it },
                            onFontSizeChange = { fontSize = it },
                            onColorChange = { textColorHex = "#%06X".format(0xFFFFFF and it.toArgb()) }
                        )
                        Button(
                            onClick = {
                                val ops = JSONArray().put(
                                    JSONObject().put("type", "add_text").put(
                                        "params", JSONObject().put("text", textOverlay).put("x", 60).put("y", 140).put("fontSize", fontSize).put("color", textColorHex)
                                    )
                                )
                                applyServerOps("Text added", ops)
                            },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            enabled = textOverlay.isNotBlank() && currentImageUrl.isNotBlank() && !isProcessing
                        ) { Text("Stamp text") }
                        Text("Tip: stack multiple lines in Layers.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                        if (toolStatus.isNotBlank()) Text(toolStatus, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
                EditorTool.LAYERS -> Surface(modifier = Modifier.fillMaxHeight().width(200.dp), tonalElevation = 2.dp) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Text layers", style = MaterialTheme.typography.titleSmall)
                        Text("Stack lines, then flatten them onto the image in one pass.", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = textOverlay, onValueChange = { textOverlay = it },
                            label = { Text("Layer text") }, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedButton(
                            onClick = {
                                if (textOverlay.isNotBlank()) {
                                    textLayers = textLayers + TextLayer("l${System.currentTimeMillis()}", textOverlay, fontSize)
                                    textOverlay = ""
                                }
                            },
                            modifier = Modifier.fillMaxWidth(), enabled = textOverlay.isNotBlank()
                        ) { Text("Add layer") }
                        LayerPanel(
                            layers = listOf(LayerItem("layer_1", "Background", true, 1f)) +
                                textLayers.map { LayerItem(it.id, it.text.take(18), it.visible, 1f) },
                            selectedLayerId = selectedLayerId ?: "layer_1",
                            onLayerSelected = { id -> selectedLayerId = if (id == "layer_1") null else id },
                            onToggleVisibility = { id ->
                                if (id != "layer_1") textLayers = textLayers.map { if (it.id == id) it.copy(visible = !it.visible) else it }
                            },
                            onReorderLayers = { from, to ->
                                // Background is pinned at index 0; only reorder within text layers.
                                val fi = from - 1
                                val ti = to - 1
                                if (fi in textLayers.indices && ti in textLayers.indices) {
                                    textLayers = textLayers.toMutableList().apply { add(ti, removeAt(fi)) }
                                }
                            }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = { textLayers = emptyList(); selectedLayerId = null },
                                modifier = Modifier.weight(1f), enabled = textLayers.isNotEmpty()
                            ) { Text("Clear") }
                            TextButton(
                                onClick = {
                                    val sel = selectedLayerId
                                    if (sel != null) { textLayers = textLayers.filterNot { it.id == sel }; selectedLayerId = null }
                                },
                                modifier = Modifier.weight(1f), enabled = selectedLayerId != null
                            ) { Text("Delete") }
                            Button(
                                onClick = {
                                    val visible = textLayers.filter { it.visible && it.text.isNotBlank() }
                                    if (visible.isEmpty() || currentImageUrl.isBlank()) {
                                        toolStatus = "Add a visible layer first"
                                        return@Button
                                    }
                                    val ops = JSONArray()
                                    visible.forEachIndexed { i, l ->
                                        ops.put(
                                            JSONObject().put("type", "add_text").put(
                                                "params", JSONObject().put("text", l.text).put("x", 60).put("y", 140 + i * 90).put("fontSize", l.size).put("color", textColorHex)
                                            )
                                        )
                                    }
                                    applyServerOps("${visible.size} layer(s) flattened", ops)
                                },
                                modifier = Modifier.weight(1f), enabled = currentImageUrl.isNotBlank() && !isProcessing
                            ) { Text("Flatten") }
                        }
                        if (toolStatus.isNotBlank()) Text(toolStatus, style = MaterialTheme.typography.bodySmall)
                    }
                }
                EditorTool.REMOVE_BG, EditorTool.RETOUCH, EditorTool.GENERATIVE_FILL, EditorTool.UPSCALE, EditorTool.STYLE_TRANSFER -> Surface(modifier = Modifier.fillMaxHeight().width(220.dp), tonalElevation = 2.dp) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("AI Tools", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("BG" to EditorTool.REMOVE_BG, "Fix" to EditorTool.RETOUCH, "Fill" to EditorTool.GENERATIVE_FILL).forEach { (label, tool) ->
                                FilterChip(selected = activeTool == tool, onClick = { activeTool = tool }, label = { Text(label) })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("2x/4x" to EditorTool.UPSCALE, "Style" to EditorTool.STYLE_TRANSFER).forEach { (label, tool) ->
                                FilterChip(selected = activeTool == tool, onClick = { activeTool = tool }, label = { Text(label) })
                            }
                        }
                        val noImage = currentImageUrl.isBlank()
                        if (activeTool == EditorTool.REMOVE_BG) {
                            Button(
                                onClick = { runAi("Background removed") { NetworkClient.removeBackground(it) } },
                                modifier = Modifier.fillMaxWidth(), enabled = !noImage && !isProcessing
                            ) { Text("Remove Background") }
                        }
                        if (activeTool == EditorTool.RETOUCH) {
                            Button(
                                onClick = { runAi("Retouch done") { NetworkClient.retouchImage(it, JSONObject().put("skin_smooth", true)) } },
                                modifier = Modifier.fillMaxWidth(), enabled = !noImage && !isProcessing
                            ) { Text("Skin Smooth") }
                            Button(
                                onClick = { runAi("Retouch done") { NetworkClient.retouchImage(it, JSONObject().put("object_removal", true)) } },
                                modifier = Modifier.fillMaxWidth(), enabled = !noImage && !isProcessing
                            ) { Text("Object Removal") }
                            Button(
                                onClick = { runAi("Retouch done") { NetworkClient.retouchImage(it, JSONObject().put("lighting", true)) } },
                                modifier = Modifier.fillMaxWidth(), enabled = !noImage && !isProcessing
                            ) { Text("Lighting Fix") }
                        }
                        if (activeTool == EditorTool.STYLE_TRANSFER) {
                            StylePresetGrid(presets = AiStylePresets, selectedPreset = selectedStyle, onPresetSelected = { selectedStyle = it })
                            Button(
                                onClick = {
                                    val style = selectedStyle ?: return@Button
                                    runAi("Style applied") { NetworkClient.styleTransfer(it, style) }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = selectedStyle != null && !noImage && !isProcessing
                            ) { Text("Apply style") }
                        }
                        if (activeTool == EditorTool.GENERATIVE_FILL) {
                            OutlinedTextField(value = promptIdea, onValueChange = { promptIdea = it }, label = { Text("Fill prompt") }, modifier = Modifier.fillMaxWidth())
                            OutlinedButton(
                                onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        ideasBusy = true
                                        try {
                                            val r = JSONObject(NetworkClient.suggestPrompts(promptIdea.ifBlank { "surreal portrait" }, "generative fill"))
                                            val arr = r.optJSONArray("suggestions")
                                            promptSuggestions = if (arr != null) (0 until arr.length()).mapNotNull { i ->
                                                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                                                val p = o.optString("prompt").ifBlank { null } ?: return@mapNotNull null
                                                PromptSuggestion(p, o.optString("description", p), o.optString("style_preset").ifBlank { null })
                                            } else emptyList()
                                        } catch (e: Exception) { Diag.warn("Creative", "prompt_suggest", e) }
                                        ideasBusy = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(), enabled = !ideasBusy && !isProcessing
                            ) { Text(if (ideasBusy) "Thinking…" else "Prompt ideas") }
                            promptSuggestions.forEach { s ->
                                PromptSuggestionCard(suggestion = s, onUse = { promptIdea = it.prompt; promptSuggestions = emptyList() })
                            }
                            Text("Draw the fill area on the canvas:", style = MaterialTheme.typography.labelSmall)
                            MaskCanvas(
                                modifier = Modifier.fillMaxWidth().height(160.dp).onSizeChanged { maskCanvasSize = it },
                                onMaskChanged = { maskPath = it }
                            )
                            Button(
                                onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        isProcessing = true
                                        toolStatus = ""
                                        try {
                                            val imgBytes = editorUrlToBytes(currentImageUrl)
                                                ?: throw Exception("load an image first")
                                            val bmp = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.size)
                                                ?: throw Exception("could not decode image")
                                            val path = maskPath ?: throw Exception("draw a mask area first")
                                            val cs = maskCanvasSize
                                            if (cs.width <= 0 || cs.height <= 0) throw Exception("mask canvas not ready — redraw")
                                            val maskBmp = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                                            val canvas = android.graphics.Canvas(maskBmp)
                                            canvas.drawColor(android.graphics.Color.WHITE)
                                            val m = android.graphics.Matrix()
                                            m.setScale(bmp.width / cs.width.toFloat(), bmp.height / cs.height.toFloat())
                                            val p = android.graphics.Path(path.asAndroidPath())
                                            p.transform(m)
                                            canvas.drawPath(p, android.graphics.Paint().apply {
                                                color = android.graphics.Color.BLACK
                                                style = android.graphics.Paint.Style.FILL
                                            })
                                            val mout = ByteArrayOutputStream()
                                            maskBmp.compress(Bitmap.CompressFormat.PNG, 100, mout)
                                            val r = JSONObject(NetworkClient.generativeFill(
                                                Base64.encodeToString(imgBytes, Base64.NO_WRAP),
                                                Base64.encodeToString(mout.toByteArray(), Base64.NO_WRAP),
                                                promptIdea
                                            ))
                                            toolStatus = if (applyAiResult(r)) "Fill done" else "Fill failed: ${r.optString("error", "unknown error")}"
                                        } catch (e: Exception) {
                                            toolStatus = "Fill failed: ${e.message}"
                                        }
                                        isProcessing = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(), enabled = !noImage && !isProcessing
                            ) { Text("Generate Fill") }
                        }
                        if (activeTool == EditorTool.UPSCALE) {
                            Button(
                                onClick = { runAi("Upscale done") { NetworkClient.upscaleImage(it, 2) } },
                                modifier = Modifier.fillMaxWidth(), enabled = !noImage && !isProcessing
                            ) { Text("2x Upscale") }
                            Button(
                                onClick = { runAi("Upscale done") { NetworkClient.upscaleImage(it, 4) } },
                                modifier = Modifier.fillMaxWidth(), enabled = !noImage && !isProcessing
                            ) { Text("4x Upscale") }
                        }
                        if (toolStatus.isNotBlank()) {
                            Text(toolStatus, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EditorToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}
