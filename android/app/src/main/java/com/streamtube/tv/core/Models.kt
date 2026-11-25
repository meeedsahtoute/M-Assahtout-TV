package com.streamtube.tv.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class Envelope<T>(
    val ok: Boolean,
    val data: T? = null,
    val error: String? = null
)

@Serializable
data class SettingsDto(
    val theme: String = "dark",
    @SerialName("app_language") val appLanguage: String = "en",
    val accent: Long = 0xff0033,
    @SerialName("start_page") val startPage: String = "home",
    @SerialName("grid_columns") val gridColumns: Int = 0,
    val region: String = "US",
    val language: String = "en",
    @SerialName("stream_backend") val streamBackend: String = "auto",
    @SerialName("display_server") val displayServer: String = "auto",
    @SerialName("ytdlp_path") val ytdlpPath: String = "",
    val proxy: String = "",
    @SerialName("theater_mode") val theaterMode: Boolean = false,
    @SerialName("default_quality") val defaultQuality: Int = 1080,
    @SerialName("codec_pref") val codecPref: String = "vp9",
    @SerialName("default_speed") val defaultSpeed: Double = 1.0,
    @SerialName("remember_speed_per_channel") val rememberSpeedPerChannel: Boolean = true,
    @SerialName("autoplay_next") val autoplayNext: Boolean = true,
    @SerialName("resume_playback") val resumePlayback: Boolean = true,
    val volume: Double = 80.0,
    val hwdec: Boolean = true,
    @SerialName("audio_only") val audioOnly: Boolean = false,
    @SerialName("captions_enabled") val captionsEnabled: Boolean = false,
    @SerialName("captions_lang") val captionsLang: String = "en",
    @SerialName("loop_video") val loopVideo: Boolean = false,
    @SerialName("seek_step") val seekStep: Double = 5.0,
    val sponsorblock: Boolean = true,
    @SerialName("sponsorblock_server") val sponsorblockServer: String = "https://sponsor.ajay.app",
    @SerialName("sb_actions") val sbActions: Map<String, String> = emptyMap(),
    @SerialName("sb_min_duration") val sbMinDuration: Double = 0.0,
    @SerialName("sb_notify") val sbNotify: Boolean = true,
    @SerialName("dearrow_titles") val dearrowTitles: Boolean = false,
    @SerialName("dearrow_thumbnails") val dearrowThumbnails: Boolean = false,
    val ryd: Boolean = true,
    @SerialName("hide_shorts") val hideShorts: Boolean = false,
    @SerialName("hide_live") val hideLive: Boolean = false,
    @SerialName("hide_upcoming") val hideUpcoming: Boolean = false,
    @SerialName("hide_comments") val hideComments: Boolean = false,
    @SerialName("hide_related") val hideRelated: Boolean = false,
    @SerialName("hide_watched_in_feed") val hideWatchedInFeed: Boolean = false,
    @SerialName("hide_view_counts") val hideViewCounts: Boolean = false,
    @SerialName("hide_description") val hideDescription: Boolean = false,
    @SerialName("hide_channel_avatars") val hideChannelAvatars: Boolean = false,
    @SerialName("hide_chapters") val hideChapters: Boolean = false,
    @SerialName("hide_explore") val hideExplore: Boolean = false,
    @SerialName("blocked_keywords") val blockedKeywords: List<String> = emptyList(),
    @SerialName("blocked_channels") val blockedChannels: List<List<String>> = emptyList(),
    @SerialName("history_enabled") val historyEnabled: Boolean = true,
    @SerialName("search_history_enabled") val searchHistoryEnabled: Boolean = true,
    @SerialName("check_updates") val checkUpdates: Boolean = true,
    @SerialName("feed_source") val feedSource: String = "full",
    @SerialName("feed_include_shorts") val feedIncludeShorts: Boolean = false,
    @SerialName("feed_include_live") val feedIncludeLive: Boolean = true,
    @SerialName("download_dir") val downloadDir: String = "",
    @SerialName("download_dir_chosen") val downloadDirChosen: Boolean = false,
    @SerialName("download_format") val downloadFormat: String = "1080",
    @SerialName("external_player") val externalPlayer: String = "",
    @SerialName("ui_scale") val uiScale: Float = 1.0f,
    val density: String = "default",
    @SerialName("thumb_radius") val thumbRadius: Float = 12.0f,
    @SerialName("caption_size") val captionSize: Int = 40,
    @SerialName("caption_background") val captionBackground: Boolean = true,
    @SerialName("caption_font") val captionFont: String = "sans",
    @SerialName("caption_color") val captionColor: Long = 0xFFFFFF,
    @SerialName("caption_opacity") val captionOpacity: Int = 100,
    @SerialName("caption_bg_color") val captionBgColor: Long = 0x000000,
    @SerialName("caption_bg_opacity") val captionBgOpacity: Int = 75,
    @SerialName("caption_edge") val captionEdge: String = "outline-shadow",
    @SerialName("controls_hide_ms") val controlsHideMs: Long = 2500,
    @SerialName("show_storyboard") val showStoryboard: Boolean = true,
    @SerialName("sb_user_id") val sbUserId: String = "",
    @SerialName("show_heatmap") val showHeatmap: Boolean = true,
    @SerialName("jump_ahead") val jumpAhead: Boolean = true,
    @SerialName("enhanced_bitrate") val enhancedBitrate: Boolean = false,
    @SerialName("ipv4_only") val ipv4Only: Boolean = true,
    @SerialName("background_play") val backgroundPlay: Boolean = true,
    @SerialName("ambient_mode") val ambientMode: Boolean = true,
    @SerialName("shorts_in_player") val shortsInPlayer: Boolean = false,
    @SerialName("shorts_autoadvance") val shortsAutoadvance: Boolean = false,
    @SerialName("shorts_preload") val shortsPreload: Int = 1,
    @SerialName("shorts_yt_layout") val shortsYtLayout: Boolean = false,
    @SerialName("shorts_edge_nav") val shortsEdgeNav: Boolean = true,
    @SerialName("shorts_show_subscribe") val shortsShowSubscribe: Boolean = true,
    @SerialName("shorts_show_dislike") val shortsShowDislike: Boolean = true,
    @SerialName("remember_quality") val rememberQuality: Boolean = false,
    @SerialName("exit_fullscreen_at_end") val exitFullscreenAtEnd: Boolean = true,
    @SerialName("controls_shade") val controlsShade: String = "default",
    @SerialName("double_click_fullscreen") val doubleClickFullscreen: Boolean = true,
    @SerialName("original_audio") val originalAudio: Boolean = true,
    @SerialName("hover_preview") val hoverPreview: Boolean = true,
    val annotations: Boolean = true,
    @SerialName("aspect_mode") val aspectMode: String = "original",
    @SerialName("always_scrollbars") val alwaysScrollbars: Boolean = false
)

