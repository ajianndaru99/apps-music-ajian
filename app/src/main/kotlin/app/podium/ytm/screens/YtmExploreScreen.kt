@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import app.podium.ytm.YtmSquareCard
import app.podium.ytm.YtmTheme
import app.podium.ytm.YtmTrackRow
import app.podium.ytm.explore.ExploreBlueprint
import app.podium.ytm.explore.ExploreBlueprintSource
import app.podium.ytm.explore.ExploreCuratedShelf
import app.podium.ytm.explore.ExploreGenreCard
import kotlin.math.abs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Composable
fun YtmExploreScreen(
    graph: AppGraph,
    actions: OnlineActions,
    onOpenGenre: (name: String, query: String) -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    val colors = YtmTheme.colors
    val status by graph.online.status.collectAsStateWithLifecycle()
    val accountKey by graph.online.accountKey.collectAsStateWithLifecycle()
    val aiApiKey by graph.deviceSettings.aiExploreApiKey.collectAsStateWithLifecycle()
    val aiProvider by graph.deviceSettings.aiExploreProvider.collectAsStateWithLifecycle()
    val aiBackupKey by graph.deviceSettings.aiExploreBackupApiKey.collectAsStateWithLifecycle()
    val snapshot by graph.playbackController.snapshot.collectAsStateWithLifecycle()
    val likedIds by graph.favorites.favorites.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var blueprint by remember { mutableStateOf<ExploreBlueprint?>(null) }
    var nativeGenres by remember { mutableStateOf<List<String>>(emptyList()) }
    var shelfTracks by remember { mutableStateOf<Map<String, List<Track>>>(emptyMap()) }
    var liveGenres by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedLiveGenre by remember { mutableStateOf<String?>(null) }
    var liveStations by remember { mutableStateOf<List<Track>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }

    suspend fun loadShelvesFor(bp: ExploreBlueprint) {
        val resolvedShelves = coroutineScope {
            bp.shelves.map { shelf ->
                async {
                    shelf.title to graph.aiExplore.searchTracksForQuery(shelf.query, limit = 8)
                }
            }.awaitAll().toMap()
        }
        shelfTracks = resolvedShelves
    }

    suspend fun loadAll(forceRefresh: Boolean) {
        val loadedBlueprint = graph.aiExplore.loadExplore(forceRefresh = forceRefresh)
        blueprint = loadedBlueprint

        val nativeResult = graph.online.genres()
        if (nativeResult is Outcome.Success) {
            nativeGenres = nativeResult.value
        }

        loadShelvesFor(loadedBlueprint)

        if (status?.canLiveRadio == true && liveGenres.isEmpty()) {
            val liveOutcome = graph.online.liveGenres()
            if (liveOutcome is Outcome.Success) {
                liveGenres = liveOutcome.value
                selectedLiveGenre = liveOutcome.value.firstOrNull()
            }
        }
    }

    LaunchedEffect(accountKey, aiApiKey, aiProvider, aiBackupKey) {
        loading = true
        loadAll(forceRefresh = false)
        loading = false
    }

    LaunchedEffect(selectedLiveGenre) {
        val g = selectedLiveGenre ?: return@LaunchedEffect
        val stationsOutcome = graph.online.liveStations(g)
        if (stationsOutcome is Outcome.Success) {
            liveStations = stationsOutcome.value
        }
    }

    val combinedGenres = remember(blueprint, nativeGenres) {
        val fromBlueprint = blueprint?.genres.orEmpty()
        val existing = fromBlueprint.map { it.title.lowercase() }.toSet()
        val extra = nativeGenres
            .filter { it.lowercase() !in existing }
            .map { ExploreGenreCard(title = it, query = it) }
        fromBlueprint + extra
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            if (!refreshing) {
                scope.launch {
                    refreshing = true
                    loadAll(forceRefresh = true)
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
            if (loading && blueprint == null) {
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

            blueprint?.let { bp ->
                item(key = "explore_banner") {
                    AiExploreHeaderCard(
                        blueprint = bp,
                        hasAnyApiKey = aiApiKey.isNotBlank() || aiBackupKey.isNotBlank(),
                        refreshing = refreshing,
                        onShuffleLocal = {
                            val shuffled = graph.aiExplore.shuffleLocal(bp)
                            blueprint = shuffled
                            scope.launch { loadShelvesFor(shuffled) }
                        },
                        onRefresh = {
                            if (!refreshing) {
                                scope.launch {
                                    refreshing = true
                                    loadAll(forceRefresh = true)
                                    refreshing = false
                                }
                            }
                        },
                        onOpenSettings = onOpenSettings,
                    )
                }
            }

            if (combinedGenres.isNotEmpty()) {
                item(key = "genres_header") {
                    YtmSectionHeader(title = "Moods & genres")
                }

                val rows = combinedGenres.chunked(2)
                items(rows, key = { pair -> pair.joinToString("_") { it.title } }) { pair ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        pair.forEach { card ->
                            val accent = remember(card.title) {
                                val hue = (abs(card.title.hashCode()) % 360).toFloat()
                                Color.hsl(hue, 0.55f, 0.55f)
                            }
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(56.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.surfaceVariant)
                                    .clickable { onOpenGenre(card.title, card.query) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(6.dp)
                                        .height(56.dp)
                                        .background(accent),
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                    Text(
                                        text = card.title,
                                        style = YtmTheme.type.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = colors.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    card.badge?.let { badge ->
                                        Text(
                                            text = badge,
                                            style = YtmTheme.type.labelSmall,
                                            color = colors.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                        if (pair.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            blueprint?.shelves?.forEach { shelf: ExploreCuratedShelf ->
                val tracks = shelfTracks[shelf.title].orEmpty()
                if (tracks.isNotEmpty()) {
                    item(key = "shelf_header_${shelf.title}") {
                        Spacer(Modifier.height(8.dp))
                        YtmSectionHeader(
                            title = shelf.title,
                            actionLabel = "Play all",
                            onAction = { actions.play(tracks, 0, shelf.title) },
                        )
                        if (shelf.subtitle.isNotBlank()) {
                            Text(
                                text = shelf.subtitle,
                                style = YtmTheme.type.labelMedium,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                            )
                        }
                    }
                    item(key = "shelf_row_${shelf.title}") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            itemsIndexed(tracks, key = { idx, t -> "${shelf.title}_${idx}_${t.id.value}" }) { _, track ->
                                YtmSquareCard(
                                    title = track.title,
                                    subtitle = track.artistDisplay,
                                    artworkUri = track.artwork?.uri,
                                    onClick = { actions.playAlone(track) },
                                )
                            }
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
}

@Composable
private fun AiExploreHeaderCard(
    blueprint: ExploreBlueprint,
    hasAnyApiKey: Boolean,
    refreshing: Boolean,
    onShuffleLocal: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = YtmTheme.colors
    val isAiConnected = blueprint.source != ExploreBlueprintSource.LOCAL_FALLBACK
    val aiStatusTint = if (isAiConnected) Color(0xFF4ADE80) else Color(0xFFF87171)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceVariant)
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = if (isAiConnected) "AI connected" else "AI disconnected",
                tint = aiStatusTint,
                modifier = Modifier.size(20.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onShuffleLocal,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Shuffle,
                        contentDescription = "Shuffle Explore (0 quota)",
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                if (refreshing) {
                    CircularProgressIndicator(
                        color = colors.onSurface,
                        strokeWidth = 2.dp,
                        modifier = Modifier.padding(6.dp).size(18.dp),
                    )
                } else {
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh from recent habits",
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = blueprint.profileSummary,
            style = YtmTheme.type.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        blueprint.statusNote?.let { note ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = note,
                style = YtmTheme.type.labelSmall,
                color = colors.onSurfaceMuted,
            )
        }
        if (!hasAnyApiKey) {
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onOpenSettings,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accent,
                    contentColor = colors.background,
                ),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "Add Free AI Key",
                    color = colors.background,
                    style = YtmTheme.type.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}
