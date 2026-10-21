package com.frit.app.ui.creative.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.frit.app.features.creative.CreativeTemplate

@Composable
fun TemplatePreviewCard(
    template: CreativeTemplate,
    onUse: (CreativeTemplate) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp)
            .clickable { onUse(template) },
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(
                        when (template.aspectRatio) {
                            "9:16" -> 9f / 16f
                            "16:9" -> 16f / 9f
                            "4:5" -> 4f / 5f
                            else -> 1f
                        }
                    )
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                // F-13 follow-up: the API returns a thumbnail for each
                // template and the gallery drew a grey box with the aspect
                // ratio instead — the user saw no pictures at all. Render the
                // real thumbnail, and keep the aspect-ratio label as the
                // placeholder when there is none.
                if (template.thumbnailUrl != null) {
                    CreativeImage(
                        source = template.thumbnailUrl,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        label = "",
                    )
                } else {
                    Text(
                        template.aspectRatio,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                template.name,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
            Text(
                "${template.category} • ${template.platform}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