@Serializable
data class InitInfoDto(
    @SerialName("core_version") val coreVersion: String,
    val settings: SettingsDto
)

@Serializable
data class ExploreCategoryDto(
    val name: String,
    @SerialName("channel_id") val channelId: String
)

@Serializable
data class VideoItemDto(
    val id: String = "",
    val title: String = "",
    @SerialName("channel_name") val channelName: String = "",
    @SerialName("channel_id") val channelId: String? = null,
    @SerialName("channel_avatar") val channelAvatar: String? = null,
    val thumbnail: String = "",
    @SerialName("compact_thumbnail") val compactThumbnail: String? = null,
    @SerialName("duration_text") val durationText: String? = null,
    @SerialName("duration_secs") val durationSecs: Long? = null,
    @SerialName("views_text") val viewsText: String? = null,
    @SerialName("published_text") val publishedText: String? = null,
    @SerialName("published_ts") val publishedTs: Long? = null,
    @SerialName("description_snippet") val descriptionSnippet: String? = null,
    @SerialName("is_live") val isLive: Boolean = false,
    @SerialName("is_short") val isShort: Boolean = false,
    @SerialName("is_upcoming") val isUpcoming: Boolean = false
)

@Serializable
data class ChannelItemDto(
    val id: String = "",
    val name: String = "",
    val avatar: String? = null,
    val handle: String? = null,
    @SerialName("subscribers_text") val subscribersText: String? = null,
    @SerialName("video_count_text") val videoCountText: String? = null,
    val description: String? = null
)

