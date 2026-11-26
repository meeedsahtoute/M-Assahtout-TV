package com.streamtube.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.streamtube.tv.R
import com.streamtube.tv.StreamTubeApp
import com.streamtube.tv.core.FeedCache
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
    HOME("Home", TvIcons.Home),
    SUBSCRIPTIONS("Subscriptions", TvIcons.Subscriptions),
    SEARCH("Search", TvIcons.Search),
    SHORTS("Shorts", TvIcons.Shorts),
    LIBRARY("Library", TvIcons.Library),
    SETTINGS("Settings", TvIcons.Settings)
}

sealed class Route {
    data class Tab(val dest: RailDestination) : Route()
    data class Player(val video: VideoItemDto, val playlistId: String? = null) : Route()
    data class Channel(val channelId: String) : Route()
    data class Playlist(val playlistId: String) : Route()
}

private val RailCollapsedWidth = 72.dp
private val RailExpandedWidth = 216.dp
private val RailItemShape = RoundedCornerShape(10.dp)
private val RailFocusBorder = Border(BorderStroke(2.dp, Color.White))
private val RailIdleBorder = Border(BorderStroke(2.dp, Color.Transparent))

@Composable
fun AppRoot() {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(SettingsDto()) }
    val backStack = remember { mutableStateListOf<Route>(Route.Tab(RailDestination.HOME)) }
    val contentFocusRequester = remember { FocusRequester() }
    val railFocusRequesters = remember {
        RailDestination.entries.associateWith { FocusRequester() }
    }
    var railExpanded by remember { mutableStateOf(false) }
    var pendingContentFocus by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        FeedCache.readSettings(context)?.let { settings = it }
        NativeBridge.getSettings().onSuccess { s ->
            settings = s
            FeedCache.writeSettings(context, s)
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

    LaunchedEffect(currentRoute, pendingContentFocus) {
        if (pendingContentFocus && currentRoute !is Route.Player) {
            kotlinx.coroutines.yield()
            runCatching { contentFocusRequester.requestFocus() }
            pendingContentFocus = false
        }
    }

    BackHandler(enabled = backStack.size > 1 && currentRoute !is Route.Player) {
        backStack.removeAt(backStack.lastIndex)
    }

    BackHandler(enabled = backStack.size == 1 && currentRoute is Route.Tab && currentRoute.dest != RailDestination.HOME) {
        navigate(Route.Tab(RailDestination.HOME))
    }

    StreamTubeTheme(accentHex = settings.accent) {
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
            val activeTab = (currentRoute as? Route.Tab)?.dest
            val activeRailFocus = railFocusRequesters[activeTab ?: RailDestination.HOME]
                ?: railFocusRequesters.getValue(RailDestination.HOME)

            // Zero-reflow spatial layout: content sits strictly at start=72.dp; expanding the
            // rail overlays its label drawer cleanly on top without relayouting the video grid.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(StColors.Background)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = RailCollapsedWidth)
                ) {
                    when (currentRoute) {
                        is Route.Tab -> when (currentRoute.dest) {
                            RailDestination.HOME -> HomeScreen(
                                contentFocusRequester = contentFocusRequester,
                                railFocusRequester = activeRailFocus,
                                onPlayVideo = { v -> navigate(Route.Player(v)) },
                                onOpenChannel = { cid -> navigate(Route.Channel(cid)) },
                                onOpenPlaylist = { pid -> navigate(Route.Playlist(pid)) },
                                onOpenSearch = { navigate(Route.Tab(RailDestination.SEARCH)) }
                            )
                            RailDestination.SUBSCRIPTIONS -> SubscriptionsScreen(
                                contentFocusRequester = contentFocusRequester,
                                railFocusRequester = activeRailFocus,
                                onPlayVideo = { v -> navigate(Route.Player(v)) },
                                onOpenChannel = { cid -> navigate(Route.Channel(cid)) }
                            )
                            RailDestination.SEARCH -> SearchScreen(
                                contentFocusRequester = contentFocusRequester,
                                railFocusRequester = activeRailFocus,
                                onPlayVideo = { v -> navigate(Route.Player(v)) },
                                onOpenChannel = { cid -> navigate(Route.Channel(cid)) },
                                onOpenPlaylist = { pid -> navigate(Route.Playlist(pid)) }
                            )
                            RailDestination.SHORTS -> ShortsScreen(
                                onOpenInFullPlayer = { v -> navigate(Route.Player(v)) },
                                onOpenChannel = { cid -> navigate(Route.Channel(cid)) }
                            )
                            RailDestination.LIBRARY -> LibraryScreen(
                                contentFocusRequester = contentFocusRequester,
                                railFocusRequester = activeRailFocus,
                                onPlayVideo = { v -> navigate(Route.Player(v)) }
                            )
                            RailDestination.SETTINGS -> SettingsScreen(
                                onSettingsChanged = { s ->
                                    settings = s
                                }
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

                CollapsibleNavigationRail(
                    selected = activeTab,
                    expanded = railExpanded,
                    railFocusRequesters = railFocusRequesters,
                    contentFocusRequester = contentFocusRequester,
                    onExpandedChange = { railExpanded = it },
                    onSelect = { dest ->
                        pendingContentFocus = true
                        navigate(Route.Tab(dest))
                    },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .zIndex(2f)
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CollapsibleNavigationRail(
    selected: RailDestination?,
    expanded: Boolean,
    railFocusRequesters: Map<RailDestination, FocusRequester>,
    contentFocusRequester: FocusRequester,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (RailDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val headerLogo = remember { ImageBitmap.imageResource(context.resources, R.drawable.ic_header_logo) }
    val accent = MaterialTheme.colorScheme.primary
    val labelAlpha by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "rail_label_alpha"
    )

    Row(
        modifier = modifier
            .fillMaxHeight()
            .width(if (expanded) RailExpandedWidth else RailCollapsedWidth)
            .background(if (expanded) Color(0xF50F0F0F) else StColors.Background)
            .onFocusChanged { state ->
                onExpandedChange(state.hasFocus)
            }
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 10.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Image(
                    bitmap = headerLogo,
                    contentDescription = "M. ASSAHTOUT TV",
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.Low,
                    modifier = Modifier.size(34.dp)
                )
                if (expanded) {
                    Text(
                        text = "M. ASSAHTOUT TV",
                        fontSize = 13.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = StColors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.graphicsLayer { alpha = labelAlpha }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            val entries = RailDestination.entries
            entries.forEachIndexed { index, dest ->
                val isSelected = selected == dest
                val req = railFocusRequesters.getValue(dest)
                Surface(
                    onClick = { onSelect(dest) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(req)
                        .focusProperties {
                            right = contentFocusRequester
                            if (index == 0) up = req
                            if (index == entries.lastIndex) down = req
                        },
                    shape = ClickableSurfaceDefaults.shape(RailItemShape),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (isSelected) StColors.Chip else Color.Transparent,
                        focusedContainerColor = StColors.Hover,
                        contentColor = if (isSelected) StColors.TextPrimary else StColors.TextSecondary,
                        focusedContentColor = Color.White
                    ),
                    border = ClickableSurfaceDefaults.border(
                        border = RailIdleBorder,
                        focusedBorder = RailFocusBorder
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(
                            imageVector = dest.icon,
                            contentDescription = dest.label,
                            tint = if (isSelected) accent else StColors.TextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                        if (expanded) {
                            Text(
                                text = dest.label,
                                fontSize = 14.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.graphicsLayer { alpha = labelAlpha }
                            )
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(if (expanded) StColors.Border else Color(0xFF1A1A1A))
        )
    }
}
