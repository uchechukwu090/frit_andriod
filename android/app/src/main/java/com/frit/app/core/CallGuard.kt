package com.frit.app.core

/**
 * CallGuard — the confirmation gate for outgoing calls (audit F-18a).
 *
 * BEFORE this, `AutomationEngine.makeCall` fired `Intent.ACTION_CALL`, which
 * PLACES the call immediately: no dialer, no user gesture, nothing. Any
 * agent-initiated path could therefore place a call to an arbitrary number
 * without the user ever confirming it:
 *
 *   - the cloud brain's generic `execute_local_action` tool ("call 080...")
 *     bypassed SensitiveGate entirely, because SensitiveGate only knows the
 *     explicit `make_call` tool name;
 *   - the on-device LLM's own tool loop could emit a `call` tool on its own
 *     initiative (or be pushed into it by text on screen).
 *
 * This object holds the PURE decision logic so it can be JVM unit tested
 * (see app/src/test/java/com/frit/app/core/CallGuardTest.kt). The Android
 * side — actually opening the dialer vs placing the call — lives in
 * AutomationEngine and LocalInferenceEngine.
 *
 * The rule is deliberately conservative:
 *
 *   PLACE_NOW     the user's OWN utterance named this target -> place it.
 *   CONFIRM_FIRST the call was agent/automation-initiated -> open the DIALER
 *                 with the number pre-filled. The call is NOT placed; the
 *                 user taps the call button. That is the confirmation.
 *   DENY          the user turned calls off on this device -> do nothing.
 */
object CallGuard {

    enum class Decision {
        PLACE_NOW,
        CONFIRM_FIRST,
        DENY
    }

    /** A target word shorter than this is too generic to count as "named". */
    private const val MIN_TARGET_WORD = 3

    /** A number is considered "named" when at least this many digits are echoed. */
    private const val MIN_DIGITS = 7

    /**
     * Does the user's own message name this target?
     *
     * - any 3+ character word of the target appearing in the message
     *   ("call John" matches target "John Smith"; "call John" does NOT match
     *   target "John" + "Jonathan" when the message says only "Jonathan")
     * - or, for a number, the first 7 digits appearing in the message
     */
    fun namesTarget(userMessage: String?, target: String?): Boolean {
        val msg = userMessage?.lowercase()?.trim().orEmpty()
        val tgt = target?.lowercase()?.trim().orEmpty()
        if (msg.isEmpty() || tgt.isEmpty()) return false

        val words = tgt.split(Regex("\\s+")).filter { it.length >= MIN_TARGET_WORD }
        if (words.any { msg.contains(it) }) return true

        val digits = tgt.filter { it.isDigit() }
        return digits.length >= MIN_DIGITS && msg.contains(digits.take(MIN_DIGITS))
    }

    /**
     * Decide what to do with a call.
     *
     * @param userMessage the user's own utterance, or null/blank when the call
     *        came from an autonomous path (background task, agent tool, LLM
     *        tool loop). A null message ALWAYS means "confirm first" — an
     *        autonomous caller has no user intent to rely on.
     * @param target contact name or number being called
     * @param callsAllowed the device kill switch (frit_prefs `allow_calls`)
     */
    fun decide(userMessage: String?, target: String?, callsAllowed: Boolean): Decision {
        if (!callsAllowed) return Decision.DENY
        if (userMessage.isNullOrBlank()) return Decision.CONFIRM_FIRST
        return if (namesTarget(userMessage, target)) Decision.PLACE_NOW else Decision.CONFIRM_FIRST
    }

    /**
     * Extract the call target from a free-form local action such as
     * "call John", "ring +2348012345678" or "phone Dr Ada". Returns null when
     * the action is not a call.
     */
    fun callTargetFromAction(action: String?): String? {
        val t = action?.lowercase()?.trim().orEmpty()
        if (t.isEmpty()) return null
        val m = Regex("^(?:call|ring|dial|phone)\\s+(.+)$").find(t) ?: return null
        val target = m.groupValues[1].trim()
        return target.ifEmpty { null }
    }
}
