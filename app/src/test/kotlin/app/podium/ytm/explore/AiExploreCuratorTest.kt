package app.podium.ytm.explore

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.SharedPrefsDeviceSettings
import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import app.podium.core.model.ArtistId
import app.podium.core.model.PlaylistId
import app.podium.core.model.SourceId
import app.podium.core.model.SourceRef
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.feature.online.AccountState
import app.podium.feature.online.OnlineAccount
import app.podium.feature.online.OnlinePlaylist
import app.podium.feature.online.OnlinePlaylistContents
import app.podium.feature.online.OnlineRepository
import app.podium.feature.online.OnlineStatus
import app.podium.feature.settings.AiExploreProvider
import app.podium.sources.api.AlbumDetail
import app.podium.sources.api.ArtistDetail
import app.podium.sources.api.ArtistSummary
import app.podium.sources.api.HistoryEntry
import app.podium.sources.api.PlaylistDetail
import app.podium.sources.api.SearchResults
import app.podium.sources.api.Shelf
import app.podium.sources.api.ShelfPage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AiExploreCuratorTest {

    private lateinit var context: Context
    private lateinit var settings: SharedPrefsDeviceSettings
    private lateinit var store: PrefsExploreCacheStore
    private lateinit var online: FakeOnlineRepo

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("device", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(AiExploreCurator.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        settings = SharedPrefsDeviceSettings(context)
        store = PrefsExploreCacheStore(context.getSharedPreferences(AiExploreCurator.PREFS_NAME, Context.MODE_PRIVATE))
        online = FakeOnlineRepo()
    }

    @Test
    fun `without api key uses local fallback including youtube account history and likes`() = runBlocking {
        var calls = 0
        val curator = AiExploreCurator(
            store = store,
            online = online,
            settings = settings,
            accountKey = { "google:userA" },
            nowMillis = { 1_700_000_000_000L },
            localeCountry = { "ID" },
            transport = AiHttpTransport { _, _, _ ->
                calls++
                error("should not be called")
            },
        )

        val bp = curator.loadExplore()

        assertEquals(0, calls)
        assertEquals(ExploreBlueprintSource.LOCAL_FALLBACK, bp.source)
        assertTrue(bp.genres.isNotEmpty())
        assertTrue(bp.shelves.isNotEmpty())
        assertTrue(bp.genres.any { it.title.contains("Tulus") || it.title.contains("NIKI") })
    }

    @Test
    fun `gemini caches 24h, refreshes adaptively after 30s with avoidTitles, and isolates accounts`() = runBlocking {
        var now = 1_700_000_000_000L
        var account = "google:userA"
        var calls = 0
        var lastUrl = ""
        var lastHeaderKey = ""
        val promptsSeen = mutableListOf<String>()
        settings.setAiExploreProvider(AiExploreProvider.GEMINI)
        settings.setAiExploreApiKey("AIza-test-free-key")

        val geminiJson = """
            {
              "candidates": [{
                "content": {
                  "parts": [{
                    "text": "{\"profileSummary\":\"Warm indie & Indonesian pop picks\",\"genres\":[{\"title\":\"Indie Santai\",\"query\":\"indie pop indonesia chill\",\"badge\":\"For You\"}],\"shelves\":[{\"title\":\"Trending Today\",\"subtitle\":\"Hits matching your vibe\",\"query\":\"top indonesia pop hits\"}]}"
                  }]
                }
              }]
            }
        """.trimIndent()

        val curator = AiExploreCurator(
            store = store,
            online = online,
            settings = settings,
            accountKey = { account },
            nowMillis = { now },
            localeCountry = { "ID" },
            transport = AiHttpTransport { url, headers, body ->
                calls++
                lastUrl = url
                lastHeaderKey = headers["x-goog-api-key"].orEmpty()
                promptsSeen += body
                AiHttpResponse(code = 200, body = geminiJson)
            },
        )

        val first = curator.loadExplore()
        assertEquals(1, calls)
        assertEquals(ExploreBlueprintSource.AI_GEMINI, first.source)
        assertFalse(lastUrl.contains("AIza-test-free-key"))
        assertEquals("AIza-test-free-key", lastHeaderKey)
        assertTrue(promptsSeen.last().contains("NIKI")) // from YouTube accountHistory

        // Second open 2 hours later without manual refresh: strictly 0 AI calls (reads 24h cache)
        now += 2L * 60 * 60 * 1000
        curator.loadExplore()
        assertEquals(1, calls)

        // Switching to a different Google account gets its own personalized AI profile
        account = "google:userB"
        val userB = curator.loadExplore()
        assertEquals(2, calls)
        assertEquals("google:userB", userB.accountKey)

        // Manual refresh within 30s cooldown on userB does not call AI
        now += 10_000L
        val tooSoon = curator.loadExplore(forceRefresh = true)
        assertEquals(2, calls)
        assertNotNull(tooSoon.statusNote)

        // Manual refresh after 30s adapts using updated account history & avoids previous genre titles
        now += 35_000L
        curator.loadExplore(forceRefresh = true)
        assertEquals(3, calls)
        assertTrue(promptsSeen.last().contains("Indie Santai"))

        // Zero-quota shuffle never hits transport
        curator.shuffleLocal(userB)
        assertEquals(3, calls)
    }

    @Test
    fun `weekend failover automatically switches from rate limited gemini to backup groq key`() = runBlocking {
        var now = 1_700_000_000_000L
        val calledUrls = mutableListOf<String>()
        settings.setAiExploreProvider(AiExploreProvider.GEMINI)
        settings.setAiExploreApiKey("AIza-exhausted")
        settings.setAiExploreBackupProvider(AiExploreProvider.GROQ)
        settings.setAiExploreBackupApiKey("gsk-backup-free-key")

        val groqJson = """
            {
              "choices": [{
                "message": {
                  "content": "{\"profileSummary\":\"Weekend Groq mix\",\"genres\":[{\"title\":\"Weekend Groove\",\"query\":\"indie pop weekend\",\"badge\":\"For You\"}],\"shelves\":[{\"title\":\"Chill Weekend\",\"subtitle\":\"Backup AI picks\",\"query\":\"chill hits\"}]}"
                }
              }]
            }
        """.trimIndent()

        val curator = AiExploreCurator(
            store = store,
            online = online,
            settings = settings,
            accountKey = { "google:weekend" },
            nowMillis = { now },
            localeCountry = { "ID" },
            transport = AiHttpTransport { url, _, _ ->
                calledUrls += url
                if (url.contains("generativelanguage.googleapis.com")) {
                    AiHttpResponse(code = 429, body = "RESOURCE_EXHAUSTED", retryAfterSeconds = 3600)
                } else {
                    AiHttpResponse(code = 200, body = groqJson)
                }
            },
        )

        val result = curator.loadExplore()
        assertEquals(2, calledUrls.size)
        assertEquals(ExploreBlueprintSource.AI_GROQ, result.source)
        assertEquals("Weekend Groove", result.genres.first().title)
        assertTrue(result.statusNote?.contains("Groq", ignoreCase = true) == true)
    }

    @Test
    fun `daily safety cap blocks calls past max calls per day`() = runBlocking {
        val now = 1_700_000_000_000L
        var calls = 0
        settings.setAiExploreApiKey("AIza-cap-test")

        val validBody = """{"candidates":[{"content":{"parts":[{"text":"{\"profileSummary\":\"Daily mix\",\"genres\":[{\"title\":\"Chill\",\"query\":\"chill music\"}],\"shelves\":[{\"title\":\"Mix\",\"subtitle\":\"Daily\",\"query\":\"pop hits\"}]}"}]}}]}"""
        val curator = AiExploreCurator(
            store = store,
            online = online,
            settings = settings,
            accountKey = { "google:cap" },
            nowMillis = { now },
            localeCountry = { "ID" },
            transport = AiHttpTransport { _, _, _ ->
                calls++
                AiHttpResponse(code = 200, body = validBody)
            },
        )

        repeat(AiExploreCurator.MAX_AI_CALLS_PER_DAY) { i ->
            curator.clearCache()
            val c = AiExploreCurator(
                store = store,
                online = online,
                settings = settings,
                accountKey = { "google:cap_$i" },
                nowMillis = { now },
                localeCountry = { "ID" },
                transport = AiHttpTransport { _, _, _ ->
                    calls++
                    AiHttpResponse(code = 200, body = validBody)
                },
            )
            c.loadExplore()
        }
        assertEquals(AiExploreCurator.MAX_AI_CALLS_PER_DAY, calls)

        // Call past daily cap must be blocked locally and still return adapted local habits
        val blocked = curator.loadExplore(forceRefresh = true)
        assertEquals(AiExploreCurator.MAX_AI_CALLS_PER_DAY, calls)
        assertEquals(ExploreBlueprintSource.LOCAL_FALLBACK, blocked.source)
        assertTrue(blocked.statusNote?.contains("Daily AI cap", ignoreCase = true) == true)
    }
}

private class FakeOnlineRepo : OnlineRepository {
    private val likedSample = Track(
        id = TrackId.of(SourceId("ytmusic"), "vid1"),
        source = SourceRef(SourceId("ytmusic"), "vid1"),
        title = "Hati-Hati di Jalan",
        artists = emptyList(),
        artistDisplay = "Tulus",
    )
    private val historySample = Track(
        id = TrackId.of(SourceId("ytmusic"), "vid2"),
        source = SourceRef(SourceId("ytmusic"), "vid2"),
        title = "lowkey",
        artists = emptyList(),
        artistDisplay = "NIKI",
    )

    override val status: StateFlow<OnlineStatus?> = MutableStateFlow(
        OnlineStatus(
            canSearch = true,
            canBrowse = true,
            canRecommend = true,
            account = OnlineAccount(AccountState.SIGNED_IN, "Ajian"),
        ),
    )

    override fun canStartRadio(track: Track) = true
    override fun canRelate(artist: ArtistId) = true
    override suspend fun shelves(): Outcome<List<Shelf>> = Outcome.Success(
        listOf(Shelf("quick_picks", "Quick picks", tracks = listOf(likedSample))),
    )

    override suspend fun shelf(id: String, offset: Int, limit: Int): Outcome<ShelfPage> = Outcome.Success(ShelfPage())
    override suspend fun genres(): Outcome<List<String>> = Outcome.Success(emptyList())
    override suspend fun genre(name: String, offset: Int, limit: Int): Outcome<List<Track>> =
        Outcome.Failure(PodiumError.Unsupported("genres"))

    override fun search(text: String, offset: Int, limit: Int): Flow<Outcome<SearchResults>> =
        flowOf(Outcome.Success(SearchResults(tracks = listOf(likedSample))))

    override suspend fun accountHistory(offset: Int, limit: Int): Outcome<List<HistoryEntry>> =
        Outcome.Success(listOf(HistoryEntry(historySample, "Today")))

    override suspend fun artist(id: ArtistId): Outcome<ArtistDetail> = Outcome.Failure(PodiumError.NotFound("artist"))
    override suspend fun relatedArtists(id: ArtistId): Outcome<List<ArtistSummary>> = Outcome.Success(emptyList())
    override suspend fun collection(id: PlaylistId): Outcome<PlaylistDetail> = Outcome.Failure(PodiumError.NotFound("collection"))
    override val likedIds: StateFlow<Set<TrackId>> = MutableStateFlow(emptySet())
    override val likedTracks: Flow<List<Track>> = MutableStateFlow(listOf(likedSample))
    override fun setLiked(track: Track, liked: Boolean) = Unit
    override val playlists: Flow<List<OnlinePlaylist>> = MutableStateFlow(emptyList())
    override fun playlist(id: String): Flow<OnlinePlaylistContents?> = MutableStateFlow(null)
    override suspend fun createPlaylist(name: String, tracks: List<Track>) = ""
    override fun renamePlaylist(id: String, name: String) = Unit
    override fun deletePlaylist(id: String) = Unit
    override fun addToPlaylist(id: String, tracks: List<Track>, onAdded: (Int) -> Unit) = Unit
    override fun removeFromPlaylist(id: String, entryId: Long) = Unit
    override fun movePlaylistEntry(id: String, entryId: Long, toIndex: Int) = Unit
    override val recentlyPlayed: Flow<List<Track>> = MutableStateFlow(listOf(likedSample))
    override fun clearHistory() = Unit
}
