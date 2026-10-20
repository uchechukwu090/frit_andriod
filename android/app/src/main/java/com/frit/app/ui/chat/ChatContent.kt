package com.frit.app.ui.chat

import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frit.app.core.FritMode
import com.frit.app.data.ChatMessage
import com.frit.app.ui.theme.Montserrat

/**
 * Chat surface composables, split out of MainActivity.kt (audit F-14).
 *
 * ChatContent is the live chat screen body; AttachmentPreviewStrip is the
 * small strip above the input bar that shows the pending attachment.
 */
@Composable
private fun AttachmentPreviewStrip(
    imageB64: String?,
    fileName: String?,
    fg: Color,
    surf: Color,
    stroke: Color,
    onRemoveImage: () -> Unit,
    onRemoveFile: () -> Unit
) {
    if (imageB64 == null && fileName == null) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        imageB64?.let { b64 ->
            val bmp = remember(b64) {
                try {
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (_: Exception) { null }
            }
            Box(
                modifier = Modifier.size(56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(surf)
                    .border(0.5.dp, stroke, RoundedCornerShape(10.dp))
            ) {
                bmp?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Attached image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                Box(
                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(18.dp)
                        .clip(CircleShape).background(fg.copy(alpha = 0.75f))
                        .clickable(onClick = onRemoveImage),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Close, "Remove image", tint = surf, modifier = Modifier.size(11.dp))
                }
            }
        }
        fileName?.let { name ->
            Row(
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(surf)
                    .border(0.5.dp, stroke, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.AttachFile, "Attached file", tint = fg.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(name.take(24), color = fg, fontSize = 12.sp, fontFamily = Montserrat, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier.size(18.dp).clip(CircleShape)
                        .background(fg.copy(alpha = 0.12f)).clickable(onClick = onRemoveFile),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Close, "Remove file", tint = fg, modifier = Modifier.size(11.dp))
                }
            }
        }
    }
}

@Composable
fun ChatContent(
    bg: Color, fg: Color, surf: Color, stroke: Color, isDark: Boolean, fritMode: FritMode,
    messages: List<ChatMessage>, input: String, onInputChange: (String) -> Unit,
    replyQuotes: androidx.compose.runtime.snapshots.SnapshotStateList<com.frit.app.data.ReplyQuote> = remember { androidx.compose.runtime.mutableStateListOf() },
    onSend: (String) -> Unit, onVoiceTap: () -> Unit, onVoiceLongPress: () -> Unit,
    isVisionActive: Boolean = false, onVisionTap: () -> Unit, onVisionLongPress: () -> Unit,
    onAttachmentTap: () -> Unit, onAttachmentLongPress: () -> Unit = {},
    onSettingsTap: () -> Unit, onAutomationTap: () -> Unit,
    onChatsTap: () -> Unit, onNewChatTap: () -> Unit,
    onEdit: (Int, String) -> Unit, onDelete: (Int) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState, thinkSteps: List<String>,
    isQuickRecording: Boolean = false,
    isFileAttached: Boolean = false,
    isGenerating: Boolean = false,
    onStop: () -> Unit = {},
    onThumbsUp: ((String) -> Unit)? = null,
    onThumbsDown: ((String) -> Unit)? = null,
    brainMode: com.frit.app.core.RouteController.Brain = com.frit.app.core.RouteController.Brain.AUTO,
    onBrainModeChange: (com.frit.app.core.RouteController.Brain) -> Unit = {},
    pendingImageB64: String? = null,
    pendingFileName: String? = null,
    streamingText: String = "",
    onRemoveImage: () -> Unit = {},
    onRemoveFile: () -> Unit = {},
    onOpenCreativeSuite: (() -> Unit)? = null
) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            com.frit.app.ui.FritTopBar(
                onLogoTap = onChatsTap,
                onAutomationTap = onAutomationTap, onSettingsTap = onSettingsTap,
                fg = fg, bg = bg, brainMode = brainMode, onBrainModeChange = onBrainModeChange, isDark = isDark
            )
            Box(Modifier.weight(1f)) {
                val context = LocalContext.current
                val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                LaunchedEffect(messages.lastOrNull()?.content) {
                    if (messages.isNotEmpty()) {
                        listState.animateScrollToItem(messages.size - 1)
                    }
                }
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    itemsIndexed(messages) { idx, msg ->
                        com.frit.app.ui.FritChatBubble(
                            msg = msg, isDark = isDark, fg = fg, bg = bg, surf = surf, stroke = stroke,
                            onCopy = { text ->
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(text))
                                android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            onThumbsUp = onThumbsUp,
                            onThumbsDown = onThumbsDown,
                            onEdit = if (msg.role == "user") { { txt -> onEdit(idx, txt) } } else null,
                            onDelete = if (msg.role == "user") { { onDelete(idx) } } else null,
                            onReply = { m ->
                                val t = m.content.take(200)
                                if (replyQuotes.size < 3 && replyQuotes.none { it.text == t }) {
                                    replyQuotes.add(com.frit.app.data.ReplyQuote(m.role, t))
                                }
                            },
                            onOpenCreativeSuite = onOpenCreativeSuite
                        )
                    }
                    // Live streaming bubble (audit: streaming AI chat). While the
                    // model is producing text, a provisional assistant bubble
                    // grows in place so the user sees the reply as it forms
                    // instead of staring at a spinner.
                    if (streamingText.isNotBlank()) {
                        item(key = "streaming") {
                            com.frit.app.ui.FritChatBubble(
                                msg = com.frit.app.data.ChatMessage("assistant", streamingText, isStreaming = true),
                                isDark = isDark, fg = fg, bg = bg, surf = surf, stroke = stroke,
                                onCopy = null, onThumbsUp = null, onThumbsDown = null,
                                onEdit = null, onDelete = null, onReply = null,
                                onOpenCreativeSuite = onOpenCreativeSuite
                            )
                        }
                    }
                    if (fritMode == FritMode.THINKING) {
                        item { com.frit.app.ui.FritThinkingBar(thinkSteps, fg) }
                    }
                }
                if (isQuickRecording || fritMode == FritMode.LISTENING) {
                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)) {
                        RecordingOverlay(fg, bg, fritMode == FritMode.LISTENING)
                    }
                }

            }
            com.frit.app.ui.ReplyQuoteStrip(
                quotes = replyQuotes, fg = fg, surf = surf, stroke = stroke,
                onRemove = { replyQuotes.removeAt(it) }
            )
            AttachmentPreviewStrip(
                imageB64 = pendingImageB64, fileName = pendingFileName,
                fg = fg, surf = surf, stroke = stroke,
                onRemoveImage = onRemoveImage, onRemoveFile = onRemoveFile
            )
            com.frit.app.ui.FritInputBar(
                input = input, onInputChange = onInputChange, onSend = { onSend(input) },
                onVoiceTap = onVoiceTap, onVoiceLongPress = onVoiceLongPress, 
                onAttachImage = onAttachmentTap, onAttachLongPress = onAttachmentLongPress,
                fg = fg, bg = bg, surf = surf, stroke = stroke, isListening = isQuickRecording || fritMode == FritMode.LISTENING, isFileAttached = isFileAttached,
                isGenerating = isGenerating || fritMode == FritMode.THINKING || fritMode == FritMode.SPEAKING, onStop = onStop
            )
        }
    }
}
