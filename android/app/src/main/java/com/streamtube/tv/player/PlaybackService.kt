package com.streamtube.tv.player

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.streamtube.tv.core.AppRuntime

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayerHolder.getOrCreate(applicationContext)
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.playbackState == Player.STATE_ENDED || !ExoPlayerHolder.keepBackgroundAudio) {
            player?.pause()
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}

object ExoPlayerHolder {
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    @Volatile
    private var instance: ExoPlayer? = null

    @Volatile
    var keepBackgroundAudio: Boolean = false

    fun getOrCreate(context: Context): ExoPlayer {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val appCtx = context.applicationContext
            val am = appCtx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val lowRam = am?.isLowRamDevice == true || Runtime.getRuntime().maxMemory() <= 268_435_456L
            val targetBufferBytes = if (lowRam) 10 * 1024 * 1024 else 18 * 1024 * 1024

            // Sub-400ms startup latency & tight memory cap for 1GB-2GB Android TV boxes
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 4_000,
                    /* maxBufferMs = */ 15_000,
                    /* bufferForPlaybackMs = */ 300,
                    /* bufferForPlaybackAfterRebufferMs = */ 600
                )
                .setTargetBufferBytes(targetBufferBytes)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build()

            val renderersFactory = DefaultRenderersFactory(appCtx)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                .setEnableDecoderFallback(true)

            val httpFactory = OkHttpDataSource.Factory(AppRuntime.okHttpClient(appCtx))
                .setUserAgent(USER_AGENT)

            val player = ExoPlayer.Builder(appCtx, renderersFactory)
                .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
                .setLoadControl(loadControl)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true
                )
                .setHandleAudioBecomingNoisy(true)
                .build()
            instance = player
            return player
        }
    }

    fun release() {
        synchronized(this) {
            instance?.release()
            instance = null
        }
    }
}
