package app.podium.ytm.explore

import app.podium.core.common.Outcome
import app.podium.core.model.Track
import app.podium.feature.online.OnlineRepository
import app.podium.sources.api.PlaylistSummary
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ChartOrigin(val label: String) {
    YOUTUBE_MUSIC("YouTube Music"),
    SPOTIFY("Spotify Hits"),
}

data class ChartEntry(
    val id: String,
    val title: String,
    val subtitle: String,
    val origin: ChartOrigin,
    val playlist: PlaylistSummary,
    val previewTracks: List<Track> = emptyList(),
)

private data class ChartSpec(
    val id: String,
    val title: String,
    val subtitle: String,
    val origin: ChartOrigin,
    val queries: List<String>,
    val targetTokens: List<String>,
)

class YtmChartsCurator internal constructor(
    private val online: OnlineRepository,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val localeCountry: () -> String = { Locale.getDefault().country.ifBlank { "ID" } },
) {
    private val mutex = Mutex()
    private var cachedAtMs: Long = 0L
    private var cachedCharts: List<ChartEntry> = emptyList()

    suspend fun loadCharts(forceRefresh: Boolean = false): List<ChartEntry> = mutex.withLock {
        val now = nowMillis()
        if (!forceRefresh && cachedCharts.isNotEmpty() && now - cachedAtMs < CACHE_TTL_MS) {
            return@withLock cachedCharts
        }

        val specs = chartSpecsForRegion(localeCountry())
        val resolved = coroutineScope {
            specs.map { spec ->
                async { resolveSpec(spec) }
            }.awaitAll().filterNotNull()
        }

        if (resolved.isNotEmpty()) {
            cachedCharts = resolved
            cachedAtMs = now
        }
        return@withLock cachedCharts
    }

    private suspend fun resolveSpec(spec: ChartSpec): ChartEntry? {
        val candidates = mutableListOf<PlaylistSummary>()
        for (query in spec.queries) {
            val found = searchPlaylists(query)
            candidates += found
            if (found.any { (it.trackCount ?: 0) >= 35 }) break
        }
        val chosen = pickBestChartPlaylist(spec.targetTokens, candidates.distinctBy { it.id }) ?: return null
        val previewTracks = when (val detail = online.collection(chosen.id)) {
            is Outcome.Success -> detail.value.tracks.take(12)
            is Outcome.Failure -> emptyList()
        }
        return ChartEntry(
            id = spec.id,
            title = spec.title,
            subtitle = spec.subtitle,
            origin = spec.origin,
            playlist = chosen,
            previewTracks = previewTracks,
        )
    }

    private suspend fun searchPlaylists(query: String): List<PlaylistSummary> {
        var found: List<PlaylistSummary> = emptyList()
        runCatching {
            online.search(query, offset = 0, limit = 10).collect { outcome ->
                if (outcome is Outcome.Success && outcome.value.playlists.isNotEmpty()) {
                    found = outcome.value.playlists
                }
            }
        }
        return found
    }

    private fun chartSpecsForRegion(country: String): List<ChartSpec> {
        val isIndonesia = country.equals("ID", ignoreCase = true)
        val regionName = if (isIndonesia) "Indonesia" else "Global"

        val ytmRegional = ChartSpec(
            id = "ytm_regional",
            title = "Top 50 YouTube Music",
            subtitle = "$regionName Charts",
            origin = ChartOrigin.YOUTUBE_MUSIC,
            queries = if (isIndonesia) {
                listOf("Top 100 Music Videos Indonesia", "Top Songs Indonesia", "Top 50 YouTube Music Indonesia")
            } else {
                listOf("Top 100 Music Videos Global", "Top Songs Global", "Top 50 YouTube Music Global")
            },
            targetTokens = listOf("top", regionName.lowercase(), "songs", "music", "50", "100"),
        )
        val spotifyRegional = ChartSpec(
            id = "spotify_regional",
            title = "Top 50 Spotify Hits",
            subtitle = "$regionName • Playable",
            origin = ChartOrigin.SPOTIFY,
            queries = if (isIndonesia) {
                listOf("Top 50 Spotify Indonesia", "Spotify Top 50 Indonesia")
            } else {
                listOf("Top 50 Spotify Global", "Spotify Top 50 Global")
            },
            targetTokens = listOf("spotify", "top", "50", regionName.lowercase()),
        )
        val ytmGlobal = ChartSpec(
            id = "ytm_global",
            title = "Top 50 YouTube Music",
            subtitle = "Global Charts",
            origin = ChartOrigin.YOUTUBE_MUSIC,
            queries = listOf("Top 100 Songs Global", "Top 50 YouTube Music Global"),
            targetTokens = listOf("top", "global", "songs", "100", "50"),
        )
        val spotifyGlobal = ChartSpec(
            id = "spotify_global",
            title = "Top 50 Spotify Hits",
            subtitle = "Global • Playable",
            origin = ChartOrigin.SPOTIFY,
            queries = listOf("Top 50 Spotify Global", "Spotify Top 50 Global"),
            targetTokens = listOf("spotify", "top", "50", "global"),
        )

        return if (isIndonesia) {
            listOf(ytmRegional, spotifyRegional, ytmGlobal, spotifyGlobal)
        } else {
            listOf(ytmGlobal, spotifyGlobal)
        }
    }

    companion object {
        const val CACHE_TTL_MS = 6L * 60 * 60 * 1000
    }
}

internal fun pickBestChartPlaylist(
    targetTokens: List<String>,
    candidates: List<PlaylistSummary>,
): PlaylistSummary? {
    if (candidates.isEmpty()) return null
    return candidates.maxByOrNull { playlist ->
        val titleLower = playlist.title.lowercase()
        val ownerLower = playlist.ownerName.lowercase()
        var score = 0
        if ("top 50" in titleLower || "top 100" in titleLower || "top songs" in titleLower) score += 45
        if ("youtube music" in ownerLower || ownerLower == "youtube" || "chart" in titleLower) score += 20
        targetTokens.forEach { token ->
            if (token in titleLower) score += 16
        }
        val count = playlist.trackCount ?: 0
        score += when {
            count >= 45 -> 35
            count >= 25 -> 20
            count in 1..10 -> -30
            else -> 5
        }
        score
    }
}
