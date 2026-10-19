package com.frit.app.features.creative

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.frit.app.data.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object CreativeSuiteEngine {
    private const val TAG = "CreativeSuiteEngine"

    private var appContext: Context? = null

    private val _currentProject = MutableStateFlow<CreativeProject?>(null)
    val currentProject = _currentProject.asStateFlow()

    private val undoStacks = mutableMapOf<String, MutableList<EditOperation>>()
    private val redoStacks = mutableMapOf<String, MutableList<EditOperation>>()

    private val _activeBatchJobs = MutableStateFlow<Map<String, BatchJob>>(emptyMap())
    val activeBatchJobs = _activeBatchJobs.asStateFlow()

    private val offlineQueue = mutableListOf<PendingOperation>()

    // Soul ID handoff: CharactersScreen drops element references here;
    // CinemaStudioScreen picks them up on launch so the same face follows.
    var pendingCinemaRefs: List<String> = emptyList()

    // Brand Kit handoff: the applied kit's ID is handed to the next studio.
    // F-13: the lock itself is applied SERVER-SIDE (the client sends only the
    // id), so every studio honours it and it survives retries. The legacy
    // pendingBrandPrompt is kept for one release so an older client build
    // still works against a new server, but new code must not write it.
    @Deprecated("Brand lock is applied server-side — send the brandkit id instead")
    var pendingBrandPrompt: String = ""
    var pendingBrandKitId: String? = null

    // Cross-studio handoff bus: any studio stages (media, kind) here and the
    // target studio picks it up on launch — no re-importing, no re-pasting.
    // kind is "image" | "video" | "audio".
    private var handoff: Pair<String, String>? = null
    fun sendHandoff(media: String, kind: String) { handoff = media to kind }
    fun takeHandoff(): Pair<String, String>? {
        val h = handoff
        handoff = null
        return h
    }
    fun peekHandoff(): Pair<String, String>? = handoff

    /**
     * Save-to-library: persists a generated asset into the "My Creations"
     * project (created on first use). This is what the top-bar Save icon does.
     */
    suspend fun saveToLibrary(url: String, kind: String, name: String = "Creation"): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                if (url.isBlank()) return@withContext Result.failure(Exception("nothing to save yet"))
                val listResp = NetworkClient.getProjects()
                val listJson = JSONObject(listResp)
                val arr = listJson.optJSONArray("projects")
                var projectId: String? = null
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val p = arr.optJSONObject(i) ?: continue
                        if (p.optString("name") == "My Creations") { projectId = p.optString("id"); break }
                    }
                }
                if (projectId.isNullOrBlank()) {
                    val cr = JSONObject(NetworkClient.createProject("My Creations", "mixed", null))
                    if (!cr.optBoolean("ok")) return@withContext Result.failure(Exception(cr.optString("error", "could not create library")))
                    projectId = cr.optString("project_id")
                }
                val asset = JSONObject().apply {
                    put("kind", kind)
                    put("source", "generated")
                    put("url", url)
                    put("metadata", JSONObject().put("name", name))
                }
                val ar = JSONObject(NetworkClient.addAssetToProject(projectId, asset))
                if (!ar.optBoolean("ok")) return@withContext Result.failure(Exception(ar.optString("error", "could not save asset")))
                Result.success("Saved to My Creations")
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    suspend fun createProject(name: String, type: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val response = NetworkClient.createProject(name, type, null)
            val json = JSONObject(response)
            if (json.optBoolean("ok")) Result.success(json.getString("project_id"))
            else Result.failure(Exception(json.optString("error", "Unknown error")))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun loadProject(projectId: String): Result<CreativeProject> = withContext(Dispatchers.IO) {
        try {
            val response = NetworkClient.getProject(projectId)
            val json = JSONObject(response)
            if (json.optBoolean("ok")) {
                val project = CreativeProject.fromJson(json.getJSONObject("project"))
                _currentProject.value = project
                Result.success(project)
            } else Result.failure(Exception(json.optString("error")))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun editImage(assetId: String, operations: List<EditOperation>): Result<EditedResult> = withContext(Dispatchers.IO) {
        try {
            undoStacks.getOrPut(assetId) { mutableListOf() }.addAll(operations)
            redoStacks[assetId]?.clear()
            val opsArray = JSONArray()
            operations.forEach { opsArray.put(it.toJson()) }
            val response = com.frit.app.data.NetworkClient.editCreativeImage(operations.first().sourceImage, opsArray)
            val json = JSONObject(response)
            if (json.optBoolean("ok")) Result.success(EditedResult.fromJson(json))
            else Result.failure(Exception(json.optString("error")))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun undo(assetId: String): Result<EditedResult>? {
        val stack = undoStacks[assetId] ?: return null
        if (stack.isEmpty()) return null
        val operation = stack.removeAt(stack.size - 1)
        redoStacks.getOrPut(assetId) { mutableListOf() }.add(operation)
        return editImage(assetId, stack.toList())
    }

    suspend fun redo(assetId: String): Result<EditedResult>? {
        val stack = redoStacks[assetId] ?: return null
        if (stack.isEmpty()) return null
        val operation = stack.removeAt(stack.size - 1)
        undoStacks.getOrPut(assetId) { mutableListOf() }.add(operation)
        return editImage(assetId, undoStacks[assetId]?.toList() ?: emptyList())
    }

    suspend fun startBatch(images: List<String>, operation: String, params: JSONObject): String {
        val imagesArray = JSONArray()
        images.forEach { imagesArray.put(it) }
        val response = NetworkClient.batchEdit(imagesArray, operation, params)
        val json = JSONObject(response)
        val jobId = json.optString("job_id", "")
        if (jobId.isNotBlank()) {
            _activeBatchJobs.value = _activeBatchJobs.value + (jobId to BatchJob(
                id = jobId, operation = operation, total = images.size, completed = 0
            ))
        }
        return jobId
    }

    /**
     * Run a batch edit synchronously: the server processes every image and
     * returns per-image results inline. Returns (resultUrl?, error?) per input.
     */
    suspend fun runBatch(images: List<String>, operation: String, params: JSONObject): List<Pair<String?, String>> =
        withContext(Dispatchers.IO) {
            val imagesArray = JSONArray()
            images.forEach { imagesArray.put(it) }
            val response = NetworkClient.batchEdit(imagesArray, operation, params)
            val json = JSONObject(response)
            if (!json.optBoolean("ok", false)) {
                throw Exception(json.optString("error", "batch failed"))
            }
            val arr = json.optJSONArray("results") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val o = arr.optJSONObject(i)
                if (o != null && o.optBoolean("ok", false)) {
                    o.optString("result").ifBlank { null } to ""
                } else {
                    null to (o?.optString("error", "failed") ?: "failed")
                }
            }
        }

    suspend fun pollBatchStatus(jobId: String): BatchJob? {
        val response = NetworkClient.getBatchStatus(jobId)
        val json = JSONObject(response)
        val status = json.optString("status")
        val completed = json.optInt("completed_count", 0)
        val job = _activeBatchJobs.value[jobId]?.copy(status = status, completed = completed) ?: return null
        _activeBatchJobs.value = _activeBatchJobs.value + (jobId to job)
        return job
    }

    fun queueOffline(operation: PendingOperation) {
        offlineQueue.add(operation)
    }

    suspend fun syncOfflineQueue() {
        if (!isOnline()) return
        val pending = offlineQueue.toList()
        offlineQueue.clear()
        for (op in pending) {
            try { op.execute() } catch (e: Exception) { offlineQueue.add(op) }
        }
    }

    private fun isOnline(): Boolean {
        return try {
            val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) { false }
    }
}
