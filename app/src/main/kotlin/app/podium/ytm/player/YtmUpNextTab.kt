package app.podium.ytm.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.AppGraph
import app.podium.ytm.YtmArtworkImage
import app.podium.ytm.YtmTheme
import app.podium.ytm.formatDurationMs

@Composable
fun YtmUpNextTab(
    graph: AppGraph,
    modifier: Modifier = Modifier,
) {
    val colors = YtmTheme.colors
    val controller = graph.playbackController
    val queueView by controller.queue.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val label = queueView.contextLabel?.let { "Playing from $it" } ?: "Up next"
            Text(
                text = label,
                style = YtmTheme.type.labelMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!queueView.readOnly && queueView.upNext.isNotEmpty()) {
                TextButton(onClick = { controller.clearUpcoming() }) {
                    Text("Clear", color = colors.onSurface)
                }
            }
        }

        if (queueView.hidden) {
            Text(
                text = "Queue is managed by the remote player.",
                style = YtmTheme.type.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        } else if (queueView.entries.isEmpty()) {
            Text(
                text = "Queue is empty",
                style = YtmTheme.type.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(queueView.entries, key = { it.uid.value }) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (entry.isCurrent) Color.White.copy(alpha = 0.08f) else Color.Transparent)
                            .clickable(enabled = !queueView.readOnly) { controller.skipTo(entry.uid) }
                            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        YtmArtworkImage(
                            uri = entry.artworkUri,
                            size = 46.dp,
                            fallbackText = entry.title,
                            cornerRadius = 4.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.title,
                                style = YtmTheme.type.titleMedium,
                                color = if (entry.isCurrent) colors.primary else colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val sub = buildString {
                                append(entry.artistDisplay)
                                entry.durationMs?.let {
                                    append(" • ")
                                    append(formatDurationMs(it))
                                }
                            }
                            Text(
                                text = sub,
                                style = YtmTheme.type.bodyMedium,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (!queueView.readOnly && !entry.isCurrent) {
                            IconButton(onClick = { controller.remove(setOf(entry.uid)) }) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Remove from queue",
                                    tint = colors.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
