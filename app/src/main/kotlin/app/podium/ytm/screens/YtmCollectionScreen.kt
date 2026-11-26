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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
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
import app.podium.sources.api.PlaylistDetail
import app.podium.ytm.YtmArtworkImage
import app.podium.ytm.YtmTheme
import app.podium.ytm.YtmTrackRow
import app.podium.ytm.openArtistAction
import app.podium.ytm.toDisplayMessage

@Composable
fun YtmCollectionScreen(
    id: PlaylistId,
    initialTitle: String,
    graph: AppGraph,
    actions: OnlineActions,
    onBack: () -> Unit,
    onOpenArtist: (ArtistId, String) -> Unit,
) {
    val colors = YtmTheme.colors
    val snapshot by graph.playbackController.snapshot.collectAsStateWithLifecycle()
    val likedIds by graph.favorites.favorites.collectAsStateWithLifecycle()

    var detail by remember(id) { mutableStateOf<PlaylistDetail?>(null) }
    var loading by remember(id) { mutableStateOf(true) }
    var errorMessage by remember(id) { mutableStateOf<String?>(null) }

    LaunchedEffect(id) {
        loading = true
        errorMessage = null
        when (val result = graph.online.collection(id)) {
            is Outcome.Success -> {
                detail = result.value
                loading = false
            }
            is Outcome.Failure -> {
                errorMessage = result.error.toDisplayMessage()
                loading = false
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
                text = detail?.summary?.title ?: initialTitle,
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
                    text = errorMessage ?: "Unable to load",
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
                            size = 200.dp,
                            fallbackText = summary.title,
                            cornerRadius = 10.dp,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = summary.title,
                            style = YtmTheme.type.headlineLarge,
                            color = colors.onSurface,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        val meta = buildString {
                            if (summary.ownerName.isNotBlank()) append(summary.ownerName)
                            summary.year?.let {
                                if (isNotEmpty()) append(" • ")
                                append(it)
                            }
                            if (tracks.isNotEmpty()) {
                                if (isNotEmpty()) append(" • ")
                                append("${tracks.size} songs")
                            }
                        }
                        if (meta.isNotEmpty()) {
                            Text(
                                text = meta,
                                style = YtmTheme.type.bodyMedium,
                                color = colors.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        ) {
                            Button(
                                onClick = { actions.playCollection(tracks, 0, summary.title, id, shuffle = false) },
                                enabled = tracks.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.accent,
                                    contentColor = colors.background,
                                ),
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Play", fontWeight = FontWeight.SemiBold)
                            }
                            OutlinedButton(
                                onClick = { actions.playCollection(tracks, 0, summary.title, id, shuffle = true) },
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

                itemsIndexed(
                    items = tracks,
                    key = { idx, t -> "${idx}_${t.id.value}" },
                ) { index, track ->
                    YtmTrackRow(
                        track = track,
                        indexLabel = if (summary.isAlbum) "${index + 1}" else null,
                        isPlaying = snapshot.item?.trackId == track.id,
                        isLiked = track.id in likedIds,
                        onClick = { actions.playCollection(tracks, index, summary.title, id) },
                        onPlayNext = { actions.playNext(track) },
                        onAddToQueue = { actions.addToQueue(track) },
                        onStartRadio = if (graph.online.canStartRadio(track)) {
                            { actions.startRadio(track) }
                        } else null,
                        onToggleLike = { graph.favorites.toggle(track.id) },
                        onOpenArtist = track.openArtistAction(onOpenArtist),
                    )
                }
            }
        }
    }
}
