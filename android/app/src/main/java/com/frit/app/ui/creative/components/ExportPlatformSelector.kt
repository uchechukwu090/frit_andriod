package com.frit.app.ui.creative.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

data class ExportPlatform(
    val id: String,
    val name: String,
    val resolution: String,
    val aspectRatio: String
)

val EXPORT_PLATFORMS = listOf(
    ExportPlatform("tiktok", "TikTok", "1080x1920", "9:16"),
    ExportPlatform("instagram", "Instagram", "1080x1080", "1:1"),
    ExportPlatform("instagram_story", "IG Story", "1080x1920", "9:16"),
    ExportPlatform("youtube", "YouTube", "1920x1080", "16:9"),
    ExportPlatform("youtube_short", "YT Short", "1080x1920", "9:16"),
    ExportPlatform("twitter", "Twitter/X", "1920x1080", "16:9"),
    ExportPlatform("facebook", "Facebook", "1920x1080", "16:9")
)

@Composable
fun ExportPlatformSelector(
    selectedPlatform: String?,
    onPlatformSelected: (String) -> Unit
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        items(EXPORT_PLATFORMS) { platform ->
            val selected = platform.id == selectedPlatform
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onPlatformSelected(platform.id) },
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = if (selected) 4.dp else 0.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        platform.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        platform.aspectRatio,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}
