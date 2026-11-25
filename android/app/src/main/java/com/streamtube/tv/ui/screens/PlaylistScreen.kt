package com.streamtube.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.streamtube.tv.core.NativeBridge
import com.streamtube.tv.core.PlaylistPageDto
import com.streamtube.tv.core.ProgressEntryDto
import com.streamtube.tv.core.VideoItemDto
import com.streamtube.tv.ui.ErrorPane
import com.streamtube.tv.ui.LoadingPane
import com.streamtube.tv.ui.StColors
import com.streamtube.tv.ui.TvButton
import com.streamtube.tv.ui.VideoCard

@Composable
fun PlaylistScreen(
    playlistId: String,
    onBack: () -> Unit,
    onPlayVideo: (VideoItemDto, String) -> Unit
) {
    var page by remember { mutableStateOf<PlaylistPageDto?>(null) }
    var progress by remember { mutableStateOf<Map<String, ProgressEntryDto>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(playlistId) {
        loading = true
        error = null
        NativeBridge.getPlaylist(playlistId).fold(
            onSuccess = { res ->
                page = res.playlist
                progress = res.progress
                loading = false
            },
            onFailure = { e ->
                error = e.message ?: "Failed to load playlist"
                loading = false
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            TvButton(label = "Back", onClick = onBack)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = page?.info?.title?.ifBlank { "Playlist" } ?: "Playlist",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = StColors.TextPrimary
                )
                val meta = listOfNotNull(page?.info?.channelName, page?.info?.videoCountText).joinToString(" • ")
                if (meta.isNotBlank()) {
                    Text(meta, fontSize = 13.sp, color = StColors.TextSecondary)
                }
            }
            page?.videos?.firstOrNull()?.let { first ->
                TvButton(
                    label = "Play All",
                    selected = true,
                    onClick = { onPlayVideo(first, playlistId) }
                )
            }
        }

        when {
            loading -> LoadingPane("Loading playlist…")
            error != null -> ErrorPane(error!!)
            page?.videos.isNullOrEmpty() -> ErrorPane("Playlist is empty")
            else -> {
                val vids = page!!.videos
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 250.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(vids, key = { it.id }) { v ->
                        VideoCard(
                            video = v,
                            progress = progress[v.id],
                            onClick = { onPlayVideo(v, playlistId) }
                        )
                    }
                }
            }
        }
    }
}
