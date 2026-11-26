package app.podium.ytm.explore

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import app.podium.core.common.Outcome
import app.podium.core.model.Track
import app.podium.feature.online.OnlineRepository
import app.podium.feature.online.OnlineStatus
import app.podium.feature.settings.AiExploreProvider
import app.podium.feature.settings.DeviceSettingsRepository
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ExploreGenreCard(
    val title: String,
    val query: String,
    val badge: String? = null,
)

data class ExploreCuratedShelf(
    val title: String,
    val subtitle: String,
    val query: String,
)

enum class ExploreBlueprintSource {
    AI_GEMINI,
    AI_GROQ,
    LOCAL_FALLBACK,
}

data class ExploreBlueprint(
    val accountKey: String,
    val generatedAtMs: Long,
    val source: ExploreBlueprintSource,
    val profileSummary: String,
    val genres: List<ExploreGenreCard>,
    val shelves: List<ExploreCuratedShelf>,
    val statusNote: String? = null,
)

data class AiQuotaStatus(
    val callsUsedToday: Int,
    val maxCallsPerDay: Int,
    val lastGeneratedAtMs: Long?,
    val rateLimitedUntilMs: Long,
    val hasApiKey: Boolean,
    val hasBackupKey: Boolean,
    val provider: AiExploreProvider,
)

internal data class AiHttpResponse(
    val code: Int,
    val body: String,
    val retryAfterSeconds: Long? = null,
)

internal fun interface AiHttpTransport {
    suspend fun postJson(url: String, headers: Map<String, String>, body: String): AiHttpResponse
}

internal interface ExploreCacheStore {
    fun readBlueprint(accountKey: String): ExploreBlueprint?
    fun writeBlueprint(accountKey: String, blueprint: ExploreBlueprint)
    fun callsOnDay(dayKey: String): Int
    fun incrementCallsOnDay(dayKey: String): Int
    fun rateLimitedUntilMs(): Long
    fun setRateLimitedUntilMs(untilMs: Long)
    fun clearAccountCache(accountKey: String)
    fun clearAllCaches()
}

