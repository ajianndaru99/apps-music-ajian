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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.AppGraph
import app.podium.BuildConfig
import app.podium.feature.settings.AiExploreProvider
import app.podium.feature.settings.ServiceAccount
import app.podium.ytm.YtmGoogleSignInButton
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
    val preCacheNext by graph.deviceSettings.preCacheNext.collectAsStateWithLifecycle()
    val aiProvider by graph.deviceSettings.aiExploreProvider.collectAsStateWithLifecycle()
    val savedAiKey by graph.deviceSettings.aiExploreApiKey.collectAsStateWithLifecycle()
    val autoUpdate by graph.updates.automatic.collectAsStateWithLifecycle()
    var cacheBytes by remember { mutableLongStateOf(graph.streamCache.cacheSizeBytes()) }
    var aiKeyDraft by remember(savedAiKey) { mutableStateOf(savedAiKey) }
    var revealAiKey by remember { mutableStateOf(false) }
    var aiQuota by remember { mutableStateOf(graph.aiExplore.quotaStatus()) }
    LaunchedEffect(savedAiKey, aiProvider) {
        aiQuota = graph.aiExplore.quotaStatus()
    }
    val scope = rememberCoroutineScope()

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
                            YtmGoogleSignInButton(onClick = { graph.onlineService.signIn() })
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

            item(key = "storage_cache") {
                HorizontalDivider(color = colors.divider)
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(
                        text = "Storage & Cache",
                        style = YtmTheme.type.labelMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    SettingsToggleRow(
                        title = "Pre-cache next song",
                        subtitle = "Download the full next song into phone cache while playing",
                        checked = preCacheNext,
                        onCheckedChange = { graph.deviceSettings.setPreCacheNext(it) },
                    )
                    val usedMb = cacheBytes / (1024.0 * 1024.0)
                    val maxMb = graph.streamCache.maxSizeBytes() / (1024L * 1024L)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "Audio stream cache",
                                style = YtmTheme.type.titleMedium,
                                color = colors.onSurface,
                            )
                            Text(
                                text = "%.1f MB / %d MB used on device".format(usedMb, maxMb),
                                style = YtmTheme.type.bodyMedium,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    cacheBytes = withContext(Dispatchers.IO) {
                                        graph.streamCache.clear()
                                        graph.streamCache.cacheSizeBytes()
                                    }
                                }
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface),
                            shape = RoundedCornerShape(50),
                        ) {
                            Text("Clear")
                        }
                    }
                }
            }

            item(key = "ai_explore") {
                HorizontalDivider(color = colors.divider)
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = "AI Explore (Free Tier)",
                        style = YtmTheme.type.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Personalizes Explore genres & trends per Google account. Cached 24h on device (max ${aiQuota.maxCallsPerDay} calls/day) so free-tier quota lasts.",
                        style = YtmTheme.type.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AiExploreProvider.entries.forEach { provider ->
                            val selected = aiProvider == provider
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (selected) colors.chipSelectedBg else colors.chipUnselectedBg)
                                    .clickable { graph.deviceSettings.setAiExploreProvider(provider) }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    text = if (provider == AiExploreProvider.GEMINI) "Gemini Flash-Lite" else "Groq Llama-3.3",
                                    style = YtmTheme.type.labelMedium.copy(
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    ),
                                    color = if (selected) colors.chipSelectedFg else colors.chipUnselectedFg,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = aiKeyDraft,
                        onValueChange = { aiKeyDraft = it },
                        singleLine = true,
                        label = {
                            Text(
                                if (aiProvider == AiExploreProvider.GEMINI) {
                                    "Gemini API Key (aistudio.google.com)"
                                } else {
                                    "Groq API Key (console.groq.com)"
                                },
                            )
                        },
                        visualTransformation = if (revealAiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { revealAiKey = !revealAiKey }) {
                                Icon(
                                    imageVector = if (revealAiKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (revealAiKey) "Hide key" else "Show key",
                                    tint = colors.onSurfaceVariant,
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = colors.onSurface,
                            unfocusedTextColor = colors.onSurface,
                            focusedBorderColor = colors.onSurface,
                            unfocusedBorderColor = colors.divider,
                            focusedLabelColor = colors.onSurface,
                            unfocusedLabelColor = colors.onSurfaceVariant,
                            cursorColor = colors.onSurface,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                graph.deviceSettings.setAiExploreApiKey(aiKeyDraft)
                                graph.aiExplore.clearCache()
                                aiQuota = graph.aiExplore.quotaStatus()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.accent,
                                contentColor = colors.background,
                            ),
                            shape = RoundedCornerShape(50),
                        ) {
                            Text("Save key", color = colors.background, fontWeight = FontWeight.SemiBold)
                        }
                        if (savedAiKey.isNotEmpty()) {
                            OutlinedButton(
                                onClick = {
                                    aiKeyDraft = ""
                                    graph.deviceSettings.setAiExploreApiKey("")
                                    graph.aiExplore.clearCache()
                                    aiQuota = graph.aiExplore.quotaStatus()
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface),
                                shape = RoundedCornerShape(50),
                            ) {
                                Text("Remove")
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                graph.aiExplore.clearCache()
                                aiQuota = graph.aiExplore.quotaStatus()
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface),
                            shape = RoundedCornerShape(50),
                        ) {
                            Text("Reset cache")
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "${aiQuota.callsUsedToday}/${aiQuota.maxCallsPerDay} AI calls used today • Stored locally on device only",
                        style = YtmTheme.type.labelSmall,
                        color = colors.onSurfaceMuted,
                    )
                }
            }

            item(key = "app_updates") {
                HorizontalDivider(color = colors.divider)
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(
                        text = "App Updates",
                        style = YtmTheme.type.labelMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    SettingsToggleRow(
                        title = "Auto-check for updates",
                        subtitle = "Automatically notify when a new version is available",
                        checked = autoUpdate,
                        onCheckedChange = { graph.updates.setAutomatic(it) },
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "Current version",
                                style = YtmTheme.type.titleMedium,
                                color = colors.onSurface,
                            )
                            Text(
                                text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                style = YtmTheme.type.bodyMedium,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = { graph.updates.checkNow() },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface),
                            shape = RoundedCornerShape(50),
                        ) {
                            Text("Check now")
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
