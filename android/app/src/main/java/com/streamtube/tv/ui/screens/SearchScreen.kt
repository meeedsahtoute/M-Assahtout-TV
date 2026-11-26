package com.streamtube.tv.ui.screens

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
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
import com.streamtube.tv.ui.TvChip
import com.streamtube.tv.ui.TvIcons
import com.streamtube.tv.ui.TvTextField
import com.streamtube.tv.ui.VideoCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen(
    contentFocusRequester: FocusRequester,
    railFocusRequester: FocusRequester,
    onPlayVideo: (VideoItemDto) -> Unit,
    onOpenChannel: (String) -> Unit,
    onOpenPlaylist: (String) -> Unit
) {
    val context = LocalContext.current
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
    var voiceStatus by remember { mutableStateOf<String?>(null) }
    var searchedOnce by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()

    fun runSearch(q: String, f: SearchFilterDto = filter, cont: String? = null) {
        val trimmed = q.trim()
        if (trimmed.isEmpty() && cont == null) return
        voiceStatus = null
        scope.launch {
            if (cont == null) {
                loading = true
                error = null
                searchedOnce = true
            } else {
                if (loadingMore) return@launch
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

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
            if (!spoken.isNullOrEmpty()) {
                query = spoken
                runSearch(spoken)
            }
        }
    }

    fun launchVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to search M. ASSAHTOUT TV")
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            voiceLauncher.launch(intent)
        } else {
            voiceStatus = "Voice recognizer not installed on this device"
        }
    }

    LaunchedEffect(query) {
        delay(180)
        NativeBridge.searchSuggestions(query).onSuccess { suggestions = it }
    }

    LaunchedEffect(gridState, continuation, results.size) {
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
                    runSearch(query, filter, tok)
                }
            }
    }

    Column(modifier = Modifier.fillMaxSize().padding(top = 14.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
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
                    .focusRequester(contentFocusRequester)
                    .focusProperties { left = railFocusRequester }
            )
            Surface(
                onClick = { launchVoiceSearch() },
                shape = ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.CircleShape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = StColors.Chip,
                    focusedContainerColor = StColors.Hover,
                    contentColor = StColors.TextPrimary,
                    focusedContentColor = androidx.compose.ui.graphics.Color.White
                ),
                border = ClickableSurfaceDefaults.border(
                    border = Border(androidx.compose.foundation.BorderStroke(2.dp, androidx.compose.ui.graphics.Color.Transparent)),
                    focusedBorder = Border(androidx.compose.foundation.BorderStroke(2.dp, androidx.compose.ui.graphics.Color.White))
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f)
            ) {
                Box(
                    modifier = Modifier.size(44.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = TvIcons.Mic,
                        contentDescription = "Voice Search",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            TvButton(label = "Search", selected = true, onClick = { runSearch(query) })
            if (query.isNotEmpty()) {
                TvButton(label = "Clear", onClick = { query = "" })
            }
        }

        voiceStatus?.let { msg ->
            Text(
                text = msg,
                color = StColors.Warning,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
            )
        }

        if (suggestions.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(
                    items = suggestions,
                    key = { idx, sugg -> "sug_${idx}_$sugg" },
                    contentType = { _, _ -> "suggestion" }
                ) { idx, sugg ->
                    TvChip(
                        label = sugg,
                        selected = false,
                        onClick = {
                            query = sugg
                            runSearch(sugg)
                        },
                        modifier = if (idx == 0) Modifier.focusProperties { left = railFocusRequester } else Modifier
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .focusProperties { up = contentFocusRequester }
                .onPreviewKeyEvent { ev ->
                    ev.type == KeyEventType.KeyDown &&
                        ev.key == Key.DirectionDown &&
                        results.isEmpty() &&
                        !loading &&
                        error == null
                }
        ) {
            val sortOptions = listOf(0 to "Relevance", 2 to "Upload Date", 3 to "View Count", 1 to "Rating")
            itemsIndexed(sortOptions, key = { _, it -> "sort_${it.first}" }, contentType = { _, _ -> "filter_chip" }) { idx, (code, label) ->
                TvChip(
                    label = "Sort: $label",
                    selected = filter.sort == code,
                    onClick = {
                        filter = filter.copy(sort = code)
                        if (query.isNotBlank()) runSearch(query, filter)
                    },
                    modifier = if (idx == 0) Modifier.focusProperties { left = railFocusRequester } else Modifier
                )
            }
            val kindOptions = listOf(0 to "All", 1 to "Videos", 2 to "Channels", 3 to "Playlists")
            itemsIndexed(kindOptions, key = { _, it -> "kind_${it.first}" }, contentType = { _, _ -> "filter_chip" }) { _, (code, label) ->
                TvChip(
                    label = label,
                    selected = filter.kind == code,
                    onClick = {
                        filter = filter.copy(kind = code)
                        if (query.isNotBlank()) runSearch(query, filter)
                    }
                )
            }
            item(key = "filter_4k", contentType = "filter_chip") {
                TvChip(
                    label = "4K",
                    selected = filter.fourK,
                    onClick = {
                        filter = filter.copy(fourK = !filter.fourK)
                        if (query.isNotBlank()) runSearch(query, filter)
                    }
                )
            }
            item(key = "filter_live", contentType = "filter_chip") {
                TvChip(
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
                        "Type a query, press Voice Search, or pick a recent suggestion above",
                        color = StColors.TextSecondary,
                        fontSize = 15.sp
                    )
                }
            }
            results.isEmpty() -> ErrorPane("No results for \"$query\"")
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
                        items = results,
                        key = { idx, item ->
                            when {
                                item.Video != null -> "sv_${item.Video.id}_$idx"
                                item.Channel != null -> "sc_${item.Channel.id}_$idx"
                                item.Playlist != null -> "sp_${item.Playlist.id}_$idx"
                                else -> "sr_$idx"
                            }
                        },
                        contentType = { _, item ->
                            when {
                                item.Video != null -> "video_card"
                                item.Channel != null -> "channel_card"
                                else -> "playlist_card"
                            }
                        }
                    ) { _, item ->
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
                        item(
                            key = "search_load_more",
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
