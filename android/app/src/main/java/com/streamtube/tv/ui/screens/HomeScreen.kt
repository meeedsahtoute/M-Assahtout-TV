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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.streamtube.tv.core.ExploreCategoryDto
import com.streamtube.tv.core.FeedCache
import com.streamtube.tv.core.NativeBridge
import com.streamtube.tv.core.ProgressEntryDto
import com.streamtube.tv.core.RawItemDto
import com.streamtube.tv.core.VideoItemDto
import com.streamtube.tv.ui.ErrorPane
import com.streamtube.tv.ui.LoadingPane
import com.streamtube.tv.ui.StColors
import com.streamtube.tv.ui.TvButton
import com.streamtube.tv.ui.TvChip
import com.streamtube.tv.ui.VideoCard
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(
    contentFocusRequester: FocusRequester,
    railFocusRequester: FocusRequester,
    onPlayVideo: (VideoItemDto) -> Unit,
    onOpenChannel: (String) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onOpenSearch: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var categories by remember { mutableStateOf<List<ExploreCategoryDto>>(emptyList()) }
    var selectedChip by remember { mutableStateOf("for_you") }
    var items by remember { mutableStateOf<List<RawItemDto>>(emptyList()) }
    var progress by remember { mutableStateOf<Map<String, ProgressEntryDto>>(emptyMap()) }
    var continuation by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val gridState = rememberLazyGridState()
    val firstChipFocus = remember { FocusRequester() }

    fun load(chip: String, cont: String? = null, forceSpinner: Boolean = true) {
        scope.launch {
            if (cont == null) {
                if (forceSpinner && items.isEmpty()) {
                    loading = true
                } else {
                    refreshing = true
                }
                error = null
            } else {
                if (loadingMore) return@launch
                loadingMore = true
            }

            NativeBridge.getHomeFeed(chip, cont).fold(
                onSuccess = { res ->
                    if (cont == null) {
                        items = res.items
                        progress = res.progress
                        FeedCache.writeHomeFeed(context, chip, res)
                    } else {
                        val seen = HashSet<String>(items.size)
                        for (existing in items) {
                            (existing.Video?.id ?: existing.Playlist?.id ?: existing.Channel?.id)?.let { seen.add(it) }
                        }
                        val append = res.items.filter { raw ->
                            val id = raw.Video?.id ?: raw.Playlist?.id ?: raw.Channel?.id
                            id == null || seen.add(id)
                        }
                        items = items + append
                        progress = progress + res.progress
                    }
                    continuation = res.continuation
                    loading = false
                    refreshing = false
                    loadingMore = false
                },
                onFailure = { e ->
                    if (cont == null && items.isEmpty()) {
                        error = e.message ?: "Failed to load feed"
                    }
                    loading = false
                    refreshing = false
                    loadingMore = false
                }
            )
        }
    }

    // Stale-while-revalidate startup: paint cached Home grid on Frame 1 while fresh feed syncs off main thread.
    LaunchedEffect(Unit) {
        val catsDeferred = async { FeedCache.readExploreCategories(context) }
        val homeDeferred = async { FeedCache.readHomeFeed(context, "for_you") }
        catsDeferred.await()?.let { categories = it }
        homeDeferred.await()?.let { cached ->
            if (cached.items.isNotEmpty()) {
                items = cached.items
                progress = cached.progress
                continuation = cached.continuation
                loading = false
            }
        }
        launch {
            NativeBridge.getExploreCategories().onSuccess { freshCats ->
                categories = freshCats
                FeedCache.writeExploreCategories(context, freshCats)
            }
        }
        load(chip = "for_you", cont = null, forceSpinner = items.isEmpty())
    }

    // Infinite scroll prefetch 1 row ahead cleanly on D-Pad Down
    LaunchedEffect(gridState, continuation, items.size) {
        snapshotFlow {
            val layout = gridState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible to layout.totalItemsCount
        }
            .map { (last, total) -> total > 0 && last >= total - 5 }
            .distinctUntilChanged()
            .collect { nearEnd ->
                val tok = continuation
                if (nearEnd && tok != null && !loadingMore && !loading) {
                    load(selectedChip, tok)
                }
            }
    }

    val chips = remember(categories) {
        buildList {
            add("for_you" to "All")
            add("recent" to "Recently uploaded")
            add("subscriptions" to "Subscriptions")
            for (c in categories) {
                add("explore:${c.channelId}" to c.name)
            }
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
            Text(
                text = "Home",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = StColors.TextPrimary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton(
                    label = "Search",
                    onClick = onOpenSearch,
                    modifier = Modifier.focusProperties {
                        left = railFocusRequester
                        down = firstChipFocus
                    }
                )
                TvButton(
                    label = if (refreshing) "Refreshing…" else "Refresh",
                    onClick = { if (!refreshing) load(selectedChip, forceSpinner = false) },
                    modifier = Modifier.focusProperties {
                        down = firstChipFocus
                    }
                )
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            itemsIndexed(
                items = chips,
                key = { _, item -> item.first },
                contentType = { _, _ -> "category_chip" }
            ) { idx, (key, label) ->
                TvChip(
                    label = label,
                    selected = selectedChip == key,
                    onClick = {
                        if (selectedChip != key) {
                            selectedChip = key
                            scope.launch {
                                val cached = FeedCache.readHomeFeed(context, key)
                                if (cached != null && cached.items.isNotEmpty()) {
                                    items = cached.items
                                    progress = cached.progress
                                    continuation = cached.continuation
                                    loading = false
                                } else {
                                    items = emptyList()
                                    loading = true
                                }
                                load(key, forceSpinner = items.isEmpty())
                            }
                        }
                    },
                    modifier = if (idx == 0) {
                        Modifier
                            .focusRequester(contentFocusRequester)
                            .focusRequester(firstChipFocus)
                            .focusProperties { left = railFocusRequester }
                    } else {
                        Modifier
                    }
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        when {
            loading -> LoadingPane("Loading home feed…")
            error != null && items.isEmpty() -> ErrorPane(error!!, onRetry = { load(selectedChip) })
            items.isEmpty() -> ErrorPane("No videos found for this feed.", onRetry = { load(selectedChip) })
            else -> {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 250.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(
                        items = items,
                        key = { idx, item ->
                            when {
                                item.Video != null -> "v_${item.Video.id}_$idx"
                                item.Playlist != null -> "p_${item.Playlist.id}_$idx"
                                item.Channel != null -> "c_${item.Channel.id}_$idx"
                                else -> "raw_$idx"
                            }
                        },
                        contentType = { _, item ->
                            when {
                                item.Video != null -> "video_card"
                                item.Playlist != null -> "playlist_card"
                                else -> "channel_card"
                            }
                        }
                    ) { _, item ->
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
                            item.Channel != null -> VideoCard(
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
                    if (continuation != null) {
                        item(
                            key = "load_more_footer",
                            contentType = "footer",
                            span = { GridItemSpan(maxLineSpan) }
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp),
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
