package com.frit.app.ui.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frit.app.ui.theme.Montserrat

/**
 * Chat history list, split out of MainActivity.kt (audit F-14).
 *
 * ChatsScreen lists archived conversations; exportChat writes one as a
 * shareable .txt file.
 */
/** Export a chat as shareable .txt (cache + system share sheet). */
fun exportChat(context: android.content.Context, title: String, messages: List<com.frit.app.data.ChatMessage>) {
    try {
        val safe = title.ifBlank { "Chat" }.replace(Regex("[^A-Za-z0-9-_ ]"), "").trim().take(40).ifBlank { "Chat" }
        val sb = StringBuilder("Frit chat export — $safe\n")
        sb.append("=".repeat(32)).append("\n")
        messages.forEach { m ->
            if (m.content.isBlank()) return@forEach
            sb.append(if (m.role == "user") "\nYou: " else "\nFrit: ").append(m.content.trim()).append("\n")
        }
        val dir = java.io.File(context.cacheDir, "exports").also { it.mkdirs() }
        val file = java.io.File(dir, "frit-chat-${System.currentTimeMillis()}.txt")
        file.writeText(sb.toString())
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(share, "Export chat"))
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "Export failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
    }
}

@Composable
fun ChatsScreen(
    fg: Color, surf: Color, stroke: Color, bg: Color,
    onClose: () -> Unit,
    onOpen: (String) -> Unit,
    onNew: () -> Unit
) {
    val context = LocalContext.current
    val chatsList = remember { mutableStateListOf<com.frit.app.core.ChatHistory.SavedChat>() }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        chatsList.clear()
        chatsList.addAll(com.frit.app.core.ChatHistory.snapshot())
    }
    val visibleChats = if (expanded) chatsList else chatsList.take(5)
    Column(Modifier.fillMaxSize().background(bg).padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Chats", color = fg, fontSize = 24.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = fg) }
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onNew, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = fg)) {
            Text("Start a new chat", color = bg)
        }
        Spacer(Modifier.height(20.dp))
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(visibleChats, key = { _, c -> c.id }) { _, c ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(c.id) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(c.title.ifBlank { "Chat" }, color = fg, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        exportChat(context, c.title.ifBlank { "Chat" }, c.messages)
                    }) {
                        Icon(Icons.Default.Share, "Export chat", tint = fg)
                    }
                    IconButton(onClick = {
                        com.frit.app.core.ChatHistory.delete(c.id)
                        chatsList.remove(c)
                    }) {
                        Icon(Icons.Default.Delete, "Delete Chat", tint = Color.Red)
                    }
                }
            }
        }
        if (chatsList.size > 5) {
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (expanded) "Show less" else "Show all ${chatsList.size} chats",
                    color = fg
                )
            }
        }
    }
}
