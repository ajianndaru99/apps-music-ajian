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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.AppGraph
import app.podium.core.common.Outcome
import app.podium.core.model.Track
import app.podium.feature.online.OnlineActions
import app.podium.ytm.YtmSectionHeader
import app.podium.ytm.YtmTheme
import app.podium.ytm.YtmTrackRow
import kotlin.math.abs

@Composable
fun YtmExploreScreen(
    graph: AppGraph,
    actions: OnlineActions,
    onOpenGenre: (String) -> Unit,
) {
    val colors = YtmTheme.colors
    val status by graph.online.status.collectAsStateWithLifecycle()
    val snapshot by graph.playbackController.snapshot.collectAsStateWithLifecycle()
    val likedIds by graph.favorites.favorites.collectAsStateWithLifecycle()

    var genres by remember { mutableStateOf<List<String>>(emptyList()) }
    var liveGenres by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedLiveGenre by remember { mutableStateOf<String?>(null) }
    var liveStations by remember { mutableStateOf<List<Track>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        val genresOutcome = graph.online.genres()
        if (genresOutcome is Outcome.Success) {
            genres = genresOutcome.value
        }
        if (status?.canLiveRadio == true) {
            val liveOutcome = graph.online.liveGenres()
            if (liveOutcome is Outcome.Success) {
                liveGenres = liveOutcome.value
                selectedLiveGenre = liveOutcome.value.firstOrNull()
            }
        }
        loading = false
    }

    LaunchedEffect(selectedLiveGenre) {
        val g = selectedLiveGenre ?: return@LaunchedEffect
        val stationsOutcome = graph.online.liveStations(g)
        if (stationsOutcome is Outcome.Success) {
            liveStations = stationsOutcome.value
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        if (loading) {
            item(key = "loading") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = colors.onSurface)
                }
            }
        }

        if (genres.isNotEmpty()) {
            item(key = "genres_header") {
                YtmSectionHeader(title = "Moods & genres")
            }

            val rows = genres.chunked(2)
            items(rows, key = { it.joinToString("_") }) { pair ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    pair.forEach { genre ->
                        val accent = remember(genre) {
                            val hue = (abs(genre.hashCode()) % 360).toFloat()
                            Color.hsl(hue, 0.55f, 0.55f)
                        }
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.surfaceVariant)
                                .clickable { onOpenGenre(genre) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(6.dp)
                                    .height(52.dp)
                                    .background(accent),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = genre,
                                style = YtmTheme.type.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(end = 10.dp),
                            )
                        }
                    }
                    if (pair.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        if (liveGenres.isNotEmpty()) {
            item(key = "live_header") {
                Spacer(Modifier.height(12.dp))
                YtmSectionHeader(title = "Live radio")
            }

            item(key = "live_chips") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(liveGenres, key = { "lg_$it" }) { genre ->
                        val selected = genre == selectedLiveGenre
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) colors.chipSelectedBg else colors.chipUnselectedBg)
                                .clickable { selectedLiveGenre = genre }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Radio,
                                contentDescription = null,
                                tint = if (selected) colors.chipSelectedFg else colors.chipUnselectedFg,
                                modifier = Modifier.height(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = genre,
                                style = YtmTheme.type.labelMedium,
                                color = if (selected) colors.chipSelectedFg else colors.chipUnselectedFg,
                            )
                        }
                    }
                }
            }

            items(liveStations, key = { "live_${it.id.value}" }) { station ->
                YtmTrackRow(
                    track = station,
                    isPlaying = snapshot.item?.trackId == station.id,
                    isLiked = station.id in likedIds,
                    onClick = { actions.playAlone(station) },
                    onToggleLike = { graph.favorites.toggle(station.id) },
                )
            }
        }
    }
}
