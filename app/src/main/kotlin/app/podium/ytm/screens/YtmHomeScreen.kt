package app.podium.ytm.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.AppGraph
import app.podium.core.common.Outcome
import app.podium.core.model.ArtistId
import app.podium.core.model.PlaylistId
import app.podium.feature.online.OnlineActions
import app.podium.sources.api.Shelf
import app.podium.ytm.YtmFilterChips
import app.podium.ytm.YtmSectionHeader
import app.podium.ytm.YtmSquareCard
import app.podium.ytm.YtmTheme
import app.podium.ytm.YtmTrackRow
import app.podium.ytm.openAlbumAction
import app.podium.ytm.openArtistAction

@Composable
fun YtmHomeScreen(
    graph: AppGraph,
    actions: OnlineActions,
    onOpenCollection: (PlaylistId, String) -> Unit,
    onOpenArtist: (ArtistId, String) -> Unit,
    onSignIn: () -> Unit,
) {
    val colors = YtmTheme.colors
    val status by graph.online.status.collectAsStateWithLifecycle()
    val recentlyPlayed by graph.online.recentlyPlayed.collectAsStateWithLifecycle(initialValue = emptyList())
    val likedIds by graph.favorites.favorites.collectAsStateWithLifecycle()
    val snapshot by graph.playbackController.snapshot.collectAsStateWithLifecycle()

    var selectedChip by remember { mutableIntStateOf(0) }
    val chips = remember { listOf("All", "Quick picks", "Playlists", "Artists") }

    var shelves by remember { mutableStateOf<List<Shelf>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val accountKey = status?.account?.signedIn
    LaunchedEffect(accountKey) {
        loading = true
        errorMessage = null
        when (val result = graph.online.shelves()) {
            is Outcome.Success -> {
                shelves = result.value
                loading = false
            }
            is Outcome.Failure -> {
                errorMessage = result.error.message
                loading = false
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "chips") {
            YtmFilterChips(
                options = chips,
                selectedIndex = selectedChip,
                onSelect = { selectedChip = it },
            )
        }

        if (status?.account?.signedIn == false) {
            item(key = "signin_banner") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceVariant)
                        .padding(16.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Sign in to YouTube Music",
                                style = YtmTheme.type.titleMedium,
                                color = colors.onSurface,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Access your liked songs, playlists, and personalized picks",
                                style = YtmTheme.type.bodyMedium,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Button(
                            onClick = onSignIn,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.accent,
                                contentColor = colors.background,
                            ),
                            shape = RoundedCornerShape(50),
                        ) {
                            Text("Sign in", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        if (recentlyPlayed.isNotEmpty() && (selectedChip == 0 || selectedChip == 1)) {
            item(key = "recently_played_header") {
                YtmSectionHeader(
                    title = "Quick picks",
                    actionLabel = "Play all",
                    onAction = { actions.play(recentlyPlayed, 0, "Quick picks") },
                )
            }
            itemsIndexed(
                items = recentlyPlayed.take(6),
                key = { _, track -> "recent_${track.id.value}" },
            ) { index, track ->
                YtmTrackRow(
                    track = track,
                    isPlaying = snapshot.item?.trackId == track.id,
                    isLiked = track.id in likedIds,
                    onClick = { actions.play(recentlyPlayed, index, "Quick picks") },
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

        if (loading && shelves.isEmpty()) {
            item(key = "loading") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = colors.onSurface)
                }
            }
        } else if (errorMessage != null && shelves.isEmpty()) {
            item(key = "error") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = errorMessage ?: "Failed to load recommendations",
                        style = YtmTheme.type.bodyLarge,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }

        val filteredShelves = shelves.filter { shelf ->
            when (selectedChip) {
                1 -> shelf.tracks.isNotEmpty()
                2 -> shelf.playlists.isNotEmpty()
                3 -> shelf.artists.isNotEmpty()
                else -> shelf.tracks.isNotEmpty() || shelf.playlists.isNotEmpty() || shelf.artists.isNotEmpty()
            }
        }

        items(
            items = filteredShelves,
            key = { "shelf_${it.id}" },
        ) { shelf ->
            YtmShelfSection(
                shelf = shelf,
                actions = actions,
                onOpenCollection = onOpenCollection,
                onOpenArtist = onOpenArtist,
            )
        }
    }
}

@Composable
private fun YtmShelfSection(
    shelf: Shelf,
    actions: OnlineActions,
    onOpenCollection: (PlaylistId, String) -> Unit,
    onOpenArtist: (ArtistId, String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        YtmSectionHeader(title = shelf.title)

        if (shelf.playlists.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(shelf.playlists, key = { "pl_${it.id.value}" }) { playlist ->
                    YtmSquareCard(
                        title = playlist.title,
                        subtitle = playlist.ownerName,
                        artworkUri = playlist.artwork?.uri,
                        onClick = { onOpenCollection(playlist.id, playlist.title) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (shelf.tracks.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(shelf.tracks, key = { _, t -> "tr_${t.id.value}" }) { index, track ->
                    YtmSquareCard(
                        title = track.title,
                        subtitle = track.artistDisplay,
                        artworkUri = track.artwork?.uri,
                        onClick = { actions.play(shelf.tracks, index, shelf.title) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (shelf.artists.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(shelf.artists, key = { "ar_${it.id.value}" }) { artist ->
                    YtmSquareCard(
                        title = artist.name,
                        subtitle = artist.trackCount?.let { "$it songs" },
                        artworkUri = artist.artwork?.uri,
                        round = true,
                        onClick = { onOpenArtist(artist.id, artist.name) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
