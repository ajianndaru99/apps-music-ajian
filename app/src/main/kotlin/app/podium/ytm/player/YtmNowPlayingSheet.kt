package app.podium.ytm.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.AppGraph
import app.podium.core.designsystem.artwork.LocalArtworkLoader
import app.podium.core.designsystem.tonal.TonePalette
import app.podium.feature.online.OnlineActions
import app.podium.player.api.PlayIntent
import app.podium.player.api.PlaybackStatus
import app.podium.player.api.RepeatMode
import app.podium.ytm.YtmArtworkImage
import app.podium.ytm.YtmTheme
import app.podium.ytm.formatDurationMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
fun YtmNowPlayingSheet(
    graph: AppGraph,
    actions: OnlineActions,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = YtmTheme.colors
    val controller = graph.playbackController
    val snapshot by controller.snapshot.collectAsStateWithLifecycle()
    val item = snapshot.item
    val likedIds by graph.favorites.favorites.collectAsStateWithLifecycle()
    val artworkLoader = LocalArtworkLoader.current

    // Dynamic color extraction from album art (using Podium's TonePalette)
    var palette by remember { mutableStateOf(TonePalette.Neutral) }
    val artworkUri = item?.artworkUri
    LaunchedEffect(artworkUri) {
        if (artworkUri == null) {
            palette = TonePalette.Neutral
            return@LaunchedEffect
        }
        val extracted = withContext(Dispatchers.Default) {
            val bmp = artworkLoader.load(artworkUri, 96)?.asAndroidBitmap() ?: return@withContext TonePalette.Neutral
            val w = bmp.width
            val h = bmp.height
            if (w <= 0 || h <= 0) return@withContext TonePalette.Neutral
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            TonePalette.fromPixels(pixels)
        }
        palette = extracted
    }

    val topBg by animateColorAsState(
        targetValue = palette.surfaceHigh,
        animationSpec = tween(550),
        label = "ytm_np_top",
    )
    val midBg by animateColorAsState(
        targetValue = palette.surfaceLow,
        animationSpec = tween(550),
        label = "ytm_np_mid",
    )

    // 0 = Player, 1 = Up Next, 2 = Lyrics, 3 = Related
    var activeTab by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(topBg, midBg, colors.background),
                ),
            )
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Collapse",
                        tint = colors.onSurface,
                        modifier = Modifier.size(30.dp),
                    )
                }
                Text(
                    text = item?.albumTitle ?: "Now playing",
                    style = YtmTheme.type.labelMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Spacer(Modifier.width(48.dp))
            }

            if (item == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Nothing playing",
                        style = YtmTheme.type.titleLarge,
                        color = colors.onSurfaceVariant,
                    )
                }
            } else if (activeTab == 0) {
                // Full player view
                val isPlaying = snapshot.intent == PlayIntent.PLAY
                val isLiked = item.trackId in likedIds

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceEvenly,
                ) {
                    YtmArtworkImage(
                        uri = item.artworkUri,
                        size = 300.dp,
                        fallbackText = item.title,
                        cornerRadius = 12.dp,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                style = YtmTheme.type.headlineLarge,
                                color = colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = item.artistDisplay,
                                style = YtmTheme.type.bodyLarge,
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
                    }

                    // Seekbar
                    val durationMs = item.durationMs ?: 0L
                    var isDragging by remember(item.uid) { mutableStateOf(false) }
                    var sliderPos by remember(item.uid) { mutableFloatStateOf(0f) }
                    var positionMs by remember(item.uid) { mutableLongStateOf(0L) }

                    LaunchedEffect(item.uid, durationMs, isPlaying, isDragging) {
                        while (isActive && !isDragging) {
                            val currentPos = controller.positionMs().coerceAtLeast(0L)
                            positionMs = currentPos
                            sliderPos = if (durationMs > 0L) {
                                (currentPos.toFloat() / durationMs).coerceIn(0f, 1f)
                            } else 0f
                            delay(if (isPlaying) 250L else 800L)
                        }
                    }

                    Column(modifier = Modifier.fillMaxWidth()) {
                        Slider(
                            value = sliderPos,
                            onValueChange = {
                                isDragging = true
                                sliderPos = it
                            },
                            onValueChangeFinished = {
                                if (durationMs > 0L && snapshot.controls.seek) {
                                    val targetMs = (sliderPos * durationMs).toLong()
                                    positionMs = targetMs
                                    controller.seekTo(targetMs)
                                }
                                isDragging = false
                            },
                            enabled = snapshot.controls.seek && durationMs > 0L,
                            colors = SliderDefaults.colors(
                                thumbColor = colors.onSurface,
                                activeTrackColor = colors.onSurface,
                                inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                            ),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            val displayPosMs = if (isDragging && durationMs > 0L) {
                                (sliderPos * durationMs).toLong()
                            } else positionMs
                            Text(
                                text = formatDurationMs(displayPosMs),
                                style = YtmTheme.type.labelSmall,
                                color = colors.onSurfaceVariant,
                            )
                            Text(
                                text = if (durationMs > 0L) formatDurationMs(durationMs) else "--:--",
                                style = YtmTheme.type.labelSmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }

                    // Transport controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = { controller.setShuffle(!snapshot.shuffleEnabled) },
                            enabled = snapshot.controls.shuffle,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Shuffle,
                                contentDescription = "Shuffle",
                                tint = if (snapshot.shuffleEnabled) colors.onSurface else colors.onSurfaceMuted,
                            )
                        }
                        IconButton(
                            onClick = { controller.previous() },
                            enabled = snapshot.controls.previous,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SkipPrevious,
                                contentDescription = "Previous",
                                tint = colors.onSurface,
                                modifier = Modifier.size(36.dp),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(68.dp)
                                .clip(CircleShape)
                                .background(colors.onSurface)
                                .clickable(enabled = snapshot.controls.playPause) {
                                    controller.togglePlayPause()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            val isBuffering = snapshot.status is PlaybackStatus.Loading ||
                                snapshot.status is PlaybackStatus.Buffering
                            if (isBuffering) {
                                CircularProgressIndicator(
                                    color = colors.background,
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(30.dp),
                                )
                            } else {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    tint = colors.background,
                                    modifier = Modifier.size(36.dp),
                                )
                            }
                        }
                        IconButton(
                            onClick = { controller.next() },
                            enabled = snapshot.controls.next,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SkipNext,
                                contentDescription = "Next",
                                tint = colors.onSurface,
                                modifier = Modifier.size(36.dp),
                            )
                        }
                        IconButton(
                            onClick = {
                                val nextRepeat = when (snapshot.repeatMode) {
                                    RepeatMode.OFF -> RepeatMode.ALL
                                    RepeatMode.ALL -> RepeatMode.ONE
                                    RepeatMode.ONE -> RepeatMode.OFF
                                }
                                controller.setRepeat(nextRepeat)
                            },
                            enabled = snapshot.controls.repeat,
                        ) {
                            Icon(
                                imageVector = if (snapshot.repeatMode == RepeatMode.ONE) {
                                    Icons.Filled.RepeatOne
                                } else {
                                    Icons.Filled.Repeat
                                },
                                contentDescription = "Repeat",
                                tint = if (snapshot.repeatMode != RepeatMode.OFF) {
                                    colors.onSurface
                                } else {
                                    colors.onSurfaceMuted
                                },
                            )
                        }
                    }
                }
            } else {
                // Tab content (Up Next / Lyrics / Related)
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (activeTab) {
                        1 -> YtmUpNextTab(graph = graph)
                        2 -> YtmLyricsTab(graph = graph)
                        3 -> YtmRelatedTab(graph = graph, actions = actions)
                    }
                }
            }

            // Bottom 3-tab switcher (plus Player toggle if currently in a sub-tab)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                val tabs = listOf(1 to "UP NEXT", 2 to "LYRICS", 3 to "RELATED")
                tabs.forEach { (tabIndex, label) ->
                    val selected = activeTab == tabIndex
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) Color.White.copy(alpha = 0.16f) else Color.Transparent)
                            .clickable { activeTab = if (selected) 0 else tabIndex }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = label,
                            style = YtmTheme.type.labelMedium.copy(
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            ),
                            color = if (selected) colors.onSurface else colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