class AiExploreCurator internal constructor(
    private val store: ExploreCacheStore,
    private val online: OnlineRepository,
    private val settings: DeviceSettingsRepository,
    private val accountKey: () -> String,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val localeCountry: () -> String = { Locale.getDefault().country.ifBlank { "ID" } },
    private val transport: AiHttpTransport = UrlConnectionAiTransport,
) {
    constructor(
        context: Context,
        online: OnlineRepository,
        settings: DeviceSettingsRepository,
        accountKey: () -> String,
    ) : this(
        store = PrefsExploreCacheStore(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)),
        online = online,
        settings = settings,
        accountKey = accountKey,
    )

    private val mutex = Mutex()

    fun quotaStatus(): AiQuotaStatus {
        val now = nowMillis()
        val activeAccount = normalizedAccountKey(accountKey(), online.status.value)
        val cached = store.readBlueprint(activeAccount)
        return AiQuotaStatus(
            callsUsedToday = store.callsOnDay(dayKey(now)),
            maxCallsPerDay = MAX_AI_CALLS_PER_DAY,
            lastGeneratedAtMs = cached?.generatedAtMs,
            rateLimitedUntilMs = store.rateLimitedUntilMs(),
            hasApiKey = settings.aiExploreApiKey.value.isNotBlank(),
            hasBackupKey = settings.aiExploreBackupApiKey.value.isNotBlank(),
            provider = settings.aiExploreProvider.value,
        )
    }

    fun clearCache() {
        store.clearAllCaches()
    }

    /** Zero-quota local shuffle for instant weekend variety without spending any AI calls. */
    fun shuffleLocal(blueprint: ExploreBlueprint): ExploreBlueprint = blueprint.copy(
        genres = blueprint.genres.shuffled(),
        shelves = blueprint.shelves.shuffled(),
        statusNote = "Shuffled locally (0 AI quota used)",
    )

    suspend fun loadExplore(forceRefresh: Boolean = false): ExploreBlueprint = mutex.withLock {
        val now = nowMillis()
        val key = normalizedAccountKey(accountKey(), online.status.value)
        val primaryKey = settings.aiExploreApiKey.value.trim()
        val primaryProvider = settings.aiExploreProvider.value
        val backupKey = settings.aiExploreBackupApiKey.value.trim()
        val backupProvider = settings.aiExploreBackupProvider.value
        val cached = store.readBlueprint(key)
        val rateLimitedUntil = store.rateLimitedUntilMs()
        val hasAnyKey = primaryKey.isNotEmpty() || backupKey.isNotEmpty()

        // Within 24h TTL without manual refresh -> return cached instantaneously (0 AI calls).
        if (!forceRefresh && cached != null && now - cached.generatedAtMs < CACHE_TTL_MS) {
            val shouldUpgradeFallback = cached.source == ExploreBlueprintSource.LOCAL_FALLBACK &&
                hasAnyKey &&
                rateLimitedUntil <= now &&
                store.callsOnDay(dayKey(now)) < MAX_AI_CALLS_PER_DAY
            if (!shouldUpgradeFallback) {
                return@withLock cached
            }
        }

        // Anti-double-tap cooldown (30s) on manual refresh; keeps responsive customization.
        if (forceRefresh && cached != null && cached.source != ExploreBlueprintSource.LOCAL_FALLBACK &&
            now - cached.generatedAtMs < MANUAL_REFRESH_COOLDOWN_MS && hasAnyKey
        ) {
            return@withLock cached.copy(
                genres = cached.genres.shuffled(),
                statusNote = "Refreshed locally • Wait 30s before calling AI again",
            )
        }

        val taste = gatherTasteSignals(syncLikes = forceRefresh)

        if (!hasAnyKey) {
            return@withLock buildLocalFallback(
                accountKey = key,
                now = now,
                taste = taste,
                note = null,
                shuffled = forceRefresh,
            ).also { store.writeBlueprint(key, it) }
        }

        val today = dayKey(now)
        val usedToday = store.callsOnDay(today)

        // If rate-limited on primary/all or daily budget reached -> adapt locally using latest YouTube habits!
        if (rateLimitedUntil > now && backupKey.isEmpty()) {
            val note = "AI cooldown active — refreshed using latest YouTube likes & history"
            return@withLock buildLocalFallback(key, now, taste, note = note, shuffled = forceRefresh)
        }

        if (usedToday >= MAX_AI_CALLS_PER_DAY) {
            val note = "Daily AI cap ($usedToday/$MAX_AI_CALLS_PER_DAY) reached — adapted using latest YouTube likes & history"
            return@withLock buildLocalFallback(key, now, taste, note = note, shuffled = true)
        }

        val avoidTitles = if (forceRefresh) cached?.genres?.map { it.title }.orEmpty() else emptyList()
        val prompt = buildCompactPrompt(
            taste = taste,
            country = localeCountry(),
            period = timeOfDayLabel(now),
            avoidTitles = avoidTitles,
        )

        var lastOutcome: AiCallOutcome = AiCallOutcome.Failed("no key")

        // Try Primary Provider if available and not in rate-limit backoff
        if (primaryKey.isNotEmpty() && rateLimitedUntil <= now) {
            store.incrementCallsOnDay(today)
            lastOutcome = callProvider(primaryProvider, primaryKey, prompt, key, now)
            if (lastOutcome is AiCallOutcome.Success) {
                store.writeBlueprint(key, lastOutcome.blueprint)
                return@withLock lastOutcome.blueprint
            }
        }

        // Weekend / Quota Auto-Failover to Backup Provider/Key
        val canTryBackup = backupKey.isNotEmpty() &&
            (backupProvider != primaryProvider || backupKey != primaryKey) &&
            store.callsOnDay(today) < MAX_AI_CALLS_PER_DAY
        if (canTryBackup) {
            store.incrementCallsOnDay(today)
            val backupOutcome = callProvider(backupProvider, backupKey, prompt, key, now)
            if (backupOutcome is AiCallOutcome.Success) {
                val noted = backupOutcome.blueprint.copy(
                    statusNote = if (primaryKey.isNotEmpty()) {
                        "Primary key hit limit — switched to ${backupProvider.label}"
                    } else null,
                )
                store.writeBlueprint(key, noted)
                return@withLock noted
            }
            lastOutcome = backupOutcome
        }

        return@withLock when (val outcome = lastOutcome) {
            is AiCallOutcome.RateLimited -> {
                val backoffMs = maxOf(RATE_LIMIT_BACKOFF_MS, (outcome.retryAfterSeconds ?: 0L) * 1000L)
                store.setRateLimitedUntilMs(now + backoffMs)
                val note = "Free-tier rate limit (HTTP 429) — adapted locally using YouTube likes & history"
                buildLocalFallback(key, now, taste, note = note, shuffled = true).also {
                    store.writeBlueprint(key, it)
                }
            }
            is AiCallOutcome.Failed -> {
                val note = "AI unavailable (${outcome.reason}) — adapted locally using YouTube history"
                buildLocalFallback(key, now, taste, note = note, shuffled = forceRefresh)
            }
            is AiCallOutcome.Success -> outcome.blueprint
        }
    }

    suspend fun searchTracksForQuery(query: String, limit: Int = 8): List<Track> {
        var tracks: List<Track> = emptyList()
        runCatching {
            online.search(query, offset = 0, limit = limit).collect { outcome ->
                if (outcome is Outcome.Success) {
                    val combined = (outcome.value.tracks + outcome.value.videos)
                        .distinctBy { it.id }
                        .take(limit)
                    if (combined.isNotEmpty()) {
                        tracks = combined
                    }
                }
            }
        }
        return tracks
    }

    private suspend fun gatherTasteSignals(syncLikes: Boolean): TasteSignals {
        if (syncLikes) {
            runCatching { online.refreshLibrary() }
            // Brief yield so an active network likes sync (VLLM) updates Room if fast
            delay(250L)
        } else {
            runCatching { online.refreshLibrary() }
        }

        val liked = runCatching { online.likedTracks.firstOrNull().orEmpty() }.getOrElse { emptyList() }
        val recent = runCatching { online.recentlyPlayed.firstOrNull().orEmpty() }.getOrElse { emptyList() }
        val accountHistory = runCatching {
            (online.accountHistory(0, 20) as? Outcome.Success)?.value.orEmpty().map { it.track }
        }.getOrElse { emptyList() }
        val libraryArtists = runCatching {
            (online.libraryArtists(0, 12) as? Outcome.Success)?.value.orEmpty()
        }.getOrElse { emptyList() }
        val playlistTitles = runCatching {
            (online.libraryPlaylists(0, 8) as? Outcome.Success)?.value.orEmpty().map { it.title }
        }.getOrElse { emptyList() }
        val albumTitles = runCatching {
            (online.libraryAlbums(0, 8) as? Outcome.Success)?.value.orEmpty().map { "${it.title} - ${it.artistDisplay}" }
        }.getOrElse { emptyList() }
        val shelves = runCatching {
            (online.shelves() as? Outcome.Success)?.value.orEmpty()
        }.getOrElse { emptyList() }

        // Prioritize recent in-app plays + YouTube account history + liked songs for adaptive refresh
        val orderedSeeds = (recent.take(6) + accountHistory.take(8) + liked.take(8) + shelves.flatMap { it.tracks }.take(6))
            .distinctBy { "${it.title.lowercase()}|${it.artistDisplay.lowercase()}" }
            .take(15)
            .map { track ->
                val artist = track.artistDisplay.trim()
                if (artist.isNotEmpty()) "${track.title} - $artist" else track.title
            }

        val topArtists = (
            recent.map { it.artistDisplay } +
                accountHistory.map { it.artistDisplay } +
                liked.map { it.artistDisplay } +
                libraryArtists.map { it.name } +
                shelves.flatMap { it.tracks }.map { it.artistDisplay }
            )
            .flatMap { it.split(",", "&", "feat.", "ft.") }
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals("Unknown", ignoreCase = true) }
            .distinctBy { it.lowercase() }
            .take(12)

        return TasteSignals(
            topArtists = topArtists,
            seedTracks = orderedSeeds,
            playlistThemes = (playlistTitles + albumTitles).distinct().take(6),
            signedIn = online.status.value?.account?.signedIn == true,
        )
    }

    private suspend fun callProvider(
        provider: AiExploreProvider,
        apiKey: String,
        prompt: String,
        accountKey: String,
        now: Long,
    ): AiCallOutcome {
        val response = runCatching {
            when (provider) {
                AiExploreProvider.GEMINI -> {
                    val url = "https://generativelanguage.googleapis.com/v1beta/models/${provider.defaultModel}:generateContent"
                    val body = JSONObject().apply {
                        put(
                            "contents",
                            JSONArray().put(
                                JSONObject().put(
                                    "parts",
                                    JSONArray().put(JSONObject().put("text", prompt)),
                                ),
                            ),
                        )
                        put(
                            "generationConfig",
                            JSONObject().apply {
                                put("temperature", 0.85)
                                put("maxOutputTokens", 900)
                                put("responseMimeType", "application/json")
                            },
                        )
                    }.toString()
                    transport.postJson(
                        url = url,
                        headers = mapOf(
                            "x-goog-api-key" to apiKey,
                            "Content-Type" to "application/json",
                        ),
                        body = body,
                    )
                }

                AiExploreProvider.GROQ -> {
                    val url = "https://api.groq.com/openai/v1/chat/completions"
                    val body = JSONObject().apply {
                        put("model", provider.defaultModel)
                        put("temperature", 0.85)
                        put("max_tokens", 900)
                        put("response_format", JSONObject().put("type", "json_object"))
                        put(
                            "messages",
                            JSONArray()
                                .put(JSONObject().put("role", "system").put("content", "Return valid JSON only."))
                                .put(JSONObject().put("role", "user").put("content", prompt)),
                        )
                    }.toString()
                    transport.postJson(
                        url = url,
                        headers = mapOf(
                            "Authorization" to "Bearer $apiKey",
                            "Content-Type" to "application/json",
                        ),
                        body = body,
                    )
                }
            }
        }.getOrElse {
            return AiCallOutcome.Failed(it.message ?: "network error")
        }

        if (response.code == 429) {
            return AiCallOutcome.RateLimited(response.retryAfterSeconds)
        }
        if (response.code == 401 || response.code == 403) {
            return AiCallOutcome.Failed("invalid API key")
        }
        if (response.code !in 200..299) {
            return AiCallOutcome.Failed("HTTP ${response.code}")
        }

        val rawText = extractModelText(provider, response.body)
            ?: return AiCallOutcome.Failed("empty AI response")

        val parsed = parseBlueprintJson(
            rawJson = rawText,
            accountKey = accountKey,
            generatedAtMs = now,
            source = when (provider) {
                AiExploreProvider.GEMINI -> ExploreBlueprintSource.AI_GEMINI
                AiExploreProvider.GROQ -> ExploreBlueprintSource.AI_GROQ
            },
        ) ?: return AiCallOutcome.Failed("invalid JSON format")

        return AiCallOutcome.Success(parsed)
    }

    private fun extractModelText(provider: AiExploreProvider, rawResponse: String): String? = runCatching {
        val root = JSONObject(rawResponse)
        when (provider) {
            AiExploreProvider.GEMINI -> {
                root.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    ?.takeIf { it.isNotBlank() }
            }
            AiExploreProvider.GROQ -> {
                root.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?.takeIf { it.isNotBlank() }
            }
        }
    }.getOrNull()

    internal fun parseBlueprintJson(
        rawJson: String,
        accountKey: String,
        generatedAtMs: Long,
        source: ExploreBlueprintSource,
    ): ExploreBlueprint? = runCatching {
        val cleaned = extractJsonObject(rawJson) ?: return null
        val json = JSONObject(cleaned)
        val summary = json.optString("profileSummary").trim()
            .ifBlank { "Personalized mix & current trends" }

        val genresJson = json.optJSONArray("genres") ?: JSONArray()
        val genres = buildList {
            for (i in 0 until genresJson.length()) {
                val obj = genresJson.optJSONObject(i) ?: continue
                val title = obj.optString("title").trim()
                val query = obj.optString("query").trim().ifBlank { title }
                val badge = obj.optString("badge").trim().takeIf { it.isNotEmpty() }
                if (title.isNotEmpty()) add(ExploreGenreCard(title = title, query = query, badge = badge))
            }
        }.distinctBy { it.title.lowercase() }.take(10)

        val shelvesJson = json.optJSONArray("shelves") ?: JSONArray()
        val shelves = buildList {
            for (i in 0 until shelvesJson.length()) {
                val obj = shelvesJson.optJSONObject(i) ?: continue
                val title = obj.optString("title").trim()
                val subtitle = obj.optString("subtitle").trim()
                val query = obj.optString("query").trim().ifBlank { title }
                if (title.isNotEmpty()) {
                    add(ExploreCuratedShelf(title = title, subtitle = subtitle, query = query))
                }
            }
        }.distinctBy { it.title.lowercase() }.take(4)

        if (genres.isEmpty() && shelves.isEmpty()) return null

        ExploreBlueprint(
            accountKey = accountKey,
            generatedAtMs = generatedAtMs,
            source = source,
            profileSummary = summary,
            genres = genres,
            shelves = shelves,
        )
    }.getOrNull()

    private fun extractJsonObject(text: String): String? {
        val stripped = text.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = stripped.indexOf('{')
        val end = stripped.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return stripped.substring(start, end + 1)
    }

    private fun buildCompactPrompt(
        taste: TasteSignals,
        country: String,
        period: String,
        avoidTitles: List<String>,
    ): String {
        val artists = if (taste.topArtists.isEmpty()) "none yet" else taste.topArtists.joinToString(", ")
        val tracks = if (taste.seedTracks.isEmpty()) "none yet" else taste.seedTracks.joinToString("; ")
        val themes = if (taste.playlistThemes.isEmpty()) "none" else taste.playlistThemes.joinToString(", ")
        val avoidClause = if (avoidTitles.isNotEmpty()) {
            " Avoid repeating these previous titles: [${avoidTitles.joinToString(", ")}]."
        } else ""
        return """
            You curate YouTube Music Explore in region=$country during $period.
            Account habits & likes (anonymous): recent_and_liked=[$tracks]; favorite_artists=[$artists]; playlists_albums=[$themes].$avoidClause
            Return ONLY JSON with keys:
            - "profileSummary": concise 1-sentence summary of listener's YouTube habits & trends (max 15 words).
            - "genres": 10 diverse mood/genre cards adapting to recent habits, liked vibe, adjacent discovery, and regional hits. Each: {"title":"2-4 words","query":"YouTube Music search query","badge":"For You|Liked Vibe|Trending|Discover"}.
            - "shelves": 4 curated song shelves mixing habits & discovery. Each: {"title":"...","subtitle":"short reason","query":"specific search query for songs"}.
        """.trimIndent()
    }

    private fun buildLocalFallback(
        accountKey: String,
        now: Long,
        taste: TasteSignals,
        note: String?,
        shuffled: Boolean,
    ): ExploreBlueprint {
        val artist0 = taste.topArtists.getOrNull(0)
        val artist1 = taste.topArtists.getOrNull(1)
        val artist2 = taste.topArtists.getOrNull(2)
        val regionLabel = if (localeCountry().equals("ID", ignoreCase = true)) "Indonesia" else "Global"

        val baseGenres = buildList {
            if (artist0 != null) {
                add(ExploreGenreCard("$artist0 Vibe", "$artist0 similar songs mix", badge = "Liked Vibe"))
            }
            if (artist1 != null) {
                add(ExploreGenreCard("Like $artist1", "$artist1 radio top songs", badge = "For You"))
            }
            if (artist2 != null) {
                add(ExploreGenreCard("$artist2 Mix", "$artist2 popular tracks", badge = "For You"))
            }
            add(ExploreGenreCard("Trending $regionLabel", "top hits $regionLabel trending songs 2026", badge = "Trending"))
            add(ExploreGenreCard("Indie & Chill", "indie pop chill acoustic songs", badge = "Discover"))
            add(ExploreGenreCard("Pop Hits", "popular pop hits songs playlist", badge = "Trending"))
            add(ExploreGenreCard("R&B & Soul", "modern rnb soul chill songs", badge = "Mood"))
            add(ExploreGenreCard("Rock & Alt", "alternative rock hits songs", badge = "Energy"))
            add(ExploreGenreCard("Electronic & Lo-fi", "lofi chill beats synth electronic music", badge = "Focus"))
            add(ExploreGenreCard("J-Pop & Anime", "popular jpop anime hits songs", badge = "Discover"))
            add(ExploreGenreCard("90s & 2000s", "90s 2000s nostalgic hits songs", badge = "Throwback"))
        }.take(10)

        val baseShelves = buildList {
            if (artist0 != null) {
                add(
                    ExploreCuratedShelf(
                        title = "Because You Like $artist0",
                        subtitle = "Tailored to your YouTube likes & history",
                        query = "$artist0 similar artists top songs",
                    ),
                )
            }
            add(
                ExploreCuratedShelf(
                    title = "Trending in $regionLabel",
                    subtitle = "Popular songs rising right now",
                    query = "top viral hits $regionLabel songs",
                ),
            )
            if (artist1 != null) {
                add(
                    ExploreCuratedShelf(
                        title = "Mixed Around $artist1",
                        subtitle = "Inspired by your recent listening habits",
                        query = "$artist1 mix songs",
                    ),
                )
            } else {
                add(
                    ExploreCuratedShelf(
                        title = "Chill & Acoustic Picks",
                        subtitle = "Easy listening across genres",
                        query = "acoustic chill pop favorites songs",
                    ),
                )
            }
            add(
                ExploreCuratedShelf(
                    title = "Fresh Genre Discovery",
                    subtitle = "Explore outside your usual rotation",
                    query = "indie electronic rnb best songs mix",
                ),
            )
        }.take(4)

        val summary = if (artist0 != null) {
            "Personalized from your YouTube habits ($artist0) & $regionLabel trends"
        } else {
            "$regionLabel trends & multi-genre discovery"
        }

        return ExploreBlueprint(
            accountKey = accountKey,
            generatedAtMs = now,
            source = ExploreBlueprintSource.LOCAL_FALLBACK,
            profileSummary = summary,
            genres = if (shuffled) baseGenres.shuffled() else baseGenres,
            shelves = if (shuffled) baseShelves.shuffled() else baseShelves,
            statusNote = note,
        )
    }

    private fun normalizedAccountKey(rawKey: String, status: OnlineStatus?): String {
        val cleaned = rawKey.trim()
        if (cleaned.isNotEmpty() && cleaned != "signed_out") return cleaned
        val account = status?.account
        return if (account?.signedIn == true) {
            "google:${account.name?.trim()?.lowercase()?.ifBlank { "signed_in" } ?: "signed_in"}"
        } else {
            "guest"
        }
    }

    private fun dayKey(nowMs: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return "%04d-%02d-%02d".format(Locale.US, y, m, d)
    }

    private fun timeOfDayLabel(nowMs: Long): String {
        val hour = Calendar.getInstance().apply { timeInMillis = nowMs }.get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..10 -> "morning"
            in 11..15 -> "afternoon"
            in 16..20 -> "evening"
            else -> "night"
        }
    }

    private data class TasteSignals(
        val topArtists: List<String>,
        val seedTracks: List<String>,
        val playlistThemes: List<String>,
        val signedIn: Boolean,
    )

    private sealed interface AiCallOutcome {
        data class Success(val blueprint: ExploreBlueprint) : AiCallOutcome
        data class RateLimited(val retryAfterSeconds: Long?) : AiCallOutcome
        data class Failed(val reason: String) : AiCallOutcome
    }

    companion object {
        const val PREFS_NAME = "ai_explore_cache"
        const val CACHE_TTL_MS = 24L * 60 * 60 * 1000
        const val MANUAL_REFRESH_COOLDOWN_MS = 30_000L
        const val RATE_LIMIT_BACKOFF_MS = 24L * 60 * 60 * 1000
        const val MAX_AI_CALLS_PER_DAY = 5
    }
}

