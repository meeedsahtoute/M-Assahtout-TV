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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SubscriptionsScreen(
    contentFocusRequester: FocusRequester,
    railFocusRequester: FocusRequester,
    onPlayVideo: (VideoItemDto) -> Unit,
    onOpenChannel: (String) -> Unit
) {
    val context = LocalContext.current
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
                selected = refreshing,
                modifier = Modifier
                    .then(if (subs.isEmpty()) Modifier.focusRequester(contentFocusRequester) else Modifier)
                    .focusProperties { left = railFocusRequester }
            )
        }

        if (subs.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                itemsIndexed(
                    items = subs,
                    key = { _, sub -> sub.channelId },
                    contentType = { _, _ -> "sub_channel" }
                ) { idx, sub ->
                    val avatarReq = remember(sub.avatar) {
                        ImageRequest.Builder(context)
                            .data(sub.avatar)
                            .size(96, 96)
                            .precision(Precision.INEXACT)
                            .allowHardware(true)
                            .allowRgb565(true)
                            .crossfade(false)
                            .build()
                    }
                    Surface(
                        onClick = { onOpenChannel(sub.channelId) },
                        modifier = if (idx == 0) {
                            Modifier
                                .focusRequester(contentFocusRequester)
                                .focusProperties { left = railFocusRequester }
                        } else {
                            Modifier
                        },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = StColors.Surface,
                            focusedContainerColor = StColors.Hover
                        ),
                        border = ClickableSurfaceDefaults.border(
                            border = Border(BorderStroke(2.dp, Color.Transparent)),
                            focusedBorder = Border(BorderStroke(2.dp, Color.White))
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .width(88.dp)
                                .padding(10.dp)
                        ) {
                            AsyncImage(
                                model = avatarReq,
                                contentDescription = sub.name,
                                contentScale = ContentScale.Crop,
                                filterQuality = FilterQuality.Low,
                                modifier = Modifier
                                    .size(46.dp)
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
            Spacer(Modifier.height(10.dp))
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
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(
                        items = items,
                        key = { idx, item -> "subv_${item.Video?.id ?: idx}_$idx" },
                        contentType = { _, _ -> "video_card" }
                    ) { _, item ->
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
