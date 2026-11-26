package app.podium.ytm.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.AppGraph
import app.podium.core.common.Outcome
import app.podium.core.model.ArtistId
import app.podium.core.model.PlaylistId
import app.podium.core.model.Track
import app.podium.feature.online.OnlineActions
import app.podium.ytm.YtmTheme
import app.podium.ytm.YtmTrackRow
import app.podium.ytm.openAlbumAction
import app.podium.ytm.openArtistAction

@Composable
fun YtmGenreScreen(
    genre: String,
    graph: AppGraph,
    actions: OnlineActions,
    onBack: () -> Unit,
    onOpenCollection: (PlaylistId, String) -> Unit,
    onOpenArtist: (ArtistId, String) -> Unit,
    searchQuery: String = genre,
) {
    val colors = YtmTheme.colors
    val snapshot by graph.playbackController.snapshot.collectAsStateWithLifecycle()
    val likedIds by graph.favorites.favorites.collectAsStateWithLifecycle()

    var tracks by remember(genre, searchQuery) { mutableStateOf<List<Track>>(emptyList()) }
    var loading by remember(genre, searchQuery) { mutableStateOf(true) }

    LaunchedEffect(genre, searchQuery) {
        loading = true
        val nativeTracks = when (val result = graph.online.genre(genre, offset = 0, limit = 50)) {
            is Outcome.Success -> result.value
            is Outcome.Failure -> emptyList()
        }
        tracks = if (nativeTracks.isNotEmpty()) {
            nativeTracks
        } else {
            graph.aiExplore.searchTracksForQuery(searchQuery.ifBlank { genre }, limit = 30)
        }
        loading = false
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
                text = genre,
                style = YtmTheme.type.titleLarge,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (tracks.isNotEmpty()) {
                Button(
                    onClick = { actions.play(tracks, 0, genre) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                        contentColor = colors.background,
                    ),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.padding(end = 12.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = colors.background)
                    Spacer(Modifier.width(4.dp))
                    Text("Play", color = colors.background, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.onSurface)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                itemsIndexed(tracks, key = { idx, t -> "${idx}_${t.id.value}" }) { index, track ->
                    YtmTrackRow(
                        track = track,
                        isPlaying = snapshot.item?.trackId == track.id,
                        isLiked = track.id in likedIds,
                        onClick = { actions.play(tracks, index, genre) },
                        onPlayNext = { actions.playNext(track) },
                        onAddToQueue = { actions.addToQueue(track) },
                        onStartRadio = if (graph.online.canStartRadio(track)) {
                            { actions.startRadio(track) }
                        } else null,
                        onToggleLike = { graph.favorites.toggle(track.id) },
                        onOpenArtist = track.openArtistAction(onOpenArtist),
                        onOpenAlbum = track.openAlbumAction(onOpenCollection),
                    )
                }
            }
        }
    }
}
