package com.streamtube.tv.player

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

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
    @Volatile
    private var instance: ExoPlayer? = null

    @Volatile
    var keepBackgroundAudio: Boolean = false

    fun getOrCreate(context: android.content.Context): ExoPlayer {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 60_000, 1_500, 3_000)
                .build()
            val player = ExoPlayer.Builder(context.applicationContext)
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
