package app.podium.ytm.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.AppGraph
import app.podium.core.lyrics.Lyrics
import app.podium.core.lyrics.LyricsRequest
import app.podium.core.lyrics.LyricsResult
import app.podium.player.api.PlayIntent
import app.podium.ytm.YtmTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun YtmLyricsTab(
    graph: AppGraph,
    modifier: Modifier = Modifier,
) {
    val colors = YtmTheme.colors
    val controller = graph.playbackController
    val snapshot by controller.snapshot.collectAsStateWithLifecycle()
    val item = snapshot.item
    val onlineLyricsEnabled by graph.deviceSettings.onlineLyrics.collectAsStateWithLifecycle()

    var result by remember(item?.trackId, onlineLyricsEnabled) { mutableStateOf<LyricsResult?>(null) }
    var loading by remember(item?.trackId, onlineLyricsEnabled) { mutableStateOf(false) }

    LaunchedEffect(item?.trackId, onlineLyricsEnabled) {
        if (item == null) {
            result = null
            return@LaunchedEffect
        }
        loading = true
        result = graph.lyricsGateway.lyrics(
            LyricsRequest(
                key = item.trackId.value,
                title = item.title,
                artist = item.artistDisplay,
                album = item.albumTitle,
                durationMs = item.durationMs,
            ),
        )
        loading = false
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (val r = result) {
            null -> {
                if (loading) {
                    CircularProgressIndicator(color = colors.onSurface)
                }
            }
            LyricsResult.NeedsConsent -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Enable online lyrics?",
                        style = YtmTheme.type.titleLarge,
                        color = colors.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Podium sends song title, artist, album, and duration to LRCLIB to fetch synced lyrics.",
                        style = YtmTheme.type.bodyMedium,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { graph.lyricsGateway.allow() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.accent,
                            contentColor = colors.background,
                        ),
                        shape = RoundedCornerShape(50),
                    ) {
                        Text("Enable lyrics", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            LyricsResult.NotFound -> Text(
                text = "Lyrics aren't available for this song.",
                style = YtmTheme.type.bodyLarge,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            LyricsResult.Offline -> Text(
                text = "You're offline. Connect to load lyrics.",
                style = YtmTheme.type.bodyLarge,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            LyricsResult.Failed, is LyricsResult.RateLimited -> Text(
                text = "Couldn't load lyrics right now. Try again later.",
                style = YtmTheme.type.bodyLarge,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            is LyricsResult.Found -> {
                when (val lyrics = r.lyrics) {
                    Lyrics.Instrumental -> Text(
                        text = "Instrumental",
                        style = YtmTheme.type.headlineMedium,
                        color = colors.onSurfaceVariant,
                    )
                    is Lyrics.Plain -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(lyrics.lines) { line ->
                                Text(
                                    text = line,
                                    style = YtmTheme.type.titleLarge.copy(fontSize = 19.sp, lineHeight = 26.sp),
                                    color = colors.onSurface.copy(alpha = 0.9f),
                                )
                            }
                            item {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = "Lyrics provided by ${r.provider.name}",
                                    style = YtmTheme.type.labelSmall,
                                    color = colors.onSurfaceMuted,
                                )
                            }
                        }
                    }
                    is Lyrics.Synced -> {
                        val listState = rememberLazyListState()
                        val isPlaying = snapshot.intent == PlayIntent.PLAY
                        var activeIndex by remember(lyrics) { mutableIntStateOf(-1) }

                        LaunchedEffect(lyrics, isPlaying) {
                            while (isActive) {
                                val pos = controller.positionMs()
                                val idx = lyrics.lines.indexOfLast { it.startMs <= pos }
                                if (idx != activeIndex) {
                                    activeIndex = idx
                                }
                                delay(150L)
                            }
                        }

                        LaunchedEffect(activeIndex) {
                            if (activeIndex >= 0) {
                                listState.animateScrollToItem(
                                    index = (activeIndex - 2).coerceAtLeast(0),
                                )
                            }
                        }

                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            itemsIndexed(lyrics.lines) { idx, line ->
                                val isActiveLine = idx == activeIndex
                                if (line.text.isNotBlank()) {
                                    Text(
                                        text = line.text,
                                        style = YtmTheme.type.headlineMedium.copy(
                                            fontSize = if (isActiveLine) 22.sp else 19.sp,
                                            lineHeight = 28.sp,
                                            fontWeight = if (isActiveLine) FontWeight.Bold else FontWeight.SemiBold,
                                        ),
                                        color = if (isActiveLine) colors.onSurface else colors.onSurface.copy(alpha = 0.42f),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = snapshot.controls.seek) {
                                                controller.seekTo(line.startMs)
                                            },
                                    )
                                }
                            }
                            item {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = "Lyrics provided by ${r.provider.name}",
                                    style = YtmTheme.type.labelSmall,
                                    color = colors.onSurfaceMuted,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
