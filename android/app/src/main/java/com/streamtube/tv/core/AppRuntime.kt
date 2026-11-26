package com.streamtube.tv.core

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Shared low-RAM networking, Coil ImageLoader, and stale-while-revalidate disk cache.
 */
object AppRuntime {
    @Volatile
    private var httpClientInstance: OkHttpClient? = null

    @Volatile
    private var imageLoaderInstance: ImageLoader? = null

    fun okHttpClient(context: Context): OkHttpClient {
        httpClientInstance?.let { return it }
        synchronized(this) {
            httpClientInstance?.let { return it }
            val appCtx = context.applicationContext
            val cacheDir = File(appCtx.cacheDir, "http_cache")
            val client = OkHttpClient.Builder()
                .cache(Cache(cacheDir, 24L * 1024L * 1024L))
                .connectionPool(ConnectionPool(8, 2, TimeUnit.MINUTES))
                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
            httpClientInstance = client
            return client
        }
    }

    fun imageLoader(context: Context): ImageLoader {
        imageLoaderInstance?.let { return it }
        synchronized(this) {
            imageLoaderInstance?.let { return it }
            val appCtx = context.applicationContext
            val maxMemBytes = (Runtime.getRuntime().maxMemory() / 10L)
                .coerceIn(8L * 1024L * 1024L, 16L * 1024L * 1024L)
                .toInt()
            val loader = ImageLoader.Builder(appCtx)
                .okHttpClient { okHttpClient(appCtx) }
                .memoryCache {
                    MemoryCache.Builder(appCtx)
                        .maxSizeBytes(maxMemBytes)
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(File(appCtx.cacheDir, "img_cache"))
                        .maxSizeBytes(32L * 1024L * 1024L)
                        .build()
                }
                .bitmapConfig(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Bitmap.Config.HARDWARE
                    } else {
                        Bitmap.Config.RGB_565
                    }
                )
                .allowHardware(true)
                .allowRgb565(true)
                .crossfade(false)
                .respectCacheHeaders(false)
                .build()
            imageLoaderInstance = loader
            return loader
        }
    }

    fun trimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        ) {
            imageLoaderInstance?.memoryCache?.clear()
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            httpClientInstance?.connectionPool?.evictAll()
        }
    }

    fun onLowMemory() {
        imageLoaderInstance?.memoryCache?.clear()
        httpClientInstance?.connectionPool?.evictAll()
    }
}

/**
 * Lightweight stale-while-revalidate JSON cache on cacheDir so Home & categories
 * render immediately on Frame 1 while fresh data fetches silently off the main thread.
 */
object FeedCache {
    private const val CACHE_DIR = "feed_swr"

    private fun safeKey(chip: String): String =
        chip.replace(Regex("[^a-zA-Z0-9._-]"), "_")

    private fun cacheFile(context: Context, name: String): File {
        val dir = File(context.cacheDir, CACHE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$name.json")
    }

    private fun writeAtomic(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            target.writeText(content)
            tmp.delete()
        }
    }

    suspend fun readHomeFeed(context: Context, chip: String): FeedResponseDto? =
        withContext(Dispatchers.IO) {
            runCatching {
                val f = cacheFile(context, "home_${safeKey(chip)}")
                if (!f.exists()) return@withContext null
                NativeBridge.json.decodeFromString<FeedResponseDto>(f.readText())
            }.getOrNull()
        }

    suspend fun writeHomeFeed(context: Context, chip: String, feed: FeedResponseDto) {
        withContext(Dispatchers.IO) {
            runCatching {
                val trimmed = feed.copy(items = feed.items.take(48))
                writeAtomic(
                    cacheFile(context, "home_${safeKey(chip)}"),
                    NativeBridge.json.encodeToString(trimmed)
                )
            }
        }
    }

    suspend fun readExploreCategories(context: Context): List<ExploreCategoryDto>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val f = cacheFile(context, "explore_cats")
                if (!f.exists()) return@withContext null
                NativeBridge.json.decodeFromString<List<ExploreCategoryDto>>(f.readText())
            }.getOrNull()
        }

    suspend fun writeExploreCategories(context: Context, cats: List<ExploreCategoryDto>) {
        withContext(Dispatchers.IO) {
            runCatching {
                writeAtomic(
                    cacheFile(context, "explore_cats"),
                    NativeBridge.json.encodeToString(cats)
                )
            }
        }
    }

    suspend fun readSettings(context: Context): SettingsDto? =
        withContext(Dispatchers.IO) {
            runCatching {
                val f = cacheFile(context, "settings")
                if (!f.exists()) return@withContext null
                NativeBridge.json.decodeFromString<SettingsDto>(f.readText())
            }.getOrNull()
        }

    suspend fun writeSettings(context: Context, settings: SettingsDto) {
        withContext(Dispatchers.IO) {
            runCatching {
                writeAtomic(
                    cacheFile(context, "settings"),
                    NativeBridge.json.encodeToString(settings)
                )
            }
        }
    }
}
