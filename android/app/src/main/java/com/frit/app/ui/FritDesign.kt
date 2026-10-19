package com.frit.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frit.app.R
import com.frit.app.data.Artifact
import com.frit.app.data.ChatMessage
import com.frit.app.data.ReplyQuote
import com.frit.app.ui.theme.FritCream
import com.frit.app.ui.theme.FritEspresso
import com.frit.app.ui.theme.FritStroke
import com.frit.app.ui.theme.FritStrokeStrong
import com.frit.app.ui.theme.FritSurface
import com.frit.app.ui.theme.Montserrat
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.util.Patterns
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalUriHandler

/* ---------- Design tokens ---------- */

/** Inner content rhythm. The screen has zero side insets; bubbles constrain their own width. */
object FritSpace {
    val bubbleMax = 560.dp
    val gutter = 24.dp           // between stacked regions (header, list, input)
    val hairline = 1.dp          // dividers
    val t8 = 8.dp
    val t12 = 12.dp
    val t16 = 16.dp
}

object FritType {
    fun title(color: Color) = TextStyle(
        color = color, fontFamily = Montserrat, fontSize = 22.sp, fontWeight = FontWeight.Black
    )
    fun sectionLabel(color: Color) = TextStyle(
        color = color.copy(alpha = 0.55f), fontFamily = Montserrat,
        fontSize = 11.sp, fontWeight = FontWeight.Bold
    )
    fun body(color: Color) = TextStyle(
        color = color, fontFamily = Montserrat, fontSize = 15.sp,
        fontWeight = FontWeight.Normal, lineHeight = 22.sp
    )
    fun bodyUser(color: Color) = TextStyle(
        color = color, fontFamily = Montserrat, fontSize = 15.sp,
        fontWeight = FontWeight.Medium, lineHeight = 22.sp
    )
    fun caption(color: Color) = TextStyle(
        color = color.copy(alpha = 0.55f), fontFamily = Montserrat,
        fontSize = 11.sp, fontWeight = FontWeight.Medium
    )
    fun mono(color: Color) = TextStyle(
        color = color.copy(alpha = 0.6f), fontFamily = Montserrat,
        fontSize = 11.sp, fontWeight = FontWeight.Medium
    )
    fun editorial(color: Color) = TextStyle(
        color = color.copy(alpha = 0.35f), fontFamily = Montserrat,
        fontSize = 10.sp, fontWeight = FontWeight.Bold
    )
}

/* ---------- Logo variants ---------- */

@Composable
fun FritWordmark(modifier: Modifier = Modifier, color: Color = FritEspresso, isDark: Boolean = false) {
    val logoRes = if (isDark) R.drawable.frit_logo_white_theme else R.drawable.frit_logo
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        Image(
            painter = painterResource(id = logoRes),
            contentDescription = "FRIT",
            modifier = Modifier.height(20.dp)
        )
    }
}

@Composable
fun FritWatermark(modifier: Modifier = Modifier, color: Color = FritEspresso, alpha: Float = 0.10f, isDark: Boolean = false) {
    val logoRes = if (isDark) R.drawable.frit_logo_white_theme else R.drawable.frit_logo
    Box(modifier, contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(id = logoRes),
            contentDescription = null,
            modifier = Modifier
                .size(220.dp)
                .alpha(alpha)
        )
    }
}

/* ---------- Top bar ---------- */

