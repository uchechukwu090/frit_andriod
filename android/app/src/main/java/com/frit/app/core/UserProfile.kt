package com.frit.app.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * UserProfile — personal details collected once (first launch onboarding) and
 * edited any time in Settings → Personal Details.
 *
 * Extended set (per user choice): display name, preferred name, language,
 * city/country, birthday, occupation, work schedule, interests,
 * communication tone, verbosity, trading risk level, emergency contact.
 *
 * Auto-update contract:
 * - [learnFromText] scans a user message for explicit facts
 *   ("remember that…", "my name is…", "call me…", "I work as…",
 *   "I live in…", "my birthday is…", "my emergency contact is…",
 *   "I like…", "my language is…", "I'm a … trader") and persists them.
 * - Returns a short human-readable note ("Saved: …") when something was
 *   learned, or null when nothing matched — callers post the note in chat
 *   (auto-save + note, never silent).
 *
 * Everything lives in app-private `frit_prefs`; [clear] wipes it for
 * DataWipe / GDPR erasure. Nothing here leaves the phone except inside the
 * `user_profile` block of `device_state` for the cloud brain the user
 * explicitly chats with (same trust level as memory/screen text).
 */
object UserProfile {
    private const val PREFS = "frit_prefs"
    private const val KEY_ONBOARDED = "profile_onboarded"

    private const val K_NAME = "user_name"
    private const val K_NICK = "user_nickname"
    private const val K_LANG = "user_language"
    private const val K_CITY = "user_city"
    private const val K_COUNTRY = "user_country"
    private const val K_BIRTHDAY = "user_birthday"
    private const val K_JOB = "user_occupation"
    private const val K_SCHEDULE = "user_work_schedule"
    private const val K_INTERESTS = "user_interests"
    private const val K_TONE = "user_tone"
    private const val K_VERBOSE = "user_verbosity"
    private const val K_RISK = "user_trading_risk"
    private const val K_EMERGENCY = "user_emergency_contact"

    data class Profile(
        val displayName: String = "",
        val preferredName: String = "",
        val language: String = "",
        val city: String = "",
        val country: String = "",
        val birthday: String = "",
        val occupation: String = "",
        val workSchedule: String = "",
        val interests: String = "",
        val tone: String = "",
        val verbosity: String = "",
        val tradingRisk: String = "",
        val emergencyContact: String = ""
    )

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    private fun p(): SharedPreferences? = prefs

    fun isOnboarded(): Boolean =
        try { p()?.getBoolean(KEY_ONBOARDED, false) == true } catch (_: Exception) { false }

    fun setOnboarded(done: Boolean = true) {
        try { p()?.edit()?.putBoolean(KEY_ONBOARDED, done)?.apply() } catch (_: Exception) {}
    }

    fun load(): Profile {
        val s = p() ?: return Profile()
        return try {
            Profile(
                displayName = s.getString(K_NAME, "") ?: "",
                preferredName = s.getString(K_NICK, "") ?: "",
                language = s.getString(K_LANG, "") ?: "",
                city = s.getString(K_CITY, "") ?: "",
                country = s.getString(K_COUNTRY, "") ?: "",
                birthday = s.getString(K_BIRTHDAY, "") ?: "",
                occupation = s.getString(K_JOB, "") ?: "",
                workSchedule = s.getString(K_SCHEDULE, "") ?: "",
                interests = s.getString(K_INTERESTS, "") ?: "",
                tone = s.getString(K_TONE, "") ?: "",
                verbosity = s.getString(K_VERBOSE, "") ?: "",
                tradingRisk = s.getString(K_RISK, "") ?: "",
                emergencyContact = s.getString(K_EMERGENCY, "") ?: ""
            )
        } catch (_: Exception) { Profile() }
    }

    fun save(profile: Profile) {
        try {
            p()?.edit()
                ?.putString(K_NAME, profile.displayName.trim())
                ?.putString(K_NICK, profile.preferredName.trim())
                ?.putString(K_LANG, profile.language.trim())
                ?.putString(K_CITY, profile.city.trim())
                ?.putString(K_COUNTRY, profile.country.trim())
                ?.putString(K_BIRTHDAY, profile.birthday.trim())
                ?.putString(K_JOB, profile.occupation.trim())
                ?.putString(K_SCHEDULE, profile.workSchedule.trim())
                ?.putString(K_INTERESTS, profile.interests.trim())
                ?.putString(K_TONE, profile.tone.trim())
                ?.putString(K_VERBOSE, profile.verbosity.trim())
                ?.putString(K_RISK, profile.tradingRisk.trim())
                ?.putString(K_EMERGENCY, profile.emergencyContact.trim())
                ?.apply()
        } catch (_: Exception) {}
    }

