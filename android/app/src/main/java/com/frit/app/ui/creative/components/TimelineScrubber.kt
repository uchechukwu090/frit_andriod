package com.frit.app.ui.creative.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

data class TimelineClip(
    val id: String,
    val name: String,
    val duration: Float,
    val color: Long = 0xFF6200EE
)

@Composable
fun TimelineScrubber(
    clips: List<TimelineClip>,
    currentPosition: Float = 0f,
    totalDuration: Float = 10f,
    onPositionChange: (Float) -> Unit,
    onClipSelected: (String) -> Unit = {}
) {
    var isDragging by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            "Timeline — ${String.format("%.1f", currentPosition)}s / ${String.format("%.1f", totalDuration)}s",
            style = MaterialTheme.typography.labelMedium
        )
        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { isDragging = true },
                        onDragEnd = { isDragging = false },
                        onHorizontalDrag = { change, _ ->
                            val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                            onPositionChange(fraction * totalDuration)
                        }
                    )
                }
        ) {
            var xOffset = 0.dp
            clips.forEach { clip ->
                val widthDp = (clip.duration / totalDuration * 300).dp
                Surface(
                    modifier = Modifier
                        .offset(x = xOffset)
                        .width(widthDp)
                        .fillMaxHeight()
                        .padding(2.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(4.dp),
                    onClick = { onClipSelected(clip.id) }
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            clip.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
                xOffset += widthDp
            }

            val playheadX = (currentPosition / totalDuration * 300).dp
            Box(
                modifier = Modifier
                    .offset(x = playheadX)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.error)
            )
        }
    }
}
