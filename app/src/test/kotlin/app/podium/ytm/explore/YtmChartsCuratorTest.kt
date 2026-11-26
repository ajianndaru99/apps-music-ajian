package app.podium.ytm.explore

import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import app.podium.core.model.ArtistId
import app.podium.core.model.PlaylistId
import app.podium.core.model.SourceId
import app.podium.core.model.SourceRef
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.feature.online.OnlinePlaylist
import app.podium.feature.online.OnlinePlaylistContents
import app.podium.feature.online.OnlineRepository
import app.podium.feature.online.OnlineStatus
import app.podium.sources.api.AlbumDetail
import app.podium.sources.api.ArtistDetail
import app.podium.sources.api.ArtistSummary
import app.podium.sources.api.PlaylistDetail
import app.podium.sources.api.PlaylistSummary
import app.podium.sources.api.SearchResults
import app.podium.sources.api.Shelf
import app.podium.sources.api.ShelfPage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class YtmChartsCuratorTest {

    @Test
    fun `pickBestChartPlaylist prefers full top 50 chart playlists over tiny user lists`() {
        val tiny = PlaylistSummary(
            id = PlaylistId.of(SourceId("ytmusic"), "PL_TINY"),
            title = "Top 50 Spotify Indonesia",
            ownerName = "random",
            trackCount = 5,
        )
        val full = PlaylistSummary(
            id = PlaylistId.of(SourceId("ytmusic"), "PL_FULL"),
            title = "Spotify Top 50 Indonesia",
            ownerName = "Charts",
            trackCount = 50,
        )

        val picked = pickBestChartPlaylist(
            targetTokens = listOf("spotify", "top", "50", "indonesia"),
            candidates = listOf(tiny, full),
        )
        assertNotNull(picked)
        assertEquals("PL_FULL", picked.id.providerKey)
    }

    @Test
    fun `loadCharts resolves YouTube Music and Spotify charts and caches for 6h`() = runBlocking {
        var now = 1_700_000_000_000L
        var searchCalls = 0
        val sampleTrack = Track(
            id = TrackId.of(SourceId("ytmusic"), "tr1"),
            source = SourceRef(SourceId("ytmusic"), "tr1"),
            title = "Gala Bunga Matahari",
            artists = emptyList(),
            artistDisplay = "Sal Priadi",
        )
        val ytmPlaylist = PlaylistSummary(
            id = PlaylistId.of(SourceId("ytmusic"), "PL_YTM_ID"),
            title = "Top 100 Music Videos Indonesia",
            ownerName = "YouTube Music",
            trackCount = 100,
        )
        val spotifyPlaylist = PlaylistSummary(
            id = PlaylistId.of(SourceId("ytmusic"), "PL_SPOT_ID"),
            title = "Top 50 Spotify Indonesia",
            ownerName = "Top Hits",
            trackCount = 50,
        )

        val repo = object : OnlineRepository {
            override val status: StateFlow<OnlineStatus?> = MutableStateFlow(null)
            override fun canStartRadio(track: Track) = true
            override fun canRelate(artist: ArtistId) = true
            override suspend fun shelves(): Outcome<List<Shelf>> = Outcome.Success(emptyList())
            override suspend fun shelf(id: String, offset: Int, limit: Int): Outcome<ShelfPage> = Outcome.Success(ShelfPage())
            override suspend fun genres(): Outcome<List<String>> = Outcome.Success(emptyList())
            override suspend fun genre(name: String, offset: Int, limit: Int): Outcome<List<Track>> =
                Outcome.Failure(PodiumError.Unsupported("genres"))

            override fun search(text: String, offset: Int, limit: Int): Flow<Outcome<SearchResults>> {
                searchCalls++
                val list = if ("spotify" in text.lowercase()) listOf(spotifyPlaylist) else listOf(ytmPlaylist)
                return flowOf(Outcome.Success(SearchResults(playlists = list)))
            }

            override suspend fun artist(id: ArtistId): Outcome<ArtistDetail> = Outcome.Failure(PodiumError.NotFound("a"))
            override suspend fun relatedArtists(id: ArtistId): Outcome<List<ArtistSummary>> = Outcome.Success(emptyList())
            override suspend fun collection(id: PlaylistId): Outcome<PlaylistDetail> =
                Outcome.Success(PlaylistDetail(if (id == spotifyPlaylist.id) spotifyPlaylist else ytmPlaylist, listOf(sampleTrack)))

            override val likedIds: StateFlow<Set<TrackId>> = MutableStateFlow(emptySet())
            override val likedTracks: Flow<List<Track>> = MutableStateFlow(emptyList())
            override fun setLiked(track: Track, liked: Boolean) = Unit
            override val playlists: Flow<List<OnlinePlaylist>> = MutableStateFlow(emptyList())
            override fun playlist(id: String): Flow<OnlinePlaylistContents?> = MutableStateFlow(null)
            override suspend fun createPlaylist(name: String, tracks: List<Track>) = ""
            override fun renamePlaylist(id: String, name: String) = Unit
            override fun deletePlaylist(id: String) = Unit
            override fun addToPlaylist(id: String, tracks: List<Track>, onAdded: (Int) -> Unit) = Unit
            override fun removeFromPlaylist(id: String, entryId: Long) = Unit
            override fun movePlaylistEntry(id: String, entryId: Long, toIndex: Int) = Unit
            override val recentlyPlayed: Flow<List<Track>> = MutableStateFlow(emptyList())
            override fun clearHistory() = Unit
        }

        val curator = YtmChartsCurator(repo, nowMillis = { now }, localeCountry = { "ID" })
        val first = curator.loadCharts()
        assertEquals(4, first.size)
        assertTrue(first.any { it.origin == ChartOrigin.YOUTUBE_MUSIC })
        assertTrue(first.any { it.origin == ChartOrigin.SPOTIFY })
        assertTrue(first.first().previewTracks.isNotEmpty())
        val callsAfterFirst = searchCalls
        assertTrue(callsAfterFirst > 0)

        // Second call within 6h uses cache (0 extra search calls)
        now += 60 * 60 * 1000L
        curator.loadCharts()
        assertEquals(callsAfterFirst, searchCalls)

        // Force refresh refreshes charts
        curator.loadCharts(forceRefresh = true)
        assertTrue(searchCalls > callsAfterFirst)
    }
}