@Composable
fun FritTopBar(
    onLogoTap: () -> Unit,
    onAutomationTap: () -> Unit,
    onSettingsTap: () -> Unit,
    fg: Color,
    bg: Color,
    brainMode: com.frit.app.core.RouteController.Brain = com.frit.app.core.RouteController.Brain.AUTO,
    onBrainModeChange: (com.frit.app.core.RouteController.Brain) -> Unit = {},
    isDark: Boolean = false
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.clickable(onClick = onLogoTap),
                color = Color.Transparent
            ) {
                FritWordmark(color = fg, isDark = isDark)
            }
            Spacer(Modifier.width(18.dp))
            BrainSelector(brainMode, onBrainModeChange, fg, bg)
            Spacer(Modifier.weight(1f))
            HeaderIconButton(Icons.Outlined.AutoFixHigh, "Automation", fg, onAutomationTap)
            Spacer(Modifier.width(10.dp))
            HeaderIconButton(Icons.Outlined.Settings, "Settings", fg, onSettingsTap)
        }
        HorizontalDivider(color = fg.copy(alpha = 0.08f), thickness = 0.5.dp)
    }
}

@Composable
fun FritSidebar(
    onChatsTap: () -> Unit,
    onNewChatTap: () -> Unit,
    onCreativeSuiteTap: () -> Unit,
    onTradingTap: () -> Unit = {},
    onClose: () -> Unit,
    fg: Color,
    bg: Color
) {
    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .width(280.dp),
        color = bg,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FritWordmark(color = fg, isDark = false)
                Spacer(Modifier.weight(1f))
                HeaderIconButton(Icons.Default.Close, "Close", fg, onClose)
            }
            Spacer(Modifier.height(24.dp))
            SidebarItem(Icons.Outlined.History, "Chat History", fg, onChatsTap)
            SidebarItem(Icons.Outlined.Edit, "New Chat", fg, onNewChatTap)
            SidebarItem(Icons.Outlined.Palette, "Creative Suite", fg, onCreativeSuiteTap)
            SidebarItem(Icons.Outlined.ShowChart, "Trading Suite", fg, onTradingTap)
        }
    }
}

@Composable
private fun SidebarItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    fg: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = label, tint = fg)
            Spacer(Modifier.width(16.dp))
            Text(label, color = fg, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
        }
    }
}

/** Uniform, tappable header action (icon only, evenly spaced). */
@Composable
private fun HeaderIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDesc: String,
    fg: Color,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDesc, tint = fg, modifier = Modifier.size(19.dp))
    }
}

@Composable
fun BrainSelector(
    selected: com.frit.app.core.RouteController.Brain,
    onSelect: (com.frit.app.core.RouteController.Brain) -> Unit,
    fg: Color,
    bg: Color
) {
    val options = com.frit.app.core.RouteController.Brain.values()
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(fg.copy(alpha = 0.05f))
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEach { brain ->
            val isSelected = selected == brain
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isSelected) fg else Color.Transparent)
                    .clickable { onSelect(brain) }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = brain.label.split(" ").first(), // "Auto", "Local", "Cloud"
                    // Selected pill is painted with fg, so its label must be
                    // the contrasting bg (white box -> black text in dark
                    // theme, black box -> white text in light theme).
                    color = if (isSelected) bg else fg.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = Montserrat
                )
            }
        }
    }
}

/* ---------- Chat bubble (shared) ---------- */

