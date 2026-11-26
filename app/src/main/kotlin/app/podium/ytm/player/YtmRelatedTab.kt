package app.podium.ytm.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.AppGraph
import app.podium.core.model.Track
import app.podium.feature.online.OnlineActions
import app.podium.player.api.RecommendationRequest
import app.podium.ytm.YtmTheme
import app.podium.ytm.YtmTrackRow

@Composable
fun YtmRelatedTab(
    graph: AppGraph,
    actions: OnlineActions,
    modifier: Modifier = Modifier,
) {
    val colors = YtmTheme.colors
    val snapshot by graph.playbackController.snapshot.collectAsStateWithLifecycle()
    val likedIds by graph.favorites.favorites.collectAsStateWithLifecycle()
    val currentId = snapshot.item?.trackId

    var related by remember(currentId) { mutableStateOf<List<Track>>(emptyList()) }
    var loading by remember(currentId) { mutableStateOf(false) }

    LaunchedEffect(currentId) {
        val id = currentId ?: run {
            related = emptyList()
            return@LaunchedEffect
        }
        val track = graph.catalog.cached(id)
        if (track == null) {
            related = emptyList()
            return@LaunchedEffect
        }
        loading = true
        related = runCatching {
            graph.recommendations.recommend(
                RecommendationRequest(
                    seeds = listOf(track),
                    exclude = setOf(track.id),
                    limit = 20,
                ),
            )
        }.getOrDefault(emptyList())
        loading = false
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (loading) {
            CircularProgressIndicator(color = colors.onSurface)
        } else if (related.isEmpty()) {
            Text(
                text = "No related tracks available",
                style = YtmTheme.type.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                itemsIndexed(related, key = { idx, t -> "${idx}_${t.id.value}" }) { index, track ->
                    YtmTrackRow(
                        track = track,
                        isPlaying = currentId == track.id,
                        isLiked = track.id in likedIds,
                        onClick = { actions.play(related, index, "Related") },
                        onPlayNext = { actions.playNext(track) },
                        onAddToQueue = { actions.addToQueue(track) },
                        onToggleLike = { graph.favorites.toggle(track.id) },
                    )
                }
            }
        }
    }
}
