package com.frit.app.features.creative

import org.json.JSONArray
import org.json.JSONObject

/**
 * A JSON array of scalars as a list of non-blank strings.
 *
 * Lives at the top level (not inside a companion) because both the capabilities
 * parser and the Design Studio's template list need it — the Design Studio used
 * to import a `strList` that had moved into this file's companion, which is what
 * broke the build.
 */
fun strList(arr: JSONArray?): List<String> {
    if (arr == null) return emptyList()
    val out = mutableListOf<String>()
    for (i in 0 until arr.length()) {
        val v = arr.opt(i) ?: continue
        val s = v.toString().trim()
        if (s.isNotEmpty() && s != "null") out += s
    }
    return out
}

/**
 * CapabilitySync — what the ACTIVE creative model can actually do.
 *
 * The studios used to hardcode their own option lists (Generate always offered
 * 4:5 even when the model rejects it; video always asked for 4s/720p whatever
 * the model supported). The server resolves the live model per role from
 * GET /creative-suite/capabilities, which is derived from the provider's own
 * model metadata, and the UI renders only those options.
 *
 * Rules: * - Empty list = "unknown", not "nothing". Falls back to the documented
 *    defaults so the UI never renders zero chips.
 * - Image-input features (mood-board, logo) are gated on [reference], and the
 *   Design Studio now explains what happens when references are picked.
 * - Never silently send an unsupported value: [pickAspect] / [pickDuration] /
 *   [pickResolution] snap to the nearest supported choice, and the screens
 *   show which model is in charge.
 */
data class RoleCaps(
    val role: String = "",
    val model: String = "",
    val label: String = "",
    val kinds: List<String> = listOf("image"),
    val aspects: List<String> = emptyList(),
    val resolutions: List<String> = emptyList(),
    val durations: List<Int> = emptyList(),
    val maxN: Int = 1,
    val reference: Boolean = false,
    val audio: Boolean? = null,
    val source: String = "fallback",
) {
    fun supportsAspect(a: String?): Boolean =
        a.isNullOrBlank() || aspects.isEmpty() || aspects.contains(a)

    /** Preferred if supported, else the closest sensible supported ratio. */
    fun pickAspect(preferred: String? = null): String? {
        if (aspects.isEmpty()) return preferred
        preferred?.let { p -> picksAspectLike(p)?.let { return it } }
        return aspects.firstOrNull()
    }

    private fun picksAspectLike(p: String): String? {
        if (aspects.contains(p)) return p
        val want = p.split(":").mapNotNull { it.trim().toDoubleOrNull() }
        if (want.size != 2 || want[0] == 0.0) return null
        val target = want[1] / want[0]
        return aspects.minByOrNull { a ->
            val v = a.split(":").mapNotNull { it.trim().toDoubleOrNull() }
            if (v.size != 2 || v[0] == 0.0) Double.MAX_VALUE else kotlin.math.abs(v[1] / v[0] - target)
        }
    }

    /** Nearest supported duration (2..30s) to what the studio asked for. */
    fun pickDuration(preferred: Int? = null): Int? {
        if (durations.isEmpty()) return preferred
        val want = preferred ?: durations.min()
        return durations.minByOrNull { kotlin.math.abs(it - want) }
    }

    /**
     * Nearest supported resolution. Asking for 720p on a model that only lists
     * 480p/768p/2K upgrades to 768p — it does NOT silently drop to 480p, which
     * would quietly halve the quality of every render.
     */
    fun pickResolution(preferred: String? = null): String? {
        if (resolutions.isEmpty()) return preferred
        if (preferred == null) return resolutions.maxByOrNull { pixels(it) ?: 0.0 } ?: resolutions.first()
        if (resolutions.contains(preferred)) return preferred
        val want = pixels(preferred) ?: return resolutions.maxByOrNull { pixels(it) ?: 0.0 } ?: resolutions.first()
        val atLeast = resolutions
            .mapNotNull { r -> pixels(r)?.let { r to it } }
            .filter { it.second >= want }
            .minByOrNull { it.second }
        return atLeast?.first
            ?: resolutions.maxByOrNull { pixels(it) ?: 0.0 }
            ?: resolutions.first()
    }

    /** "480p" -> 480, "2K" -> 2000, "1080p" -> 1080; null when unparseable. */
    private fun pixels(label: String): Double? {
        val t = label.trim().lowercase()
        val m = Regex("^([0-9]+(?:\\.[0-9]+)?)([pk])?$").find(t) ?: return null
        val n = m.groupValues[1].toDoubleOrNull() ?: return null
        return if (m.groupValues[2] == "k") n * 1000 else n
    }

    /**
     * One-line capability summary used by Settings and the studio footers.
     * Long provider lists (a model with 27 durations) are summarised, not
     * dumped — the footer is a caption, not the picker.
     */
    val summary: String
        get() {
            val bits = mutableListOf<String>()
            if (aspects.isNotEmpty()) {
                bits += if (aspects.size <= 6) aspects.joinToString(" ") else "${aspects.size} ratios"
            }
            if (durations.isNotEmpty()) {
                bits += if (durations.size <= 5) durations.joinToString("/") + "s"
                else "${durations.min()}-${durations.max()}s"
            }
            if (resolutions.isNotEmpty()) bits += resolutions.joinToString("/")
            return bits.joinToString(" · ")
        }
}

