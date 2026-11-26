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
            provider = settings.aiExploreProvider.value,
        )
    }

    fun clearCache() {
        store.clearAllCaches()
    }

    suspend fun loadExplore(forceRefresh: Boolean = false): ExploreBlueprint = mutex.withLock {
        val now = nowMillis()
        val key = normalizedAccountKey(accountKey(), online.status.value)
        val apiKey = settings.aiExploreApiKey.value.trim()
        val provider = settings.aiExploreProvider.value
        val cached = store.readBlueprint(key)
        val rateLimitedUntil = store.rateLimitedUntilMs()

        // Never spend quota when a valid 24-hour cache exists, unless user just added an AI key
        // while only a local fallback was cached before.
        if (!forceRefresh && cached != null && now - cached.generatedAtMs < CACHE_TTL_MS) {
            val shouldUpgradeFallback = cached.source == ExploreBlueprintSource.LOCAL_FALLBACK &&
                apiKey.isNotEmpty() &&
                rateLimitedUntil <= now &&
                store.callsOnDay(dayKey(now)) < MAX_AI_CALLS_PER_DAY
            if (!shouldUpgradeFallback) {
                return@withLock cached
            }
        }

        // Guardrail 1: Manual refresh cooldown when cached AI curation is still fresh.
        if (forceRefresh && cached != null && cached.source != ExploreBlueprintSource.LOCAL_FALLBACK &&
            now - cached.generatedAtMs < MANUAL_REFRESH_COOLDOWN_MS && apiKey.isNotEmpty()
        ) {
            return@withLock cached.copy(
                statusNote = "Cached recently • Wait up to 1 hour between AI refreshes to protect free-tier quota",
            )
        }

        val taste = gatherTasteSignals()

        // Guardrail 2: No API key configured -> local personalized + regional trend blueprint.
        if (apiKey.isEmpty()) {
            return@withLock buildLocalFallback(key, now, taste, note = null).also {
                store.writeBlueprint(key, it)
            }
        }

        // Guardrail 3: Previous HTTP 429 rate-limit backoff -> stay offline/local, no retry storm.
        if (rateLimitedUntil > now) {
            val note = "AI quota paused temporarily — using local smart mix"
            return@withLock (cached?.takeIf { it.source != ExploreBlueprintSource.LOCAL_FALLBACK }?.copy(statusNote = note)
                ?: buildLocalFallback(key, now, taste, note = note))
        }

        // Guardrail 4: Daily client-side safety cap (default 3 calls/day vs normal 1 call/day usage).
        val today = dayKey(now)
        val usedToday = store.callsOnDay(today)
        if (usedToday >= MAX_AI_CALLS_PER_DAY) {
            val note = "Daily AI safety cap ($usedToday/$MAX_AI_CALLS_PER_DAY) reached — keeping free-tier key safe"
            return@withLock (cached?.takeIf { it.source != ExploreBlueprintSource.LOCAL_FALLBACK }?.copy(statusNote = note)
                ?: buildLocalFallback(key, now, taste, note = note))
        }

        // Guardrail 5: Record call before network I/O so repeated retries never slip past budget.
        store.incrementCallsOnDay(today)

        val prompt = buildCompactPrompt(taste, country = localeCountry(), period = timeOfDayLabel(now))
        val aiResult = callProvider(provider, apiKey, prompt, key, now)

        return@withLock when (aiResult) {
            is AiCallOutcome.Success -> {
                store.writeBlueprint(key, aiResult.blueprint)
                aiResult.blueprint
            }
            is AiCallOutcome.RateLimited -> {
                val backoffMs = maxOf(RATE_LIMIT_BACKOFF_MS, (aiResult.retryAfterSeconds ?: 0L) * 1000L)
                store.setRateLimitedUntilMs(now + backoffMs)
                val note = "Free-tier rate limit reached (HTTP 429) — paused AI for 24h & switched to local mix"
                cached?.takeIf { it.source != ExploreBlueprintSource.LOCAL_FALLBACK }?.copy(statusNote = note)
                    ?: buildLocalFallback(key, now, taste, note = note).also { store.writeBlueprint(key, it) }
            }
            is AiCallOutcome.Failed -> {
                val note = "AI unavailable (${aiResult.reason}) — using local smart mix"
                cached?.takeIf { it.source != ExploreBlueprintSource.LOCAL_FALLBACK }?.copy(statusNote = note)
                    ?: buildLocalFallback(key, now, taste, note = note)
            }
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

    private suspend fun gatherTasteSignals(): TasteSignals {
        val liked = runCatching { online.likedTracks.firstOrNull().orEmpty() }.getOrElse { emptyList() }
        val recent = runCatching { online.recentlyPlayed.firstOrNull().orEmpty() }.getOrElse { emptyList() }
        val libraryArtists = runCatching {
            (online.libraryArtists(0, 10) as? Outcome.Success)?.value.orEmpty()
        }.getOrElse { emptyList() }
        val shelves = runCatching {
            (online.shelves() as? Outcome.Success)?.value.orEmpty()
        }.getOrElse { emptyList() }

        val seedTracks = (liked.take(8) + recent.take(6) + shelves.flatMap { it.tracks }.take(8))
            .distinctBy { "${it.title.lowercase()}|${it.artistDisplay.lowercase()}" }
            .take(14)
            .map { track ->
                val artist = track.artistDisplay.trim()
                if (artist.isNotEmpty()) "${track.title} - $artist" else track.title
            }

        val topArtists = (
            libraryArtists.map { it.name } +
                liked.map { it.artistDisplay } +
                recent.map { it.artistDisplay } +
                shelves.flatMap { it.tracks }.map { it.artistDisplay }
            )
            .flatMap { it.split(",", "&", "feat.", "ft.") }
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals("Unknown", ignoreCase = true) }
            .distinctBy { it.lowercase() }
            .take(10)

        return TasteSignals(
            topArtists = topArtists,
            seedTracks = seedTracks,
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
                                put("temperature", 0.75)
                                put("maxOutputTokens", 700)
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
                        put("temperature", 0.75)
                        put("max_tokens", 700)
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
    ): String {
        val artists = if (taste.topArtists.isEmpty()) "none yet" else taste.topArtists.joinToString(", ")
        val tracks = if (taste.seedTracks.isEmpty()) "none yet" else taste.seedTracks.joinToString("; ")
        return """
            You curate music discovery for YouTube Music in region=$country during $period.
            Listener taste (anonymous): artists=[$artists]; tracks=[$tracks].
            Return ONLY JSON with keys:
            - "profileSummary": short 1-sentence taste + trend summary (max 14 words).
            - "genres": 8 diverse genre/mood tiles blending listener's favorites, adjacent genres, and current hits. Each object: {"title":"2-4 words","query":"YouTube Music search query","badge":"For You|Trending|Discover"}.
            - "shelves": 3 curated song shelves ("Trending Now", "Because of your vibe", "Fresh Genre Mix"). Each object: {"title":"...","subtitle":"short reason","query":"specific search query for songs"}.
        """.trimIndent()
    }

    private fun buildLocalFallback(
        accountKey: String,
        now: Long,
        taste: TasteSignals,
        note: String?,
    ): ExploreBlueprint {
        val topArtist = taste.topArtists.firstOrNull()
        val secondArtist = taste.topArtists.getOrNull(1)
        val regionLabel = if (localeCountry().equals("ID", ignoreCase = true)) "Indonesia" else "Global"

        val baseGenres = buildList {
            if (topArtist != null) {
                add(ExploreGenreCard("$topArtist Mix", "$topArtist top songs mix", badge = "For You"))
            }
            if (secondArtist != null) {
                add(ExploreGenreCard("Similar to $secondArtist", "$secondArtist radio mix songs", badge = "For You"))
            }
            add(ExploreGenreCard("Trending $regionLabel", "top hits $regionLabel trending songs 2026", badge = "Trending"))
            add(ExploreGenreCard("Indie & Chill", "indie pop chill acoustic songs", badge = "Discover"))
            add(ExploreGenreCard("Pop Hits", "popular pop hits songs playlist", badge = "Trending"))
            add(ExploreGenreCard("R&B & Soul", "modern rnb soul chill songs", badge = "Mood"))
            add(ExploreGenreCard("Rock & Alt", "alternative rock hits songs", badge = "Energy"))
            add(ExploreGenreCard("Electronic & Lo-fi", "lofi chill beats synth electronic music", badge = "Focus"))
            add(ExploreGenreCard("J-Pop & Anime", "popular jpop anime hits songs", badge = "Discover"))
            add(ExploreGenreCard("90s & 2000s", "90s 2000s nostalgic hits songs", badge = "Throwback"))
        }.take(8)

        val shelves = buildList {
            add(
                ExploreCuratedShelf(
                    title = "Trending in $regionLabel",
                    subtitle = "Popular songs rising right now",
                    query = "top viral hits $regionLabel songs",
                ),
            )
            if (topArtist != null) {
                add(
                    ExploreCuratedShelf(
                        title = "Inspired by $topArtist",
                        subtitle = "Tailored to your active account taste",
                        query = "$topArtist similar artists top songs",
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
                    title = "Genre Discovery Mix",
                    subtitle = "Explore outside your usual rotation",
                    query = "indie electronic rnb best songs mix",
                ),
            )
        }

        val summary = if (topArtist != null) {
            "Mix inspired by $topArtist & $regionLabel trends"
        } else {
            "$regionLabel trends & multi-genre discovery"
        }

        return ExploreBlueprint(
            accountKey = accountKey,
            generatedAtMs = now,
            source = ExploreBlueprintSource.LOCAL_FALLBACK,
            profileSummary = summary,
            genres = baseGenres,
            shelves = shelves,
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
        const val MANUAL_REFRESH_COOLDOWN_MS = 60L * 60 * 1000
        const val RATE_LIMIT_BACKOFF_MS = 24L * 60 * 60 * 1000
        const val MAX_AI_CALLS_PER_DAY = 3
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