    fun clear() {
        try {
            p()?.edit()
                ?.remove(K_NAME)?.remove(K_NICK)?.remove(K_LANG)
                ?.remove(K_CITY)?.remove(K_COUNTRY)?.remove(K_BIRTHDAY)
                ?.remove(K_JOB)?.remove(K_SCHEDULE)?.remove(K_INTERESTS)
                ?.remove(K_TONE)?.remove(K_VERBOSE)?.remove(K_RISK)
                ?.remove(K_EMERGENCY)?.remove(KEY_ONBOARDED)
                ?.apply()
        } catch (_: Exception) {}
    }

    /** Compact snapshot for `device_state.user_profile` (server prompt). */
    fun snapshotJson(): JSONObject {
        val pr = load()
        return JSONObject().apply {
            put("display_name", pr.displayName)
            put("preferred_name", pr.preferredName)
            put("language", pr.language)
            put("city", pr.city)
            put("country", pr.country)
            put("birthday", pr.birthday)
            put("occupation", pr.occupation)
            put("work_schedule", pr.workSchedule)
            put("interests", pr.interests)
            put("tone", pr.tone)
            put("verbosity", pr.verbosity)
            put("trading_risk", pr.tradingRisk)
            put("emergency_contact", pr.emergencyContact)
        }
    }

    /**
     * Short human-readable block for agent prompts (sweeps + device state).
     * Blank profile → "" so prompts stay clean before onboarding.
     */
    fun toPromptBlock(): String {
        val pr = load()
        val lines = mutableListOf<String>()
        val callAs = pr.preferredName.ifBlank { pr.displayName }
        if (callAs.isNotBlank()) lines.add("Name to call the user: $callAs")
        if (pr.displayName.isNotBlank() && pr.displayName != callAs) lines.add("Full name: ${pr.displayName}")
        if (pr.language.isNotBlank()) lines.add("Language: ${pr.language}")
        val loc = listOf(pr.city, pr.country).filter { it.isNotBlank() }.joinToString(", ")
        if (loc.isNotBlank()) lines.add("Location: $loc")
        if (pr.birthday.isNotBlank()) lines.add("Birthday: ${pr.birthday}")
        if (pr.occupation.isNotBlank()) lines.add("Occupation: ${pr.occupation}")
        if (pr.workSchedule.isNotBlank()) lines.add("Work schedule: ${pr.workSchedule}")
        if (pr.interests.isNotBlank()) lines.add("Interests: ${pr.interests}")
        if (pr.tone.isNotBlank()) lines.add("Preferred tone (baseline): ${pr.tone}")
        if (pr.verbosity.isNotBlank()) lines.add("Reply length (baseline): ${pr.verbosity}")
        if (pr.tradingRisk.isNotBlank()) lines.add("Trading risk: ${pr.tradingRisk}")
        if (pr.emergencyContact.isNotBlank()) {
            // Redact digits before the block reaches the cloud brain — the
            // local dialer resolves contacts, the brain only needs to know one exists.
            lines.add("Emergency contact: ${PrivacyGuard.redact(pr.emergencyContact)}")
        }
        if (lines.isEmpty()) return ""
        return "User profile (from Settings → Personal Details; baseline only — the live chat tone always wins):\n" +
            lines.joinToString("\n") { "- $it" }
    }

    // ---------- Auto-learn from user messages ----------
    // Only explicit statements ("remember…", "my X is…") — never guesses.

    private val rememberRe = Regex("^\\s*(please\\s+)?remember\\s+(that\\s+)?(.+)", RegexOption.IGNORE_CASE)
    private val nameIsRe = Regex("my name is\\s+([a-zA-Z][a-zA-Z .'-]{1,40})", RegexOption.IGNORE_CASE)
    private val callMeRe = Regex("call me\\s+([a-zA-Z][a-zA-Z .'-]{1,40})", RegexOption.IGNORE_CASE)
    private val iAmNameRe = Regex("^\\s*i['’]m\\s+([A-Z][a-z]+)\\s*$")
    private val jobRe = Regex("(i work as|i['’]m a|i am a|my job is|my occupation is|i work at)\\s+(.+)", RegexOption.IGNORE_CASE)
    private val liveRe = Regex("(i live in|i['’]m from|i am from|my location is|i stay in)\\s+(.+)", RegexOption.IGNORE_CASE)
    private val birthdayRe = Regex("(my birthday is|my date of birth is|i was born on|born on)\\s+(.+)", RegexOption.IGNORE_CASE)
    private val emergencyRe = Regex("(my emergency contact is|emergency contact:?|my next of kin is)\\s*(.+)", RegexOption.IGNORE_CASE)
    private val interestsRe = Regex("(i like|i love|i['’]m interested in|i am interested in|my interests? (are|is)|my hobbies? (are|is))\\s+(.+)", RegexOption.IGNORE_CASE)
    private val languageRe = Regex("(my language is|speak to me in|talk to me in|reply in|respond in)\\s+([a-zA-Z ]{3,30})", RegexOption.IGNORE_CASE)
    private val riskRe = Regex("i['’]?m an?\\s+(conservative|moderate|aggressive)\\s+trader", RegexOption.IGNORE_CASE)
    private val scheduleRe = Regex("(i work|my (work|office) hours? (are|is)|my schedule is)\\s+(.+)", RegexOption.IGNORE_CASE)
    private val toneRe = Regex("(talk to me|speak to me|be)\\s+(more\\s+)?(formal|casual|friendly|professional|official|funny|short|brief|detailed)", RegexOption.IGNORE_CASE)

