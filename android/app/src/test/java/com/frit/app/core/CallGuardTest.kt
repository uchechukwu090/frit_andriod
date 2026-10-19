package com.frit.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the F-18a call confirmation gate (pure logic, no Android deps).
 */
class CallGuardTest {

    // --- namesTarget ---

    @Test
    fun namesTargetMatchesAnExplicitName() {
        assertTrue(CallGuard.namesTarget("call John now", "John"))
    }

    @Test
    fun namesTargetMatchesAFullName() {
        assertTrue(CallGuard.namesTarget("please call Dr Ada Lovelace", "Ada Lovelace"))
    }

    @Test
    fun namesTargetRejectsAnUnnamedTarget() {
        assertFalse(CallGuard.namesTarget("call John now", "Sarah"))
    }

    @Test
    fun namesTargetIsCaseInsensitive() {
        assertTrue(CallGuard.namesTarget("CALL JOHN", "john"))
    }

    @Test
    fun namesTargetIgnoresVeryShortWords() {
        // "Jo" is 2 characters — too generic to count as naming the target.
        assertFalse(CallGuard.namesTarget("call Jo", "Jo"))
    }

    @Test
    fun namesTargetMatchesSevenDigitsOfANumber() {
        assertTrue(CallGuard.namesTarget("call 08012345678 now", "08012345678"))
    }

    @Test
    fun namesTargetRejectsAPartialNumber() {
        assertFalse(CallGuard.namesTarget("call 080 now", "08012345678"))
    }

    @Test
    fun namesTargetHandlesNullAndBlank() {
        assertFalse(CallGuard.namesTarget(null, "John"))
        assertFalse(CallGuard.namesTarget("", "John"))
        assertFalse(CallGuard.namesTarget("call John", null))
        assertFalse(CallGuard.namesTarget("call John", ""))
    }

    // --- decide ---

    @Test
    fun userNamingTheTargetPlacesTheCallNow() {
        assertEquals(
            CallGuard.Decision.PLACE_NOW,
            CallGuard.decide("call John", "John", callsAllowed = true)
        )
    }

    @Test
    fun anUnnamedTargetOnlyConfirms() {
        assertEquals(
            CallGuard.Decision.CONFIRM_FIRST,
            CallGuard.decide("call John", "Sarah", callsAllowed = true)
        )
    }

    @Test
    fun anAutonomousCallerAlwaysConfirms() {
        // No user utterance at all -> never place a call outright.
        assertEquals(
            CallGuard.Decision.CONFIRM_FIRST,
            CallGuard.decide(null, "John", callsAllowed = true)
        )
        assertEquals(
            CallGuard.Decision.CONFIRM_FIRST,
            CallGuard.decide("   ", "John", callsAllowed = true)
        )
    }

    @Test
    fun theKillSwitchAlwaysWins() {
        assertEquals(
            CallGuard.Decision.DENY,
            CallGuard.decide("call John", "John", callsAllowed = false)
        )
        assertEquals(
            CallGuard.Decision.DENY,
            CallGuard.decide(null, "John", callsAllowed = false)
        )
    }

    // --- callTargetFromAction ---

    @Test
    fun extractsTheTargetFromCallActions() {
        assertEquals("john", CallGuard.callTargetFromAction("call John"))
        assertEquals("+2348012345678", CallGuard.callTargetFromAction("ring +2348012345678"))
        assertEquals("dr ada", CallGuard.callTargetFromAction("dial Dr Ada"))
        assertEquals("0812", CallGuard.callTargetFromAction("phone 0812"))
    }

    @Test
    fun returnsNullForNonCallActions() {
        assertEquals(null, CallGuard.callTargetFromAction("open settings"))
        assertEquals(null, CallGuard.callTargetFromAction(""))
        assertEquals(null, CallGuard.callTargetFromAction(null))
        // "caller" must not be mistaken for "call <target>".
        assertEquals(null, CallGuard.callTargetFromAction("caller id"))
    }
}
