@file:OptIn(UnstableApi::class)

package app.podium.player.service

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import app.podium.core.model.AudioQuality
import app.podium.core.model.Codec
import app.podium.core.model.SourceId
import app.podium.core.model.TrackId
import app.podium.sources.api.PlayableMedia
import org.json.JSONObject
import java.io.File

/**
 * Persistent audio streaming cache on device storage (512 MB LRU in `cacheDir/stream`), keyed
 * by [PlayableMedia.cacheKey] (`"<trackId>|<itag>"`) so cached audio survives expiring CDN URLs.
 */
class StreamAudioCache private constructor(context: Context) {

    val cacheDir: File = File(context.cacheDir, CACHE_DIR_NAME)

    val cache: SimpleCache = SimpleCache(
        cacheDir,
        LeastRecentlyUsedCacheEvictor(MAX_BYTES),
        StandaloneDatabaseProvider(context.applicationContext),
    )

    private val metaPrefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Records [media] metadata for [trackId] so a cache-complete track can bypass network walk later. */
    fun recordMeta(trackId: TrackId, media: PlayableMedia) {
        val json = JSONObject().apply {
            put("cacheKey", media.cacheKey)
            put("sourceId", media.sourceId.value)
            media.mimeType?.let { put("mimeType", it) }
            media.durationMs?.let { put("durationMs", it) }
            media.claimedQuality?.let { q ->
                q.codec?.let { put("codec", it.name) }
                q.container?.let { put("container", it) }
                q.bitrateKbps?.let { put("bitrate", it) }
                q.sampleRateHz?.let { put("sampleRate", it) }
                q.bitDepth?.let { put("bitDepth", it) }
                q.channels?.let { put("channels", it) }
            }
        }
        metaPrefs.edit { putString(trackId.value, json.toString()) }
    }

    /** Removes stored metadata for [trackId] (e.g. if evicted or refused). */
    fun forgetMeta(trackId: TrackId) {
        metaPrefs.edit { remove(trackId.value) }
    }

    /**
     * Returns a synthetic [PlayableMedia] pointing to the disk cache if ALL bytes of [trackId] are
     * currently cached on disk in [SimpleCache], or null if not fully cached.
     */
    fun findCompleteCached(trackId: TrackId): PlayableMedia? {
        val raw = metaPrefs.getString(trackId.value, null) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val cacheKey = json.optString("cacheKey", "").takeIf { it.isNotEmpty() } ?: return null
        if (!isKeyComplete(cacheKey)) return null

        val sourceId = SourceId(json.optString("sourceId", trackId.sourceId.value))
        val codec = json.optString("codec", "").takeIf { it.isNotEmpty() }?.let { name ->
            Codec.entries.firstOrNull { it.name == name }
        }
        val quality = if (codec != null || json.has("bitrate") || json.has("sampleRate")) {
            AudioQuality(
                codec = codec,
                container = json.optString("container", "").takeIf { it.isNotEmpty() },
                bitrateKbps = json.optInt("bitrate", -1).takeIf { it > 0 },
                sampleRateHz = json.optInt("sampleRate", -1).takeIf { it > 0 },
                bitDepth = json.optInt("bitDepth", -1).takeIf { it > 0 },
                channels = json.optInt("channels", -1).takeIf { it > 0 },
            )
        } else {
            null
        }
        return PlayableMedia(
            uri = "$CACHED_URI_PREFIX${trackId.providerKey}",
            mimeType = json.optString("mimeType", "").takeIf { it.isNotEmpty() },
            claimedQuality = quality,
            durationMs = json.optLong("durationMs", -1L).takeIf { it > 0L },
            expiresAtMillis = null,
            headers = emptyMap(),
            sourceId = sourceId,
            cacheKey = cacheKey,
        )
    }

    fun isKeyComplete(cacheKey: String): Boolean {
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(cacheKey))
        return length != C.LENGTH_UNSET.toLong() && length > 0L && cache.isCached(cacheKey, 0L, length)
    }

    fun cacheSizeBytes(): Long = runCatching { cache.cacheSpace }.getOrDefault(0L)

    fun maxSizeBytes(): Long = MAX_BYTES

    /** Safe while [PlaybackService] is alive: removes every cached resource via [SimpleCache] API. */
    fun clear() {
        runCatching {
            cache.keys.toList().forEach { key ->
                runCatching { cache.removeResource(key) }
            }
        }
        metaPrefs.edit { clear() }
    }

    companion object {
        private const val CACHE_DIR_NAME = "stream"
        private const val PREFS_NAME = "podium_stream_cache_meta"
        const val MAX_BYTES = 512L * 1024L * 1024L
        const val CACHED_URI_PREFIX = "https://podium-cache.invalid/"

        @Volatile
        private var instance: StreamAudioCache? = null

        fun get(context: Context): StreamAudioCache =
            instance ?: synchronized(this) {
                instance ?: StreamAudioCache(context.applicationContext).also { instance = it }
            }
    }
}
