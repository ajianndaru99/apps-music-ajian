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
    val activeModel: String? = null,
)

internal data class AiHttpResponse(
    val code: Int,
    val body: String,
    val retryAfterSeconds: Long? = null,
)

internal fun interface AiHttpTransport {
    suspend fun send(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): AiHttpResponse
}

internal interface ExploreCacheStore {
    fun readBlueprint(accountKey: String): ExploreBlueprint?
    fun writeBlueprint(accountKey: String, blueprint: ExploreBlueprint)
    fun callsOnDay(dayKey: String): Int
    fun incrementCallsOnDay(dayKey: String): Int
    fun rateLimitedUntilMs(): Long
    fun setRateLimitedUntilMs(untilMs: Long)
    fun workingModel(provider: AiExploreProvider): String?
    fun setWorkingModel(provider: AiExploreProvider, model: String?)
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
        val provider = settings.aiExploreProvider.value
        return AiQuotaStatus(
            callsUsedToday = store.callsOnDay(dayKey(now)),
            maxCallsPerDay = MAX_AI_CALLS_PER_DAY,
            lastGeneratedAtMs = cached?.generatedAtMs,
            rateLimitedUntilMs = store.rateLimitedUntilMs(),
            hasApiKey = settings.aiExploreApiKey.value.isNotBlank(),
            hasBackupKey = settings.aiExploreBackupApiKey.value.isNotBlank(),
            provider = provider,
            activeModel = store.workingModel(provider),
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

        if (primaryKey.isNotEmpty() && rateLimitedUntil <= now) {
            lastOutcome = callProviderWithDiscovery(primaryProvider, primaryKey, prompt, key, now, today)
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
            val backupOutcome = callProviderWithDiscovery(backupProvider, backupKey, prompt, key, now, today)
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

    private suspend fun callProviderWithDiscovery(
        provider: AiExploreProvider,
        apiKey: String,
        prompt: String,
        accountKey: String,
        now: Long,
        today: String,
    ): AiCallOutcome {
        val candidates = buildList {
            store.workingModel(provider)?.takeIf { it.isNotBlank() }?.let { add(it) }
            addAll(defaultModelCandidates(provider))
        }.distinct().toMutableList()

        var discovered = false
        var lastOutcome: AiCallOutcome = AiCallOutcome.Failed("no models")
        var idx = 0
        var attempts = 0

        while (idx < candidates.size && attempts < MAX_MODEL_CANDIDATES_PER_REQUEST) {
            val model = candidates[idx++]
            attempts++
            val result = invokeSingleModel(provider, model, apiKey, prompt, accountKey, now)

            when (result) {
                is SingleModelResult.Success -> {
                    store.incrementCallsOnDay(today)
                    store.setWorkingModel(provider, model)
                    return AiCallOutcome.Success(result.blueprint)
                }
                is SingleModelResult.RateLimited -> {
                    store.incrementCallsOnDay(today)
                    return AiCallOutcome.RateLimited(result.retryAfterSeconds)
                }
                is SingleModelResult.ModelNotFound -> {
                    // HTTP 404: model retired/unavailable on this key (0 quota consumed).
                    if (store.workingModel(provider) == model) {
                        store.setWorkingModel(provider, null)
                    }
                    if (!discovered) {
                        discovered = true
                        val liveModels = discoverModels(provider, apiKey)
                        if (liveModels.isNotEmpty()) {
                            val alreadyTried = candidates.subList(0, idx).toSet()
                            val tail = candidates.subList(idx, candidates.size).toList()
                            candidates.subList(idx, candidates.size).clear()
                            candidates.addAll((liveModels + tail).filter { it !in alreadyTried }.distinct())
                        }
                    }
                    lastOutcome = AiCallOutcome.Failed("HTTP 404 ($model)")
                }
                is SingleModelResult.Failed -> {
                    // Don't burn daily counter on 401/403 key typo or network disconnect
                    return AiCallOutcome.Failed(result.reason)
                }
            }
        }
        return lastOutcome
    }

    private suspend fun discoverModels(provider: AiExploreProvider, apiKey: String): List<String> = runCatching {
        when (provider) {
            AiExploreProvider.GEMINI -> {
                val res = transport.send(
                    method = "GET",
                    url = "https://generativelanguage.googleapis.com/v1beta/models",
                    headers = mapOf("x-goog-api-key" to apiKey),
                    body = null,
                )
                if (res.code in 200..299) rankGeminiModels(res.body) else emptyList()
            }
            AiExploreProvider.GROQ -> {
                val res = transport.send(
                    method = "GET",
                    url = "https://api.groq.com/openai/v1/models",
                    headers = mapOf("Authorization" to "Bearer $apiKey"),
                    body = null,
                )
                if (res.code in 200..299) rankGroqModels(res.body) else emptyList()
            }
        }
    }.getOrElse { emptyList() }

    private suspend fun invokeSingleModel(
        provider: AiExploreProvider,
        model: String,
        apiKey: String,
        prompt: String,
        accountKey: String,
        now: Long,
    ): SingleModelResult {
        val response = runCatching {
            when (provider) {
                AiExploreProvider.GEMINI -> {
                    val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
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
                    transport.send(
                        method = "POST",
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
                        put("model", model)
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
                    transport.send(
                        method = "POST",
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
            return SingleModelResult.Failed(it.message ?: "network error")
        }

        if (response.code == 404) {
            return SingleModelResult.ModelNotFound
        }
        if (response.code == 429) {
            return SingleModelResult.RateLimited(response.retryAfterSeconds)
        }
        if (response.code == 401 || response.code == 403) {
            return SingleModelResult.Failed("invalid API key")
        }
        if (response.code !in 200..299) {
            return SingleModelResult.Failed("HTTP ${response.code}")
        }

        val rawText = extractModelText(provider, response.body)
            ?: return SingleModelResult.Failed("empty AI response")

        val parsed = parseBlueprintJson(
            rawJson = rawText,
            accountKey = accountKey,
            generatedAtMs = now,
            source = when (provider) {
                AiExploreProvider.GEMINI -> ExploreBlueprintSource.AI_GEMINI
                AiExploreProvider.GROQ -> ExploreBlueprintSource.AI_GROQ
            },
        ) ?: return SingleModelResult.Failed("invalid JSON format")

        return SingleModelResult.Success(parsed)
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

    private sealed interface SingleModelResult {
        data class Success(val blueprint: ExploreBlueprint) : SingleModelResult
        data class RateLimited(val retryAfterSeconds: Long?) : SingleModelResult
        data object ModelNotFound : SingleModelResult
        data class Failed(val reason: String) : SingleModelResult
    }

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
        private const val MAX_MODEL_CANDIDATES_PER_REQUEST = 8

        fun defaultModelCandidates(provider: AiExploreProvider): List<String> = when (provider) {
            AiExploreProvider.GEMINI -> listOf(
                "gemini-3.5-flash-lite",
                "gemini-3.8-flash",
                "gemini-3.6-flash",
                "gemini-3.1-flash-lite",
                "gemini-2.5-flash",
                "gemini-2.5-flash-lite",
                "gemini-2.0-flash",
            )
            AiExploreProvider.GROQ -> listOf(
                "llama-3.3-70b-versatile",
                "llama-3.1-8b-instant",
            )
        }
    }
}

private val GEMINI_VERSION_REGEX = Regex("""gemini-(\d+)(?:\.(\d+))?""")

internal fun rankGeminiModels(modelsJson: String): List<String> = runCatching {
    val root = JSONObject(modelsJson)
    val arr = root.optJSONArray("models") ?: return emptyList()
    val ranked = mutableListOf<Pair<Int, String>>()
    for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val rawName = obj.optString("name").removePrefix("models/").trim()
        val lower = rawName.lowercase()
        if (!lower.startsWith("gemini")) continue
        if ("tts" in lower || "image" in lower || "audio" in lower || "live" in lower ||
            "embedding" in lower || "robotics" in lower || "vision" in lower
        ) continue

        val methods = obj.optJSONArray("supportedGenerationMethods") ?: continue
        var canGenerate = false
        for (j in 0 until methods.length()) {
            if (methods.optString(j) == "generateContent") {
                canGenerate = true
                break
            }
        }
        if (!canGenerate) continue

        val match = GEMINI_VERSION_REGEX.find(lower)
        val major = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val minor = match?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
        var score = major * 100 + minor * 10
        if ("flash-lite" in lower) score += 25
        else if ("flash" in lower) score += 15
        if ("exp" in lower || "preview" in lower) score -= 5
        ranked += score to rawName
    }
    ranked.sortedByDescending { it.first }.map { it.second }.distinct()
}.getOrElse { emptyList() }

internal fun rankGroqModels(modelsJson: String): List<String> = runCatching {
    val root = JSONObject(modelsJson)
    val arr = root.optJSONArray("data") ?: return emptyList()
    val out = mutableListOf<String>()
    for (i in 0 until arr.length()) {
        val id = arr.optJSONObject(i)?.optString("id")?.trim().orEmpty()
        val lower = id.lowercase()
        if (id.isEmpty() || "whisper" in lower || "guard" in lower || "tts" in lower) continue
        if ("llama" in lower || "qwen" in lower || "gpt" in lower) out += id
    }
    out.distinct()
}.getOrElse { emptyList() }

private object UrlConnectionAiTransport : AiHttpTransport {
    override suspend fun send(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): AiHttpResponse = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 18_000
            doOutput = body != null
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            if (body != null) {
                conn.outputStream.use { os ->
                    os.write(body.toByteArray(Charsets.UTF_8))
                }
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

    override fun workingModel(provider: AiExploreProvider): String? =
        prefs.getString("$KEY_WORKING_MODEL_PREFIX${provider.name}", null)

    override fun setWorkingModel(provider: AiExploreProvider, model: String?) {
        prefs.edit {
            if (model.isNullOrBlank()) remove("$KEY_WORKING_MODEL_PREFIX${provider.name}")
            else putString("$KEY_WORKING_MODEL_PREFIX${provider.name}", model)
        }
    }

    override fun clearAccountCache(accountKey: String) {
        prefs.edit { remove("$KEY_BLUEPRINT_PREFIX$accountKey") }
    }

    override fun clearAllCaches() {
        val keysToRemove = prefs.all.keys.filter {
            it.startsWith(KEY_BLUEPRINT_PREFIX) || it.startsWith(KEY_WORKING_MODEL_PREFIX)
        }
        prefs.edit {
            keysToRemove.forEach { remove(it) }
            remove(KEY_RATE_LIMITED_UNTIL)
        }
    }

    private companion object {
        const val KEY_BLUEPRINT_PREFIX = "bp_"
        const val KEY_WORKING_MODEL_PREFIX = "model_"
        const val KEY_CALL_DAY = "call_day_v2"
        const val KEY_CALL_COUNT = "call_count_v2"
        const val KEY_RATE_LIMITED_UNTIL = "rate_limited_until"
    }
}