@Serializable
data class PlaylistItemDto(
    val id: String = "",
    val title: String = "",
    val thumbnail: String? = null,
    @SerialName("channel_name") val channelName: String? = null,
    @SerialName("channel_id") val channelId: String? = null,
    @SerialName("video_count_text") val videoCountText: String? = null,
    @SerialName("first_video_id") val firstVideoId: String? = null
)

@Serializable
data class RawItemDto(
    val Video: VideoItemDto? = null,
    val Channel: ChannelItemDto? = null,
    val Playlist: PlaylistItemDto? = null
)

@Serializable
data class ProgressEntryDto(
    val progress: Double = 0.0,
    val duration: Double = 0.0
)

@Serializable
data class FeedResponseDto(
    val items: List<RawItemDto> = emptyList(),
    val continuation: String? = null,
    val progress: Map<String, ProgressEntryDto> = emptyMap()
)

@Serializable
data class SearchFilterDto(
    val sort: Int = 0,
    val date: Int = 0,
    val kind: Int = 0,
    val duration: Int = 0,
    val hd: Boolean = false,
    val subtitles: Boolean = false,
    @SerialName("creative_commons") val creativeCommons: Boolean = false,
    val live: Boolean = false,
    @SerialName("four_k") val fourK: Boolean = false,
    val hdr: Boolean = false
)

@Serializable
data class ChapterDto(
    val title: String = "",
    val start: Double = 0.0,
    val thumbnail: String? = null
)

@Serializable
data class WatchPlaylistDto(
    val id: String = "",
    val title: String = "",
    val items: List<VideoItemDto> = emptyList()
)

@Serializable
data class WatchInfoDto(
    val id: String = "",
    val title: String = "",
    @SerialName("views_text") val viewsText: String = "",
    @SerialName("date_text") val dateText: String = "",
    @SerialName("likes_text") val likesText: String? = null,
    val description: String = "",
    val channel: ChannelItemDto = ChannelItemDto(),
    val chapters: List<ChapterDto> = emptyList(),
    val heatmap: List<List<Double>> = emptyList(),
    val related: List<RawItemDto> = emptyList(),
    @SerialName("related_continuation") val relatedContinuation: String? = null,
    @SerialName("comments_token") val commentsToken: String? = null,
    @SerialName("comments_sort") val commentsSort: List<String?> = emptyList(),
    @SerialName("comment_count_text") val commentCountText: String? = null,
    @SerialName("is_live") val isLive: Boolean = false,
    @SerialName("live_chat") val liveChat: List<String>? = null,
    val playlist: WatchPlaylistDto? = null
)

@Serializable
data class StreamFormatDto(
    val itag: Int = 0,
    val url: String = "",
    val mime: String = "",
    val codecs: String = "",
    val bitrate: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    @SerialName("quality_label") val qualityLabel: String = "",
    @SerialName("audio_language") val audioLanguage: String? = null,
    @SerialName("audio_default") val audioDefault: Boolean = true,
    @SerialName("content_length") val contentLength: Long? = null,
    @SerialName("is_audio") val isAudio: Boolean = false,
    @SerialName("is_muxed") val isMuxed: Boolean = false
)

@Serializable
data class CaptionTrackDto(
    val lang: String = "",
    val name: String = "",
    val url: String = "",
    val auto: Boolean = false
)

@Serializable
data class StoryboardDto(
    @SerialName("url_template") val urlTemplate: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val count: Int = 0,
    val cols: Int = 0,
    val rows: Int = 0,
    @SerialName("interval_ms") val intervalMs: Int = 0
)

