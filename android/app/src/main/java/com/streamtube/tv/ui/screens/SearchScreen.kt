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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.streamtube.tv.core.NativeBridge
import com.streamtube.tv.core.ProgressEntryDto
import com.streamtube.tv.core.RawItemDto
import com.streamtube.tv.core.SearchFilterDto
import com.streamtube.tv.core.VideoItemDto
import com.streamtube.tv.ui.ErrorPane
import com.streamtube.tv.ui.LoadingPane
import com.streamtube.tv.ui.StColors
import com.streamtube.tv.ui.TvButton
import com.streamtube.tv.ui.TvTextField
import com.streamtube.tv.ui.VideoCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen(
    onPlayVideo: (VideoItemDto) -> Unit,
    onOpenChannel: (String) -> Unit,
    onOpenPlaylist: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var filter by remember { mutableStateOf(SearchFilterDto()) }
    var results by remember { mutableStateOf<List<RawItemDto>>(emptyList()) }
    var progress by remember { mutableStateOf<Map<String, ProgressEntryDto>>(emptyMap()) }
    var continuation by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searchedOnce by remember { mutableStateOf(false) }
    val searchInputFocus = remember { FocusRequester() }

    fun runSearch(q: String, f: SearchFilterDto = filter, cont: String? = null) {
        val trimmed = q.trim()
        if (trimmed.isEmpty() && cont == null) return
        scope.launch {
            if (cont == null) {
                loading = true
                error = null
                searchedOnce = true
            } else {
                loadingMore = true
            }
            NativeBridge.search(trimmed, f, cont).fold(
                onSuccess = { res ->
                    if (cont == null) {
                        results = res.items
                        progress = res.progress
                    } else {
                        results = results + res.items
                        progress = progress + res.progress
                    }
                    continuation = res.continuation
                    loading = false
                    loadingMore = false
                },
                onFailure = { e ->
                    if (cont == null) {
                        error = e.message ?: "Search failed"
                        loading = false
                    } else {
                        loadingMore = false
                    }
                }
            )
        }
    }

    LaunchedEffect(query) {
        delay(180)
        NativeBridge.searchSuggestions(query).onSuccess { suggestions = it }
    }

    Column(modifier = Modifier.fillMaxSize().padding(top = 12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TvTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search YouTube videos, channels, playlists…",
                imeAction = ImeAction.Search,
                onSubmit = { runSearch(query) },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(searchInputFocus)
            )
            TvButton(label = "Search", selected = true, onClick = { runSearch(query) })
            if (query.isNotEmpty()) {
                TvButton(label = "Clear", onClick = { query = "" })
            }
        }

        if (suggestions.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(suggestions) { sugg ->
                    TvButton(
                        label = sugg,
                        onClick = {
                            query = sugg
                            runSearch(sugg)
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .focusProperties { up = searchInputFocus }
                .onPreviewKeyEvent { ev ->
                    ev.type == KeyEventType.KeyDown &&
                        ev.key == Key.DirectionDown &&
                        results.isEmpty() &&
                        !loading &&
                        error == null
                }
        ) {
            val sortOptions = listOf(0 to "Relevance", 2 to "Upload Date", 3 to "View Count", 1 to "Rating")
            items(sortOptions) { (code, label) ->
                TvButton(
                    label = "Sort: $label",
                    selected = filter.sort == code,
                    onClick = {
                        filter = filter.copy(sort = code)
                        if (query.isNotBlank()) runSearch(query, filter)
                    }
                )
            }
            val kindOptions = listOf(0 to "All", 1 to "Videos", 2 to "Channels", 3 to "Playlists")
            items(kindOptions) { (code, label) ->
                TvButton(
                    label = label,
                    selected = filter.kind == code,
                    onClick = {
                        filter = filter.copy(kind = code)
                        if (query.isNotBlank()) runSearch(query, filter)
                    }
                )
            }
            item {
                TvButton(
                    label = "4K",
                    selected = filter.fourK,
                    onClick = {
                        filter = filter.copy(fourK = !filter.fourK)
                        if (query.isNotBlank()) runSearch(query, filter)
                    }
                )
            }
            item {
                TvButton(
                    label = "Live",
                    selected = filter.live,
                    onClick = {
                        filter = filter.copy(live = !filter.live)
                        if (query.isNotBlank()) runSearch(query, filter)
                    }
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        when {
            loading -> LoadingPane("Searching…")
            error != null -> ErrorPane(error!!, onRetry = { runSearch(query) })
            !searchedOnce -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Type a query or pick a recent suggestion above",
                        color = StColors.TextSecondary,
                        fontSize = 15.sp
                    )
                }
            }
            results.isEmpty() -> ErrorPane("No results for \"$query\"")
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 250.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(results) { item ->
                        when {
                            item.Video != null -> VideoCard(
                                video = item.Video,
                                progress = progress[item.Video.id],
                                onClick = { onPlayVideo(item.Video) }
                            )
                            item.Channel != null -> VideoCard(
                                video = VideoItemDto(
                                    id = item.Channel.id,
                                    title = item.Channel.name,
                                    channelName = listOfNotNull(item.Channel.handle, item.Channel.subscribersText).joinToString(" • "),
                                    thumbnail = item.Channel.avatar.orEmpty()
                                ),
                                onClick = { onOpenChannel(item.Channel.id) }
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
                                    onClick = { if (!loadingMore) continuation?.let { runSearch(query, filter, it) } }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
