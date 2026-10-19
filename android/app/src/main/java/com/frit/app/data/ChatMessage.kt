package com.frit.app.data
/** One quoted message attached as reply-context (Messenger/WhatsApp style). */
data class ReplyQuote(
    val role: String,
    val text: String,
)
data class ChatMessage(
    val role: String,
    val content: String,
    val imageBase64: String? = null,
    val artifacts: List<Artifact> = emptyList(),
    val isStreaming: Boolean = false,
    val replyQuotes: List<ReplyQuote> = emptyList(),
)