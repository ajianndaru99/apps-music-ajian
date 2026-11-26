package app.podium.ytm.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.AppGraph
import app.podium.feature.settings.ServiceAccount
import app.podium.ytm.YtmTheme

@Composable
fun YtmSettingsScreen(
    graph: AppGraph,
    onBack: () -> Unit,
) {
    val colors = YtmTheme.colors
    val serviceState by graph.onlineService.state.collectAsStateWithLifecycle()
    val autoplay by graph.deviceSettings.autoplay.collectAsStateWithLifecycle()
    val onlineLyrics by graph.deviceSettings.onlineLyrics.collectAsStateWithLifecycle()
    val onlineRecs by graph.deviceSettings.onlineRecommendations.collectAsStateWithLifecycle()
    val avoidRepeats by graph.deviceSettings.avoidRepeats.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = colors.onSurface,
                )
            }
            Text(
                text = "Settings",
                style = YtmTheme.type.titleLarge,
                color = colors.onSurface,
            )
        }

        HorizontalDivider(color = colors.divider)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item(key = "account") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Text(
                        text = "Account",
                        style = YtmTheme.type.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    val account = serviceState?.account
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(colors.surfaceElevated),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Person,
                                    contentDescription = null,
                                    tint = colors.onSurface,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = when (account) {
                                        is ServiceAccount.SignedIn -> account.name
                                        ServiceAccount.SigningIn -> "Signing in..."
                                        ServiceAccount.Expired -> "Session expired"
                                        else -> "Not signed in"
                                    },
                                    style = YtmTheme.type.titleMedium,
                                    color = colors.onSurface,
                                )
                                Text(
                                    text = serviceState?.name ?: "YouTube Music",
                                    style = YtmTheme.type.bodyMedium,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                        }

                        if (account is ServiceAccount.SignedIn) {
                            OutlinedButton(
                                onClick = { graph.onlineService.signOut() },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface),
                                shape = RoundedCornerShape(50),
                            ) {
                                Text("Sign out")
                            }
                        } else {
                            Button(
                                onClick = { graph.onlineService.signIn() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.accent,
                                    contentColor = colors.background,
                                ),
                                shape = RoundedCornerShape(50),
                            ) {
                                Text("Sign in", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            val channels = serviceState?.channels.orEmpty()
            if (channels.isNotEmpty()) {
                item(key = "channels") {
                    HorizontalDivider(color = colors.divider)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        Text(
                            text = "Channel",
                            style = YtmTheme.type.labelMedium,
                            color = colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        channels.forEach { channel ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { graph.onlineService.selectChannel(channel.id) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = channel.name,
                                        style = YtmTheme.type.titleMedium,
                                        color = colors.onSurface,
                                    )
                                    channel.detail?.let {
                                        Text(
                                            text = it,
                                            style = YtmTheme.type.bodyMedium,
                                            color = colors.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (channel.selected) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = "Selected",
                                        tint = colors.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item(key = "playback_prefs") {
                HorizontalDivider(color = colors.divider)
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(
                        text = "Playback & Lyrics",
                        style = YtmTheme.type.labelMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    SettingsToggleRow(
                        title = "Autoplay",
                        subtitle = "Keep similar music playing after your queue ends",
                        checked = autoplay,
                        onCheckedChange = { graph.deviceSettings.setAutoplay(it) },
                    )
                    SettingsToggleRow(
                        title = "Online recommendations",
                        subtitle = "Use YouTube Music recommendations for radio and autoplay",
                        checked = onlineRecs,
                        onCheckedChange = { graph.deviceSettings.setOnlineRecommendations(it) },
                    )
                    SettingsToggleRow(
                        title = "Avoid repeats",
                        subtitle = "Skip songs already heard recently",
                        checked = avoidRepeats,
                        onCheckedChange = { graph.deviceSettings.setAvoidRepeats(it) },
                    )
                    SettingsToggleRow(
                        title = "Online lyrics",
                        subtitle = "Fetch synced lyrics from LRCLIB",
                        checked = onlineLyrics,
                        onCheckedChange = { graph.deviceSettings.setOnlineLyrics(it) },
                    )
                }
            }

            item(key = "interface") {
                HorizontalDivider(color = colors.divider)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Text(
                        text = "Interface",
                        style = YtmTheme.type.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surfaceVariant)
                            .clickable { graph.deviceSettings.setUseYtmUi(false) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Switch to Podium Classic UI",
                                style = YtmTheme.type.titleMedium,
                                color = colors.onSurface,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Use the original wheel-driven Podium interface",
                                style = YtmTheme.type.bodyMedium,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = YtmTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                text = title,
                style = YtmTheme.type.titleMedium,
                color = colors.onSurface,
            )
            Text(
                text = subtitle,
                style = YtmTheme.type.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.background,
                checkedTrackColor = colors.onSurface,
                uncheckedThumbColor = colors.onSurfaceVariant,
                uncheckedTrackColor = colors.surface,
            ),
        )
    }
}