data class CreativeCapabilities(
    val roles: Map<String, RoleCaps> = emptyMap(),
    val imagesLive: Boolean = false,
    val videosLive: Boolean = false,
) {
    fun role(name: String): RoleCaps = roles[name] ?: when (name) {
        "video", "genjutsu", "avatar" -> FALLBACK_VIDEO.copy(role = name)
        else -> FALLBACK_IMAGE.copy(role = name)
    }

    val image: RoleCaps get() = role("image")
    val video: RoleCaps get() = role("video")
    val design: RoleCaps get() = role("design")

    companion object {
        val FALLBACK_IMAGE = RoleCaps(
            role = "image", label = "image model",
            aspects = listOf("1:1", "4:3", "3:4", "16:9", "9:16"),
            maxN = 1, reference = true, source = "fallback",
        )
        val FALLBACK_VIDEO = RoleCaps(
            role = "video", label = "video model", kinds = listOf("video"),
            aspects = listOf("16:9", "9:16", "1:1"),
            resolutions = listOf("720p"), durations = listOf(4),
            source = "fallback",
        )
        val FALLBACK = CreativeCapabilities(
            roles = mapOf(
                "image" to FALLBACK_IMAGE,
                "design" to FALLBACK_IMAGE.copy(role = "design"),
                "video" to FALLBACK_VIDEO,
                "genjutsu" to FALLBACK_VIDEO.copy(role = "genjutsu"),
                "avatar" to FALLBACK_VIDEO.copy(role = "avatar"),
            )
        )

        /** Parse the server payload. Null when it is not the expected shape. */
        fun fromJson(raw: String?): CreativeCapabilities? {
            if (raw.isNullOrBlank()) return null
            return try {
                val o = JSONObject(raw)
                val rolesObj = o.optJSONObject("roles") ?: return null
                val src = o.optJSONObject("source")
                val out = mutableMapOf<String, RoleCaps>()
                for (key in rolesObj.keys()) {
                    val r = rolesObj.optJSONObject(key) ?: continue
                    out[key] = RoleCaps(
                        role = r.optString("role", key),
                        model = r.optString("model", ""),
                        label = r.optString("label", r.optString("model", "")),
                        kinds = strList(r.optJSONArray("kinds")).ifEmpty { listOf("image") },
                        aspects = strList(r.optJSONArray("aspects")),
                        resolutions = strList(r.optJSONArray("resolutions")),
                        durations = strList(r.optJSONArray("durations"))
                            .mapNotNull { it.toDoubleOrNull()?.toInt() }
                            .filter { it > 0 },
                        maxN = r.optInt("maxN", 1).coerceAtLeast(1),
                        reference = r.optBoolean("reference", false),
                        audio = if (r.has("audio") && !r.isNull("audio")) r.optBoolean("audio") else null,
                        source = r.optString("source", "fallback"),
                    )
                }
                if (out.isEmpty()) return null
                CreativeCapabilities(
                    roles = out,
                    imagesLive = src?.optString("images") == "live",
                    videosLive = src?.optString("videos") == "live",
                )
            } catch (_: Exception) {
                null
            }
        }

    }
}
