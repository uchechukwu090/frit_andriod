package com.frit.app.ui.creative

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.frit.app.features.creative.CreativeCapabilities
import com.frit.app.features.creative.CreativeSuiteEngine
import com.frit.app.ui.creative.components.CreativeTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class StudioCard(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)

/**
 * Creative Suite hub — studios only.
 *
 * The "Edit & Manage" block (Image Editor, Video Editor, Storyboard,
 * Templates, Batch Edit, Export, Projects) has been removed from the hub. The
 * studios still reach Export through the in-studio handoff (top-bar Export),
 * so nothing in the production flow depends on those tiles.
 *
 * The capability footer names the ACTIVE image/video models and the options
 * they accept, so a studio can never offer a setting its model rejects.
 */
@Composable
fun CreativeSuiteScreen(
    onClose: () -> Unit = {},
    onGenerate: () -> Unit = {},
    onCinema: () -> Unit = {},
    onKatana: () -> Unit = {},
    onGenjutsu: () -> Unit = {},
    onAds: () -> Unit = {},
    onInfluencer: () -> Unit = {},
    onCharacters: () -> Unit = {},
    onLipSync: () -> Unit = {},
    onBrandKit: () -> Unit = {},
    onDesign: () -> Unit = {},
    onDirector: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var caps by remember { mutableStateOf(CreativeSuiteEngine.cachedCapabilities() ?: CreativeCapabilities.FALLBACK) }
    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) { caps = CreativeSuiteEngine.capabilities() }
    }

    val studios = listOf(
        StudioCard("Generate", "Prompt or import → image & video", Icons.Default.AutoAwesome, onGenerate),
        StudioCard("Cinema Studio", "Director's Panel: camera, lens, light, grade", Icons.Default.Movie, onCinema),
        StudioCard("Katana", "One-command video edits", Icons.Default.Bolt, onKatana),
        StudioCard("Genjutsu", "Motion transfer & object swap", Icons.Default.Transform, onGenjutsu),
        StudioCard("Ads Studio", "Product ads & UGC from image/URL", Icons.Default.Campaign, onAds),
        StudioCard("AI Influencer", "Consistent virtual persona", Icons.Default.Person, onInfluencer),
        StudioCard("Characters", "Soul ID consistency elements", Icons.Default.Face, onCharacters),
        StudioCard("Lip-Sync", "Voiceover dub + timed captions", Icons.Default.RecordVoiceOver, onLipSync),
        StudioCard("Brand Kit", "Lock colors, font & mood", Icons.Default.BrandingWatermark, onBrandKit),
        StudioCard("Design Studio", "Flyers, posters, thumbnails", Icons.Default.Brush, onDesign),
        StudioCard("AI Director", "Brief → planned, shot & edited film", Icons.Default.MovieCreation, onDirector),
    )

    Scaffold(
        topBar = {
            CreativeTopBar(title = "Creative Suite", onBack = onClose, compact = true)
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Studios", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground)
            }
            // Plain rows instead of a nested LazyVerticalGrid with a hardcoded
            // height: the old one clipped the last row on small screens and at
            // large font scales (fixed 780.dp for a list that needed more).
            items(studios.chunked(2)) { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { s -> StudioGridCard(s, Modifier.weight(1f)) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            item {
                Spacer(Modifier.height(4.dp))
                CapabilityFooter(caps)
            }
        }
    }
}

@Composable
private fun CapabilityFooter(caps: CreativeCapabilities) {
    val image = caps.image
    val video = caps.video
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Active models", style = MaterialTheme.typography.labelMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Image · ${image.label.ifBlank { image.model }}" +
                    if (image.summary.isNotBlank()) " — ${image.summary}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Video · ${video.label.ifBlank { video.model }}" +
                    if (video.summary.isNotBlank()) " — ${video.summary}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun StudioGridCard(s: StudioCard, modifier: Modifier = Modifier) {
    Card(onClick = s.onClick, modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Icon(s.icon, contentDescription = s.title, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(s.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(s.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
