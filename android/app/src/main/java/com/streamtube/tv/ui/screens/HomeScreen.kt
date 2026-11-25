package com.streamtube.tv.ui.screens

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.streamtube.tv.core.ExploreCategoryDto
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
fun HomeScreen(
    onPlayVideo: (VideoItemDto) -> Unit,
    onOpenChannel: (String) -> Unit,
    onOpenPlaylist: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var categories by remember { mutableStateOf<List<ExploreCategoryDto>>(emptyList()) }
    var selectedChip by remember { mutableStateOf("for_you") }
    var items by remember { mutableStateOf<List<RawItemDto>>(emptyList()) }
    var progress by remember { mutableStateOf<Map<String, ProgressEntryDto>>(emptyMap()) }
    var continuation by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load(chip: String, cont: String? = null) {
        scope.launch {
            if (cont == null) {
                loading = true
                error = null
            } else {
                loadingMore = true
            }
            NativeBridge.getHomeFeed(chip, cont).fold(
                onSuccess = { res ->
                    if (cont == null) {
                        items = res.items
                        progress = res.progress
                    } else {
                        items = items + res.items
                        progress = progress + res.progress
                    }
                    continuation = res.continuation
                    loading = false
                    loadingMore = false
                },
                onFailure = { e ->
                    if (cont == null) {
                        error = e.message ?: "Failed to load feed"
                        loading = false
                    } else {
                        loadingMore = false
                    }
                }
            )
        }
    }

    LaunchedEffect(Unit) {
        NativeBridge.getExploreCategories().onSuccess { categories = it }
        load(selectedChip)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Home", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = StColors.TextPrimary)
            TvButton(label = "Refresh", onClick = { load(selectedChip) })
        }

        val chips = remember(categories) {
            buildList {
                add("for_you" to "For You")
                add("recent" to "Recently Uploaded")
                add("subscriptions" to "Subscriptions")
                for (c in categories) {
                    add("explore:${c.channelId}" to c.name)
                }
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(chips, key = { it.first }) { (key, label) ->
                TvButton(
                    label = label,
                    selected = selectedChip == key,
                    onClick = {
                        if (selectedChip != key) {
                            selectedChip = key
                            load(key)
                        }
                    }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        when {
            loading -> LoadingPane("Loading home feed…")
            error != null -> ErrorPane(error!!, onRetry = { load(selectedChip) })
            items.isEmpty() -> ErrorPane("No videos found for this feed.", onRetry = { load(selectedChip) })
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
                            item.Video != null -> {
                                VideoCard(
                                    video = item.Video,
                                    progress = progress[item.Video.id],
                                    onClick = { onPlayVideo(item.Video) }
                                )
                            }
                            item.Playlist != null -> {
                                VideoCard(
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
                            item.Channel != null -> {
                                VideoCard(
                                    video = VideoItemDto(
                                        id = item.Channel.id,
                                        title = item.Channel.name,
                                        channelName = item.Channel.subscribersText ?: "Channel",
                                        thumbnail = item.Channel.avatar.orEmpty()
                                    ),
                                    onClick = { onOpenChannel(item.Channel.id) }
                                )
                            }
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
                                    label = if (loadingMore) "Loading more…" else "Load More",
                                    onClick = {
                                        if (!loadingMore) continuation?.let { load(selectedChip, it) }
                                    },
                                    selected = true
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
