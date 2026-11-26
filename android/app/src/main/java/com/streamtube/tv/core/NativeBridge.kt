package com.streamtube.tv.core

import com.streamtube.tv.StreamTubeApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object NativeBridge {
    init {
        System.loadLibrary("streamtube_core")
    }

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    private external fun nativeInit(filesDir: String, cacheDir: String): String
    private external fun nativeGetSettings(): String
    private external fun nativeUpdateSettings(settingsJson: String): String
    private external fun nativeGetExploreCategories(): String
    private external fun nativeGetHomeFeed(chip: String, continuation: String?): String
    private external fun nativeGetShortsFeed(token: String?, seed: String?): String
    private external fun nativeSearch(query: String, filterJson: String?, continuation: String?): String
    private external fun nativeSearchSuggestions(query: String): String
    private external fun nativeClearSearchHistory(): String
    private external fun nativeRemoveSearchHistory(query: String): String
    private external fun nativeGetWatchBundle(videoId: String, playlistId: String?): String
    private external fun nativeResolvePlayback(
        videoId: String,
        maxHeight: Int,
        codecPref: String,
        audioLang: String?,
        audioOnly: Boolean
    ): String
    private external fun nativeGetComments(token: String): String
    private external fun nativeGetRelatedContinue(token: String): String
    private external fun nativeGetLiveChat(token: String): String
    private external fun nativeSendLiveChat(channelId: String, videoId: String, message: String): String
    private external fun nativeReportSponsorViewed(uuid: String): String
    private external fun nativeVoteSponsorSegment(uuid: String, up: Boolean): String
    private external fun nativeGetChannel(channelId: String, tabParams: String?): String
    private external fun nativeGetChannelContinue(token: String): String
    private external fun nativeSearchChannel(channelId: String, query: String): String
    private external fun nativeGetPlaylist(playlistId: String): String
    private external fun nativeResolveUrl(url: String): String
    private external fun nativeGetSubscriptions(): String
    private external fun nativeSubscribe(channelId: String, name: String, avatar: String?): String
    private external fun nativeUnsubscribe(channelId: String): String
    private external fun nativeRefreshSubscriptionsFeed(): String
    private external fun nativeGetCachedSubscriptionsFeed(): String
    private external fun nativeGetHistory(query: String, limit: Int): String
    private external fun nativeRecordWatchStart(videoJson: String, durationSecs: Double): String
    private external fun nativeSaveWatchProgress(
        videoId: String,
        progress: Double,
        duration: Double,
        playing: Boolean
    ): String
    private external fun nativeReportWatchStop(videoId: String, progress: Double, duration: Double): String
    private external fun nativeRemoveHistoryItem(videoId: String): String
    private external fun nativeClearHistory(): String
    private external fun nativeGetLocalPlaylists(): String
    private external fun nativeGetLocalPlaylistItems(playlistId: Long): String
    private external fun nativeCreateLocalPlaylist(name: String): String
    private external fun nativeDeleteLocalPlaylist(playlistId: Long): String
    private external fun nativeTogglePlaylistItem(playlistId: Long, videoJson: String): String
    private external fun nativeSetVideoRating(videoId: String, rating: Int): String
    private external fun nativeSetChannelSpeed(channelId: String, speed: Double): String
    private external fun nativeGetAuthStatus(): String
    private external fun nativeStartTvLogin(): String
    private external fun nativePollTvLogin(deviceCodeJson: String): String
    private external fun nativeSignOutTv(): String
    private external fun nativeSyncTvAccount(): String
    private external fun nativeImportCookiesTxt(content: String): String
    private external fun nativeDisconnectWebSession(): String
    private external fun nativeImportContent(content: String): String
    private external fun nativeExportBackupJson(): String
    private external fun nativeCheckGitHubUpdate(currentVersion: String): String
    private external fun nativeDownloadApk(url: String, destPath: String, expectedSha256: String?): String

    private inline fun <reified T> decode(raw: String): Result<T> {
        return try {
            val env = json.decodeFromString<Envelope<T>>(raw)
            if (env.ok && env.data != null) {
                Result.success(env.data)
            } else {
                Result.failure(IllegalStateException(env.error ?: "Unknown native error"))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private suspend inline fun <reified T> callNative(crossinline block: () -> String): Result<T> =
        withContext(Dispatchers.IO) {
            StreamTubeApp.instance.awaitCoreReady()
            decode(block())
        }

    fun initSync(filesDir: String, cacheDir: String): Result<InitInfoDto> =
        decode(nativeInit(filesDir, cacheDir))

    suspend fun getSettings(): Result<SettingsDto> = callNative { nativeGetSettings() }

    suspend fun updateSettings(settings: SettingsDto): Result<SettingsDto> =
        callNative { nativeUpdateSettings(json.encodeToString(settings)) }

    suspend fun getExploreCategories(): Result<List<ExploreCategoryDto>> =
        callNative { nativeGetExploreCategories() }

    suspend fun getHomeFeed(chip: String, continuation: String? = null): Result<FeedResponseDto> =
        callNative { nativeGetHomeFeed(chip, continuation) }

    suspend fun getShortsFeed(token: String? = null, seed: String? = null): Result<FeedResponseDto> =
        callNative { nativeGetShortsFeed(token, seed) }

    suspend fun search(
        query: String,
        filter: SearchFilterDto = SearchFilterDto(),
        continuation: String? = null
    ): Result<FeedResponseDto> =
        callNative { nativeSearch(query, json.encodeToString(filter), continuation) }

    suspend fun searchSuggestions(query: String): Result<List<String>> =
        callNative { nativeSearchSuggestions(query) }

    suspend fun clearSearchHistory(): Result<Boolean> = callNative { nativeClearSearchHistory() }

    suspend fun removeSearchHistory(query: String): Result<Boolean> =
        callNative { nativeRemoveSearchHistory(query) }

    suspend fun getWatchBundle(videoId: String, playlistId: String? = null): Result<WatchBundleDto> =
        callNative { nativeGetWatchBundle(videoId, playlistId) }

    suspend fun resolvePlayback(
        videoId: String,
        maxHeight: Int,
        codecPref: String,
        audioLang: String?,
        audioOnly: Boolean
    ): Result<PlaybackPlanDto> =
        callNative { nativeResolvePlayback(videoId, maxHeight, codecPref, audioLang, audioOnly) }

    suspend fun getComments(token: String): Result<CommentPageDto> =
        callNative { nativeGetComments(token) }

    suspend fun getRelatedContinue(token: String): Result<FeedResponseDto> =
        callNative { nativeGetRelatedContinue(token) }

    suspend fun getLiveChat(token: String): Result<ChatBatchDto> =
        callNative { nativeGetLiveChat(token) }

    suspend fun sendLiveChat(channelId: String, videoId: String, message: String): Result<Boolean> =
        callNative { nativeSendLiveChat(channelId, videoId, message) }

    fun reportSponsorViewedAsync(uuid: String) {
        if (StreamTubeApp.instance.isCoreInitialized) {
            nativeReportSponsorViewed(uuid)
        }
    }

    suspend fun voteSponsorSegment(uuid: String, up: Boolean): Result<Boolean> =
        callNative { nativeVoteSponsorSegment(uuid, up) }

    suspend fun getChannel(channelId: String, tabParams: String? = null): Result<ChannelResponseDto> =
        callNative { nativeGetChannel(channelId, tabParams) }

    suspend fun getChannelContinue(token: String): Result<FeedResponseDto> =
        callNative { nativeGetChannelContinue(token) }

    suspend fun searchChannel(channelId: String, query: String): Result<FeedResponseDto> =
        callNative { nativeSearchChannel(channelId, query) }

    suspend fun getPlaylist(playlistId: String): Result<PlaylistResponseDto> =
        callNative { nativeGetPlaylist(playlistId) }

    suspend fun resolveUrl(url: String): Result<ResolvedUrlDto> =
        callNative { nativeResolveUrl(url) }

    suspend fun getSubscriptions(): Result<List<SubscriptionDto>> =
        callNative { nativeGetSubscriptions() }

    suspend fun subscribe(channelId: String, name: String, avatar: String?): Result<Boolean> =
        callNative { nativeSubscribe(channelId, name, avatar) }

    suspend fun unsubscribe(channelId: String): Result<Boolean> =
        callNative { nativeUnsubscribe(channelId) }

    suspend fun refreshSubscriptionsFeed(): Result<FeedResponseDto> =
        callNative { nativeRefreshSubscriptionsFeed() }

    suspend fun getCachedSubscriptionsFeed(): Result<FeedResponseDto> =
        callNative { nativeGetCachedSubscriptionsFeed() }

    suspend fun getHistory(query: String = "", limit: Int = 200): Result<List<HistoryEntryDto>> =
        callNative { nativeGetHistory(query, limit) }

    suspend fun recordWatchStart(video: VideoItemDto, durationSecs: Double): Result<Boolean> =
        callNative { nativeRecordWatchStart(json.encodeToString(video), durationSecs) }

    suspend fun saveWatchProgress(
        videoId: String,
        progress: Double,
        duration: Double,
        playing: Boolean
    ): Result<Boolean> =
        callNative { nativeSaveWatchProgress(videoId, progress, duration, playing) }

    suspend fun reportWatchStop(videoId: String, progress: Double, duration: Double): Result<Boolean> =
        callNative { nativeReportWatchStop(videoId, progress, duration) }

    suspend fun removeHistoryItem(videoId: String): Result<Boolean> =
        callNative { nativeRemoveHistoryItem(videoId) }

    suspend fun clearHistory(): Result<Boolean> = callNative { nativeClearHistory() }

    suspend fun getLocalPlaylists(): Result<List<LocalPlaylistDto>> =
        callNative { nativeGetLocalPlaylists() }

    suspend fun getLocalPlaylistItems(playlistId: Long): Result<FeedResponseDto> =
        callNative { nativeGetLocalPlaylistItems(playlistId) }

    suspend fun createLocalPlaylist(name: String): Result<Long> =
        callNative { nativeCreateLocalPlaylist(name) }

    suspend fun deleteLocalPlaylist(playlistId: Long): Result<Boolean> =
        callNative { nativeDeleteLocalPlaylist(playlistId) }

    suspend fun togglePlaylistItem(playlistId: Long, video: VideoItemDto): Result<Boolean> =
        callNative { nativeTogglePlaylistItem(playlistId, json.encodeToString(video)) }

    suspend fun setVideoRating(videoId: String, rating: Int): Result<Int> =
        callNative { nativeSetVideoRating(videoId, rating) }

    suspend fun setChannelSpeed(channelId: String, speed: Double): Result<Boolean> =
        callNative { nativeSetChannelSpeed(channelId, speed) }

    suspend fun getAuthStatus(): Result<AuthStatusDto> =
        callNative { nativeGetAuthStatus() }

    suspend fun startTvLogin(): Result<DeviceCodeDto> =
        callNative { nativeStartTvLogin() }

    suspend fun pollTvLogin(dc: DeviceCodeDto): Result<PollResultDto> =
        callNative { nativePollTvLogin(json.encodeToString(dc)) }

    suspend fun signOutTv(): Result<Boolean> = callNative { nativeSignOutTv() }

    suspend fun syncTvAccount(): Result<SyncAccountResultDto> =
        callNative { nativeSyncTvAccount() }

    suspend fun importCookiesTxt(content: String): Result<String> =
        callNative { nativeImportCookiesTxt(content) }

    suspend fun disconnectWebSession(): Result<Boolean> =
        callNative { nativeDisconnectWebSession() }

    suspend fun importContent(content: String): Result<ImportReportDto> =
        callNative { nativeImportContent(content) }

    suspend fun exportBackupJson(): Result<String> =
        callNative { nativeExportBackupJson() }

    suspend fun checkGitHubUpdate(currentVersion: String): Result<UpdateCheckDto> =
        callNative { nativeCheckGitHubUpdate(currentVersion) }

    suspend fun downloadApk(url: String, destPath: String, expectedSha256: String?): Result<String> =
        callNative { nativeDownloadApk(url, destPath, expectedSha256) }
}
