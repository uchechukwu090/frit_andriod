package com.frit.app.ui.creative

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.frit.app.ui.creative.components.CreativeTopBar

data class StudioCard(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)

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
    onImageEdit: (String) -> Unit = {},
    onVideoEdit: (String) -> Unit = {},
    onStoryboard: () -> Unit = {},
    onTemplates: () -> Unit = {},
    onBatchEdit: () -> Unit = {},
    onExport: () -> Unit = {},
    onProjects: () -> Unit = {}
) {
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
    val tools = listOf(
        StudioCard("Image Editor", "Layers, masks, filters, retouch", Icons.Default.Image, { onImageEdit("") }),
        StudioCard("Video Editor", "Timeline, subtitles, dub", Icons.Default.Videocam, { onVideoEdit("") }),
        StudioCard("Storyboard", "Script → visual frames", Icons.Default.ViewCarousel, onStoryboard),
        StudioCard("Templates", "Posts, ads, reels, thumbnails", Icons.Default.GridView, onTemplates),
        StudioCard("Batch Edit", "One edit → many images", Icons.Default.Layers, onBatchEdit),
        StudioCard("Export", "TikTok, Reels, YouTube presets", Icons.Default.Share, onExport),
        StudioCard("Projects", "Save, sync & collaborate", Icons.Default.FolderOpen, onProjects),
    )

    Scaffold(
        topBar = {
            CreativeTopBar(title = "Creative Suite", onBack = onClose, compact = true)
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text("Studios", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            }
            item {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxWidth().height(780.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    userScrollEnabled = false
                ) {
                    items(studios) { s -> StudioGridCard(s) }
                }
            }
            item {
                Text("Edit & Manage", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            }
            item {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxWidth().height(490.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    userScrollEnabled = false
                ) {
                    items(tools) { s -> StudioGridCard(s) }
                }
            }
        }
    }
}

@Composable
fun StudioGridCard(s: StudioCard) {
    Card(onClick = s.onClick, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Icon(s.icon, contentDescription = s.title, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(s.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(2.dp))
            Text(s.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
