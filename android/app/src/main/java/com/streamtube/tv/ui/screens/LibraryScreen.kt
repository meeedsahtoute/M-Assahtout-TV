package com.streamtube.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import com.streamtube.tv.core.HistoryEntryDto
import com.streamtube.tv.core.LocalPlaylistDto
import com.streamtube.tv.core.NativeBridge
import com.streamtube.tv.core.ProgressEntryDto
import com.streamtube.tv.core.VideoItemDto
import com.streamtube.tv.ui.ErrorPane
import com.streamtube.tv.ui.LoadingPane
import com.streamtube.tv.ui.StColors
import com.streamtube.tv.ui.TvButton
import com.streamtube.tv.ui.TvTextField
import com.streamtube.tv.ui.VideoCard
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(
    onPlayVideo: (VideoItemDto) -> Unit
) {
    val scope = rememberCoroutineScope()
    var playlists by remember { mutableStateOf<List<LocalPlaylistDto>>(emptyList()) }
    var selectedTab by remember { mutableStateOf<Long>(-1L) } // -1 = History
    var history by remember { mutableStateOf<List<HistoryEntryDto>>(emptyList()) }
    var playlistVideos by remember { mutableStateOf<List<VideoItemDto>>(emptyList()) }
    var playlistProgress by remember { mutableStateOf<Map<String, ProgressEntryDto>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var newPlaylistName by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }

    fun loadTab(tabId: Long) {
        scope.launch {
            loading = true
            playlists = NativeBridge.getLocalPlaylists().getOrDefault(emptyList())
            if (tabId == -1L) {
                history = NativeBridge.getHistory("", 200).getOrDefault(emptyList())
            } else {
                NativeBridge.getLocalPlaylistItems(tabId).onSuccess { res ->
                    playlistVideos = res.items.mapNotNull { it.Video }
                    playlistProgress = res.progress
                }
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        loadTab(selectedTab)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Library", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = StColors.TextPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton(label = "+ New Playlist", onClick = { showCreate = !showCreate })
                if (selectedTab == -1L && history.isNotEmpty()) {
                    TvButton(
                        label = "Clear History",
                        onClick = {
                            scope.launch {
                                NativeBridge.clearHistory()
                                loadTab(-1L)
                            }
                        }
                    )
                } else if (selectedTab > 0L && playlists.firstOrNull { it.id == selectedTab }?.protected == false) {
                    TvButton(
                        label = "Delete Playlist",
                        onClick = {
                            scope.launch {
                                NativeBridge.deleteLocalPlaylist(selectedTab)
                                selectedTab = -1L
                                loadTab(-1L)
                            }
                        }
                    )
                }
            }
        }

        if (showCreate) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TvTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    placeholder = "Playlist name…",
                    onSubmit = {
                        val name = newPlaylistName.trim()
                        if (name.isNotEmpty()) {
                            scope.launch {
                                NativeBridge.createLocalPlaylist(name).onSuccess { newId ->
                                    newPlaylistName = ""
                                    showCreate = false
                                    selectedTab = newId
                                    loadTab(newId)
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                TvButton(
                    label = "Create",
                    selected = true,
                    onClick = {
                        val name = newPlaylistName.trim()
                        if (name.isNotEmpty()) {
                            scope.launch {
                                NativeBridge.createLocalPlaylist(name).onSuccess { newId ->
                                    newPlaylistName = ""
                                    showCreate = false
                                    selectedTab = newId
                                    loadTab(newId)
                                }
                            }
                        }
                    }
                )
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                TvButton(
                    label = "Watch History",
                    selected = selectedTab == -1L,
                    onClick = {
                        selectedTab = -1L
                        loadTab(-1L)
                    }
                )
            }
            items(playlists, key = { it.id }) { pl ->
                TvButton(
                    label = "${pl.name} (${pl.count})",
                    selected = selectedTab == pl.id,
                    onClick = {
                        selectedTab = pl.id
                        loadTab(pl.id)
                    }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        when {
            loading -> LoadingPane("Loading library…")
            selectedTab == -1L && history.isEmpty() -> ErrorPane("Your local watch history is empty.")
            selectedTab != -1L && playlistVideos.isEmpty() -> ErrorPane("This playlist has no videos yet.")
            selectedTab == -1L -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 250.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(history, key = { it.video.id }) { entry ->
                        VideoCard(
                            video = entry.video,
                            progress = ProgressEntryDto(entry.progress, entry.duration),
                            onClick = { onPlayVideo(entry.video) }
                        )
                    }
                }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 250.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(playlistVideos, key = { it.id }) { v ->
                        VideoCard(
                            video = v,
                            progress = playlistProgress[v.id],
                            onClick = { onPlayVideo(v) }
                        )
                    }
                }
            }
        }
    }
}
