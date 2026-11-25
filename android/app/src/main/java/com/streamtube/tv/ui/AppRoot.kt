package com.streamtube.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.streamtube.tv.R
import com.streamtube.tv.StreamTubeApp
import com.streamtube.tv.core.NativeBridge
import com.streamtube.tv.core.ResolvedUrlDto
import com.streamtube.tv.core.SettingsDto
import com.streamtube.tv.core.VideoItemDto
import com.streamtube.tv.ui.screens.ChannelScreen
import com.streamtube.tv.ui.screens.HomeScreen
import com.streamtube.tv.ui.screens.LibraryScreen
import com.streamtube.tv.ui.screens.PlayerScreen
import com.streamtube.tv.ui.screens.PlaylistScreen
import com.streamtube.tv.ui.screens.SearchScreen
import com.streamtube.tv.ui.screens.SettingsScreen
import com.streamtube.tv.ui.screens.ShortsScreen
import com.streamtube.tv.ui.screens.SubscriptionsScreen

enum class RailDestination(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    SUBSCRIPTIONS("Subscriptions", Icons.Default.Subscriptions),
    SEARCH("Search", Icons.Default.Search),
    SHORTS("Shorts", Icons.Default.PlayArrow),
    LIBRARY("Library", Icons.Default.VideoLibrary),
    SETTINGS("Settings", Icons.Default.Settings)
}

sealed class Route {
    data class Tab(val dest: RailDestination) : Route()
    data class Player(val video: VideoItemDto, val playlistId: String? = null) : Route()
    data class Channel(val channelId: String) : Route()
    data class Playlist(val playlistId: String) : Route()
}

@Composable
fun AppRoot() {
    var settings by remember { mutableStateOf(SettingsDto()) }
    var showSplash by rememberSaveable { mutableStateOf(true) }
    var homeMounted by rememberSaveable { mutableStateOf(false) }
    var homeZoomProgress by remember { mutableFloatStateOf(0f) }
    val backStack = remember { mutableStateListOf<Route>(Route.Tab(RailDestination.HOME)) }
    val homeRailFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        NativeBridge.getSettings().onSuccess { settings = it }
    }

    LaunchedEffect(showSplash) {
        if (!showSplash) {
            homeMounted = true
            homeZoomProgress = 1f
            runCatching { homeRailFocus.requestFocus() }
        }
    }

    fun navigate(route: Route) {
        if (route is Route.Tab) {
            backStack.clear()
            backStack.add(route)
        } else {
            backStack.add(route)
        }
    }

    fun handleResolvedUrl(res: ResolvedUrlDto) {
        when (res.kind) {
            "video" -> navigate(
                Route.Player(
                    VideoItemDto(
                        id = res.id,
                        thumbnail = "https://i.ytimg.com/vi/${res.id}/hqdefault.jpg"
                    )
                )
            )
            "channel" -> navigate(Route.Channel(res.id))
            "playlist" -> navigate(Route.Playlist(res.id))
        }
    }

    LaunchedEffect(Unit) {
        StreamTubeApp.instance.incomingRoutes.collect { resolved ->
            handleResolvedUrl(resolved)
        }
    }

    val currentRoute = backStack.lastOrNull() ?: Route.Tab(RailDestination.HOME)

    BackHandler(enabled = !showSplash && backStack.size > 1 && currentRoute !is Route.Player) {
        backStack.removeAt(backStack.lastIndex)
    }

    BackHandler(enabled = !showSplash && backStack.size == 1 && currentRoute is Route.Tab && currentRoute.dest != RailDestination.HOME) {
        navigate(Route.Tab(RailDestination.HOME))
    }

    StreamTubeTheme(accentHex = settings.accent) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            if (homeMounted || !showSplash) {
                val hProgress = if (showSplash) homeZoomProgress else 1f
                val hScale = 0.94f + 0.06f * hProgress
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = hScale
                            scaleY = hScale
                            alpha = 1f
                        }
                ) {
                    if (currentRoute is Route.Player) {
                        PlayerScreen(
                            initialVideo = currentRoute.video,
                            playlistId = currentRoute.playlistId,
                            onPlayVideo = { v, pid -> navigate(Route.Player(v, pid)) },
                            onOpenChannel = { cid -> navigate(Route.Channel(cid)) },
                            onExit = {
                                if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                                else navigate(Route.Tab(RailDestination.HOME))
                            }
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(StColors.Background)
                        ) {
                            val activeTab = (currentRoute as? Route.Tab)?.dest
                            NavigationRail(
                                selected = activeTab,
                                homeFocusRequester = homeRailFocus,
                                onSelect = { dest -> navigate(Route.Tab(dest)) }
                            )

                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                when (currentRoute) {
                                    is Route.Tab -> when (currentRoute.dest) {
                                        RailDestination.HOME -> HomeScreen(
                                            onPlayVideo = { v -> navigate(Route.Player(v)) },
                                            onOpenChannel = { cid -> navigate(Route.Channel(cid)) },
                                            onOpenPlaylist = { pid -> navigate(Route.Playlist(pid)) }
                                        )
                                        RailDestination.SUBSCRIPTIONS -> SubscriptionsScreen(
                                            onPlayVideo = { v -> navigate(Route.Player(v)) },
                                            onOpenChannel = { cid -> navigate(Route.Channel(cid)) }
                                        )
                                        RailDestination.SEARCH -> SearchScreen(
                                            onPlayVideo = { v -> navigate(Route.Player(v)) },
                                            onOpenChannel = { cid -> navigate(Route.Channel(cid)) },
                                            onOpenPlaylist = { pid -> navigate(Route.Playlist(pid)) }
                                        )
                                        RailDestination.SHORTS -> ShortsScreen(
                                            onOpenInFullPlayer = { v -> navigate(Route.Player(v)) },
                                            onOpenChannel = { cid -> navigate(Route.Channel(cid)) }
                                        )
                                        RailDestination.LIBRARY -> LibraryScreen(
                                            onPlayVideo = { v -> navigate(Route.Player(v)) }
                                        )
                                        RailDestination.SETTINGS -> SettingsScreen(
                                            onSettingsChanged = { settings = it }
                                        )
                                    }
                                    is Route.Channel -> ChannelScreen(
                                        channelId = currentRoute.channelId,
                                        onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
                                        onPlayVideo = { v -> navigate(Route.Player(v)) },
                                        onOpenPlaylist = { pid -> navigate(Route.Playlist(pid)) }
                                    )
                                    is Route.Playlist -> PlaylistScreen(
                                        playlistId = currentRoute.playlistId,
                                        onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
                                        onPlayVideo = { v, pid -> navigate(Route.Player(v, pid)) }
                                    )
                                    is Route.Player -> Unit
                                }
                            }
                        }
                    }
                }
            }

            if (showSplash) {
                SplashScreen(
                    onMountHome = { homeMounted = true },
                    onHomeTransitionProgress = { p -> homeZoomProgress = p },
                    onFinished = { showSplash = false }
                )
            }
        }
    }
}

