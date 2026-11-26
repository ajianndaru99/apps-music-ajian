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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.AppGraph
import app.podium.core.common.Outcome
import app.podium.core.model.ArtistId
import app.podium.core.model.PlaylistId
import app.podium.feature.library.LibraryState
import app.podium.feature.online.OnlineActions
import app.podium.sources.api.AlbumSummary
import app.podium.sources.api.ArtistSummary
import app.podium.sources.api.CapabilityAction
import app.podium.sources.api.PlaylistSummary
import app.podium.ytm.YtmArtworkImage
import app.podium.ytm.YtmFilterChips
import app.podium.ytm.YtmGoogleSignInButton
import app.podium.ytm.YtmTheme
import app.podium.ytm.YtmTrackRow
import app.podium.ytm.openAlbumAction
import app.podium.ytm.openArtistAction

@Composable
fun YtmLibraryScreen(
    graph: AppGraph,
    actions: OnlineActions,
    onSourceAction: (CapabilityAction) -> Unit,
    onOpenCollection: (PlaylistId, String) -> Unit,
    onOpenArtist: (ArtistId, String) -> Unit,
) {
    val colors = YtmTheme.colors
    val status by graph.online.status.collectAsStateWithLifecycle()
    val snapshot by graph.playbackController.snapshot.collectAsStateWithLifecycle()
    val likedIds by graph.favorites.favorites.collectAsStateWithLifecycle()
    val likedTracks by graph.online.likedTracks.collectAsStateWithLifecycle(initialValue = emptyList())
    val localPlaylists by graph.online.playlists.collectAsStateWithLifecycle(initialValue = emptyList())
    val deviceLibrary by graph.library.songs.collectAsStateWithLifecycle()
    val pendingActions by graph.library.pendingActions.collectAsStateWithLifecycle()

    var selectedFilter by rememberSaveable { mutableIntStateOf(0) }
    val filters = remember { listOf("Playlists", "Songs", "Albums", "Artists", "Device Files") }

    var accountPlaylists by remember { mutableStateOf<List<PlaylistSummary>>(emptyList()) }
    var accountAlbums by remember { mutableStateOf<List<AlbumSummary>>(emptyList()) }
    var accountArtists by remember { mutableStateOf<List<ArtistSummary>>(emptyList()) }
    var loadingOnline by remember { mutableStateOf(false) }

    val signedIn = status?.account?.signedIn == true

    LaunchedEffect(signedIn, selectedFilter) {
        if (!signedIn) return@LaunchedEffect
        loadingOnline = true
        when (selectedFilter) {
            0 -> {
                val res = graph.online.libraryPlaylists(0, 50)
                if (res is Outcome.Success) accountPlaylists = res.value
            }
            2 -> {
                val res = graph.online.libraryAlbums(0, 50)
                if (res is Outcome.Success) accountAlbums = res.value
            }
            3 -> {
                val res = graph.online.libraryArtists(0, 50)
                if (res is Outcome.Success) accountArtists = res.value
            }
        }
        loadingOnline = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        YtmFilterChips(
            options = filters,
            selectedIndex = selectedFilter,
            onSelect = { selectedFilter = it },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (selectedFilter == 4) {
                // Device Files (local offline music)
                if (pendingActions.isNotEmpty()) {
                    items(pendingActions, key = { "perm_${it.sourceName}" }) { pending ->
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
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Folder,
                                        contentDescription = null,
                                        tint = colors.onSurface,
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = pending.sourceName,
                                            style = YtmTheme.type.titleMedium,
                                            color = colors.onSurface,
                                        )
                                        Text(
                                            text = pending.note,
                                            style = YtmTheme.type.bodyMedium,
                                            color = colors.onSurfaceVariant,
                                        )
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Button(
                                    onClick = { onSourceAction(pending.action) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = colors.accent,
                                        contentColor = colors.background,
                                    ),
                                    shape = RoundedCornerShape(50),
                                ) {
                                    Text("Allow", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }

                when (val state = deviceLibrary) {
                    LibraryState.Loading -> {
                        item(key = "dev_loading") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(color = colors.onSurface)
                            }
                        }
                    }
                    is LibraryState.Ready -> {
                        if (state.tracks.isEmpty()) {
                            item(key = "dev_empty") {
                                Text(
                                    text = "No audio files found on this device",
                                    style = YtmTheme.type.bodyLarge,
                                    color = colors.onSurfaceVariant,
                                    modifier = Modifier.padding(24.dp),
                                )
                            }
                        } else {
                            itemsIndexed(
                                items = state.tracks,
                                key = { _, t -> "dev_${t.id.value}" },
                            ) { index, track ->
                                YtmTrackRow(
                                    track = track,
                                    isPlaying = snapshot.item?.trackId == track.id,
                                    isLiked = track.id in likedIds,
                                    onClick = {
                                        graph.catalog.remember(state.tracks)
                                        graph.playbackController.playContext(
                                            state.tracks.map { it.id },
                                            index,
                                            "Device files",
                                        )
                                    },
                                    onPlayNext = {
                                        graph.catalog.remember(listOf(track))
                                        graph.playbackController.playNext(listOf(track.id))
                                    },
                                    onAddToQueue = {
                                        graph.catalog.remember(listOf(track))
                                        graph.playbackController.addToQueue(listOf(track.id))
                                    },
                                    onToggleLike = { graph.favorites.toggle(track.id) },
                                )
                            }
                        }
                    }
                }
            } else {
                // Online Library tabs
                if (!signedIn) {
                    item(key = "signin_card") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.surfaceVariant)
                                .padding(20.dp),
                        ) {
                            Column {
                                Text(
                                    text = "Sign in to view your YouTube Music library",
                                    style = YtmTheme.type.titleLarge,
                                    color = colors.onSurface,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "Your liked songs, playlists, albums, and subscribed artists will appear here.",
                                    style = YtmTheme.type.bodyMedium,
                                    color = colors.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(14.dp))
                                YtmGoogleSignInButton(onClick = { actions.signIn() })
                            }
                        }
                    }
                }

                if (loadingOnline) {
                    item(key = "online_loading") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = colors.onSurface)
                        }
                    }
                }

                when (selectedFilter) {
                    0 -> {
                        items(accountPlaylists, key = { "apl_${it.id.value}" }) { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenCollection(playlist.id, playlist.title) }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                YtmArtworkImage(
                                    uri = playlist.artwork?.uri,
                                    size = 54.dp,
                                    fallbackText = playlist.title,
                                    cornerRadius = 6.dp,
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = playlist.title,
                                        style = YtmTheme.type.titleMedium,
                                        color = colors.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val sub = buildString {
                                        append(playlist.ownerName.ifBlank { "Playlist" })
                                        playlist.trackCount?.let { append(" • $it songs") }
                                    }
                                    Text(
                                        text = sub,
                                        style = YtmTheme.type.bodyMedium,
                                        color = colors.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        if (localPlaylists.isNotEmpty()) {
                            items(localPlaylists, key = { "lpl_${it.id}" }) { pl ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    YtmArtworkImage(
                                        uri = null,
                                        size = 54.dp,
                                        fallbackText = pl.name,
                                        cornerRadius = 6.dp,
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = pl.name,
                                            style = YtmTheme.type.titleMedium,
                                            color = colors.onSurface,
                                        )
                                        Text(
                                            text = "${pl.trackCount} songs",
                                            style = YtmTheme.type.bodyMedium,
                                            color = colors.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    1 -> {
                        itemsIndexed(likedTracks, key = { _, t -> "liked_${t.id.value}" }) { index, track ->
                            YtmTrackRow(
                                track = track,
                                isPlaying = snapshot.item?.trackId == track.id,
                                isLiked = true,
                                onClick = { actions.play(likedTracks, index, "Liked songs") },
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
                    2 -> {
                        items(accountAlbums, key = { "alb_${it.id.value}" }) { album ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenCollection(PlaylistId(album.id.value), album.title) }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                YtmArtworkImage(
                                    uri = album.artwork?.uri,
                                    size = 54.dp,
                                    fallbackText = album.title,
                                    cornerRadius = 6.dp,
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = album.title,
                                        style = YtmTheme.type.titleMedium,
                                        color = colors.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = album.artistDisplay,
                                        style = YtmTheme.type.bodyMedium,
                                        color = colors.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                    3 -> {
                        items(accountArtists, key = { "art_${it.id.value}" }) { artist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenArtist(artist.id, artist.name) }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                YtmArtworkImage(
                                    uri = artist.artwork?.uri,
                                    size = 54.dp,
                                    fallbackText = artist.name,
                                    round = true,
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = artist.name,
                                    style = YtmTheme.type.titleMedium,
                                    color = colors.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
