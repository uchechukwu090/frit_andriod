package com.frit.app.features.creative

import com.frit.app.ui.creative.strOrNull
import com.frit.app.data.NetworkClient
import org.json.JSONObject

data class CreativeProject(
    val id: String,
    val name: String,
    val type: String,
    val status: String,
    val thumbnailUrl: String?,
    val assets: List<CreativeAsset>,
    val metadata: JSONObject?,
    val updatedAt: String,
    val assetCount: Int = 0
) {
    companion object {
        fun fromJson(json: JSONObject) = CreativeProject(
            id = json.getString("id"),
            name = json.optString("name", "Untitled"),
            type = json.optString("type", "image"),
            status = json.optString("status", "draft"),
            thumbnailUrl = json.strOrNull("thumbnail_url"),
            assets = json.optJSONArray("assets")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    CreativeAsset.fromJson(o)
                }
            } ?: emptyList(),
            metadata = json.optJSONObject("metadata"),
            updatedAt = json.optString("updated_at", ""),
            assetCount = if (json.has("asset_count")) json.optInt("asset_count") else (json.optJSONArray("assets")?.length() ?: 0)
        )
    }
}

data class CreativeAsset(
    val id: String,
    val projectId: String,
    val kind: String,
    val source: String,
    val currentUrl: String,
    val thumbnailUrl: String?,
    val metadata: JSONObject?,
    val version: Int
) {
    companion object {
        fun fromJson(json: JSONObject) = CreativeAsset(
            id = json.getString("id"),
            projectId = json.optString("project_id", ""),
            kind = json.optString("kind", "image"),
            source = json.optString("source", "uploaded"),
            currentUrl = json.optString("current_url", ""),
            thumbnailUrl = json.strOrNull("thumbnail_url"),
            metadata = json.optJSONObject("metadata"),
            version = json.optInt("version", 1)
        )
    }
}

data class EditOperation(
    val type: String,
    val params: JSONObject,
    val sourceImage: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        put("params", params)
    }
}

data class EditedResult(
    val resultUrl: String,
    val thumbnailUrl: String?,
    val width: Int,
    val height: Int
) {
    companion object {
        fun fromJson(json: JSONObject) = EditedResult(
            resultUrl = json.optString("result", json.optString("result_url", "")),
            thumbnailUrl = json.strOrNull("thumbnail_url"),
            width = json.optInt("width", 0),
            height = json.optInt("height", 0)
        )
    }
}

data class BatchJob(
    val id: String,
    val operation: String,
    val total: Int,
    val completed: Int,
    val status: String = "pending"
)

data class StylePreset(
    val id: String,
    val name: String,
    val category: String,
    val description: String
) {
    companion object {
        fun fromJson(json: JSONObject) = StylePreset(
            id = json.getString("id"),
            name = json.optString("name", ""),
            category = json.optString("category", ""),
            description = json.optString("description", "")
        )
    }
}

data class CreativeTemplate(
    val id: String,
    val name: String,
    val category: String,
    val platform: String,
    val aspectRatio: String,
    val thumbnailUrl: String?
) {
    companion object {
        fun fromJson(json: JSONObject) = CreativeTemplate(
            id = json.getString("id"),
            name = json.optString("name", ""),
            category = json.optString("category", ""),
            platform = json.optString("platform", ""),
            aspectRatio = json.optString("aspect_ratio", "1:1"),
            thumbnailUrl = json.strOrNull("thumbnail_url")
        )
    }
}

sealed class PendingOperation {
    abstract suspend fun execute()

    data class ImageEdit(val assetId: String, val operations: List<EditOperation>) : PendingOperation() {
        override suspend fun execute() { CreativeSuiteEngine.editImage(assetId, operations) }
    }

    data class ProjectSync(val projectId: String, val data: JSONObject) : PendingOperation() {
        override suspend fun execute() { NetworkClient.syncProject(projectId, "device", data) }
    }
}
