package app.podium.ytm

import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.podium.AppGraph
import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import app.podium.core.designsystem.artwork.ArtworkState
import app.podium.core.designsystem.artwork.rememberArtworkState
import app.podium.core.model.ArtistId
import app.podium.core.model.PlaylistId
import app.podium.core.model.Track
import app.podium.feature.online.OnlineActions
import app.podium.player.api.RecommendationRequest
import app.podium.sources.api.ArtistSummary
import app.podium.sources.api.RemoteContext
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Renders album/artist artwork using Podium's [rememberArtworkState] and [LocalArtworkLoader],
 * styled for YouTube Music without depending on PodiumTheme.
 */
@Composable
fun YtmArtworkImage(
    uri: String?,
    size: Dp,
    fallbackText: String,
    modifier: Modifier = Modifier,
    round: Boolean = false,
    cornerRadius: Dp = 6.dp,
) {
    val colors = YtmTheme.colors
    val shape = if (round) CircleShape else RoundedCornerShape(cornerRadius)
    val art: ArtworkState = rememberArtworkState(uri, size)

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(colors.surface),
        contentAlignment = Alignment.Center,
    ) {
        if (art.bitmap == null && art.settled) {
            YtmArtworkFallback(fallbackText, Modifier.fillMaxSize())
        }
        if (art.bitmap == null) {
            art.placeholder?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        art.bitmap?.let { bitmap ->
            YtmFadeInImage(
                bitmap = bitmap,
                immediate = art.fromCache || art.placeholder != null,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun YtmFadeInImage(
    bitmap: ImageBitmap,
    immediate: Boolean,
    modifier: Modifier = Modifier,
) {
    val alpha = remember(bitmap) { Animatable(if (immediate) 1f else 0f) }
    LaunchedEffect(bitmap) {
        if (alpha.value < 1f) alpha.animateTo(1f, tween(200))
    }
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.graphicsLayer { this.alpha = alpha.value },
    )
}

@Composable
private fun YtmArtworkFallback(
    seed: String,
    modifier: Modifier = Modifier,
) {
    val hue = remember(seed) { abs(seed.hashCode()) % 360 }
    val top = remember(hue) { Color.hsl(hue.toFloat(), 0.25f, 0.28f) }
    val bottom = remember(hue) { Color.hsl(((hue + 28) % 360).toFloat(), 0.30f, 0.16f) }
    val initial = remember(seed) { seed.trim().firstOrNull()?.uppercase() ?: "" }

    Box(
        modifier = modifier.background(Brush.linearGradient(listOf(top, bottom))),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                color = Color.White.copy(alpha = 0.14f),
                radius = size.minDimension * 0.30f,
                style = Stroke(width = size.minDimension * 0.035f),
            )
        }
        if (initial.isNotEmpty()) {
            Text(
                text = initial,
                color = Color.White.copy(alpha = 0.65f),
                style = YtmTheme.type.titleLarge,
            )
        }
    }
}

@Composable
fun YtmSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = YtmTheme.type.headlineMedium,
            color = YtmTheme.colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(YtmTheme.colors.chipUnselectedBg)
                    .clickable(onClick = onAction)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = actionLabel,
                    style = YtmTheme.type.labelMedium,
                    color = YtmTheme.colors.onSurface,
                )
            }
        }
    }
}

@Composable
fun YtmFilterChips(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = YtmTheme.colors
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(options.size) { index ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) colors.chipSelectedBg else colors.chipUnselectedBg)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = options[index],
                    style = YtmTheme.type.labelMedium.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                    color = if (selected) colors.chipSelectedFg else colors.chipUnselectedFg,
                )
            }
        }
    }
}

