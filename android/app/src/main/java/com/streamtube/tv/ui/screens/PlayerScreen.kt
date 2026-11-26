package com.streamtube.tv.ui.screens

import android.app.Activity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.streamtube.tv.R
import com.streamtube.tv.core.ChatMessageDto
import com.streamtube.tv.core.CommentDto
import com.streamtube.tv.core.NativeBridge
import com.streamtube.tv.core.SettingsDto
import com.streamtube.tv.core.VideoItemDto
import com.streamtube.tv.core.WatchBundleDto
import com.streamtube.tv.player.ExoPlayerController
import com.streamtube.tv.ui.ErrorPane
import com.streamtube.tv.ui.LoadingPane
import com.streamtube.tv.ui.StColors
import com.streamtube.tv.ui.TvButton
import com.streamtube.tv.ui.TvChip
import com.streamtube.tv.ui.VideoCard
import com.streamtube.tv.ui.formatCompactCount
import com.streamtube.tv.ui.formatDurationSecs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class DrawerTab(val label: String) {
    CONTROLS("Quick Controls"),
    RELATED("Up Next"),
    CHAPTERS("Chapters"),
    INFO("Description & Stats"),
    COMMENTS("Comments"),
    CHAT("Live Chat")
}

@Composable
fun PlayerScreen(
    initialVideo: VideoItemDto,
    playlistId: String? = null,
    onPlayVideo: (VideoItemDto, String?) -> Unit,
    onOpenChannel: (String) -> Unit,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { ExoPlayerController(context) }
    val pState by controller.state.collectAsState()

    var currentVideo by remember(initialVideo.id) { mutableStateOf(initialVideo) }
    var bundle by remember { mutableStateOf<WatchBundleDto?>(null) }
    var settings by remember { mutableStateOf(SettingsDto()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    var hudVisible by remember { mutableStateOf(true) }
    var drawerOpen by remember { mutableStateOf(false) }
    var drawerTab by remember { mutableStateOf(DrawerTab.CONTROLS) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var previewSeekMs by remember { mutableStateOf<Long?>(null) }
    var scrubCommitJob by remember { mutableStateOf<Job?>(null) }

    var isSubscribed by remember { mutableStateOf(false) }
    var rating by remember { mutableIntStateOf(0) }
    var inWatchLater by remember { mutableStateOf(false) }
    var inFavorites by remember { mutableStateOf(false) }

    var comments by remember { mutableStateOf<List<CommentDto>>(emptyList()) }
    var commentsCont by remember { mutableStateOf<String?>(null) }
    var commentsLoading by remember { mutableStateOf(false) }

    var chatMessages by remember { mutableStateOf<List<ChatMessageDto>>(emptyList()) }
    var chatNext by remember { mutableStateOf<String?>(null) }

    val rootFocus = remember { FocusRequester() }
    val drawerFocus = remember { FocusRequester() }
    val accent = MaterialTheme.colorScheme.primary

    fun touchHud() {
        hudVisible = true
        lastInteraction = System.currentTimeMillis()
    }

    fun playNextVideo() {
        val next = bundle?.watch?.playlist?.items?.let { pl ->
            val idx = pl.indexOfFirst { it.id == currentVideo.id }
            if (idx >= 0 && idx + 1 < pl.size) pl[idx + 1] else null
        } ?: bundle?.watch?.related?.firstNotNullOfOrNull { it.Video }
        if (next != null) {
            scope.launch {
                controller.stopAndSave()
                currentVideo = next
            }
        }
    }

    fun scrubBy(deltaMs: Long, repeatCount: Int) {
        val dur = pState.durationMs.coerceAtLeast(0L)
        if (dur <= 0L) return
        val mult = when {
            repeatCount >= 16 -> 6L
            repeatCount >= 6 -> 3L
            else -> 1L
        }
        val base = previewSeekMs ?: pState.positionMs
        val target = (base + deltaMs * mult).coerceIn(0L, dur)
        previewSeekMs = target
        touchHud()
        scrubCommitJob?.cancel()
        scrubCommitJob = scope.launch {
            delay(140)
            controller.seekToMs(target)
            previewSeekMs = null
        }
    }

    val activity = context as? Activity
    DisposableEffect(activity, pState.isPlaying, pState.isBuffering) {
        val win = activity?.window
        if (win != null && (pState.isPlaying || pState.isBuffering)) {
            win.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            win?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    fun exitPlayer() {
        scope.launch {
            controller.stopAndSave()
            onExit()
        }
    }

    BackHandler {
        when {
            drawerOpen -> {
                drawerOpen = false
                touchHud()
            }
            else -> exitPlayer()
        }
    }

    LaunchedEffect(currentVideo.id) {
        loading = true
        loadError = null
        comments = emptyList()
        commentsCont = null
        chatMessages = emptyList()
        chatNext = null

        settings = NativeBridge.getSettings().getOrDefault(SettingsDto())
        NativeBridge.getWatchBundle(currentVideo.id, playlistId).fold(
            onSuccess = { b ->
                bundle = b
                isSubscribed = b.isSubscribed
                rating = b.rating
                inWatchLater = b.inWatchLater
                inFavorites = b.inFavorites
                chatNext = b.watch.liveChat?.firstOrNull()

                val title = b.watch.title.ifBlank { currentVideo.title }
                val channelName = b.watch.channel.name.ifBlank { currentVideo.channelName }
                val enriched = currentVideo.copy(
                    title = title,
                    channelName = channelName,
                    channelId = b.watch.channel.id.ifBlank { currentVideo.channelId },
                    channelAvatar = b.watch.channel.avatar ?: currentVideo.channelAvatar,
                    thumbnail = b.dearrowThumbnailUrl ?: currentVideo.thumbnail,
                    durationSecs = b.streams.lengthSecs.takeIf { it > 0 } ?: currentVideo.durationSecs,
                    isLive = b.watch.isLive
                )
                currentVideo = enriched

                val capLang = if (settings.captionsEnabled) {
                    if (settings.captionsLang == "original") b.streams.originalLanguage else settings.captionsLang
                } else null

                val initSpeed = (b.channelSpeed ?: settings.defaultSpeed).toFloat().coerceIn(0.25f, 3.0f)

                controller.load(
                    video = enriched,
                    plan = b.playback,
                    segments = b.segments,
                    resumeSecs = b.resumeSecs,
                    initialSpeed = initSpeed,
                    settings = settings,
                    selectedQuality = settings.defaultQuality,
                    selectedCodec = settings.codecPref,
                    captionsLang = capLang,
                    audioOnly = settings.audioOnly
                )
                NativeBridge.recordWatchStart(enriched, b.streams.lengthSecs.toDouble())
                loading = false
                touchHud()
            },
            onFailure = { e ->
                loadError = e.message ?: "Failed to load video"
                loading = false
            }
        )
    }

    // Auto-hide HUD when playing and drawer closed
    LaunchedEffect(hudVisible, drawerOpen, pState.isPlaying, lastInteraction) {
        if (hudVisible && !drawerOpen && pState.isPlaying) {
            delay(settings.controlsHideMs.coerceAtLeast(2500L))
            if (!drawerOpen && pState.isPlaying && System.currentTimeMillis() - lastInteraction >= 2400L) {
                hudVisible = false
            }
        }
    }

    // Autoplay next video when ended
    LaunchedEffect(pState.isEnded) {
        if (pState.isEnded && settings.autoplayNext && !pState.loopVideo) {
            val next = bundle?.watch?.playlist?.items?.let { pl ->
                val idx = pl.indexOfFirst { it.id == currentVideo.id }
                if (idx >= 0 && idx + 1 < pl.size) pl[idx + 1] else null
            } ?: bundle?.watch?.related?.firstNotNullOfOrNull { it.Video }
            if (next != null) {
                controller.stopAndSave()
                currentVideo = next
            }
        }
    }

    // Poll live chat when chat tab is open
    LaunchedEffect(drawerOpen, drawerTab, chatNext) {
        if (drawerOpen && drawerTab == DrawerTab.CHAT) {
            val tok = chatNext ?: return@LaunchedEffect
            NativeBridge.getLiveChat(tok).onSuccess { batch ->
                chatMessages = (chatMessages + batch.messages).takeLast(120)
                chatNext = batch.next
                delay(batch.timeoutMs.coerceIn(2000L, 10_000L))
            }
        }
    }

    // Load initial comments when Comments tab is selected
    LaunchedEffect(drawerOpen, drawerTab) {
        if (drawerOpen && drawerTab == DrawerTab.COMMENTS && comments.isEmpty() && !commentsLoading) {
            val tok = bundle?.watch?.commentsToken ?: return@LaunchedEffect
            commentsLoading = true
            NativeBridge.getComments(tok).onSuccess { cp ->
                comments = cp.comments
                commentsCont = cp.continuation
            }
            commentsLoading = false
        }
    }

    LaunchedEffect(drawerOpen) {
        if (drawerOpen) {
            runCatching { drawerFocus.requestFocus() }
        } else {
            runCatching { rootFocus.requestFocus() }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            scope.launch { controller.stopAndSave() }
            controller.disposeListenerOnly()
        }
    }

    fun switchFormat(
        quality: Int = pState.selectedQuality,
        codec: String = pState.selectedCodec,
        captionsLang: String? = pState.captionsLang,
        audioOnly: Boolean = pState.audioOnly
    ) {
        val b = bundle ?: return
        val resumeAt = pState.positionMs / 1000.0
        scope.launch {
            NativeBridge.resolvePlayback(currentVideo.id, quality, codec, null, audioOnly).onSuccess { plan ->
                controller.load(
                    video = currentVideo,
                    plan = plan,
                    segments = b.segments,
                    resumeSecs = resumeAt,
                    initialSpeed = pState.speed,
                    settings = settings,
                    selectedQuality = quality,
                    selectedCodec = codec,
                    captionsLang = captionsLang,
                    audioOnly = audioOnly
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .onKeyEvent { ev ->
                if (ev.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                if (drawerOpen) {
                    if (ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK) {
                        drawerOpen = false
                        touchHud()
                        return@onKeyEvent true
                    }
                    return@onKeyEvent false
                }
                when (ev.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        controller.togglePlayPause()
                        touchHud()
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        controller.player.playWhenReady = true
                        touchHud()
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        controller.player.playWhenReady = false
                        touchHud()
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        playNextVideo()
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        controller.seekToMs(0L)
                        touchHud()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        scrubBy(-(settings.seekStep * 1000.0).toLong(), ev.nativeKeyEvent.repeatCount)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        scrubBy((settings.seekStep * 1000.0).toLong(), ev.nativeKeyEvent.repeatCount)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        drawerOpen = true
                        hudVisible = true
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        touchHud()
                        true
                    }
                    else -> false
                }
            }
            .focusable()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        if (drawerOpen) {
                            drawerOpen = false
                        } else {
                            hudVisible = !hudVisible
                            lastInteraction = System.currentTimeMillis()
                        }
                    },
                    onDoubleTap = { controller.togglePlayPause() }
                )
            }
    ) {
        when {
            loading -> LoadingPane("Resolving stream & SponsorBlock…")
            loadError != null -> ErrorPane(loadError!!, onRetry = { onPlayVideo(currentVideo, playlistId) })
            else -> {
                // 1. Video Surface (or Audio-Only Cover)
                if (pState.audioOnly) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            AsyncImage(
                                model = currentVideo.thumbnail,
                                contentDescription = currentVideo.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(320.dp, 180.dp)
                                    .clip(RoundedCornerShape(14.dp))
                            )
                            Text("Audio-Only Mode (Background Enabled)", color = accent, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Text(currentVideo.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    AndroidView(
                        factory = { ctx ->
                            (LayoutInflater.from(ctx).inflate(R.layout.exo_surface_view, null, false) as PlayerView).apply {
                                resizeMode = pState.resizeMode
                                player = controller.player
                            }
                        },
                        update = { pv ->
                            if (pv.player !== controller.player) pv.player = controller.player
                            if (pv.resizeMode != pState.resizeMode) pv.resizeMode = pState.resizeMode
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // 2. Buffering / Error indicator
                if (pState.isBuffering) {
                    Box(modifier = Modifier.align(Alignment.Center)) {
                        LoadingPane("")
                    }
                }
                pState.error?.let { err ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
                            .padding(20.dp)
                    ) {
                        Text(err, color = StColors.Error, fontSize = 15.sp)
                    }
                }

                // 3. SponsorBlock Auto-Skip Toast
                pState.skipToast?.let { msg ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(24.dp)
                            .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(20.dp))
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(msg, color = Color(0xFF4ADE80), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                }

                // 4. Stats for Nerds Overlay
                if (pState.statsForNerds) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(24.dp)
                            .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(10.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Stats for Nerds", color = accent, fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        Text("Video ID: ${currentVideo.id}  |  Source: ${pState.streamSource}", color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Text("Resolution: ${pState.videoWidth}x${pState.videoHeight} @ ${"%.1f".format(pState.videoFps)} fps (${pState.qualityLabel})", color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Text("Video Codec: ${pState.videoCodec.ifBlank { "unknown" }}  (${pState.videoBitrate / 1000} kbps)", color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Text("Audio Codec: ${pState.audioCodec.ifBlank { "embedded/HLS" }}  (${pState.audioBitrate / 1000} kbps)", color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Text("Dropped Frames: ${pState.droppedFrames}  |  Buffer: ${"%.1f".format(pState.bufferHealthSecs)}s  |  Speed: ${pState.speed}x", color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }

                // 5. Player HUD & Quick Controls Drawer
                if (hudVisible || drawerOpen) {
                    val b = bundle
                    val posSecs = pState.positionMs / 1000.0
                    val activeChapter = remember(b?.watch?.chapters, pState.positionMs) {
                        b?.watch?.chapters?.lastOrNull { it.start <= posSecs }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Black.copy(alpha = 0.6f),
                                        Color.Transparent,
                                        Color.Black.copy(alpha = if (drawerOpen) 0.94f else 0.85f)
                                    )
                                )
                            )
                    ) {
                        // Top Title Bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter)
                                .padding(horizontal = 28.dp, vertical = 20.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = currentVideo.title,
                                    color = Color.White,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val sub = buildString {
                                    append(currentVideo.channelName)
                                    b?.votes?.let { v ->
                                        append("  •  👍 ${formatCompactCount(v.likes)}  👎 ${formatCompactCount(v.dislikes)}")
                                    }
                                    if (pState.qualityLabel.isNotBlank()) {
                                        append("  •  ${pState.qualityLabel}")
                                    }
                                }
                                Text(sub, color = StColors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (drawerOpen) {
                                TvButton(label = "Hide Menu", onClick = { drawerOpen = false })
                            } else {
                                TvButton(label = "Menu (Down)", onClick = { drawerOpen = true })
                            }
                        }

                        // Bottom Seekbar + Drawer
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 28.dp, vertical = 18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Seekbar with SponsorBlock markers + live scrub preview
                            val durMs = pState.durationMs.coerceAtLeast(1L)
                            val displayPosMs = previewSeekMs ?: pState.positionMs
                            val frac = (displayPosMs.toFloat() / durMs.toFloat()).coerceIn(0f, 1f)
                            val bufFrac = (pState.bufferedMs.toFloat() / durMs.toFloat()).coerceIn(0f, 1f)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val timeText = when {
                                    currentVideo.isLive -> "LIVE"
                                    previewSeekMs != null -> "${formatDurationSecs(displayPosMs / 1000)} / ${formatDurationSecs(pState.durationMs / 1000)}  (Seek)"
                                    else -> "${formatDurationSecs(displayPosMs / 1000)} / ${formatDurationSecs(pState.durationMs / 1000)}"
                                }
                                Text(
                                    text = timeText,
                                    color = if (previewSeekMs != null) accent else Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                activeChapter?.let { ch ->
                                    Text(
                                        text = "Chapter: ${ch.title}",
                                        color = Color.White.copy(alpha = 0.9f),
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(16.dp)
                                    .pointerInput(durMs) {
                                        detectTapGestures { offset ->
                                            val pct = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                            controller.seekToMs((pct * durMs).toLong())
                                            touchHud()
                                        }
                                    },
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Canvas(modifier = Modifier.fillMaxWidth().height(6.dp)) {
                                    val w = size.width
                                    val h = size.height
                                    drawRoundRect(
                                        color = Color.White.copy(alpha = 0.25f),
                                        size = Size(w, h),
                                        cornerRadius = CornerRadius(h / 2, h / 2)
                                    )
                                    drawRoundRect(
                                        color = Color.White.copy(alpha = 0.45f),
                                        size = Size(w * bufFrac, h),
                                        cornerRadius = CornerRadius(h / 2, h / 2)
                                    )
                                    // SponsorBlock colored segments on seekbar
                                    val durSec = durMs / 1000.0
                                    if (durSec > 0.0 && pState.sponsorBlockEnabled) {
                                        for (seg in b?.segments.orEmpty()) {
                                            if (seg.userAction == "off") continue
                                            val sx = ((seg.start / durSec).toFloat().coerceIn(0f, 1f)) * w
                                            val ex = ((seg.end / durSec).toFloat().coerceIn(0f, 1f)) * w
                                            if (ex > sx) {
                                                drawRect(
                                                    color = Color((0xFF000000L or (seg.color and 0xFFFFFFL)).toInt()),
                                                    topLeft = Offset(sx, 0f),
                                                    size = Size((ex - sx).coerceAtLeast(3f), h)
                                                )
                                            }
                                        }
                                    }
                                    drawRoundRect(
                                        color = accent,
                                        size = Size(w * frac, h),
                                        cornerRadius = CornerRadius(h / 2, h / 2)
                                    )
                                    drawCircle(
                                        color = Color.White,
                                        radius = 7.dp.toPx(),
                                        center = Offset(w * frac, h / 2)
                                    )
                                }
                            }

                            if (!drawerOpen) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TvButton(label = if (pState.isPlaying) "Pause" else "Play", selected = true, onClick = { controller.togglePlayPause(); touchHud() })
                                    TvButton(label = "-${settings.seekStep.toInt()}s", onClick = { controller.seekBy(-(settings.seekStep * 1000).toLong()); touchHud() })
                                    TvButton(label = "+${settings.seekStep.toInt()}s", onClick = { controller.seekBy((settings.seekStep * 1000).toLong()); touchHud() })
                                    TvButton(label = "Controls & Info", onClick = { drawerOpen = true })
                                    TvButton(label = "Back", onClick = { exitPlayer() })
                                }
                            } else {
                                // Drawer Tabs
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val tabs = buildList {
                                        add(DrawerTab.CONTROLS)
                                        add(DrawerTab.RELATED)
                                        if (!b?.watch?.chapters.isNullOrEmpty()) add(DrawerTab.CHAPTERS)
                                        add(DrawerTab.INFO)
                                        if (!settings.hideComments && b?.watch?.commentsToken != null) add(DrawerTab.COMMENTS)
                                        if (b?.watch?.liveChat != null) add(DrawerTab.CHAT)
                                    }
                                    items(tabs, key = { it.name }) { tab ->
                                        TvChip(
                                            label = tab.label,
                                            selected = drawerTab == tab,
                                            onClick = { drawerTab = tab },
                                            modifier = if (tab == DrawerTab.CONTROLS) Modifier.focusRequester(drawerFocus) else Modifier
                                        )
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(270.dp)
                                        .background(StColors.Surface.copy(alpha = 0.94f), RoundedCornerShape(14.dp))
                                        .padding(14.dp)
                                ) {
                                    when (drawerTab) {
                                        DrawerTab.CONTROLS -> {
                                            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                                item {
                                                    Text("Quality", color = StColors.TextSecondary, fontSize = 12.sp)
                                                    Spacer(Modifier.height(4.dp))
                                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        val qualities = listOf(0 to "Auto", 4320 to "4320p", 2160 to "2160p 4K", 1440 to "1440p", 1080 to "1080p", 720 to "720p", 480 to "480p", 360 to "360p")
                                                        items(qualities) { (q, lbl) ->
                                                            TvButton(
                                                                label = lbl,
                                                                selected = pState.selectedQuality == q,
                                                                onClick = { switchFormat(quality = q) }
                                                            )
                                                        }
                                                    }
                                                }
                                                item {
                                                    Text("Codec & Speed", color = StColors.TextSecondary, fontSize = 12.sp)
                                                    Spacer(Modifier.height(4.dp))
                                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        for ((c, lbl) in listOf("av1" to "AV1", "vp9" to "VP9", "h264" to "H.264")) {
                                                            item {
                                                                TvButton(
                                                                    label = lbl,
                                                                    selected = pState.selectedCodec == c,
                                                                    onClick = { switchFormat(codec = c) }
                                                                )
                                                            }
                                                        }
                                                        for (sp in listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)) {
                                                            item {
                                                                TvButton(
                                                                    label = "${sp}x",
                                                                    selected = pState.speed == sp,
                                                                    onClick = {
                                                                        controller.setSpeed(sp)
                                                                        val cid = currentVideo.channelId.orEmpty()
                                                                        if (settings.rememberSpeedPerChannel && cid.isNotEmpty()) {
                                                                            scope.launch { NativeBridge.setChannelSpeed(cid, sp.toDouble()) }
                                                                        }
                                                                    }
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                                item {
                                                    Text("Captions & Player Modes", color = StColors.TextSecondary, fontSize = 12.sp)
                                                    Spacer(Modifier.height(4.dp))
                                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        item {
                                                            TvButton(
                                                                label = "CC: Off",
                                                                selected = pState.captionsLang == null,
                                                                onClick = { switchFormat(captionsLang = null) }
                                                            )
                                                        }
                                                        items(b?.playback?.captions.orEmpty().take(8)) { cap ->
                                                            TvButton(
                                                                label = "CC: ${cap.name}",
                                                                selected = pState.captionsLang == cap.lang,
                                                                onClick = { switchFormat(captionsLang = cap.lang) }
                                                            )
                                                        }
                                                    }
                                                }
                                                item {
                                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        item {
                                                            TvButton(
                                                                label = "Audio-Only: ${if (pState.audioOnly) "ON" else "OFF"}",
                                                                selected = pState.audioOnly,
                                                                onClick = { switchFormat(audioOnly = !pState.audioOnly) }
                                                            )
                                                        }
                                                        item {
                                                            TvButton(
                                                                label = "Stats for Nerds",
                                                                selected = pState.statsForNerds,
                                                                onClick = { controller.toggleStatsForNerds() }
                                                            )
                                                        }
                                                        item {
                                                            TvButton(
                                                                label = "SponsorBlock: ${if (pState.sponsorBlockEnabled) "ON" else "OFF"}",
                                                                selected = pState.sponsorBlockEnabled,
                                                                onClick = { controller.toggleSponsorBlock() }
                                                            )
                                                        }
                                                        item {
                                                            val rLbl = when (pState.resizeMode) {
                                                                AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretch"
                                                                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
                                                                else -> "Fit"
                                                            }
                                                            TvButton(label = "Aspect: $rLbl", onClick = { controller.cycleResizeMode() })
                                                        }
                                                        item {
                                                            TvButton(label = "Loop: ${if (pState.loopVideo) "ON" else "OFF"}", selected = pState.loopVideo, onClick = { controller.toggleLoop() })
                                                        }
                                                    }
                                                }
                                                item {
                                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        item {
                                                            TvButton(
                                                                label = if (rating == 1) "Liked" else "Like",
                                                                selected = rating == 1,
                                                                onClick = {
                                                                    val next = if (rating == 1) 0 else 1
                                                                    rating = next
                                                                    scope.launch { NativeBridge.setVideoRating(currentVideo.id, next) }
                                                                }
                                                            )
                                                        }
                                                        item {
                                                            val dis = b?.votes?.dislikes?.let { " (${formatCompactCount(it)})" } ?: ""
                                                            TvButton(
                                                                label = "Dislike$dis",
                                                                selected = rating == -1,
                                                                onClick = {
                                                                    val next = if (rating == -1) 0 else -1
                                                                    rating = next
                                                                    scope.launch { NativeBridge.setVideoRating(currentVideo.id, next) }
                                                                }
                                                            )
                                                        }
                                                        item {
                                                            val cid = currentVideo.channelId.orEmpty()
                                                            TvButton(
                                                                label = if (isSubscribed) "Subscribed" else "Subscribe",
                                                                selected = isSubscribed,
                                                                onClick = {
                                                                    if (cid.isNotEmpty()) {
                                                                        scope.launch {
                                                                            if (isSubscribed) {
                                                                                NativeBridge.unsubscribe(cid)
                                                                                isSubscribed = false
                                                                            } else {
                                                                                NativeBridge.subscribe(cid, currentVideo.channelName, currentVideo.channelAvatar)
                                                                                isSubscribed = true
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                            )
                                                        }
                                                        item {
                                                            TvButton(
                                                                label = if (inWatchLater) "In Watch Later" else "+ Watch Later",
                                                                selected = inWatchLater,
                                                                onClick = {
                                                                    scope.launch {
                                                                        val pls = NativeBridge.getLocalPlaylists().getOrDefault(emptyList())
                                                                        pls.firstOrNull { it.protected && it.name == "Watch later" }?.let { wl ->
                                                                            NativeBridge.togglePlaylistItem(wl.id, currentVideo).onSuccess { inWatchLater = it }
                                                                        }
                                                                    }
                                                                }
                                                            )
                                                        }
                                                        item {
                                                            TvButton(
                                                                label = if (inFavorites) "Favorited" else "+ Favorite",
                                                                selected = inFavorites,
                                                                onClick = {
                                                                    scope.launch {
                                                                        val pls = NativeBridge.getLocalPlaylists().getOrDefault(emptyList())
                                                                        pls.firstOrNull { it.protected && it.name == "Favorites" }?.let { fav ->
                                                                            NativeBridge.togglePlaylistItem(fav.id, currentVideo).onSuccess { inFavorites = it }
                                                                        }
                                                                    }
                                                                }
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        DrawerTab.RELATED -> {
                                            val list = b?.watch?.playlist?.items?.takeIf { it.isNotEmpty() }
                                                ?: b?.watch?.related?.mapNotNull { it.Video }.orEmpty()
                                            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                                items(list, key = { it.id }) { vid ->
                                                    VideoCard(
                                                        video = vid,
                                                        onClick = {
                                                            drawerOpen = false
                                                            scope.launch {
                                                                controller.stopAndSave()
                                                                currentVideo = vid
                                                            }
                                                        },
                                                        modifier = Modifier.width(240.dp)
                                                    )
                                                }
                                            }
                                        }
                                        DrawerTab.CHAPTERS -> {
                                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                items(b?.watch?.chapters.orEmpty()) { ch ->
                                                    TvButton(
                                                        label = "${formatDurationSecs(ch.start.toLong())} • ${ch.title}",
                                                        selected = activeChapter == ch,
                                                        onClick = {
                                                            controller.seekToMs((ch.start * 1000.0).toLong())
                                                            drawerOpen = false
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                        DrawerTab.INFO -> {
                                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                item {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                    ) {
                                                        AsyncImage(
                                                            model = b?.watch?.channel?.avatar,
                                                            contentDescription = b?.watch?.channel?.name,
                                                            contentScale = ContentScale.Crop,
                                                            modifier = Modifier.size(40.dp).clip(CircleShape)
                                                        )
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(b?.watch?.channel?.name.orEmpty(), color = Color.White, fontWeight = FontWeight.SemiBold)
                                                            Text(b?.watch?.channel?.subscribersText.orEmpty(), color = StColors.TextSecondary, fontSize = 12.sp)
                                                        }
                                                        b?.watch?.channel?.id?.takeIf { it.isNotEmpty() }?.let { cid ->
                                                            TvButton(label = "Open Channel", onClick = {
                                                                scope.launch {
                                                                    controller.stopAndSave()
                                                                    onOpenChannel(cid)
                                                                }
                                                            })
                                                        }
                                                    }
                                                }
                                                item {
                                                    val stats = buildString {
                                                        append("${b?.watch?.viewsText.orEmpty()}  •  ${b?.watch?.dateText.orEmpty()}")
                                                        b?.votes?.let { v ->
                                                            append("  •  Likes: ${formatCompactCount(v.likes)}  •  Dislikes (RYD): ${formatCompactCount(v.dislikes)}")
                                                        }
                                                    }
                                                    Text(stats, color = StColors.TextSecondary, fontSize = 13.sp)
                                                }
                                                item {
                                                    Text(
                                                        text = b?.watch?.description.orEmpty(),
                                                        color = StColors.TextPrimary,
                                                        fontSize = 13.sp,
                                                        lineHeight = 18.sp
                                                    )
                                                }
                                            }
                                        }
                                        DrawerTab.COMMENTS -> {
                                            if (commentsLoading) {
                                                LoadingPane("Loading comments…")
                                            } else {
                                                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                                    items(comments) { c ->
                                                        Column(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .background(StColors.Background, RoundedCornerShape(8.dp))
                                                                .padding(10.dp)
                                                        ) {
                                                            Text(
                                                                "${c.author} • ${c.published} • 👍 ${c.likes}",
                                                                color = if (c.isCreator) accent else StColors.TextSecondary,
                                                                fontSize = 12.sp,
                                                                fontWeight = FontWeight.SemiBold
                                                            )
                                                            Spacer(Modifier.height(4.dp))
                                                            Text(c.text, color = StColors.TextPrimary, fontSize = 13.sp)
                                                        }
                                                    }
                                                    if (commentsCont != null) {
                                                        item {
                                                            TvButton(label = "Load More Comments", onClick = {
                                                                val tok = commentsCont ?: return@TvButton
                                                                scope.launch {
                                                                    NativeBridge.getComments(tok).onSuccess { cp ->
                                                                        comments = comments + cp.comments
                                                                        commentsCont = cp.continuation
                                                                    }
                                                                }
                                                            })
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        DrawerTab.CHAT -> {
                                            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                items(chatMessages) { m ->
                                                    Text(
                                                        text = "${m.author}: ${m.text}",
                                                        color = StColors.TextPrimary,
                                                        fontSize = 13.sp
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
