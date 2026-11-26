package com.streamtube.tv.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import com.streamtube.tv.core.AppRuntime
import com.streamtube.tv.core.NativeBridge
import com.streamtube.tv.core.PlaybackPlanDto
import com.streamtube.tv.core.SegmentDto
import com.streamtube.tv.core.SettingsDto
import com.streamtube.tv.core.VideoItemDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlayerUiState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = true,
    val isEnded: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val error: String? = null,
    val qualityLabel: String = "",
    val selectedQuality: Int = 1080,
    val selectedCodec: String = "vp9",
    val speed: Float = 1.0f,
    val audioOnly: Boolean = false,
    val captionsLang: String? = null,
    val resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT,
    val loopVideo: Boolean = false,
    val sponsorBlockEnabled: Boolean = true,
    val skipToast: String? = null,
    val statsForNerds: Boolean = false,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val videoFps: Float = 0f,
    val videoCodec: String = "",
    val audioCodec: String = "",
    val videoBitrate: Long = 0L,
    val audioBitrate: Long = 0L,
    val droppedFrames: Int = 0,
    val streamSource: String = "",
    val bufferHealthSecs: Float = 0f
)

class ExoPlayerController(context: Context) {
    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val player: ExoPlayer = ExoPlayerHolder.getOrCreate(appContext)
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null