@Composable
fun YtmTrackRow(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    indexLabel: String? = null,
    isPlaying: Boolean = false,
    onPlayNext: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onStartRadio: (() -> Unit)? = null,
    onToggleLike: (() -> Unit)? = null,
    isLiked: Boolean = false,
    onOpenArtist: (() -> Unit)? = null,
    onOpenAlbum: (() -> Unit)? = null,
) {
    val colors = YtmTheme.colors
    var menuExpanded by remember { mutableStateOf(false) }
    val hasMenu = onPlayNext != null || onAddToQueue != null || onStartRadio != null ||
        onToggleLike != null || onOpenArtist != null || onOpenAlbum != null

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (indexLabel != null) {
            Text(
                text = indexLabel,
                style = YtmTheme.type.bodyMedium,
                color = if (isPlaying) colors.primary else colors.onSurfaceVariant,
                modifier = Modifier.width(28.dp),
            )
        } else {
            YtmArtworkImage(
                uri = track.artwork?.uri,
                size = 50.dp,
                fallbackText = track.title,
                cornerRadius = 4.dp,
            )
            Spacer(Modifier.width(12.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = YtmTheme.type.titleMedium,
                color = if (isPlaying) colors.primary else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            val duration = track.durationMs?.let { formatDurationMs(it) }
            val subtitle = buildString {
                append(track.artistDisplay.ifBlank { "Unknown artist" })
                if (duration != null) {
                    append(" • ")
                    append(duration)
                }
            }
            Text(
                text = subtitle,
                style = YtmTheme.type.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (hasMenu) {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "More options",
                        tint = colors.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    containerColor = colors.surfaceElevated,
                ) {
                    onPlayNext?.let {
                        DropdownMenuItem(
                            text = { Text("Play next", color = colors.onSurface) },
                            onClick = {
                                menuExpanded = false
                                it()
                            },
                        )
                    }
                    onAddToQueue?.let {
                        DropdownMenuItem(
                            text = { Text("Add to queue", color = colors.onSurface) },
                            onClick = {
                                menuExpanded = false
                                it()
                            },
                        )
                    }
                    onStartRadio?.let {
                        DropdownMenuItem(
                            text = { Text("Start radio", color = colors.onSurface) },
                            onClick = {
                                menuExpanded = false
                                it()
                            },
                        )
                    }
                    onToggleLike?.let {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (isLiked) "Remove from liked songs" else "Add to liked songs",
                                    color = colors.onSurface,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                it()
                            },
                        )
                    }
                    onOpenAlbum?.let {
                        DropdownMenuItem(
                            text = { Text("Go to album", color = colors.onSurface) },
                            onClick = {
                                menuExpanded = false
                                it()
                            },
                        )
                    }
                    onOpenArtist?.let {
                        DropdownMenuItem(
                            text = { Text("Go to artist", color = colors.onSurface) },
                            onClick = {
                                menuExpanded = false
                                it()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun YtmSquareCard(
    title: String,
    subtitle: String?,
    artworkUri: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 148.dp,
    round: Boolean = false,
) {
    val colors = YtmTheme.colors
    Column(
        modifier = modifier
            .width(size)
            .clickable(onClick = onClick),
    ) {
        YtmArtworkImage(
            uri = artworkUri,
            size = size,
            fallbackText = title,
            round = round,
            cornerRadius = 8.dp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            style = YtmTheme.type.titleMedium,
            color = colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = YtmTheme.type.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun rememberYtmOnlineActions(
    graph: AppGraph,
    onOpenPlayer: () -> Unit,
): OnlineActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(graph, context) {
        val controller = graph.playbackController
        object : OnlineActions {
            private val radioSize = 25

            private fun startRadioInternal(seed: List<Track>, label: String) {
                if (seed.isEmpty()) return
                graph.catalog.remember(seed)
                controller.playContext(seed.map { it.id }, 0, label, radio = true)
                onOpenPlayer()
            }

            override fun play(tracks: List<Track>, index: Int, label: String) {
                if (tracks.isEmpty()) return
                graph.catalog.remember(tracks)
                controller.playContext(tracks.map { it.id }, index.coerceIn(0, tracks.lastIndex), label)
                onOpenPlayer()
            }

            override fun shuffle(tracks: List<Track>, label: String) {
                if (tracks.isEmpty()) return
                graph.catalog.remember(tracks)
                controller.playContext(tracks.map { it.id }, tracks.indices.random(), label, shuffle = true)
                onOpenPlayer()
            }

            override fun playNext(track: Track) {
                graph.catalog.remember(listOf(track))
                controller.playNext(listOf(track.id))
            }

            override fun addToQueue(track: Track) {
                graph.catalog.remember(listOf(track))
                controller.addToQueue(listOf(track.id))
            }

            override fun playAlone(track: Track) {
                graph.catalog.remember(listOf(track))
                if (graph.playsRemotely(track)) {
                    controller.playCollection(listOf(track.id), 0, "${track.title} radio", RemoteContext.Radio(track))
                } else {
                    controller.playContext(listOf(track.id), 0, track.title)
                }
                onOpenPlayer()
            }

            override fun startRadio(track: Track) {
                graph.catalog.remember(listOf(track))
                if (graph.playsRemotely(track)) {
                    controller.playCollection(listOf(track.id), 0, "${track.title} radio", RemoteContext.Radio(track))
                    onOpenPlayer()
                    return
                }
                scope.launch {
                    val more = graph.recommendations.recommend(RecommendationRequest(listOf(track), setOf(track.id), radioSize))
                    startRadioInternal(listOf(track) + more.filter { it.id != track.id }, "${track.title} radio")
                }
            }

            override fun startArtistRadio(artist: ArtistSummary) {
                scope.launch {
                    val top = (graph.online.artist(artist.id) as? Outcome.Success)?.value?.tracks?.firstOrNull()
                    if (top != null && graph.playsRemotely(top)) {
                        graph.catalog.remember(listOf(top))
                        controller.playCollection(listOf(top.id), 0, "${artist.name} radio", RemoteContext.ArtistRadio(artist.id))
                        onOpenPlayer()
                        return@launch
                    }
                    val tracks = graph.recommendations.artistRadio(artist.id, artist.id.sourceId, radioSize, emptySet())
                    startRadioInternal(tracks, "${artist.name} radio")
                }
            }

            override fun playCollection(
                tracks: List<Track>,
                index: Int,
                label: String,
                collection: PlaylistId,
                shuffle: Boolean,
            ) {
                if (tracks.isEmpty()) return
                graph.catalog.remember(tracks)
                val start = if (shuffle) tracks.indices.random() else index.coerceIn(0, tracks.lastIndex)
                controller.playCollection(tracks.map { it.id }, start, label, RemoteContext.Collection(collection), shuffle)
                onOpenPlayer()
            }

            override fun signIn() = graph.onlineService.signIn()

            override fun startGenreRadio(genre: String) {
                scope.launch {
                    val tracks = (graph.online.genre(genre, 0, radioSize) as? Outcome.Success)?.value.orEmpty().shuffled()
                    startRadioInternal(tracks, "$genre radio")
                }
            }

            override fun share(track: Track) {
                val link = track.source.providerUri ?: return
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "${track.title} — ${track.artistDisplay}")
                    .putExtra(Intent.EXTRA_TEXT, link)
                context.startActivity(Intent.createChooser(send, track.title))
            }

            override val nowPlaying = kotlinx.coroutines.flow.MutableStateFlow<Track?>(null)
        }
    }
}

fun Track.openArtistAction(onOpenArtist: (ArtistId, String) -> Unit): (() -> Unit)? {
    val artist = primaryArtists.firstOrNull() ?: return null
    val id = artist.id ?: return null
    return { onOpenArtist(id, artist.name) }
}

fun Track.openAlbumAction(onOpenCollection: (PlaylistId, String) -> Unit): (() -> Unit)? {
    val albumRef = album ?: return null
    val id = albumRef.id ?: return null
    return { onOpenCollection(PlaylistId(id.value), albumRef.title) }
}

fun PodiumError.toDisplayMessage(): String = when (this) {
    PodiumError.Offline -> "You're offline. Check your connection and try again."
    is PodiumError.Network -> detail ?: "Network error. Check your connection."
    is PodiumError.Server -> "Server error${httpStatus?.let { " ($it)" }.orEmpty()}. Try again later."
    is PodiumError.AuthRequired, is PodiumError.AuthExpired -> "Please sign in to YouTube Music to continue."
    is PodiumError.RateLimited -> "Too many requests. Please wait a moment and try again."
    is PodiumError.NotFound -> "Couldn't find $what."
    is PodiumError.NotPlayable -> reason
    is PodiumError.UserActionRequired -> action
    is PodiumError.Unsupported -> "Not supported: $what"
    is PodiumError.InvalidMedia -> detail ?: "Invalid media."
    is PodiumError.UnsupportedFormat -> "Unsupported format${codec?.let { " ($it)" }.orEmpty()}."
    is PodiumError.PolicyDisabled -> detail ?: "Disabled by policy."
    is PodiumError.PermissionRequired -> "Permission required: $permission"
    is PodiumError.Unexpected -> detail ?: "Something went wrong."
}

fun formatDurationMs(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
