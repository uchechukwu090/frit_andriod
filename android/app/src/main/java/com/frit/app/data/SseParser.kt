package com.frit.app.data

import org.json.JSONObject

/**
 * SseParser — a tiny, pure Server-Sent Events parser.
 *
 * The server streams the assistant's reply as `text/event-stream`. The events
 * arrive in arbitrary chunks, so a single OkHttp read can contain half an
 * event, several whole events, or an event split across two reads. This
 * parser accumulates the raw text and hands back only the COMPLETE events,
 * keeping the remainder for the next read.
 *
 * It is pure (no Android, no network) so it can be JVM unit tested — see
 * app/src/test/java/com/frit/app/data/SseParserTest.kt.
 */
class SseParser {

    private val buffer = StringBuilder()

    /**
     * Feed a raw chunk and get back the events it completed.
     *
     * @return a list of [SseEvent] (event name + data payload)
     */
    fun feed(chunk: String): List<SseEvent> {
        if (chunk.isEmpty()) return emptyList()
        buffer.append(chunk)
        val text = buffer.toString()
        // Normalise line endings: a "\r\n" split across two reads would
        // otherwise leave a stray "\r" at the end of a data line.
        val normalised = text.replace("\r\n", "\n").replace("\r", "\n")
        val parts = normalised.split("\n\n").toMutableList()
        // The last part is either empty (the chunk ended on a boundary) or an
        // incomplete event that must wait for more bytes.
        buffer.setLength(0)
        buffer.append(parts.removeAt(parts.size - 1))
        val out = ArrayList<SseEvent>()
        for (part in parts) {
            parseBlock(part)?.let { out.add(it) }
        }
        return out
    }

    /** Flush any trailing partial event (the stream ended without a blank line). */
    fun flush(): List<SseEvent> {
        if (buffer.isEmpty()) return emptyList()
        val part = buffer.toString()
        buffer.setLength(0)
        val ev = parseBlock(part) ?: return emptyList()
        return listOf(ev)
    }

    private fun parseBlock(block: String): SseEvent? {
        val lines = block.split("\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        var name = "message"
        val data = StringBuilder()
        var sawData = false
        for (line in lines) {
            if (line.startsWith(":")) continue // comment / keep-alive
            val idx = line.indexOf(":")
            if (idx == -1) continue
            val field = line.substring(0, idx).trim()
            var value = line.substring(idx + 1)
            if (value.startsWith(" ")) value = value.substring(1)
            when (field) {
                "event" -> name = value
                "data" -> {
                    if (sawData) data.append("\n")
                    data.append(value)
                    sawData = true
                }
            }
        }
        if (!sawData) return null
        return SseEvent(name, data.toString())
    }
}

/** One parsed Server-Sent Event. */
data class SseEvent(val event: String, val data: String) {
    /** The JSON payload of the event, or null when it is not valid JSON. */
    fun json(): JSONObject? = try { JSONObject(data) } catch (_: Exception) { null }

    /** The text delta carried by a "delta" event, or "" for other events. */
    fun deltaText(): String {
        val o = json() ?: return ""
        return o.optString("text", "")
    }
}
