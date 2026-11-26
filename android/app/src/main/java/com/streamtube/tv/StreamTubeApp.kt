package com.streamtube.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.streamtube.tv.core.AppRuntime
import com.streamtube.tv.core.FeedCache
import com.streamtube.tv.core.InitInfoDto
import com.streamtube.tv.core.NativeBridge
import com.streamtube.tv.core.ResolvedUrlDto
import com.streamtube.tv.server.CompanionServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class StreamTubeApp : Application(), ImageLoaderFactory {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initReady = CompletableDeferred<InitInfoDto?>()

    private val _incomingRoutes = MutableSharedFlow<ResolvedUrlDto>(extraBufferCapacity = 16)
    val incomingRoutes = _incomingRoutes.asSharedFlow()

    val companionServer: CompanionServer by lazy {
        CompanionServer(port = 8787) { resolved ->
            _incomingRoutes.tryEmit(resolved)
        }
    }

    @Volatile
    var isCoreInitialized: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Warm up native JNI core, settings cache, and LAN companion off the main thread
        // so Activity Frame 1 renders cleanly in <400ms.
        appScope.launch {
            val res = NativeBridge.initSync(filesDir.absolutePath, cacheDir.absolutePath).getOrNull()
            if (res != null) {
                FeedCache.writeSettings(this@StreamTubeApp, res.settings)
            }
            isCoreInitialized = true
            initReady.complete(res)
            companionServer.start()
        }
    }

    suspend fun awaitCoreReady(): InitInfoDto? {
        if (isCoreInitialized) return null
        return initReady.await()
    }

    override fun newImageLoader(): ImageLoader = AppRuntime.imageLoader(this)

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        AppRuntime.trimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        AppRuntime.onLowMemory()
    }

    fun emitRoute(route: ResolvedUrlDto) {
        _incomingRoutes.tryEmit(route)
    }

    companion object {
        lateinit var instance: StreamTubeApp
            private set
    }
}