@Composable
fun FritChatBubble(
    msg: ChatMessage,
    isDark: Boolean,
    fg: Color,
    bg: Color,
    surf: Color,
    stroke: Color,
    onEdit: ((String) -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onCopy: ((String) -> Unit)? = null,
    onThumbsUp: ((String) -> Unit)? = null,
    onThumbsDown: ((String) -> Unit)? = null,
    onReply: ((ChatMessage) -> Unit)? = null,
    compact: Boolean = false,
    onOpenCreativeSuite: (() -> Unit)? = null
) {
    val isUser = msg.role == "user"
    val innerRadius = if (isUser) 14.dp else 6.dp
    val bubbleColor = if (isUser) fg else surf
    val textColor = if (isUser) bg else fg

    // Swipe-left-to-reply (Messenger style): drag past ~90dp and release.
    // Draw-phase offset only — never disturbs layout or the scroll container.
    var dragX by remember { mutableStateOf(0f) }
    val canSwipe = onReply != null && !msg.isStreaming && msg.content.isNotBlank()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val swipeThreshold = with(density) { 90.dp.toPx() }
    val maxSwipe = with(density) { 130.dp.toPx() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (compact) 6.dp else 10.dp)
            .animateContentSize(animationSpec = tween(220))
            .graphicsLayer { translationX = dragX }
            .then(
                if (canSwipe) Modifier.pointerInput(msg) {
                    detectHorizontalDragGestures(
                        onDragEnd = { if (dragX <= -swipeThreshold) onReply?.invoke(msg); dragX = 0f },
                        onDragCancel = { dragX = 0f }
                    ) { change, dragAmount ->
                        change.consume()
                        dragX = (dragX + dragAmount).coerceIn(-maxSwipe, 0f)
                    }
                } else Modifier
            ),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 20.dp)
        ) {
            if (!isUser) {
                // Brand star ships on transparency — no circle crop (it would
                // clip the points). Theme variant keeps line/text contrast.
                val brandIconRes = if (isDark) R.drawable.my_brand else R.drawable.my_brand_dark_theme
                Image(
                    painter = painterResource(id = brandIconRes),
                    contentDescription = "Brand",
                    modifier = Modifier.size(24.dp)
                )
            } else { Spacer(Modifier.width(24.dp)) }

            Column(
                modifier = Modifier
                    .widthIn(max = FritSpace.bubbleMax)
                    .clip(RoundedCornerShape(innerRadius))
                    .background(bubbleColor)
                    .border(0.5.dp, stroke, RoundedCornerShape(innerRadius))
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                // Quoted context (Messenger-style replies): accent bar + who + snippet.
                if (msg.replyQuotes.isNotEmpty()) {
                    Column(
                        modifier = Modifier.padding(bottom = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        msg.replyQuotes.forEach { q ->
                            Row(verticalAlignment = Alignment.Top) {
                                Box(
                                    Modifier.width(3.dp).heightIn(min = 30.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(textColor.copy(alpha = 0.55f))
                                )
                                Spacer(Modifier.width(7.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (q.role == "user") "You" else "FRIT",
                                        style = FritType.caption(textColor).copy(fontWeight = FontWeight.Bold)
                                    )
                                    Text(
                                        q.text.take(160),
                                        style = FritType.caption(textColor),
                                        maxLines = 3,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
                msg.imageBase64?.let { b64 ->
                    val bitmap = remember(b64) {
                        try {
                            val bytes = Base64.decode(b64, Base64.DEFAULT)
                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        } catch (e: Exception) { null }
                    }
                    bitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "Attached image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .padding(bottom = 10.dp)
                        )
                    }
                }
                // Selectable text — users can long-press to highlight and copy spans
                SelectionContainer {
                    val textToDisplay = if (msg.isStreaming) "${msg.content} ▋" else msg.content
                    Text(
                        textToDisplay,
                        style = if (isUser) FritType.bodyUser(textColor) else FritType.body(textColor)
                    )
                }
                // Link preview pills for any URLs found in the message
                val context = LocalContext.current
                val urls = remember(msg.content) {
                    val matcher = Patterns.WEB_URL.matcher(msg.content)
                    buildList {
                        while (matcher.find()) {
                            val candidate = matcher.group()
                            if (isValidWebUrl(candidate)) {
                                add(candidate)
                            }
                        }
                    }
                }
                if (urls.isNotEmpty()) {
                    Spacer(Modifier.height(FritSpace.t8))
                    urls.take(3).forEach { url ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isUser) textColor.copy(alpha = 0.12f) else fg.copy(alpha = 0.07f))
                                .border(0.5.dp, if (isUser) textColor.copy(alpha = 0.18f) else stroke, RoundedCornerShape(8.dp))
                                .clickable { safeOpenUrl(context, url) }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Link,
                                contentDescription = null,
                                tint = textColor.copy(alpha = 0.7f),
                                modifier = Modifier.size(13.dp)
                            )
                            androidx.compose.ui.text.buildAnnotatedString { append(url) }.let {
                                Text(
                                    text = url.take(60) + if (url.length > 60) "…" else "",
                                    style = FritType.caption(textColor),
                                    maxLines = 1
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
                if (msg.artifacts.isNotEmpty()) {
                    Spacer(Modifier.height(FritSpace.t12))
                    msg.artifacts.forEach { art ->
                        FritArtifactCard(art, fg, surf, stroke, textColor, onOpenCreativeSuite = onOpenCreativeSuite)
                        Spacer(Modifier.height(FritSpace.t8))
                    }
                }
            }
        }
        if (!compact && (onEdit != null || onDelete != null || onCopy != null || onReply != null)) {
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.padding(start = if (!isUser) 52.dp else 0.dp,
                    end = if (isUser) 20.dp else 0.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onReply != null) BubbleAction(icon = Icons.AutoMirrored.Filled.Reply, fg = fg) {
                    onReply(msg)
                }
                if (onCopy != null) BubbleAction(icon = Icons.Outlined.ContentCopy, fg = fg) {
                    onCopy(msg.content)
                }
                if (isUser && onEdit != null) BubbleAction(icon = Icons.Outlined.Edit, fg = fg) {
                    onEdit(msg.content)
                }
                if (isUser && onDelete != null) BubbleAction(icon = Icons.Outlined.Delete, fg = fg) {
                    onDelete()
                }
                // Feedback on AI replies: trains future identical tasks.
                if (!isUser && onThumbsUp != null) BubbleAction(icon = Icons.Outlined.ThumbUp, fg = fg) {
                    onThumbsUp(msg.content)
                }
                if (!isUser && onThumbsDown != null) BubbleAction(icon = Icons.Outlined.ThumbDown, fg = fg) {
                    onThumbsDown(msg.content)
                }
            }
        }
    }
}

@Composable
private fun BubbleAction(icon: androidx.compose.ui.graphics.vector.ImageVector, fg: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = fg.copy(alpha = 0.4f), modifier = Modifier.size(15.dp))
    }
}

/* ---------- Reply quote draft strip (shared by app + overlay input bars) ---------- */

@Composable
fun ReplyQuoteStrip(
    quotes: List<ReplyQuote>,
    fg: Color,
    surf: Color,
    stroke: Color,
    onRemove: (Int) -> Unit
) {
    if (quotes.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        quotes.forEachIndexed { i, q ->
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(surf)
                    .border(0.5.dp, stroke, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.width(3.dp).heightIn(min = 26.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(fg.copy(alpha = 0.55f))
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        (if (q.role == "user") "You" else "FRIT") + " • replying",
                        color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = Montserrat
                    )
                    Text(
                        q.text.take(120),
                        color = fg, fontSize = 12.sp, fontFamily = Montserrat,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                Box(
                    Modifier.size(26.dp).clip(CircleShape)
                        .clickable { onRemove(i) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Close, "Remove quote", tint = fg.copy(alpha = 0.5f), modifier = Modifier.size(13.dp))
                }
            }
        }
    }
}

/* ---------- Thinking affordance ---------- */

@Composable
fun FritThinkingBar(thinkSteps: List<String>, fg: Color) {
    if (thinkSteps.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 52.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ThinkingPulse(fg)
        Spacer(Modifier.width(10.dp))
        Text(
            thinkSteps.lastOrNull() ?: "Thinking…",
            color = fg.copy(alpha = 0.55f),
            fontFamily = Montserrat,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ThinkingPulse(fg: Color) {
    val alpha by animateFloatAsState(
        targetValue = 0.3f,
        animationSpec = tween(900),
        label = "pulse"
    )
    Box(
        Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(fg.copy(alpha = alpha))
    )
}

/* ---------- Input bar (shared) ---------- */

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun FritInputBar(
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onVoiceTap: () -> Unit,
    onVoiceLongPress: () -> Unit,
    onAttachImage: () -> Unit,
    onAttachLongPress: () -> Unit = {},
    fg: Color,
    bg: Color,
    surf: Color,
    stroke: Color,
    isListening: Boolean = false,
    isFileAttached: Boolean = false,
    isGenerating: Boolean = false,
    onStop: () -> Unit = {},
    compact: Boolean = false,
    onFocusChange: (Boolean) -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    val sendEnabled = input.isNotBlank()
    val barHeight = if (compact) 52.dp else 60.dp
    val recordTint = Color(0xFFD64545)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = barHeight)
                .clip(RoundedCornerShape(barHeight / 2))
                .border(0.5.dp, stroke, RoundedCornerShape(barHeight / 2))
                .background(surf)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .combinedClickable(
                        onClick = onAttachImage,
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onAttachLongPress()
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Add,
                    "Attach",
                    tint = if (isFileAttached) fg else fg.copy(alpha = 0.55f),
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { fs -> onFocusChange(fs.isFocused) },
                textStyle = TextStyle(color = fg, fontFamily = Montserrat, fontSize = 15.sp, lineHeight = 20.sp),
                cursorBrush = SolidColor(fg.copy(alpha = 0.7f)),
                singleLine = compact,
                maxLines = if (compact) 1 else 4,
                decorationBox = { inner ->
                    if (input.isEmpty())
                        Text(
                            "Message FRIT",
                            color = fg.copy(alpha = 0.55f),
                            fontFamily = Montserrat,
                            fontSize = 15.sp
                        )
                    inner()
                }
            )
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .combinedClickable(
                        onClick = onVoiceTap,
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onVoiceLongPress()
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                // Soft red fill while recording so the mic clearly changes colour
                // (previously it turned `bg` — the same as the field — and vanished).
                if (isListening) {
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(recordTint.copy(alpha = 0.14f))
                    )
                }
                Icon(
                    Icons.Outlined.Mic,
                    "Voice",
                    tint = if (isListening) recordTint else fg,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        SendButton(
            enabled = sendEnabled,
            fg = fg,
            bg = bg,
            isGenerating = isGenerating,
            onClick = { if (sendEnabled) onSend() },
            onStop = onStop
        )
    }
}

@Composable
private fun SendButton(enabled: Boolean, fg: Color, bg: Color, isGenerating: Boolean = false, onClick: () -> Unit, onStop: () -> Unit = {}) {
    // While the agent is responding, send morphs into stop (standard AI UX).
    val bgCol = if (isGenerating) Color(0xFFD64545) else if (enabled) fg else fg.copy(alpha = 0.12f)
    val fgCol = if (isGenerating || enabled) Color.White else fg.copy(alpha = 0.4f)
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(bgCol)
            .clickable(onClick = { if (isGenerating) onStop() else onClick() }),
        contentAlignment = Alignment.Center
    ) {
        if (isGenerating) {
            // Stop square
            Box(Modifier.size(16.dp).background(fgCol, RoundedCornerShape(3.dp)))
        } else {
            Icon(Icons.Outlined.Send, "Send", tint = fgCol, modifier = Modifier.size(18.dp))
        }
    }
}

/* ---------- Artifact card ---------- */

@Composable
fun FritArtifactCard(
    art: Artifact,
    fg: Color,
    surf: Color,
    stroke: Color,
    textColor: Color,
    onOpenCreativeSuite: (() -> Unit)? = null
) {
    val context = LocalContext.current
    SideEffect { rememberAppContext(context) }
    val isImage = art.isImage && art.base64 != null
    val isVideo = art.mime.startsWith("video/")
    // Async video renders: no bytes yet, just a job id carried in content.
    val isPendingJob = art.base64 == null && (art.content?.startsWith("video_job:") == true)
    val openSuite = onOpenCreativeSuite
    val showSuiteLink = openSuite != null && (isImage || isVideo)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(0.5.dp, stroke, RoundedCornerShape(10.dp))
            .background(surf)
            .padding(12.dp)
    ) {
        if (isImage) {
            val bitmap = remember(art.base64) {
                try {
                    val bytes = Base64.decode(art.base64!!, Base64.DEFAULT)
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (e: Exception) { null }
            }
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = art.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(Modifier.height(10.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    art.name,
                    style = FritType.bodyUser(textColor),
                    maxLines = 1
                )
                Text(
                    if (isPendingJob) "Rendering in the background — track it any time."
                    else "${art.mime} • ${formatBytes(art.size)}",
                    style = FritType.caption(textColor)
                )
            }
            if (!isPendingJob) {
                ArtifactAction(label = "Save", icon = Icons.Outlined.Download, fg = fg, surf = surf, stroke = stroke) {
                    Thread {
                        val location = saveArtifactToDownloads(art, context)
                        android.os.Handler(context.mainLooper).post {
                            android.widget.Toast.makeText(
                                context,
                                if (location.isBlank()) "Save failed — storage not writable"
                                else "Saved ${art.name} → $location",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }.start()
                }
                Spacer(Modifier.width(8.dp))
                ArtifactAction(label = "Open", icon = Icons.Outlined.IosShare, fg = fg, surf = surf, stroke = stroke) {
                    openArtifact(art, context)
                }
            }
        }
        if (showSuiteLink && openSuite != null) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(fg.copy(alpha = 0.07f))
                    .border(0.5.dp, stroke, RoundedCornerShape(8.dp))
                    .clickable(onClick = openSuite)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Outlined.Palette, null, tint = fg, modifier = Modifier.size(14.dp))
                Text(
                    "Open in Creative Suite for more options",
                    color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = Montserrat,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Outlined.ArrowForward, null, tint = fg.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
            }
        }
    }
}

/* ---------- URL helpers ---------- */

private val COMMON_FILE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "svg", "webp", "bmp", "ico",
    "csv", "tsv", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
    "txt", "md", "json", "xml", "yaml", "yml", "html", "css",
    "py", "js", "ts", "kt", "java", "c", "cpp", "h", "cs", "sh", "bat",
    "zip", "tar", "gz", "7z", "rar", "mp3", "wav", "mp4", "mkv", "avi", "mov", "bin", "apk"
)

private fun isValidWebUrl(candidate: String): Boolean {
    val trimmed = candidate.trim()
    if (trimmed.isBlank()) return false

    val lower = trimmed.lowercase()
    val hasScheme = lower.startsWith("http://") || lower.startsWith("https://")
    val hasWww = lower.startsWith("www.")

    if (!hasScheme && !hasWww) {
        val ext = lower.substringAfterLast('.', "")
        if (ext in COMMON_FILE_EXTENSIONS && !trimmed.contains("/")) {
            return false // Filename like "chart.png", "me.png", "data.csv"
        }
        if (!trimmed.contains("/")) {
            val knownTlds = setOf("com", "org", "net", "io", "gov", "edu", "co", "app", "dev", "ai", "info", "me", "tech", "site", "online", "store", "xyz")
            if (ext !in knownTlds) {
                return false
            }
        }
    }
    return true
}

private fun safeOpenUrl(context: Context, url: String) {
    runCatching {
        val trimmed = url.trim()
        val normalized = if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed
        } else {
            "https://$trimmed"
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalized)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, "Open link with").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }.onFailure { e ->
        Log.e("FritDesign", "Failed to open URL: $url", e)
        android.widget.Toast.makeText(context.applicationContext, "Unable to open link", android.widget.Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun ArtifactAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    fg: Color,
    surf: Color,
    stroke: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .border(0.5.dp, stroke, RoundedCornerShape(999.dp))
            .background(fg.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = fg, fontFamily = Montserrat,
            fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "—"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
}
