package app.podium.ytm.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.AppGraph
import app.podium.player.api.PlayIntent
import app.podium.ytm.YtmArtworkImage
import app.podium.ytm.YtmTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun YtmMiniPlayer(
    graph: AppGraph,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = YtmTheme.colors
    val controller = graph.playbackController
    val snapshot by controller.snapshot.collectAsStateWithLifecycle()
    val item = snapshot.item ?: return
    val likedIds by graph.favorites.favorites.collectAsStateWithLifecycle()
    val isLiked = item.trackId in likedIds
    val isPlaying = snapshot.intent == PlayIntent.PLAY

    val dur = item.durationMs ?: 0L
    var progress by remember(item.uid) { mutableFloatStateOf(0f) }
    LaunchedEffect(item.uid, dur, isPlaying) {
        while (isActive) {
            progress = if (dur > 0L) (controller.positionMs().toFloat() / dur).coerceIn(0f, 1f) else 0f
            delay(if (isPlaying) 400L else 1000L)
        }
    }

    var dragAccum by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface)
            .clickable(onClick = onOpen)
            .pointerInput(item.uid) {
                detectHorizontalDragGestures(
                    onDragStart = { dragAccum = 0f },
                    onHorizontalDrag = { _, dragAmount -> dragAccum += dragAmount },
                    onDragEnd = {
                        if (dragAccum < -90f) controller.next()
                        else if (dragAccum > 90f) controller.previous()
                        dragAccum = 0f
                    },
                )
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            YtmArtworkImage(
                uri = item.artworkUri,
                size = 44.dp,
                fallbackText = item.title,
                cornerRadius = 4.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = YtmTheme.type.titleMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.artistDisplay,
                    style = YtmTheme.type.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { graph.favorites.toggle(item.trackId) }) {
                Icon(
                    imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = "Like",
                    tint = if (isLiked) colors.primary else colors.onSurface,
                )
            }
            IconButton(
                onClick = { controller.togglePlayPause() },
                enabled = snapshot.controls.playPause,
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = colors.onSurface,
                )
            }
            IconButton(
                onClick = { controller.next() },
                enabled = snapshot.controls.next,
            ) {
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = "Next",
                    tint = colors.onSurface,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(colors.divider),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(2.dp)
                    .background(colors.onSurface),
            )
        }
    }
}