    /**
     * Scan [text] for an explicit personal fact, persist it, and return a
     * one-line confirmation for chat ("Saved: …"). Null = nothing learned.
     * Callers also mirror the fact into MemoryEngine so relevant() retrieval
     * keeps working for older prompts.
     */
    fun learnFromText(text: String): String? {
        val t = text.trim()
        if (t.isEmpty() || t.length > 600) return null
        val cur = load()
        val ed = p()?.edit() ?: return null

        fun put(key: String, value: String, label: String): String {
            ed.putString(key, value.trim().take(120)).apply()
            return "Saved: $label — ${value.trim().take(80)}"
        }

        // "remember that I work as a nurse" → try to route into a field first,
        // fall back to a generic memory fact (caller adds it to MemoryEngine).
        rememberRe.find(t)?.let { m ->
            val fact = m.groupValues[3].trim().trimEnd('.')
            if (fact.isEmpty()) return null
            // Re-run the inner fact through the field matchers ("my name is…").
            val inner = learnFromText(fact)
            if (inner != null) return inner
            return "@@GENERIC@@$fact"
        }
        callMeRe.find(t)?.let { m ->
            val v = m.groupValues[1].trim().trimEnd('.', ',', '!')
            if (v.isNotBlank() && v.equals(cur.preferredName, ignoreCase = true).not()) {
                return put(K_NICK, v, "I'll call you $v")
            }
            return null
        }
        nameIsRe.find(t)?.let { m ->
            val v = m.groupValues[1].trim().trimEnd('.', ',', '!')
            if (v.isNotBlank() && v.equals(cur.displayName, ignoreCase = true).not()) {
                return put(K_NAME, v, "your name is $v")
            }
            return null
        }
        iAmNameRe.find(t)?.let { m ->
            val v = m.groupValues[1].trim()
            if (v.isNotBlank() && cur.displayName.isBlank() && cur.preferredName.isBlank()) {
                return put(K_NICK, v, "I'll call you $v")
            }
            return null
        }
        jobRe.find(t)?.let { m ->
            val v = m.groupValues[2].trim().trimEnd('.', ',', '!')
            if (v.isNotBlank() && v.length <= 120) return put(K_JOB, v, "occupation")
            return null
        }
        liveRe.find(t)?.let { m ->
            val v = m.groupValues[2].trim().trimEnd('.', ',', '!')
            if (v.isNotBlank() && v.length <= 120) return put(K_CITY, v, "location")
            return null
        }
        birthdayRe.find(t)?.let { m ->
            val v = m.groupValues[2].trim().trimEnd('.', ',', '!')
            if (v.isNotBlank() && v.length <= 60) return put(K_BIRTHDAY, v, "birthday")
            return null
        }
        emergencyRe.find(t)?.let { m ->
            val v = m.groupValues[2].trim().trimEnd('.', ',', '!')
            if (v.isNotBlank() && v.length <= 120) return put(K_EMERGENCY, v, "emergency contact")
            return null
        }
        interestsRe.find(t)?.let { m ->
            val v = m.groupValues[3].trim().trimEnd('.', ',', '!')
            if (v.isNotBlank() && v.length <= 200) {
                val merged = if (cur.interests.isBlank()) v else "${cur.interests}, $v"
                return put(K_INTERESTS, merged.take(300), "interests")
            }
            return null
        }
        languageRe.find(t)?.let { m ->
            val v = m.groupValues[2].trim().trimEnd('.', ',', '!')
            if (v.isNotBlank() && v.length <= 30) return put(K_LANG, v, "language")
            return null
        }
        riskRe.find(t)?.let { m ->
            val v = m.groupValues[1].trim().replaceFirstChar { it.uppercase() }
            return put(K_RISK, v, "trading risk")
        }
        scheduleRe.find(t)?.let { m ->
            val v = m.groupValues[3].trim().trimEnd('.', ',', '!')
            if (v.isNotBlank() && v.length <= 120) return put(K_SCHEDULE, v, "work schedule")
            return null
        }
        toneRe.find(t)?.let { m ->
            val v = m.groupValues[3].trim().replaceFirstChar { it.uppercase() }
            return put(K_TONE, v, "tone")
        }
        return null
    }
}