    private val dataSourceFactory = OkHttpDataSource.Factory(AppRuntime.okHttpClient(appContext))
        .setUserAgent(ExoPlayerHolder.USER_AGENT)

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var currentVideoId: String = ""
    private var segments: List<SegmentDto> = emptyList()
    private var settings: SettingsDto = SettingsDto()
    private val skippedRecently = HashMap<String, Long>()
    private var mutedBySponsor = false
    private var tickerJob: Job? = null
    private var toastJob: Job? = null
    private var lastProgressSaveMs = 0L

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.update {
                it.copy(
                    isBuffering = playbackState == Player.STATE_BUFFERING,
                    isEnded = playbackState == Player.STATE_ENDED
                )
            }
            refreshStats()
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.update {
                it.copy(
                    isBuffering = false,
                    error = error.localizedMessage ?: "Playback failed (${error.errorCodeName})"
                )
            }
        }
    }

    init {
        player.addListener(listener)
        startMediaService()
        startTicker()
    }

    private fun startMediaService() {
        runCatching {
            val intent = Intent(appContext, PlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && ExoPlayerHolder.keepBackgroundAudio) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
            if (controllerFuture == null) {
                val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
                controllerFuture = MediaController.Builder(appContext, token).buildAsync()
            }
        }
    }

    fun load(
        video: VideoItemDto,
        plan: PlaybackPlanDto,
        segments: List<SegmentDto>,
        resumeSecs: Double,
        initialSpeed: Float,
        settings: SettingsDto,
        selectedQuality: Int,
        selectedCodec: String,
        captionsLang: String?,
        audioOnly: Boolean
    ) {
        this.currentVideoId = video.id
        this.segments = segments
        this.settings = settings
        this.skippedRecently.clear()
        this.mutedBySponsor = false
        player.volume = 1f

        ExoPlayerHolder.keepBackgroundAudio = audioOnly || settings.backgroundPlay

        _state.update {
            it.copy(
                error = null,
                isBuffering = true,
                isEnded = false,
                qualityLabel = plan.qualityLabel,
                selectedQuality = selectedQuality,
                selectedCodec = selectedCodec,
                speed = initialSpeed,
                audioOnly = audioOnly,
                captionsLang = captionsLang,
                loopVideo = settings.loopVideo,
                sponsorBlockEnabled = settings.sponsorblock,
                streamSource = plan.source,
                videoWidth = plan.width,
                videoHeight = plan.height,
                videoFps = plan.fps.toFloat(),
                videoCodec = plan.codecs,
                audioCodec = plan.audioCodec,
                videoBitrate = plan.videoBitrate,
                audioBitrate = plan.audioBitrate
            )
        }

        val mediaSource = buildMediaSource(video, plan, captionsLang, audioOnly)
        if (mediaSource == null) {
            _state.update { it.copy(isBuffering = false, error = "No playable stream found") }
            return
        }

        player.repeatMode = if (settings.loopVideo) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly)
            .build()
        player.playbackParameters = PlaybackParameters(initialSpeed)
        player.setMediaSource(mediaSource)
        if (resumeSecs > 1.0) {
            player.seekTo((resumeSecs * 1000.0).toLong())
        }
        player.prepare()
        player.playWhenReady = true
        if (audioOnly) {
            startMediaService()
        }
    }

    private fun buildMediaSource(
        video: VideoItemDto,
        plan: PlaybackPlanDto,
        captionsLang: String?,
        audioOnly: Boolean
    ): MediaSource? {
        val metadata = MediaMetadata.Builder()
            .setTitle(video.title)
            .setArtist(video.channelName)
            .setArtworkUri(Uri.parse(video.thumbnail))
            .build()

        val selectedCaption = if (!captionsLang.isNullOrBlank()) {
            plan.captions.firstOrNull { it.lang.equals(captionsLang, ignoreCase = true) }
                ?: plan.captions.firstOrNull { it.lang.startsWith(captionsLang, ignoreCase = true) }
        } else null

        val subtitleSource = selectedCaption?.let { cap ->
            val subConfig = MediaItem.SubtitleConfiguration.Builder(Uri.parse(cap.url))
                .setMimeType(MimeTypes.TEXT_VTT)
                .setLanguage(cap.lang)
                .setLabel(cap.name)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            SingleSampleMediaSource.Factory(dataSourceFactory)
                .createMediaSource(subConfig, C.TIME_UNSET)
        }

        val baseSource: MediaSource? = when (plan.mode) {
            "hls" -> {
                val url = plan.hlsUrl ?: return null
                val item = MediaItem.Builder()
                    .setUri(url)
                    .setMediaMetadata(metadata)
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build()
                HlsMediaSource.Factory(dataSourceFactory).createMediaSource(item)
            }
            "adaptive" -> {
                val vUrl = if (!audioOnly) plan.videoUrl else null
                val aUrl = plan.audioUrl
                when {
                    vUrl != null && aUrl != null -> {
                        val vItem = MediaItem.Builder().setUri(vUrl).setMediaMetadata(metadata).build()
                        val aItem = MediaItem.Builder().setUri(aUrl).setMediaMetadata(metadata).build()
                        val vSrc = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(vItem)
                        val aSrc = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(aItem)
                        MergingMediaSource(true, true, vSrc, aSrc)
                    }
                    aUrl != null -> {
                        val aItem = MediaItem.Builder().setUri(aUrl).setMediaMetadata(metadata).build()
                        ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(aItem)
                    }
                    vUrl != null -> {
                        val vItem = MediaItem.Builder().setUri(vUrl).setMediaMetadata(metadata).build()
                        ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(vItem)
                    }
                    else -> null
                }
            }
            "muxed" -> {
                val url = plan.videoUrl ?: return null
                val item = MediaItem.Builder().setUri(url).setMediaMetadata(metadata).build()
                ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(item)
            }
            else -> null
        }

        return when {
            baseSource == null -> null
            subtitleSource != null -> MergingMediaSource(true, true, baseSource, subtitleSource)
            else -> baseSource
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (true) {
                delay(250)
                val pos = player.currentPosition.coerceAtLeast(0L)
                val dur = player.duration.takeIf { it > 0 } ?: 0L
                val buf = player.bufferedPosition.coerceAtLeast(0L)
                val bufHealth = ((buf - pos).coerceAtLeast(0L)) / 1000f
                _state.update {
                    it.copy(
                        positionMs = pos,
                        durationMs = dur,
                        bufferedMs = buf,
                        bufferHealthSecs = bufHealth,
                        isPlaying = player.isPlaying
                    )
                }
                if (_state.value.statsForNerds) {
                    refreshStats()
                }
                checkSponsorBlock(pos)
                maybeSaveProgress(pos, dur)
            }
        }
    }

    private fun refreshStats() {
        val vf: Format? = player.videoFormat
        val af: Format? = player.audioFormat
        val counters = player.videoDecoderCounters
        _state.update { cur ->
            cur.copy(
                videoWidth = vf?.width?.takeIf { it > 0 } ?: cur.videoWidth,
                videoHeight = vf?.height?.takeIf { it > 0 } ?: cur.videoHeight,
                videoFps = vf?.frameRate?.takeIf { it > 0f } ?: cur.videoFps,
                videoCodec = vf?.codecs ?: vf?.sampleMimeType ?: cur.videoCodec,
                audioCodec = af?.codecs ?: af?.sampleMimeType ?: cur.audioCodec,
                videoBitrate = vf?.bitrate?.toLong()?.takeIf { it > 0 } ?: cur.videoBitrate,
                audioBitrate = af?.bitrate?.toLong()?.takeIf { it > 0 } ?: cur.audioBitrate,
                droppedFrames = counters?.droppedBufferCount ?: cur.droppedFrames
            )
        }
    }

    private fun checkSponsorBlock(posMs: Long) {
        if (!_state.value.sponsorBlockEnabled || !player.isPlaying || segments.isEmpty()) {
            if (mutedBySponsor) {
                mutedBySponsor = false
                player.volume = 1f
            }
            return
        }
        val t = posMs / 1000.0
        val now = System.currentTimeMillis()
        var insideMute = false

        for (seg in segments) {
            if (seg.end <= seg.start) continue
            if (seg.end - seg.start < settings.sbMinDuration) continue
            if (t >= seg.start && t < seg.end - 0.35) {
                when (seg.userAction) {
                    "skip" -> {
                        val lastSkip = skippedRecently[seg.uuid] ?: 0L
                        if (now - lastSkip > 3000L) {
                            skippedRecently[seg.uuid] = now
                            player.seekTo((seg.end * 1000.0).toLong())
                            NativeBridge.reportSponsorViewedAsync(seg.uuid)
                            if (settings.sbNotify) {
                                showSkipToast("Skipped ${seg.categoryName}")
                            }
                            return
                        }
                    }
                    "mute" -> insideMute = true
                }
            }
        }

        if (insideMute && !mutedBySponsor) {
            mutedBySponsor = true
            player.volume = 0f
            if (settings.sbNotify) showSkipToast("Muted ${segments.firstOrNull { t >= it.start && t < it.end }?.categoryName ?: "segment"}")
        } else if (!insideMute && mutedBySponsor) {
            mutedBySponsor = false
            player.volume = 1f
        }
    }

    private fun showSkipToast(msg: String) {
        toastJob?.cancel()
        _state.update { it.copy(skipToast = msg) }
        toastJob = scope.launch {
            delay(3000)
            _state.update { it.copy(skipToast = null) }
        }
    }

    private fun maybeSaveProgress(posMs: Long, durMs: Long) {
        val vid = currentVideoId
        if (vid.isEmpty() || !player.isPlaying) return
        val now = System.currentTimeMillis()
        if (now - lastProgressSaveMs >= 5000L) {
            lastProgressSaveMs = now
            scope.launch {
                NativeBridge.saveWatchProgress(vid, posMs / 1000.0, durMs / 1000.0, true)
            }
        }
    }

    fun togglePlayPause() {
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0)
            player.playWhenReady = true
        } else {
            player.playWhenReady = !player.playWhenReady
        }
    }

    fun seekBy(deltaMs: Long) {
        val target = (player.currentPosition + deltaMs).coerceIn(0L, player.duration.coerceAtLeast(0L))
        player.seekTo(target)
    }

    fun seekToMs(ms: Long) {
        val dur = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo(ms.coerceIn(0L, dur))
    }

    fun setSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed)
        _state.update { it.copy(speed = speed) }
    }

    fun cycleResizeMode() {
        val next = when (_state.value.resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        _state.update { it.copy(resizeMode = next) }
    }

    fun toggleLoop() {
        val next = !_state.value.loopVideo
        player.repeatMode = if (next) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        _state.update { it.copy(loopVideo = next) }
    }

    fun toggleStatsForNerds() {
        val next = !_state.value.statsForNerds
        _state.update { it.copy(statsForNerds = next) }
        if (next) refreshStats()
    }

    fun toggleSponsorBlock() {
        _state.update { it.copy(sponsorBlockEnabled = !it.sponsorBlockEnabled) }
    }

    suspend fun stopAndSave() {
        val vid = currentVideoId
        val pos = player.currentPosition.coerceAtLeast(0L) / 1000.0
        val dur = player.duration.takeIf { it > 0 }?.div(1000.0) ?: 0.0
        if (!ExoPlayerHolder.keepBackgroundAudio) {
            player.pause()
        }
        if (vid.isNotEmpty()) {
            NativeBridge.reportWatchStop(vid, pos, dur)
        }
    }

    fun disposeListenerOnly() {
        tickerJob?.cancel()
        toastJob?.cancel()
        player.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
    }
}
