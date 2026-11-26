package app.podium.ytm.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.AppGraph
import app.podium.core.common.Outcome
import app.podium.core.model.ArtistId
import app.podium.core.model.PlaylistId
import app.podium.feature.online.OnlineActions
import app.podium.sources.api.ArtistDetail
import app.podium.sources.api.ArtistSummary
import app.podium.ytm.YtmArtworkImage
import app.podium.ytm.YtmSectionHeader
import app.podium.ytm.YtmSquareCard
import app.podium.ytm.YtmTheme
import app.podium.ytm.YtmTrackRow

@Composable
fun YtmArtistScreen(
    id: ArtistId,
    initialName: String,
    graph: AppGraph,
    actions: OnlineActions,
    onBack: () -> Unit,
    onOpenCollection: (PlaylistId, String) -> Unit,
    onOpenArtist: (ArtistId, String) -> Unit,
) {
    val colors = YtmTheme.colors
    val snapshot by graph.playbackController.snapshot.collectAsStateWithLifecycle()
    val likedIds by graph.favorites.favorites.collectAsStateWithLifecycle()

    var detail by remember(id) { mutableStateOf<ArtistDetail?>(null) }
    var related by remember(id) { mutableStateOf<List<ArtistSummary>>(emptyList()) }
    var loading by remember(id) { mutableStateOf(true) }
    var errorMessage by remember(id) { mutableStateOf<String?>(null) }

    LaunchedEffect(id) {
        loading = true
        errorMessage = null
        when (val result = graph.online.artist(id)) {
            is Outcome.Success -> {
                detail = result.value
                loading = false
            }
            is Outcome.Failure -> {
                errorMessage = result.error.message
                loading = false
            }
        }
        if (graph.online.canRelate(id)) {
            val rel = graph.online.relatedArtists(id)
            if (rel is Outcome.Success) {
                related = rel.value
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = colors.onSurface,
                )
            }
            Text(
                text = detail?.summary?.name ?: initialName,
                style = YtmTheme.type.titleLarge,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        if (loading && detail == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.onSurface)
            }
        } else if (errorMessage != null && detail == null) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = errorMessage ?: "Unable to load artist",
                    style = YtmTheme.type.bodyLarge,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            val loaded = detail ?: return
            val summary = loaded.summary
            val tracks = loaded.tracks

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item(key = "header") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        YtmArtworkImage(
                            uri = summary.artwork?.uri,
                            size = 150.dp,
                            fallbackText = summary.name,
                            round = true,
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = summary.name,
                            style = YtmTheme.type.headlineLarge,
                            color = colors.onSurface,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        ) {
                            Button(
                                onClick = { actions.startArtistRadio(summary) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.accent,
                                    contentColor = colors.background,
                                ),
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Filled.Radio, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Radio", fontWeight = FontWeight.SemiBold)
                            }
                            OutlinedButton(
                                onClick = { actions.shuffle(tracks, summary.name) },
                                enabled = tracks.isNotEmpty(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface),
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Filled.Shuffle, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Shuffle", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                if (tracks.isNotEmpty()) {
                    item(key = "songs_header") {
                        YtmSectionHeader(
                            title = "Songs",
                            actionLabel = "Play all",
                            onAction = { actions.play(tracks, 0, summary.name) },
                        )
                    }
                    itemsIndexed(
                        items = tracks.take(10),
                        key = { idx, t -> "${idx}_${t.id.value}" },
                    ) { index, track ->
                        YtmTrackRow(
                            track = track,
                            isPlaying = snapshot.item?.trackId == track.id,
                            isLiked = track.id in likedIds,
                            onClick = { actions.play(tracks, index, summary.name) },
                            onPlayNext = { actions.playNext(track) },
                            onAddToQueue = { actions.addToQueue(track) },
                            onStartRadio = if (graph.online.canStartRadio(track)) {
                                { actions.startRadio(track) }
                            } else null,
                            onToggleLike = { graph.favorites.toggle(track.id) },
                            onOpenAlbum = track.album?.id?.let { albumId ->
                                val albumTitle = track.album?.title.orEmpty()
                                { onOpenCollection(PlaylistId(albumId.value), albumTitle) }
                            },
                        )
                    }
                }

                if (loaded.albums.isNotEmpty()) {
                    item(key = "albums_header") { YtmSectionHeader(title = "Albums") }
                    item(key = "albums_row") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(loaded.albums, key = { "alb_${it.id.value}" }) { album ->
                                YtmSquareCard(
                                    title = album.title,
                                    subtitle = album.year?.toString() ?: album.artistDisplay,
                                    artworkUri = album.artwork?.uri,
                                    onClick = { onOpenCollection(PlaylistId(album.id.value), album.title) },
                                )
                            }
                        }
                    }
                }

                if (loaded.playlists.isNotEmpty()) {
                    item(key = "playlists_header") { YtmSectionHeader(title = "Playlists") }
                    item(key = "playlists_row") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(loaded.playlists, key = { "pl_${it.id.value}" }) { playlist ->
                                YtmSquareCard(
                                    title = playlist.title,
                                    subtitle = playlist.ownerName,
                                    artworkUri = playlist.artwork?.uri,
                                    onClick = { onOpenCollection(playlist.id, playlist.title) },
                                )
                            }
                        }
                    }
                }

                if (related.isNotEmpty()) {
                    item(key = "related_header") { YtmSectionHeader(title = "Fans might also like") }
                    item(key = "related_row") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(related, key = { "rel_${it.id.value}" }) { artist ->
                                YtmSquareCard(
                                    title = artist.name,
                                    subtitle = null,
                                    artworkUri = artist.artwork?.uri,
                                    round = true,
                                    onClick = { onOpenArtist(artist.id, artist.name) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
