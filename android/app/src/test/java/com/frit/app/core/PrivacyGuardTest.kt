package com.frit.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the pure PrivacyScrubber redaction core (no Android deps).
 */
class PrivacyGuardTest {

    private fun maskAll(s: String) = PrivacyScrubber.scrub(s)

    @Test
    fun masksEmails() {
        val out = maskAll("contact me at john.doe@example.com please")
        assertFalse(out.contains("john.doe@example.com"))
        assertTrue(out.contains("[email]"))
    }

    @Test
    fun masksPhoneNumbers() {
        val out = maskAll("call +2348123456789 now")
        assertFalse(out.contains("2348123456789"))
        assertTrue(out.contains("[phone]"))
    }

    @Test
    fun masksLeadingZeroPhones() {
        val out = maskAll("my number is 08123456789")
        assertFalse(out.contains("08123456789"))
        assertTrue(out.contains("[phone]"))
    }

    @Test
    fun masksCreditCards() {
        val out = maskAll("card 4111 1111 1111 1111 expires soon")
        assertTrue(out.contains("[card]"))
        assertFalse(out.contains("4111 1111 1111 1111"))
    }

    @Test
    fun masksOtpCodes() {
        val out = maskAll("your verification code is 483920")
        assertFalse(out.contains("483920"))
        assertTrue(out.contains("[otp]"))
    }

    @Test
    fun masksIban() {
        val out = maskAll("bank GB29NWBK60161331926819")
        assertTrue(out.contains("[iban]"))
    }

    @Test
    fun leavesNormalTextUntouched() {
        val text = "What's the weather in Lagos today?"
        assertEquals(text, maskAll(text))
    }

    @Test
    fun leavesShortNumbersAlone() {
        // Short numbers like years / quantities must not be redacted.
        assertEquals("In 2024 he had 3 cats", maskAll("In 2024 he had 3 cats"))
    }

    @Test
    fun emptyOrBlankIsNoOp() {
        assertEquals("", maskAll(""))
        assertEquals("   ", maskAll("   "))
    }
}