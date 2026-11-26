package com.streamtube.tv.ui.screens

import android.app.Activity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.streamtube.tv.R
import com.streamtube.tv.core.NativeBridge
import com.streamtube.tv.core.SettingsDto
import com.streamtube.tv.core.VideoItemDto
import com.streamtube.tv.player.ExoPlayerController
import com.streamtube.tv.ui.ErrorPane
import com.streamtube.tv.ui.LoadingPane
import com.streamtube.tv.ui.StColors
import com.streamtube.tv.ui.TvButton
import kotlinx.coroutines.launch

@Composable
fun ShortsScreen(
    onOpenInFullPlayer: (VideoItemDto) -> Unit,
    onOpenChannel: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { ExoPlayerController(context) }
    val pState by controller.state.collectAsState()

    var shorts by remember { mutableStateOf<List<VideoItemDto>>(emptyList()) }
    var continuation by remember { mutableStateOf<String?>(null) }
    var index by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(SettingsDto()) }

    fun loadMore(token: String? = null) {
        scope.launch {
            if (token == null) loading = true
            NativeBridge.getShortsFeed(token, null).fold(
                onSuccess = { res ->
                    val vids = res.items.mapNotNull { it.Video }
                    shorts = if (token == null) vids else shorts + vids
                    continuation = res.continuation
                    loading = false
                },
                onFailure = { e ->
                    if (token == null) {
                        error = e.message ?: "Failed to load Shorts"
                        loading = false
                    }
                }
            )
        }
    }

    LaunchedEffect(Unit) {
        settings = NativeBridge.getSettings().getOrDefault(SettingsDto())
        loadMore(null)
    }

    val current = shorts.getOrNull(index)

    LaunchedEffect(current?.id) {
        val v = current ?: return@LaunchedEffect
        NativeBridge.getWatchBundle(v.id).onSuccess { bundle ->
            controller.load(
                video = v.copy(
                    title = bundle.watch.title.ifBlank { v.title },
                    channelName = bundle.watch.channel.name.ifBlank { v.channelName }
                ),
                plan = bundle.playback,
                segments = bundle.segments,
                resumeSecs = 0.0,
                initialSpeed = 1.0f,
                settings = settings.copy(loopVideo = true),
                selectedQuality = settings.defaultQuality,
                selectedCodec = settings.codecPref,
                captionsLang = null,
                audioOnly = false
            )
        }
        if (index >= shorts.size - 2 && continuation != null) {
            loadMore(continuation)
        }
    }

    val activity = context as? Activity
    DisposableEffect(activity, pState.isPlaying) {
        val win = activity?.window
        if (win != null && pState.isPlaying) {
            win.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            win?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            scope.launch { controller.stopAndSave() }
            controller.disposeListenerOnly()
        }
    }

    when {
        loading -> LoadingPane("Loading Shorts…")
        error != null -> ErrorPane(error!!, onRetry = { loadMore(null) })
        current == null -> ErrorPane("No Shorts available", onRetry = { loadMore(null) })
        else -> {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .padding(24.dp)
                    .onKeyEvent { ev ->
                        if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                            when (ev.nativeKeyEvent.keyCode) {
                                KeyEvent.KEYCODE_DPAD_DOWN -> {
                                    if (index + 1 < shorts.size) index += 1
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_UP -> {
                                    if (index > 0) index -= 1
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                                    controller.togglePlayPause()
                                    true
                                }
                                else -> false
                            }
                        } else false
                    }
                    .focusable(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(9f / 16f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF111116))
                ) {
                    AndroidView(
                        factory = { ctx ->
                            (LayoutInflater.from(ctx).inflate(R.layout.exo_surface_view, null, false) as PlayerView).apply {
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                player = controller.player
                            }
                        },
                        update = { pv ->
                            if (pv.player !== controller.player) pv.player = controller.player
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    if (!pState.isPlaying && !pState.isBuffering) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
                                .padding(horizontal = 18.dp, vertical = 10.dp)
                        ) {
                            Text("Paused", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .padding(start = 28.dp)
                        .fillMaxWidth(0.45f),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "Short ${index + 1} / ${shorts.size}",
                        color = StColors.TextMuted,
                        fontSize = 12.sp
                    )
                    Text(
                        text = current.title.ifBlank { "YouTube Short (${current.id})" },
                        color = StColors.TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (current.channelName.isNotBlank()) {
                        Text(current.channelName, color = StColors.TextSecondary, fontSize = 15.sp)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvButton(
                            label = "Previous",
                            onClick = { if (index > 0) index -= 1 }
                        )
                        TvButton(
                            label = "Next Short",
                            selected = true,
                            onClick = { if (index + 1 < shorts.size) index += 1 }
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvButton(
                            label = if (pState.isPlaying) "Pause" else "Play",
                            onClick = { controller.togglePlayPause() }
                        )
                        TvButton(
                            label = "Full Player",
                            onClick = { onOpenInFullPlayer(current) }
                        )
                        current.channelId?.let { cid ->
                            TvButton(label = "Channel", onClick = { onOpenChannel(cid) })
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Tip: Press Up / Down on your TV remote to switch Shorts, OK to pause.",
                        color = StColors.TextMuted,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
