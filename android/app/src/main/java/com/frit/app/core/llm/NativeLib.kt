package com.frit.app.core.llm

/**
 * JNI bridge to llama.cpp native library.
 * Compiled from android/app/src/main/jni/llama_jni.cpp via NDK.
 */
object NativeLib {
    private var loaded = false

    fun ensureLoaded() {
        if (!loaded) {
            try {
                System.loadLibrary("llama_jni")
                loaded = true
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.w("NativeLib", "llama_jni not available: ${e.message}")
            }
        }
    }

    fun isAvailable(): Boolean = loaded

    /** Load a GGUF model from file path. Returns true on success. */
    external fun loadModel(modelPath: String, nCtx: Int = 2048): Boolean

    /** Unload the current model and free memory. */
    external fun unloadModel()

    /** Check if a model is currently loaded. */
    external fun isLoaded(): Boolean

    /** Generate text from prompt (blocking). Returns generated text. */
    external fun generate(prompt: String, maxTokens: Int = 512, temperature: Float = 0.7f): String

    /** Generate text with streaming callback. */
    external fun generateStream(prompt: String, maxTokens: Int = 512, temperature: Float = 0.7f): String

    /** Get model metadata. */
    external fun getModelInfo(): String

    /** Why the last load/generate call failed ("" when it didn't). */
    external fun lastError(): String

    /**
     * Format a conversation with the chat template embedded in the loaded GGUF.
     * Returns "" when the model has no template llama.cpp can apply.
     */
    external fun formatChat(roles: Array<String>, contents: Array<String>): String
}