@Serializable
data class StreamInfoDto(
    val id: String = "",
    val title: String = "",
    val author: String = "",
    @SerialName("channel_id") val channelId: String = "",
    @SerialName("length_secs") val lengthSecs: Long = 0,
    @SerialName("view_count") val viewCount: Long? = null,
    val description: String = "",
    @SerialName("is_live") val isLive: Boolean = false,
    @SerialName("hls_url") val hlsUrl: String? = null,
    val video: List<StreamFormatDto> = emptyList(),
    val audio: List<StreamFormatDto> = emptyList(),
    val muxed: List<StreamFormatDto> = emptyList(),
    val captions: List<CaptionTrackDto> = emptyList(),
    @SerialName("original_language") val originalLanguage: String? = null,
    @SerialName("default_caption_index") val defaultCaptionIndex: Int? = null,
    val storyboard: StoryboardDto? = null,
    val source: String = "",
    @SerialName("hls_only") val hlsOnly: Boolean = false
)

@Serializable
data class PlaybackPlanDto(
    val mode: String = "none",
    @SerialName("video_url") val videoUrl: String? = null,
    @SerialName("audio_url") val audioUrl: String? = null,
    @SerialName("hls_url") val hlsUrl: String? = null,
    @SerialName("quality_label") val qualityLabel: String = "",
    val codecs: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    @SerialName("video_bitrate") val videoBitrate: Long = 0,
    @SerialName("audio_bitrate") val audioBitrate: Long = 0,
    @SerialName("audio_codec") val audioCodec: String = "",
    val source: String = "",
    val captions: List<CaptionTrackDto> = emptyList()
)

@Serializable
data class SegmentDto(
    val category: String = "",
    @SerialName("category_name") val categoryName: String = "",
    val color: Long = 0x00d400,
    val action: String = "skip",
    @SerialName("user_action") val userAction: String = "skip",
    val start: Double = 0.0,
    val end: Double = 0.0,
    val uuid: String = ""
)

@Serializable
data class BrandingDto(
    val title: String? = null,
    @SerialName("thumbnail_time") val thumbnailTime: Double? = null
)

@Serializable
data class VotesDto(
    val likes: Long = 0,
    val dislikes: Long = 0,
    val views: Long = 0,
    val rating: Double = 0.0
)

@Serializable
data class WatchBundleDto(
    val watch: WatchInfoDto,
    val streams: StreamInfoDto,
    val playback: PlaybackPlanDto,
    val segments: List<SegmentDto> = emptyList(),
    val dearrow: BrandingDto? = null,
    @SerialName("dearrow_thumbnail_url") val dearrowThumbnailUrl: String? = null,
    val votes: VotesDto? = null,
    @SerialName("resume_secs") val resumeSecs: Double = 0.0,
    @SerialName("channel_speed") val channelSpeed: Double? = null,
    val rating: Int = 0,
    @SerialName("is_subscribed") val isSubscribed: Boolean = false,
    @SerialName("in_watch_later") val inWatchLater: Boolean = false,
    @SerialName("in_favorites") val inFavorites: Boolean = false
)

@Serializable
data class CommentDto(
    val id: String = "",
    val author: String = "",
    @SerialName("author_id") val authorId: String? = null,
    @SerialName("author_avatar") val authorAvatar: String? = null,
    val text: String = "",
    val published: String = "",
    val likes: String = "",
    @SerialName("reply_count") val replyCount: String = "",
    @SerialName("replies_token") val repliesToken: String? = null,
    @SerialName("is_creator") val isCreator: Boolean = false,
    @SerialName("is_verified") val isVerified: Boolean = false,
    @SerialName("is_pinned") val isPinned: Boolean = false,
    val hearted: Boolean = false,
    val emojis: List<List<String>> = emptyList(),
    val image: List<JsonElement>? = null
)

@Serializable
data class CommentPageDto(
    val comments: List<CommentDto> = emptyList(),
    val continuation: String? = null,
    @SerialName("count_text") val countText: String? = null,
    @SerialName("sort_top_token") val sortTopToken: String? = null,
    @SerialName("sort_new_token") val sortNewToken: String? = null
)

