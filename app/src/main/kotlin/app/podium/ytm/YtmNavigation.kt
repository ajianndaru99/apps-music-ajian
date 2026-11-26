package app.podium.ytm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import app.podium.core.model.ArtistId
import app.podium.core.model.PlaylistId

enum class YtmTab(val label: String) {
    HOME("Home"),
    EXPLORE("Explore"),
    LIBRARY("Library"),
}

sealed interface YtmRoute {
    data object Home : YtmRoute
    data object Explore : YtmRoute
    data object Library : YtmRoute
    data object Search : YtmRoute
    data class Collection(val id: PlaylistId, val title: String) : YtmRoute
    data class Artist(val id: ArtistId, val name: String) : YtmRoute
    data class Genre(val name: String) : YtmRoute
    data object Settings : YtmRoute
}

@Stable
class YtmTabState {
    var currentTab by mutableStateOf(YtmTab.HOME)
        private set

    private val homeStack: SnapshotStateList<YtmRoute> = mutableStateListOf(YtmRoute.Home)
    private val exploreStack: SnapshotStateList<YtmRoute> = mutableStateListOf(YtmRoute.Explore)
    private val libraryStack: SnapshotStateList<YtmRoute> = mutableStateListOf(YtmRoute.Library)

    private fun stackFor(tab: YtmTab): SnapshotStateList<YtmRoute> = when (tab) {
        YtmTab.HOME -> homeStack
        YtmTab.EXPLORE -> exploreStack
        YtmTab.LIBRARY -> libraryStack
    }

    val currentStack: SnapshotStateList<YtmRoute>
        get() = stackFor(currentTab)

    val currentRoute: YtmRoute
        get() = currentStack.last()

    val isAtTabRoot: Boolean
        get() = currentStack.size <= 1

    val showTopBar: Boolean
        get() = when (currentRoute) {
            YtmRoute.Home, YtmRoute.Explore, YtmRoute.Library -> true
            else -> false
        }

    val showBottomBar: Boolean
        get() = currentRoute !is YtmRoute.Settings

    fun selectTab(tab: YtmTab) {
        if (currentTab == tab) {
            popToRoot()
        } else {
            currentTab = tab
        }
    }

    fun navigate(route: YtmRoute) {
        if (currentStack.lastOrNull() == route) return
        currentStack.add(route)
    }

    fun popToRoot() {
        val stack = currentStack
        while (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
        }
    }

    /**
     * Handles Android Back press:
     * 1. Pop within the active tab if deeper than root.
     * 2. Otherwise switch back to HOME tab if not on HOME.
     * 3. Otherwise return false so the system closes the activity.
     */
    fun pop(): Boolean {
        val stack = currentStack
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            return true
        }
        if (currentTab != YtmTab.HOME) {
            currentTab = YtmTab.HOME
            return true
        }
        return false
    }
}

@Composable
fun rememberYtmTabState(): YtmTabState = remember { YtmTabState() }