@Composable
private fun NavigationRail(
    selected: RailDestination?,
    homeFocusRequester: FocusRequester,
    onSelect: (RailDestination) -> Unit
) {
    val context = LocalContext.current
    val headerLogo = remember { ImageBitmap.imageResource(context.resources, R.drawable.ic_header_logo) }
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .width(196.dp)
            .fillMaxHeight()
            .background(Color(0xFF0E0E13))
            .padding(horizontal = 12.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp)
        ) {
            Image(
                bitmap = headerLogo,
                contentDescription = "M. ASSAHTOUT TV",
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.High,
                modifier = Modifier.size(36.dp)
            )
            Text(
                text = "M. ASSAHTOUT TV",
                fontSize = 14.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2
            )
        }

        Spacer(Modifier.height(6.dp))

        for (dest in RailDestination.entries) {
            val isSelected = selected == dest
            Surface(
                onClick = { onSelect(dest) },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (dest == RailDestination.HOME) Modifier.focusRequester(homeFocusRequester) else Modifier),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (isSelected) accent.copy(alpha = 0.22f) else Color.Transparent,
                    focusedContainerColor = StColors.SurfaceFocus,
                    contentColor = if (isSelected) Color.White else StColors.TextSecondary,
                    focusedContentColor = Color.White
                ),
                border = ClickableSurfaceDefaults.border(
                    border = Border(BorderStroke(1.dp, if (isSelected) accent else Color.Transparent)),
                    focusedBorder = Border(BorderStroke(2.dp, Color.White))
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = dest.icon,
                        contentDescription = dest.label,
                        tint = if (isSelected) accent else StColors.TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = dest.label,
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                    )
                }
            }
        }
    }
}
