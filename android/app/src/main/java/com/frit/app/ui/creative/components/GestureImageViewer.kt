package com.frit.app.ui.creative.components

import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter

@Composable
fun GestureImageViewer(
    imageUrl: String,
    modifier: Modifier = Modifier,
    onTransform: (Float, Offset, Float) -> Unit = { _, _, _ -> },
    onTap: () -> Unit = {},
    onDoubleTap: () -> Unit = {},
    onLongPress: () -> Unit = {}
) {
    // F-13 follow-up: the zoom/pan/rotation state was NOT keyed on the image,
    // so after an edit the NEW picture opened at the previous zoom and pan —
    // which looks exactly like "the preview did not update". Key it on the
    // image so every new image starts from a clean view.
    var scale by remember(imageUrl) { mutableFloatStateOf(1f) }
    var offset by remember(imageUrl) { mutableStateOf(Offset.Zero) }
    var rotation by remember(imageUrl) { mutableFloatStateOf(0f) }

    val state = rememberTransformableState { zoomChange, offsetChange, rotationChange ->
        scale = (scale * zoomChange).coerceIn(0.5f, 5f)
        offset += offsetChange
        rotation += rotationChange
        onTransform(scale, offset, rotation)
    }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { onDoubleTap() },
                    onLongPress = { onLongPress() }
                )
            }
            .transformable(state = state)
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                    rotationZ = rotation
                }
        )
    }
}
