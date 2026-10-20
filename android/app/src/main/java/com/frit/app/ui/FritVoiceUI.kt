package com.frit.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.frit.app.R
import com.frit.app.core.FritMode
import com.frit.app.ui.theme.Montserrat

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FritVoiceUI(
    fritMode: FritMode,
    onCancel: () -> Unit,
    fg: Color,
    bg: Color,
    isDark: Boolean = true,
    onMicTap: (() -> Unit)? = null,
    isVisionActive: Boolean = false,
    onVisionTap: (() -> Unit)? = null,
    onVisionLongPress: (() -> Unit)? = null,
    onCameraTap: (() -> Unit)? = null,
    onFilePickerTap: (() -> Unit)? = null
) {
    val infiniteTransition = rememberInfiniteTransition(label = "frit_pulse")
    val isActive = fritMode == FritMode.LISTENING || fritMode == FritMode.SPEAKING

    // Opening load-out animation
    var entryProgress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        animate(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = tween(650, easing = FastOutSlowInEasing)
        ) { value, _ -> entryProgress = value }
    }

    // Pulse scale animation for brand icon
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isActive) 1.20f else 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isActive) 800 else 2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    // Glow alpha animation
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = if (isActive) 0.65f else 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isActive) 800 else 2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    var showVisionModal by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bg.copy(alpha = 0.98f)),
        contentAlignment = Alignment.Center
    ) {
        // Render theme background abstract lines
        TopoBackground(isDark = isDark)

        // FRIT wordmark header (theme-aware logo, same proportions either way)
        FritWordmark(
            color = fg,
            isDark = isDark,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 18.dp)
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .scale(0.85f + (0.15f * entryProgress))
                .alpha(entryProgress)
        ) {
            // My Brand Icon centered in radial glow
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .scale(pulseScale * 1.5f)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    fg.copy(alpha = glowAlpha),
                                    Color.Transparent
                                )
                            ),
                            shape = CircleShape
                        )
                )

                // Star ships on transparency — no circle crop (it would clip the points).
                val brandRes = if (isDark) R.drawable.my_brand else R.drawable.my_brand_dark_theme
                Image(
                    painter = painterResource(id = brandRes),
                    contentDescription = "My Brand",
                    modifier = Modifier
                        .size(120.dp)
                        .scale(pulseScale)
                )
            }

            Spacer(Modifier.height(36.dp))

            // Animated status text below icon
            val statusText = when(fritMode) {
                FritMode.LISTENING -> "I'm listening..."
                FritMode.THINKING -> "Thinking..."
                FritMode.SPEAKING -> "FRIT is speaking..."
                else -> "I'm listening"
            }

            Text(
                text = statusText,
                color = fg,
                fontFamily = Montserrat,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(48.dp))

            // Control Action Bar (Mic, Vision, Close) — compact, sharp
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(fg.copy(alpha = 0.08f))
                    .border(1.dp, fg.copy(alpha = 0.15f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                // 1. Mic Icon: Pause AI when talking to record new speech
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (fritMode == FritMode.LISTENING) fg else fg.copy(alpha = 0.12f))
                        .clickable { onMicTap?.invoke() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (fritMode == FritMode.LISTENING) Icons.Default.Mic else Icons.Outlined.MicNone,
                        contentDescription = "Mic / Record",
                        tint = if (fritMode == FritMode.LISTENING) bg else fg,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // 2. Vision Icon: Tap = Stream screencast, Long press = Camera & File upload options
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (isVisionActive) fg else fg.copy(alpha = 0.12f))
                        .combinedClickable(
                            onClick = { onVisionTap?.invoke() },
                            onLongClick = {
                                if (onVisionLongPress != null) {
                                    onVisionLongPress.invoke()
                                } else {
                                    showVisionModal = true
                                }
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CenterFocusWeak,
                        contentDescription = "Vision",
                        tint = if (isVisionActive) bg else fg,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // 3. X Close Button
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(fg.copy(alpha = 0.12f))
                        .clickable { onCancel() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = fg,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            // Footer text
            Text(
                text = "FRIT Autonomous AI • Tap mic to interrupt",
                color = fg.copy(alpha = 0.5f),
                fontFamily = Montserrat,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // Modal Sheet for Vision Options (Camera / File Upload)
        if (showVisionModal) {
            Dialog(onDismissRequest = { showVisionModal = false }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(bg)
                        .border(1.dp, fg.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
                        .padding(24.dp)
                ) {
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(
                            text = "Vision & Image Options",
                            color = fg,
                            fontFamily = Montserrat,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(16.dp))

                        // Camera Option
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(fg.copy(alpha = 0.08f))
                                .clickable {
                                    showVisionModal = false
                                    onCameraTap?.invoke()
                                }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, tint = fg)
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text("Camera", color = fg, fontWeight = FontWeight.Bold, fontSize = 15.sp, fontFamily = Montserrat)
                                Text("Capture photos for AI analysis", color = fg.copy(alpha = 0.6f), fontSize = 12.sp, fontFamily = Montserrat)
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // File Upload Option
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(fg.copy(alpha = 0.08f))
                                .clickable {
                                    showVisionModal = false
                                    onFilePickerTap?.invoke()
                                }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, tint = fg)
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text("Upload File or Image", color = fg, fontWeight = FontWeight.Bold, fontSize = 15.sp, fontFamily = Montserrat)
                                Text("Select images or documents from device", color = fg.copy(alpha = 0.6f), fontSize = 12.sp, fontFamily = Montserrat)
                            }
                        }

                        Spacer(Modifier.height(20.dp))

                        Button(
                            onClick = { showVisionModal = false },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = fg.copy(alpha = 0.15f))
                        ) {
                            Text("Cancel", color = fg, fontWeight = FontWeight.Bold, fontFamily = Montserrat)
                        }
                    }
                }
            }
        }
    }
}
