package app.podium.player.service

import androidx.media3.common.C
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DurationResolutionTest {

    @Test
    fun `prefers positive metadata duration`() {
        assertEquals(
            215_000L,
            resolvePlaybackDurationMs(metadataDurationMs = 215_000L, playerDurationMs = 216_000L, isLive = false),
        )
    }

    @Test
    fun `falls back to player duration when metadata omits length`() {
        assertEquals(
            184_000L,
            resolvePlaybackDurationMs(metadataDurationMs = null, playerDurationMs = 184_000L, isLive = false),
        )
        assertEquals(
            184_000L,
            resolvePlaybackDurationMs(metadataDurationMs = 0L, playerDurationMs = 184_000L, isLive = false),
        )
    }

    @Test
    fun `ignores unset or non positive player duration`() {
        assertNull(resolvePlaybackDurationMs(metadataDurationMs = null, playerDurationMs = C.TIME_UNSET, isLive = false))
        assertNull(resolvePlaybackDurationMs(metadataDurationMs = null, playerDurationMs = 0L, isLive = false))
    }

    @Test
    fun `live stations never report a rewind window duration`() {
        assertNull(resolvePlaybackDurationMs(metadataDurationMs = 60_000L, playerDurationMs = 300_000L, isLive = true))
        assertNull(resolvePlaybackDurationMs(metadataDurationMs = null, playerDurationMs = 300_000L, isLive = true))
    }
}
