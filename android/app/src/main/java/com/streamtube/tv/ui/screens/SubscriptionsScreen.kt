package com.streamtube.tv.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.streamtube.tv.core.NativeBridge
import com.streamtube.tv.core.ProgressEntryDto
import com.streamtube.tv.core.RawItemDto
import com.streamtube.tv.core.SubscriptionDto
import com.streamtube.tv.core.VideoItemDto
import com.streamtube.tv.ui.ErrorPane
import com.streamtube.tv.ui.LoadingPane
import com.streamtube.tv.ui.StColors
import com.streamtube.tv.ui.TvButton
import com.streamtube.tv.ui.VideoCard
import kotlinx.coroutines.launch

@Composable
fun SubscriptionsScreen(
    onPlayVideo: (VideoItemDto) -> Unit,
    onOpenChannel: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var subs by remember { mutableStateOf<List<SubscriptionDto>>(emptyList()) }
    var items by remember { mutableStateOf<List<RawItemDto>>(emptyList()) }
    var progress by remember { mutableStateOf<Map<String, ProgressEntryDto>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }

    fun refreshFeed() {
        scope.launch {
            refreshing = true
            NativeBridge.refreshSubscriptionsFeed().onSuccess { res ->
                items = res.items
                progress = res.progress
            }
            NativeBridge.getSubscriptions().onSuccess { subs = it }
            refreshing = false
        }
    }

    LaunchedEffect(Unit) {
        loading = true
        subs = NativeBridge.getSubscriptions().getOrDefault(emptyList())
        val cached = NativeBridge.getCachedSubscriptionsFeed().getOrNull()
        if (cached != null && cached.items.isNotEmpty()) {
            items = cached.items
            progress = cached.progress
            loading = false
        } else if (subs.isNotEmpty()) {
            NativeBridge.refreshSubscriptionsFeed().onSuccess { res ->
                items = res.items
                progress = res.progress
            }
            loading = false
        } else {
            // Fall back to signed-in subscriptions if TV account is connected
            NativeBridge.getHomeFeed("subscriptions").onSuccess { res ->
                items = res.items
                progress = res.progress
            }
            loading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Subscriptions (${subs.size})", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = StColors.TextPrimary)
            TvButton(
                label = if (refreshing) "Refreshing…" else "Refresh All",
                onClick = { if (!refreshing) refreshFeed() },
                selected = refreshing
            )
        }

        if (subs.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(subs, key = { it.channelId }) { sub ->
                    Surface(
                        onClick = { onOpenChannel(sub.channelId) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = StColors.Surface,
                            focusedContainerColor = StColors.SurfaceFocus
                        ),
                        border = ClickableSurfaceDefaults.border(
                            border = Border(BorderStroke(1.dp, StColors.Border)),
                            focusedBorder = Border(BorderStroke(2.dp, Color.White))
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .width(92.dp)
                                .padding(10.dp)
                        ) {
                            AsyncImage(
                                model = sub.avatar,
                                contentDescription = sub.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(StColors.SurfaceElevated)
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = sub.name,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = StColors.TextPrimary
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        when {
            loading -> LoadingPane("Loading subscriptions…")
            items.isEmpty() -> ErrorPane(
                "No subscription videos yet. Subscribe to channels, sign in via Settings, or import NewPipe/OPML via the Companion QR page.",
                onRetry = { refreshFeed() }
            )
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 250.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items) { item ->
                        item.Video?.let { v ->
                            VideoCard(
                                video = v,
                                progress = progress[v.id],
                                onClick = { onPlayVideo(v) }
                            )
                        }
                    }
                }
            }
        }
    }
}
