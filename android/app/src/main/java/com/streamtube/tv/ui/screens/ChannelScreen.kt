package com.streamtube.tv.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.streamtube.tv.core.ChannelPageDto
import com.streamtube.tv.core.NativeBridge
import com.streamtube.tv.core.ProgressEntryDto
import com.streamtube.tv.core.RawItemDto
import com.streamtube.tv.core.VideoItemDto
import com.streamtube.tv.ui.ErrorPane
import com.streamtube.tv.ui.LoadingPane
import com.streamtube.tv.ui.StColors
import com.streamtube.tv.ui.TvButton
import com.streamtube.tv.ui.VideoCard
import kotlinx.coroutines.launch

@Composable
fun ChannelScreen(
    channelId: String,
    onBack: () -> Unit,
    onPlayVideo: (VideoItemDto) -> Unit,
    onOpenPlaylist: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf<ChannelPageDto?>(null) }
    var items by remember { mutableStateOf<List<RawItemDto>>(emptyList()) }
    var progress by remember { mutableStateOf<Map<String, ProgressEntryDto>>(emptyMap()) }
    var continuation by remember { mutableStateOf<String?>(null) }
    var isSubscribed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun loadTab(params: String?) {
        scope.launch {
            loading = true
            error = null
            NativeBridge.getChannel(channelId, params).fold(
                onSuccess = { res ->
                    page = res.channel
                    items = res.channel.page.items
                    continuation = res.channel.page.continuation
                    progress = res.progress
                    isSubscribed = res.isSubscribed
                    loading = false
                },
                onFailure = { e ->
                    error = e.message ?: "Failed to load channel"
                    loading = false
                }
            )
        }
    }

    LaunchedEffect(channelId) {
        loadTab(null)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            TvButton(label = "Back", onClick = onBack)
            page?.info?.let { info ->
                AsyncImage(
                    model = info.avatar,
                    contentDescription = info.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(StColors.SurfaceElevated)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(info.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = StColors.TextPrimary)
                    val meta = listOfNotNull(info.handle, info.subscribersText, info.videoCountText).joinToString(" • ")
                    if (meta.isNotBlank()) {
                        Text(meta, fontSize = 12.sp, color = StColors.TextSecondary)
                    }
                }
                TvButton(
                    label = if (isSubscribed) "Subscribed" else "Subscribe",
                    selected = isSubscribed,
                    onClick = {
                        scope.launch {
                            if (isSubscribed) {
                                NativeBridge.unsubscribe(info.id)
                                isSubscribed = false
                            } else {
                                NativeBridge.subscribe(info.id, info.name, info.avatar)
                                isSubscribed = true
                            }
                        }
                    }
                )
            }
        }

        page?.tabs?.takeIf { it.isNotEmpty() }?.let { tabs ->
            LazyRow(
                contentPadding = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(tabs) { tab ->
                    TvButton(
                        label = tab.title,
                        selected = tab.selected,
                        onClick = { loadTab(tab.params) }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        when {
            loading -> LoadingPane("Loading channel…")
            error != null -> ErrorPane(error!!, onRetry = { loadTab(null) })
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 250.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items) { item ->
                        when {
                            item.Video != null -> VideoCard(
                                video = item.Video,
                                progress = progress[item.Video.id],
                                onClick = { onPlayVideo(item.Video) }
                            )
                            item.Playlist != null -> VideoCard(
                                video = VideoItemDto(
                                    id = item.Playlist.firstVideoId ?: item.Playlist.id,
                                    title = "[Playlist] ${item.Playlist.title}",
                                    channelName = item.Playlist.channelName.orEmpty(),
                                    thumbnail = item.Playlist.thumbnail.orEmpty(),
                                    durationText = item.Playlist.videoCountText
                                ),
                                onClick = { onOpenPlaylist(item.Playlist.id) }
                            )
                        }
                    }
                    if (continuation != null) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                TvButton(
                                    label = if (loadingMore) "Loading…" else "Load More",
                                    selected = true,
                                    onClick = {
                                        val tok = continuation ?: return@TvButton
                                        if (!loadingMore) {
                                            scope.launch {
                                                loadingMore = true
                                                NativeBridge.getChannelContinue(tok).onSuccess { res ->
                                                    items = items + res.items
                                                    progress = progress + res.progress
                                                    continuation = res.continuation
                                                }
                                                loadingMore = false
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
