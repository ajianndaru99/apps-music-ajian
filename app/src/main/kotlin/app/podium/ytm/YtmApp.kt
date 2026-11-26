package app.podium.ytm

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.AppGraph
import app.podium.R
import app.podium.sources.api.CapabilityAction
import app.podium.ytm.player.YtmMiniPlayer
import app.podium.ytm.player.YtmNowPlayingSheet
import app.podium.ytm.screens.YtmArtistScreen
import app.podium.ytm.screens.YtmCollectionScreen
import app.podium.ytm.screens.YtmExploreScreen
import app.podium.ytm.screens.YtmGenreScreen
import app.podium.ytm.screens.YtmHomeScreen
import app.podium.ytm.screens.YtmLibraryScreen
import app.podium.ytm.screens.YtmSearchScreen
import app.podium.ytm.screens.YtmSettingsScreen

/**
 * Root composable for the YouTube Music-style UI.
 *
 * Uses Podium's [AppGraph] directly for Google sign-in, ad-free stream extraction,
 * Media3 playback, local library, synced lyrics, and artwork resolution.
 */
@Composable
fun YtmApp(
    graph: AppGraph,
    onSourceAction: (CapabilityAction) -> Unit,
) {
    YtmTheme(artworkLoader = graph.artworkLoader) {
        val colors = YtmTheme.colors
        val tabState = rememberYtmTabState()
        var nowPlayingOpen by remember { mutableStateOf(false) }

        val actions = rememberYtmOnlineActions(
            graph = graph,
            onOpenPlayer = { nowPlayingOpen = true },
        )

        val snapshot by graph.playbackController.snapshot.collectAsStateWithLifecycle()

        LaunchedEffect(Unit) {
            graph.updates.autoCheck()
        }

        BackHandler(enabled = nowPlayingOpen || !tabState.isAtTabRoot || tabState.currentTab != YtmTab.HOME) {
            if (nowPlayingOpen) {
                nowPlayingOpen = false
            } else {
                tabState.pop()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
            ) {
                AnimatedVisibility(visible = tabState.showTopBar) {
                    YtmTopBar(
                        onSearchClick = { tabState.navigate(YtmRoute.Search) },
                        onSettingsClick = { tabState.navigate(YtmRoute.Settings) },
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    when (val route = tabState.currentRoute) {
                        YtmRoute.Home -> YtmHomeScreen(
                            graph = graph,
                            actions = actions,
                            onOpenCollection = { id, title -> tabState.navigate(YtmRoute.Collection(id, title)) },
                            onOpenArtist = { id, name -> tabState.navigate(YtmRoute.Artist(id, name)) },
                            onSignIn = { graph.onlineService.signIn() },
                        )
                        YtmRoute.Explore -> YtmExploreScreen(
                            graph = graph,
                            actions = actions,
                            onOpenGenre = { genre -> tabState.navigate(YtmRoute.Genre(genre)) },
                        )
                        YtmRoute.Library -> YtmLibraryScreen(
                            graph = graph,
                            actions = actions,
                            onSourceAction = onSourceAction,
                            onOpenCollection = { id, title -> tabState.navigate(YtmRoute.Collection(id, title)) },
                            onOpenArtist = { id, name -> tabState.navigate(YtmRoute.Artist(id, name)) },
                        )
                        YtmRoute.Search -> YtmSearchScreen(
                            graph = graph,
                            actions = actions,
                            onBack = { tabState.pop() },
                            onOpenCollection = { id, title -> tabState.navigate(YtmRoute.Collection(id, title)) },
                            onOpenArtist = { id, name -> tabState.navigate(YtmRoute.Artist(id, name)) },
                        )
                        is YtmRoute.Collection -> YtmCollectionScreen(
                            id = route.id,
                            initialTitle = route.title,
                            graph = graph,
                            actions = actions,
                            onBack = { tabState.pop() },
                            onOpenArtist = { id, name -> tabState.navigate(YtmRoute.Artist(id, name)) },
                        )
                        is YtmRoute.Artist -> YtmArtistScreen(
                            id = route.id,
                            initialName = route.name,
                            graph = graph,
                            actions = actions,
                            onBack = { tabState.pop() },
                            onOpenCollection = { id, title -> tabState.navigate(YtmRoute.Collection(id, title)) },
                            onOpenArtist = { id, name -> tabState.navigate(YtmRoute.Artist(id, name)) },
                        )
                        is YtmRoute.Genre -> YtmGenreScreen(
                            genre = route.name,
                            graph = graph,
                            actions = actions,
                            onBack = { tabState.pop() },
                            onOpenCollection = { id, title -> tabState.navigate(YtmRoute.Collection(id, title)) },
                            onOpenArtist = { id, name -> tabState.navigate(YtmRoute.Artist(id, name)) },
                        )
                        YtmRoute.Settings -> YtmSettingsScreen(
                            graph = graph,
                            onBack = { tabState.pop() },
                        )
                    }
                }

                if (snapshot.isActive && tabState.showBottomBar) {
                    YtmMiniPlayer(
                        graph = graph,
                        onOpen = { nowPlayingOpen = true },
                    )
                }

                if (tabState.showBottomBar) {
                    YtmBottomNavBar(
                        currentTab = tabState.currentTab,
                        onSelectTab = { tabState.selectTab(it) },
                        modifier = Modifier.navigationBarsPadding(),
                    )
                }
            }

            AnimatedVisibility(
                visible = nowPlayingOpen,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            ) {
                YtmNowPlayingSheet(
                    graph = graph,
                    actions = actions,
                    onDismiss = { nowPlayingOpen = false },
                )
            }

            YtmUpdateDialog(graph.updates)
        }
    }
}

@Composable
private fun YtmTopBar(
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val colors = YtmTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(id = R.drawable.app_logo_mark),
                contentDescription = "App logo",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Music",
                style = YtmTheme.type.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = colors.onSurface,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onSearchClick) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = "Search",
                    tint = colors.onSurface,
                )
            }
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceElevated)
                    .clickable(onClick = onSettingsClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = "Account & Settings",
                    tint = colors.onSurface,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun YtmBottomNavBar(
    currentTab: YtmTab,
    onSelectTab: (YtmTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = YtmTheme.colors
    NavigationBar(
        modifier = modifier,
        containerColor = colors.surface,
        contentColor = colors.onSurface,
    ) {
        YtmTab.entries.forEach { tab ->
            val selected = tab == currentTab
            val icon = when (tab) {
                YtmTab.HOME -> if (selected) Icons.Filled.Home else Icons.Outlined.Home
                YtmTab.EXPLORE -> if (selected) Icons.Filled.Explore else Icons.Outlined.Explore
                YtmTab.LIBRARY -> if (selected) Icons.Filled.LibraryMusic else Icons.Outlined.LibraryMusic
            }
            NavigationBarItem(
                selected = selected,
                onClick = { onSelectTab(tab) },
                icon = {
                    Icon(
                        imageVector = icon,
                        contentDescription = tab.label,
                    )
                },
                label = {
                    Text(
                        text = tab.label,
                        style = YtmTheme.type.labelSmall,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = colors.onSurface,
                    selectedTextColor = colors.onSurface,
                    unselectedIconColor = colors.onSurfaceVariant,
                    unselectedTextColor = colors.onSurfaceVariant,
                    indicatorColor = Color.Transparent,
                ),
            )
        }
    }
}
