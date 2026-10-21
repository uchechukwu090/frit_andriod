package com.frit.app.ui.creative.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the creative-suite media normaliser.
 *
 * This is the fix for "the creative suite does not preview images": the server
 * returns `image_b64` / `video_base64` as a RAW base64 blob with no `data:`
 * prefix, and org.json's `optString` on a JSON null returns the literal
 * string "null". Both fed straight into the image loader, so the preview was
 * either unloadable or showed nothing.
 */
class CreativeImageTest {

    @Test
    fun keepsADataUriAsIs() {
        val uri = "data:image/png;base64,iVBORw0KGgo="
        assertEquals(uri, normalizeMediaUrl(uri))
    }

    @Test
    fun keepsHttpUrlsAsIs() {
        assertEquals("https://cdn.example.com/a.png", normalizeMediaUrl("https://cdn.example.com/a.png"))
        assertEquals("http://cdn.example.com/a.png", normalizeMediaUrl("http://cdn.example.com/a.png"))
    }

    @Test
    fun addsTheDataPrefixToARawBase64Blob() {
        val b64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB"
        assertEquals("data:image/png;base64,$b64", normalizeMediaUrl(b64))
    }

    @Test
    fun usesTheVideoMimeWhenAsked() {
        val b64 = "AAAAIGZ0eXBpc29t"
        assertEquals("data:video/mp4;base64,$b64", normalizeMediaUrl(b64, MIME_VIDEO))
    }

    @Test
    fun treatsTheLiteralNullStringAsAbsent() {
        // org.json's optString on a JSON null returns "null" — loading that
        // as a URL is what broke the previews.
        assertNull(normalizeMediaUrl("null"))
        assertNull(normalizeMediaUrl("NULL"))
        assertNull(normalizeMediaUrl(""))
        assertNull(normalizeMediaUrl("   "))
        assertNull(normalizeMediaUrl(null))
    }

    @Test
    fun recognisesABareBase64Blob() {
        assertTrue(isBareBase64("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk"))
        assertFalse(isBareBase64("data:image/png;base64,iVBORw0KGgo="))
        assertFalse(isBareBase64("https://cdn.example.com/a.png"))
        assertFalse(isBareBase64("null"))
        assertFalse(isBareBase64(""))
        // Too short to be a payload - more likely a stray identifier.
        assertFalse(isBareBase64("c2hvcnQ="))
    }

    @Test
    fun keepsFileAndContentUris() {
        assertEquals("file:///tmp/a.png", normalizeMediaUrl("file:///tmp/a.png"))
        assertEquals("content://media/1", normalizeMediaUrl("content://media/1"))
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertEquals("https://x/a.png", normalizeMediaUrl("  https://x/a.png  "))
    }
}