private object UrlConnectionAiTransport : AiHttpTransport {
    override suspend fun postJson(url: String, headers: Map<String, String>, body: String): AiHttpResponse =
        withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 18_000
                doOutput = true
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            try {
                conn.outputStream.use { os ->
                    os.write(body.toByteArray(Charsets.UTF_8))
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val retryAfter = conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
                AiHttpResponse(code = code, body = text, retryAfterSeconds = retryAfter)
            } finally {
                conn.disconnect()
            }
        }
}

internal class PrefsExploreCacheStore(
    private val prefs: SharedPreferences,
) : ExploreCacheStore {

    override fun readBlueprint(accountKey: String): ExploreBlueprint? {
        val raw = prefs.getString("$KEY_BLUEPRINT_PREFIX$accountKey", null) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            val source = runCatching {
                ExploreBlueprintSource.valueOf(obj.optString("source"))
            }.getOrDefault(ExploreBlueprintSource.LOCAL_FALLBACK)
            val genresArr = obj.optJSONArray("genres") ?: JSONArray()
            val genres = buildList {
                for (i in 0 until genresArr.length()) {
                    val g = genresArr.optJSONObject(i) ?: continue
                    add(
                        ExploreGenreCard(
                            title = g.optString("title"),
                            query = g.optString("query"),
                            badge = g.optString("badge").takeIf { it.isNotEmpty() },
                        ),
                    )
                }
            }
            val shelvesArr = obj.optJSONArray("shelves") ?: JSONArray()
            val shelves = buildList {
                for (i in 0 until shelvesArr.length()) {
                    val s = shelvesArr.optJSONObject(i) ?: continue
                    add(
                        ExploreCuratedShelf(
                            title = s.optString("title"),
                            subtitle = s.optString("subtitle"),
                            query = s.optString("query"),
                        ),
                    )
                }
            }
            ExploreBlueprint(
                accountKey = accountKey,
                generatedAtMs = obj.optLong("generatedAtMs", 0L),
                source = source,
                profileSummary = obj.optString("profileSummary"),
                genres = genres,
                shelves = shelves,
                statusNote = obj.optString("statusNote").takeIf { it.isNotEmpty() },
            )
        }.getOrNull()
    }

    override fun writeBlueprint(accountKey: String, blueprint: ExploreBlueprint) {
        val obj = JSONObject().apply {
            put("generatedAtMs", blueprint.generatedAtMs)
            put("source", blueprint.source.name)
            put("profileSummary", blueprint.profileSummary)
            blueprint.statusNote?.let { put("statusNote", it) }
            put(
                "genres",
                JSONArray().apply {
                    blueprint.genres.forEach { g ->
                        put(
                            JSONObject().apply {
                                put("title", g.title)
                                put("query", g.query)
                                g.badge?.let { put("badge", it) }
                            },
                        )
                    }
                },
            )
            put(
                "shelves",
                JSONArray().apply {
                    blueprint.shelves.forEach { s ->
                        put(
                            JSONObject().apply {
                                put("title", s.title)
                                put("subtitle", s.subtitle)
                                put("query", s.query)
                            },
                        )
                    }
                },
            )
        }
        prefs.edit { putString("$KEY_BLUEPRINT_PREFIX$accountKey", obj.toString()) }
    }

    override fun callsOnDay(dayKey: String): Int {
        val savedDay = prefs.getString(KEY_CALL_DAY, null)
        return if (savedDay == dayKey) prefs.getInt(KEY_CALL_COUNT, 0) else 0
    }

    override fun incrementCallsOnDay(dayKey: String): Int {
        val next = callsOnDay(dayKey) + 1
        prefs.edit {
            putString(KEY_CALL_DAY, dayKey)
            putInt(KEY_CALL_COUNT, next)
        }
        return next
    }

    override fun rateLimitedUntilMs(): Long = prefs.getLong(KEY_RATE_LIMITED_UNTIL, 0L)

    override fun setRateLimitedUntilMs(untilMs: Long) {
        prefs.edit { putLong(KEY_RATE_LIMITED_UNTIL, untilMs) }
    }

    override fun clearAccountCache(accountKey: String) {
        prefs.edit { remove("$KEY_BLUEPRINT_PREFIX$accountKey") }
    }

    override fun clearAllCaches() {
        val keysToRemove = prefs.all.keys.filter { it.startsWith(KEY_BLUEPRINT_PREFIX) }
        prefs.edit {
            keysToRemove.forEach { remove(it) }
            remove(KEY_RATE_LIMITED_UNTIL)
        }
    }

    private companion object {
        const val KEY_BLUEPRINT_PREFIX = "bp_"
        const val KEY_CALL_DAY = "call_day"
        const val KEY_CALL_COUNT = "call_count"
        const val KEY_RATE_LIMITED_UNTIL = "rate_limited_until"
    }
}
