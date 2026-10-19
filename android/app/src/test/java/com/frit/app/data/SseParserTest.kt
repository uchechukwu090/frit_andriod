package com.frit.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the SSE parser (pure logic, no Android/network deps).
 */
class SseParserTest {

    @Test
    fun parsesASingleEvent() {
        val p = SseParser()
        val events = p.feed("event: delta\ndata: {\"text\":\"Hi\"}\n\n")
        assertEquals(1, events.size)
        assertEquals("delta", events[0].event)
        assertEquals("Hi", events[0].deltaText())
    }

    @Test
    fun parsesSeveralEventsFromOneChunk() {
        val p = SseParser()
        val events = p.feed("event: delta\ndata: {\"text\":\"a\"}\n\nevent: delta\ndata: {\"text\":\"b\"}\n\n")
        assertEquals(2, events.size)
        assertEquals("a", events[0].deltaText())
        assertEquals("b", events[1].deltaText())
    }

    @Test
    fun keepsAnIncompleteEventUntilMoreArrives() {
        val p = SseParser()
        val first = p.feed("event: delta\ndata: {\"tex")
        assertTrue(first.isEmpty())
        val second = p.feed("t\":\"Hello\"}\n\n")
        assertEquals(1, second.size)
        assertEquals("Hello", second[0].deltaText())
    }

    @Test
    fun ignoresCommentsAndKeepAlives() {
        val p = SseParser()
        val events = p.feed(": keep-alive\n\nevent: delta\ndata: {\"text\":\"x\"}\n\n")
        assertEquals(1, events.size)
        assertEquals("x", events[0].deltaText())
    }

    @Test
    fun handlesCrLfLineEndings() {
        val p = SseParser()
        val events = p.feed("event: delta\r\ndata: {\"text\":\"x\"}\r\n\r\n")
        assertEquals(1, events.size)
        assertEquals("x", events[0].deltaText())
    }

    @Test
    fun defaultsTheEventNameToMessage() {
        val p = SseParser()
        val events = p.feed("data: {\"text\":\"x\"}\n\n")
        assertEquals("message", events[0].event)
    }

    @Test
    fun joinsMultiLineDataFields() {
        val p = SseParser()
        val events = p.feed("data: line1\ndata: line2\n\n")
        assertEquals(1, events.size)
        assertTrue(events[0].data.contains("line1"))
        assertTrue(events[0].data.contains("line2"))
    }

    @Test
    fun flushReturnsATrailingPartialEvent() {
        val p = SseParser()
        p.feed("event: delta\ndata: {\"text\":\"tail\"}")
        val flushed = p.flush()
        assertEquals(1, flushed.size)
        assertEquals("tail", flushed[0].deltaText())
    }

    @Test
    fun deltaTextIsEmptyForANonJsonPayload() {
        val p = SseParser()
        val events = p.feed("event: error\ndata: not json\n\n")
        assertEquals("", events[0].deltaText())
    }
}
