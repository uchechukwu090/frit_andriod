package com.frit.app.data

/**
 * Artifact — a file generated server-side (charts, CSVs, backtest reports)
 * and surfaced to the client in the /agent/start and /agent/resume responses.
 */
data class Artifact(
    val name: String,
    val mime: String,
    val size: Long,
    val base64: String? = null,
    val content: String? = null
) {
    val isImage: Boolean get() = mime.startsWith("image/") || name.endsWith(".png", true) || name.endsWith(".jpg", true) || name.endsWith(".jpeg", true)
}