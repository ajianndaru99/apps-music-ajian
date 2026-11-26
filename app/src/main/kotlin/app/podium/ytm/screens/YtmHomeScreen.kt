@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.AppGraph
import app.podium.core.common.Outcome
import app.podium.core.model.ArtistId
import app.podium.core.model.PlaylistId
import app.podium.feature.online.OnlineActions
import app.podium.sources.api.Shelf
import app.podium.ytm.YtmFilterChips
import app.podium.ytm.YtmGoogleSignInButton
import app.podium.ytm.YtmSectionHeader
import app.podium.ytm.YtmSquareCard
import app.podium.ytm.YtmTheme
import app.podium.ytm.YtmTrackRow
import app.podium.ytm.explore.ChartEntry
import app.podium.ytm.openAlbumAction
import app.podium.ytm.openArtistAction
import app.podium.ytm.toDisplayMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()

    var selectedChip by remember { mutableIntStateOf(0) }
    val chips = remember { listOf("All", "Quick picks", "Top 50", "Playlists", "Artists") }

    var shelves by remember { mutableStateOf<List<Shelf>>(emptyList()) }
    var charts by remember { mutableStateOf<List<ChartEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    suspend fun loadHome(forceRefresh: Boolean) {
        errorMessage = null
        coroutineScope {
            val shelvesTask = async { graph.online.shelves() }
            val chartsTask = async { graph.charts.loadCharts(forceRefresh = forceRefresh) }

            when (val result = shelvesTask.await()) {
                is Outcome.Success -> {
                    shelves = result.value
                    val topTracks = result.value.firstOrNull { it.tracks.isNotEmpty() }?.tracks.orEmpty()
                    if (topTracks.isNotEmpty()) graph.prewarmTracks(topTracks, limit = 2)
                }
                is Outcome.Failure -> {
                    errorMessage = result.error.toDisplayMessage()
                }
            }
            charts = chartsTask.await()
        }
    }

    val accountKey = status?.account?.signedIn
    LaunchedEffect(accountKey) {
        loading = true
        loadHome(forceRefresh = false)
        loading = false
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            if (!refreshing) {
                scope.launch {
                    refreshing = true
                    loadHome(forceRefresh = true)
                    refreshing = false
                }
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
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
                            YtmGoogleSignInButton(onClick = onSignIn)
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
                ) { _, track ->
                    YtmTrackRow(
                        track = track,
                        isPlaying = snapshot.item?.trackId == track.id,
                        isLiked = track.id in likedIds,
                        // Play single pick + queue YouTube Music recommendations right after
                        onClick = { actions.playAlone(track) },
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

            if (charts.isNotEmpty() && (selectedChip == 0 || selectedChip == 2)) {
                item(key = "top50_charts_header") {
                    Spacer(Modifier.height(6.dp))
                    YtmSectionHeader(title = "Top 50 Charts (YouTube Music & Spotify)")
                }
                item(key = "top50_charts_row") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(charts, key = { "chart_${it.id}" }) { chart ->
                            YtmSquareCard(
                                title = chart.title,
                                subtitle = chart.subtitle,
                                artworkUri = chart.playlist.artwork?.uri
                                    ?: chart.previewTracks.firstOrNull()?.artwork?.uri,
                                onClick = {
                                    onOpenCollection(chart.playlist.id, "${chart.title} • ${chart.subtitle}")
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                val spotlight = charts.firstOrNull { it.previewTracks.isNotEmpty() }
                if (spotlight != null) {
                    item(key = "top50_spotlight_header") {
                        YtmSectionHeader(
                            title = "${spotlight.title} • ${spotlight.subtitle}",
                            actionLabel = "See all",
                            onAction = {
                                onOpenCollection(
                                    spotlight.playlist.id,
                                    "${spotlight.title} • ${spotlight.subtitle}",
                                )
                            },
                        )
                    }
                    val previewLimit = if (selectedChip == 2) 12 else 5
                    itemsIndexed(
                        items = spotlight.previewTracks.take(previewLimit),
                        key = { idx, t -> "top50_spot_${idx}_${t.id.value}" },
                    ) { idx, track ->
                        YtmTrackRow(
                            track = track,
                            indexLabel = "${idx + 1}",
                            isPlaying = snapshot.item?.trackId == track.id,
                            isLiked = track.id in likedIds,
                            onClick = {
                                actions.play(spotlight.previewTracks, idx, spotlight.title)
                            },
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
                    2 -> false
                    3 -> shelf.playlists.isNotEmpty()
                    4 -> shelf.artists.isNotEmpty()
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
                itemsIndexed(shelf.tracks, key = { _, t -> "tr_${t.id.value}" }) { _, track ->
                    YtmSquareCard(
                        title = track.title,
                        subtitle = track.artistDisplay,
                        artworkUri = track.artwork?.uri,
                        // Start song + immediate YouTube Music related recommendations
                        onClick = { actions.playAlone(track) },
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
