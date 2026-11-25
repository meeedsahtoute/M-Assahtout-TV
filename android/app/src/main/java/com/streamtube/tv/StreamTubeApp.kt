package com.streamtube.tv

import android.app.Application
import com.streamtube.tv.core.NativeBridge
import com.streamtube.tv.core.ResolvedUrlDto
import com.streamtube.tv.server.CompanionServer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class StreamTubeApp : Application() {
    private val _incomingRoutes = MutableSharedFlow<ResolvedUrlDto>(extraBufferCapacity = 16)
    val incomingRoutes = _incomingRoutes.asSharedFlow()

    lateinit var companionServer: CompanionServer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        NativeBridge.initSync(filesDir.absolutePath, cacheDir.absolutePath)
        companionServer = CompanionServer(port = 8787) { resolved ->
            _incomingRoutes.tryEmit(resolved)
        }
        companionServer.start()
    }

    fun emitRoute(route: ResolvedUrlDto) {
        _incomingRoutes.tryEmit(route)
    }

    companion object {
        lateinit var instance: StreamTubeApp
            private set
    }
}
