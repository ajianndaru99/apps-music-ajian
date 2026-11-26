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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.AppGraph
import app.podium.core.common.Outcome
import app.podium.core.model.ArtistId
import app.podium.core.model.PlaylistId
import app.podium.feature.online.OnlineActions
import app.podium.sources.api.SearchResults
import app.podium.ytm.YtmArtworkImage
import app.podium.ytm.YtmSectionHeader
import app.podium.ytm.YtmTheme
import app.podium.ytm.YtmTrackRow
import app.podium.ytm.openAlbumAction
import app.podium.ytm.openArtistAction
import app.podium.ytm.toDisplayMessage
import kotlinx.coroutines.delay

@Composable
fun YtmSearchScreen(
    graph: AppGraph,
    actions: OnlineActions,
    onBack: () -> Unit,
    onOpenCollection: (PlaylistId, String) -> Unit,
    onOpenArtist: (ArtistId, String) -> Unit,
) {
    val colors = YtmTheme.colors
    val focusRequester = remember { FocusRequester() }
    val snapshot by graph.playbackController.snapshot.collectAsStateWithLifecycle()
    val likedIds by graph.favorites.favorites.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<SearchResults?>(null) }
    var searching by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            results = null
            searching = false
            errorMessage = null
            return@LaunchedEffect
        }
        searching = true
        errorMessage = null
        delay(300)
        graph.online.search(trimmed, offset = 0, limit = 25).collect { outcome ->
            when (outcome) {
                is Outcome.Success -> {
                    results = outcome.value
                    searching = false
                }
                is Outcome.Failure -> {
                    errorMessage = outcome.error.toDisplayMessage()
                    searching = false
                }
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
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = colors.onSurface,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(21.dp))
                    .background(colors.surface)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = "Search songs, albums, artists",
                        style = YtmTheme.type.bodyLarge,
                        color = colors.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = YtmTheme.type.bodyLarge.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.onSurface),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { query = "" }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Clear",
                        tint = colors.onSurfaceVariant,
                    )
                }
            } else {
                Spacer(Modifier.width(8.dp))
            }
        }

        HorizontalDivider(color = colors.divider)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (searching && results == null) {
                item(key = "searching") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = colors.onSurface)
                    }
                }
            }

            if (errorMessage != null && results == null) {
                item(key = "error") {
                    Text(
                        text = errorMessage ?: "Search failed",
                        style = YtmTheme.type.bodyLarge,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }

            val currentResults = results
            if (currentResults != null) {
                if (currentResults.tracks.isNotEmpty()) {
                    item(key = "songs_header") { YtmSectionHeader(title = "Songs") }
                    items(currentResults.tracks, key = { "song_${it.id.value}" }) { track ->
                        YtmTrackRow(
                            track = track,
                            isPlaying = snapshot.item?.trackId == track.id,
                            isLiked = track.id in likedIds,
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

                if (currentResults.videos.isNotEmpty()) {
                    item(key = "videos_header") { YtmSectionHeader(title = "Videos") }
                    items(currentResults.videos, key = { "vid_${it.id.value}" }) { track ->
                        YtmTrackRow(
                            track = track,
                            isPlaying = snapshot.item?.trackId == track.id,
                            isLiked = track.id in likedIds,
                            onClick = { actions.playAlone(track) },
                            onPlayNext = { actions.playNext(track) },
                            onAddToQueue = { actions.addToQueue(track) },
                            onToggleLike = { graph.favorites.toggle(track.id) },
                        )
                    }
                }

                if (currentResults.artists.isNotEmpty()) {
                    item(key = "artists_header") { YtmSectionHeader(title = "Artists") }
                    items(currentResults.artists, key = { "art_${it.id.value}" }) { artist ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenArtist(artist.id, artist.name) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            YtmArtworkImage(
                                uri = artist.artwork?.uri,
                                size = 50.dp,
                                fallbackText = artist.name,
                                round = true,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = artist.name,
                                    style = YtmTheme.type.titleMedium,
                                    color = colors.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "Artist",
                                    style = YtmTheme.type.bodyMedium,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                if (currentResults.albums.isNotEmpty()) {
                    item(key = "albums_header") { YtmSectionHeader(title = "Albums") }
                    items(currentResults.albums, key = { "alb_${it.id.value}" }) { album ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenCollection(PlaylistId(album.id.value), album.title) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            YtmArtworkImage(
                                uri = album.artwork?.uri,
                                size = 50.dp,
                                fallbackText = album.title,
                                cornerRadius = 4.dp,
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
                                val subtitle = buildString {
                                    append("Album")
                                    if (album.artistDisplay.isNotBlank()) {
                                        append(" • ")
                                        append(album.artistDisplay)
                                    }
                                    album.year?.let { append(" • $it") }
                                }
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
                }

                if (currentResults.playlists.isNotEmpty()) {
                    item(key = "playlists_header") { YtmSectionHeader(title = "Playlists") }
                    items(currentResults.playlists, key = { "pl_${it.id.value}" }) { playlist ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenCollection(playlist.id, playlist.title) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            YtmArtworkImage(
                                uri = playlist.artwork?.uri,
                                size = 50.dp,
                                fallbackText = playlist.title,
                                cornerRadius = 4.dp,
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
                                Text(
                                    text = "Playlist • ${playlist.ownerName}",
                                    style = YtmTheme.type.bodyMedium,
                                    color = colors.onSurfaceVariant,
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
