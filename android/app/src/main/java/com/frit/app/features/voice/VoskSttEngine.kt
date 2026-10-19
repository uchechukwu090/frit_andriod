package com.frit.app.features.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream

/**
 * VoskSttEngine — bundled on-device offline speech recognition fallback.
 * Uses the Vosk tiny/small model (~40MB-150MB peak RAM) to provide 100% offline
 * speech-to-text when server Whisper is unreachable and system STT packs are missing.
 */
object VoskSttEngine {
    private const val TAG = "VoskSttEngine"
    private const val MODEL_ASSET_DIR = "vosk-model-small-en-us-0.15"
    private const val MODEL_FOLDER_NAME = "vosk-model"

    @Volatile private var voskModel: Model? = null
    @Volatile private var isInitializing = false

    fun isModelReady(context: Context): Boolean {
        if (voskModel != null) return true
        val modelDir = getModelDir(context)
        return modelDir.exists() && (File(modelDir, "am/final.mdl").exists() || File(modelDir, "final.mdl").exists())
    }

    suspend fun init(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (voskModel != null) return@withContext true
        if (isInitializing) return@withContext false
        isInitializing = true
        return@withContext try {
            val ctx = context.applicationContext
            val modelDir = getModelDir(ctx)
            val modelFile = File(modelDir, "am/final.mdl")
            val modelFileAlt = File(modelDir, "final.mdl")

            if (!modelDir.exists() || (!modelFile.exists() && !modelFileAlt.exists())) {
                unpackModelFromAssets(ctx, modelDir)
            }

            if (modelDir.exists() && (File(modelDir, "am/final.mdl").exists() || File(modelDir, "final.mdl").exists())) {
                voskModel = Model(modelDir.absolutePath)
                Log.i(TAG, "Vosk model loaded successfully from ${modelDir.absolutePath}")
                true
            } else {
                Log.w(TAG, "Vosk model files not found at ${modelDir.absolutePath}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Vosk model", e)
            false
        } finally {
            isInitializing = false
        }
    }

    /** Transcribe PCM 16-bit short array directly using Vosk */
    suspend fun transcribePcm(
        context: Context,
        shorts: ShortArray,
        sampleRate: Int = 16000
    ): String? = withContext(Dispatchers.IO) {
        try {
            if (shorts.isEmpty()) return@withContext null
            if (!init(context)) return@withContext null

            val model = voskModel ?: return@withContext null
            val recognizer = Recognizer(model, sampleRate.toFloat())
            recognizer.acceptWaveForm(shorts, shorts.size)
            val jsonStr = recognizer.finalResult
            recognizer.close()

            val text = runCatching { JSONObject(jsonStr).optString("text", "") }.getOrDefault("")
            if (text.isNotBlank()) text else null
        } catch (e: Exception) {
            Log.e(TAG, "Vosk transcription error", e)
            null
        }
    }

    private fun getModelDir(ctx: Context): File {
        return File(ctx.filesDir, MODEL_FOLDER_NAME)
    }

    private fun unpackModelFromAssets(ctx: Context, targetDir: File) {
        try {
            val assetManager = ctx.assets
            var sourceAssetDir = MODEL_ASSET_DIR
            var list = runCatching { assetManager.list(sourceAssetDir) }.getOrNull()
            if (list.isNullOrEmpty()) {
                sourceAssetDir = MODEL_FOLDER_NAME
                list = runCatching { assetManager.list(sourceAssetDir) }.getOrNull()
            }
            if (list.isNullOrEmpty()) {
                sourceAssetDir = "model"
                list = runCatching { assetManager.list(sourceAssetDir) }.getOrNull()
            }
            if (list.isNullOrEmpty()) return

            targetDir.mkdirs()
            copyAssetFolder(assetManager, sourceAssetDir, targetDir)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unpack Vosk assets", e)
        }
    }

    private fun copyAssetFolder(assetManager: android.content.res.AssetManager, assetDir: String, targetDir: File) {
        val files = assetManager.list(assetDir) ?: return
        if (files.isEmpty()) {
            runCatching {
                assetManager.open(assetDir).use { input ->
                    FileOutputStream(targetDir).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        } else {
            if (!targetDir.exists()) targetDir.mkdirs()
            for (file in files) {
                copyAssetFolder(assetManager, "$assetDir/$file", File(targetDir, file))
            }
        }
    }
}
