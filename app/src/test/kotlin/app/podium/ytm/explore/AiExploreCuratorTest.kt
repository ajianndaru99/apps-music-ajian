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
    fun `without api key uses local fallback and makes zero network calls`() = runBlocking {
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
        assertTrue(bp.genres.any { it.title.contains("Tulus") })
    }

    @Test
    fun `gemini free tier caches 24h per account and sends key in header only`() = runBlocking {
        var now = 1_700_000_000_000L
        var account = "google:userA"
        var calls = 0
        var lastUrl = ""
        var lastHeaderKey = ""
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
            transport = AiHttpTransport { url, headers, _ ->
                calls++
                lastUrl = url
                lastHeaderKey = headers["x-goog-api-key"].orEmpty()
                AiHttpResponse(code = 200, body = geminiJson)
            },
        )

        val first = curator.loadExplore()
        assertEquals(1, calls)
        assertEquals(ExploreBlueprintSource.AI_GEMINI, first.source)
        assertFalse(lastUrl.contains("AIza-test-free-key"))
        assertEquals("AIza-test-free-key", lastHeaderKey)
        assertEquals("Indie Santai", first.genres.first().title)

        // Second open 2 hours later: strictly 0 AI calls (reads 24h cache)
        now += 2L * 60 * 60 * 1000
        val second = curator.loadExplore()
        assertEquals(1, calls)
        assertEquals("Indie Santai", second.genres.first().title)

        // Force refresh within 1h of generation is blocked by cooldown
        now -= 90L * 60 * 1000 // now 30 min after generation
        val tooSoon = curator.loadExplore(forceRefresh = true)
        assertEquals(1, calls)
        assertNotNull(tooSoon.statusNote)

        // Switching to a different Google account gets its own personalized AI profile
        account = "google:userB"
        val userB = curator.loadExplore()
        assertEquals(2, calls)
        assertEquals("google:userB", userB.accountKey)
    }

    @Test
    fun `daily safety cap and http 429 rate limit prevent quota exhaustion`() = runBlocking {
        var now = 1_700_000_000_000L
        var calls = 0
        settings.setAiExploreApiKey("AIza-rate-limit-test")

        val curator = AiExploreCurator(
            store = store,
            online = online,
            settings = settings,
            accountKey = { "google:userA" },
            nowMillis = { now },
            localeCountry = { "ID" },
            transport = AiHttpTransport { _, _, _ ->
                calls++
                AiHttpResponse(code = 429, body = "RESOURCE_EXHAUSTED", retryAfterSeconds = 3600)
            },
        )

        // First call receives HTTP 429 -> falls back and activates 24h backoff
        val first = curator.loadExplore()
        assertEquals(1, calls)
        assertEquals(ExploreBlueprintSource.LOCAL_FALLBACK, first.source)
        assertTrue(first.statusNote?.contains("429") == true)

        // Repeated loads / manual refreshes during backoff make 0 calls
        now += 2L * 60 * 60 * 1000
        curator.loadExplore(forceRefresh = true)
        curator.loadExplore(forceRefresh = true)
        assertEquals(1, calls)
    }

    @Test
    fun `daily cap blocks calls past max calls per day`() = runBlocking {
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

        // Simulate different accounts forcing fresh generations on same day up to daily limit (3)
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

        // 4th call on the same day must be blocked locally
        val blocked = curator.loadExplore(forceRefresh = true)
        assertEquals(AiExploreCurator.MAX_AI_CALLS_PER_DAY, calls)
        assertEquals(ExploreBlueprintSource.LOCAL_FALLBACK, blocked.source)
        assertTrue(blocked.statusNote?.contains("safety cap", ignoreCase = true) == true)
    }
}

private class FakeOnlineRepo : OnlineRepository {
    private val sampleTrack = Track(
        id = TrackId.of(SourceId("ytmusic"), "vid1"),
        source = SourceRef(SourceId("ytmusic"), "vid1"),
        title = "Hati-Hati di Jalan",
        artists = emptyList(),
        artistDisplay = "Tulus",
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
        listOf(Shelf("quick_picks", "Quick picks", tracks = listOf(sampleTrack))),
    )

    override suspend fun shelf(id: String, offset: Int, limit: Int): Outcome<ShelfPage> = Outcome.Success(ShelfPage())
    override suspend fun genres(): Outcome<List<String>> = Outcome.Success(emptyList())
    override suspend fun genre(name: String, offset: Int, limit: Int): Outcome<List<Track>> =
        Outcome.Failure(PodiumError.Unsupported("genres"))

    override fun search(text: String, offset: Int, limit: Int): Flow<Outcome<SearchResults>> =
        flowOf(Outcome.Success(SearchResults(tracks = listOf(sampleTrack))))

    override suspend fun artist(id: ArtistId): Outcome<ArtistDetail> = Outcome.Failure(PodiumError.NotFound("artist"))
    override suspend fun relatedArtists(id: ArtistId): Outcome<List<ArtistSummary>> = Outcome.Success(emptyList())
    override suspend fun collection(id: PlaylistId): Outcome<PlaylistDetail> = Outcome.Failure(PodiumError.NotFound("collection"))
    override val likedIds: StateFlow<Set<TrackId>> = MutableStateFlow(emptySet())
    override val likedTracks: Flow<List<Track>> = MutableStateFlow(listOf(sampleTrack))
    override fun setLiked(track: Track, liked: Boolean) = Unit
    override val playlists: Flow<List<OnlinePlaylist>> = MutableStateFlow(emptyList())
    override fun playlist(id: String): Flow<OnlinePlaylistContents?> = MutableStateFlow(null)
    override suspend fun createPlaylist(name: String, tracks: List<Track>) = ""
    override fun renamePlaylist(id: String, name: String) = Unit
    override fun deletePlaylist(id: String) = Unit
    override fun addToPlaylist(id: String, tracks: List<Track>, onAdded: (Int) -> Unit) = Unit
    override fun removeFromPlaylist(id: String, entryId: Long) = Unit
    override fun movePlaylistEntry(id: String, entryId: Long, toIndex: Int) = Unit
    override val recentlyPlayed: Flow<List<Track>> = MutableStateFlow(listOf(sampleTrack))
    override fun clearHistory() = Unit
}