@Serializable
data class ChatMessageDto(
    val id: String = "",
    val author: String = "",
    @SerialName("author_photo") val authorPhoto: String? = null,
    val text: String = "",
    val role: String = "Viewer",
    val verified: Boolean = false,
    val paid: String? = null
)

@Serializable
data class ChatBatchDto(
    val messages: List<ChatMessageDto> = emptyList(),
    val next: String? = null,
    @SerialName("timeout_ms") val timeoutMs: Long = 5000
)

@Serializable
data class ChannelTabDto(
    val title: String = "",
    val kind: String = "",
    val params: String? = null,
    val selected: Boolean = false
)

@Serializable
data class PageDto(
    val items: List<RawItemDto> = emptyList(),
    val continuation: String? = null
)

@Serializable
data class ChannelPageDto(
    val info: ChannelItemDto = ChannelItemDto(),
    val banner: String? = null,
    val tabs: List<ChannelTabDto> = emptyList(),
    val page: PageDto = PageDto()
)

@Serializable
data class ChannelResponseDto(
    val channel: ChannelPageDto,
    @SerialName("is_subscribed") val isSubscribed: Boolean = false,
    val progress: Map<String, ProgressEntryDto> = emptyMap()
)

@Serializable
data class PlaylistPageDto(
    val info: PlaylistItemDto = PlaylistItemDto(),
    val description: String? = null,
    val videos: List<VideoItemDto> = emptyList(),
    val continuation: String? = null
)

@Serializable
data class PlaylistResponseDto(
    val playlist: PlaylistPageDto,
    val progress: Map<String, ProgressEntryDto> = emptyMap()
)

@Serializable
data class SubscriptionDto(
    @SerialName("channel_id") val channelId: String = "",
    val name: String = "",
    val avatar: String? = null
)

@Serializable
data class HistoryEntryDto(
    val video: VideoItemDto,
    @SerialName("watched_at") val watchedAt: Long = 0,
    val progress: Double = 0.0,
    val duration: Double = 0.0
)

@Serializable
data class LocalPlaylistDto(
    val id: Long = 0,
    val name: String = "",
    val protected: Boolean = false,
    val count: Int = 0,
    val thumbnail: String? = null
)

@Serializable
data class AccountDto(
    val name: String = "",
    val handle: String? = null,
    val avatar: String? = null
)

@Serializable
data class AuthStatusDto(
    @SerialName("tv_signed_in") val tvSignedIn: Boolean = false,
    @SerialName("tv_account") val tvAccount: AccountDto? = null,
    @SerialName("web_connected") val webConnected: Boolean = false,
    @SerialName("web_account_name") val webAccountName: String? = null
)

@Serializable
data class DeviceCodeDto(
    @SerialName("device_code") val deviceCode: String = "",
    @SerialName("user_code") val userCode: String = "",
    @SerialName("verification_url") val verificationUrl: String = "",
    val interval: Long = 5,
    @SerialName("expires_in") val expiresIn: Long = 1800
)

@Serializable
data class PollResultDto(
    val status: String = "pending",
    val account: AccountDto? = null
)

@Serializable
data class SyncAccountResultDto(
    @SerialName("subscriptions_added") val subscriptionsAdded: Int = 0,
    @SerialName("history_added") val historyAdded: Int = 0,
    @SerialName("searches_added") val searchesAdded: Int = 0
)

@Serializable
data class ImportReportDto(
    val subscriptions: Int = 0,
    val history: Int = 0,
    val playlists: Int = 0,
    @SerialName("playlist_videos") val playlistVideos: Int = 0
)

@Serializable
data class ResolvedUrlDto(
    val kind: String = "",
    val id: String = ""
)

@Serializable
data class UpdateCheckDto(
    @SerialName("has_update") val hasUpdate: Boolean = false,
    @SerialName("current_version") val currentVersion: String = "",
    val tag: String = "",
    val name: String = "",
    val notes: String = "",
    val page: String = "",
    val prerelease: Boolean = false,
    @SerialName("apk_url") val apkUrl: String? = null,
    @SerialName("apk_name") val apkName: String? = null,
    @SerialName("apk_size") val apkSize: Long = 0,
    val sha256: String? = null
)
