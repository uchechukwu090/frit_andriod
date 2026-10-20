package com.frit.app.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import com.frit.app.ui.theme.Montserrat

/**
 * Chat-adjacent leaf composables, split out of MainActivity.kt (audit F-14).
 *
 * These have no dependency on MainScreen's state — they take their inputs as
 * parameters — so they could be moved without touching the call sites beyond
 * adding an import.
 */

/** Live mic level bars for the wake-word trainer. */
@Composable
fun WaveformBars(level: Float, fg: Color, stroke: Color) {
    val history = remember { mutableStateListOf<Float>() }
    LaunchedEffect(level) {
        history.add(level.coerceIn(0f, 1f))
        if (history.size > 48) history.removeAt(0)
    }
    Canvas(
        Modifier.fillMaxWidth().height(90.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(fg.copy(alpha = 0.06f))
            .border(1.dp, stroke, RoundedCornerShape(10.dp))
            .padding(8.dp)
    ) {
        if (history.isEmpty()) return@Canvas
        val n = history.size
        val gap = 3.dp.toPx()
        val bw = ((size.width - gap * (n - 1)) / n).coerceAtLeast(2f)
        history.forEachIndexed { i, v ->
            val h = (v * size.height).coerceAtLeast(2f)
            drawRect(
                color = fg,
                topLeft = androidx.compose.ui.geometry.Offset(i * (bw + gap), (size.height - h) / 2),
                size = androidx.compose.ui.geometry.Size(bw, h)
            )
        }
    }
}

/** Wake-word training dialog: capture, then a live score meter. */
@Composable
fun WakeWordTrainingDialog(fg: Color, surf: Color, stroke: Color, bg: Color, onClose: () -> Unit) {
    val context = LocalContext.current
    val engine = com.frit.app.features.wake.LocalWakeWordEngine
    val step by engine.trainingStateFlow.collectAsState()
    val rms by engine.currentRmsFlow.collectAsState()
    val lastScore by engine.lastScoreFlow.collectAsState()

    // Opening the dialog (re)starts training; the engine publishes step
    // updates (1 -> 2 -> 0) and live mic RMS while capturing.
    LaunchedEffect(Unit) {
        com.frit.app.features.wake.FritWakeWordService.train(context) {}
    }

    // Dismiss/Cancel = abort training and restore normal listening.
    fun cancelAndClose() {
        engine.stop()
        com.frit.app.features.wake.FritWakeWordService.start(context)
        onClose()
    }

    Dialog(onDismissRequest = { cancelAndClose() }) {
        Column(
            Modifier.clip(RoundedCornerShape(16.dp)).background(surf)
                .border(1.dp, stroke, RoundedCornerShape(16.dp)).padding(20.dp)
        ) {
            Text("Train wake word", color = fg, fontSize = 18.sp, fontWeight = FontWeight.Black, fontFamily = Montserrat)
            Spacer(Modifier.height(8.dp))
            val done = step?.first == 0
            Text(
                step?.second ?: "Starting microphone…",
                color = fg, fontSize = 14.sp, fontFamily = Montserrat
            )
            Spacer(Modifier.height(16.dp))
            if (!done) {
                WaveformBars(level = rms, fg = fg, stroke = stroke)
                Spacer(Modifier.height(8.dp))
                val live = rms > 0.02f
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(if (live) Color(0xFF4A7C59) else fg.copy(alpha = 0.3f), CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (live) "MIC LIVE — hearing you" else "quiet… speak clearly",
                        color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = Montserrat
                    )
                }
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { cancelAndClose() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Cancel", color = fg) }
            } else {
                // Test meter: the engine is back in DETECT mode, so speak the
                // phrase and watch your live score vs the trained threshold.
                // CLOSE miss (score slightly above threshold) = retrain clearer
                // / louder / closer to the mic. FAR miss = wrong phrase, room,
                // or distance — fix the condition, not the voice.
                Spacer(Modifier.height(12.dp))
                Text("Test it — say the wake word:", color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = Montserrat)
                Spacer(Modifier.height(6.dp))
                val s = lastScore
                Text(
                    if (s == null) "listening for a test phrase…"
                    else "score ${"%.2f".format(s.first)} vs threshold ${"%.2f".format(s.second)} — " +
                        if (s.first < s.second) "MATCH" else "miss",
                    color = fg, fontSize = 13.sp, fontFamily = Montserrat
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onClose,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = fg)
                ) { Text("Done", color = bg, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/** The little "Recording"/"Listening" pill shown over the chat. */
@Composable
fun RecordingOverlay(fg: Color, bg: Color, isSiriMode: Boolean) {    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .border(0.5.dp, fg.copy(alpha = 0.25f), RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val alpha by rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = 0.9f, targetValue = 0.25f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alpha"
        )
        Box(Modifier.size(7.dp).background(fg.copy(alpha = alpha), CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (isSiriMode) "Listening" else "Recording",
            color = fg,
            fontFamily = Montserrat,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
