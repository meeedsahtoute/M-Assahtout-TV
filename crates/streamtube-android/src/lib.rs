use anyhow::{Context, Result, anyhow};
use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jdouble, jint, jlong, jstring};
use serde::Serialize;
use serde_json::{Value, json};
use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use streamtube_core::api::{auth, http, innertube, json as yt_json, services, streams, types::*, web};
use streamtube_core::{feed, highlights, import, links, proxy, recommend, store, transcript};

const UPDATE_REPO: &str = "meeedsahtoute/M-Assahtout-TV";

fn opt_str(env: &mut JNIEnv, s: JString) -> Option<String> {
    if s.is_null() {
        return None;
    }
    env.get_string(&s).ok().map(String::from)
}

fn req_str(env: &mut JNIEnv, s: JString) -> String {
    opt_str(env, s).unwrap_or_default()
}

fn to_jstring(env: &mut JNIEnv, out: String) -> jstring {
    env.new_string(out)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[derive(Serialize)]
struct Envelope<T: Serialize> {
    ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    data: Option<T>,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
}

fn jni_json<T: Serialize>(env: &mut JNIEnv, f: impl FnOnce() -> Result<T>) -> jstring {
    let payload = match std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)) {
        Ok(Ok(data)) => serde_json::to_string(&Envelope {
            ok: true,
            data: Some(data),
            error: None,
        })
        .unwrap_or_else(|e| {
            serde_json::to_string(&Envelope::<()> {
                ok: false,
                data: None,
                error: Some(format!("json encode failed: {e}")),
            })
            .unwrap()
        }),
        Ok(Err(e)) => serde_json::to_string(&Envelope::<()> {
            ok: false,
            data: None,
            error: Some(format!("{e:#}")),
        })
        .unwrap(),
        Err(_) => r#"{"ok":false,"error":"native rust panic"}"#.to_string(),
    };
    to_jstring(env, payload)
}

// ───────────────────────── DTOs ─────────────────────────

#[derive(Serialize)]
struct InitInfo {
    core_version: &'static str,
    settings: store::Settings,
}

#[derive(Serialize)]
struct ExploreCategory {
    name: &'static str,
    channel_id: &'static str,
}

#[derive(Serialize)]
struct ProgressEntry {
    progress: f64,
    duration: f64,
}

#[derive(Serialize)]
struct FeedResponse {
    items: Vec<Item>,
    continuation: Option<String>,
    progress: HashMap<String, ProgressEntry>,
}

#[derive(Serialize)]
struct ChannelResponse {
    channel: ChannelPage,
    is_subscribed: bool,
    progress: HashMap<String, ProgressEntry>,
}

#[derive(Serialize)]
struct PlaylistResponse {
    playlist: PlaylistPage,
    progress: HashMap<String, ProgressEntry>,
}

#[derive(Serialize)]
struct SegmentDto {
    category: String,
    category_name: &'static str,
    color: u32,
    action: String,
    user_action: String,
    start: f64,
    end: f64,
    uuid: String,
}

#[derive(Serialize)]
struct PlaybackCaptionDto {
    lang: String,
    name: String,
    url: String,
    auto: bool,
}

#[derive(Serialize)]
struct PlaybackPlan {
    mode: &'static str,
    video_url: Option<String>,
    audio_url: Option<String>,
    hls_url: Option<String>,
    quality_label: String,
    codecs: String,
    width: u32,
    height: u32,
    fps: u32,
    video_bitrate: u64,
    audio_bitrate: u64,
    audio_codec: String,
    source: &'static str,
    captions: Vec<PlaybackCaptionDto>,
}

#[derive(Serialize)]
struct WatchBundleDto {
    watch: WatchInfo,
    streams: StreamInfo,
    playback: PlaybackPlan,
    segments: Vec<SegmentDto>,
    dearrow: Option<services::Branding>,
    dearrow_thumbnail_url: Option<String>,
    votes: Option<services::Votes>,
    resume_secs: f64,
    channel_speed: Option<f64>,
    channel_skip_silence: Option<bool>,
    highlights: Vec<highlights::HighlightPeak>,
    rating: i8,
    is_subscribed: bool,
    in_watch_later: bool,
    in_favorites: bool,
}

#[derive(Serialize)]
struct AuthStatusDto {
    tv_signed_in: bool,
    tv_account: Option<auth::Account>,
    web_connected: bool,
    web_account_name: Option<String>,
}

#[derive(Serialize)]
struct PollResultDto {
    status: &'static str,
    account: Option<auth::Account>,
}

#[derive(Serialize)]
struct SyncAccountResult {
    subscriptions_added: usize,
    history_added: usize,
    searches_added: usize,
}

#[derive(Serialize)]
struct ResolvedUrlDto {
    kind: &'static str,
    id: String,
}

#[derive(Serialize)]
struct UpdateCheckDto {
    has_update: bool,
    current_version: String,
    tag: String,
    name: String,
    notes: String,
    page: String,
    prerelease: bool,
    apk_url: Option<String>,
    apk_name: Option<String>,
    apk_size: u64,
    sha256: Option<String>,
}

// ───────────────────────── Helpers ─────────────────────────

fn progress_for_items(items: &[Item]) -> HashMap<String, ProgressEntry> {
    let map = store::progress_map();
    let mut out = HashMap::new();
    for it in items {
        if let Item::Video(v) = it {
            if let Some(&(progress, duration)) = map.get(&v.id) {
                if progress > 0.0 || duration > 0.0 {
                    out.insert(v.id.clone(), ProgressEntry { progress, duration });
                }
            }
        }
    }
    out
}

fn progress_for_videos(videos: &[VideoItem]) -> HashMap<String, ProgressEntry> {
    let map = store::progress_map();
    let mut out = HashMap::new();
    for v in videos {
        if let Some(&(progress, duration)) = map.get(&v.id) {
            if progress > 0.0 || duration > 0.0 {
                out.insert(v.id.clone(), ProgressEntry { progress, duration });
            }
        }
    }
    out
}

fn apply_dearrow_to_items(items: &mut [Item], titles: bool, thumbs: bool) {
    if !titles && !thumbs {
        return;
    }
    let ids: Vec<String> = items
        .iter()
        .filter_map(|i| i.as_video().map(|v| v.id.clone()))
        .take(24)
        .collect();
    let brandings: HashMap<String, services::Branding> = std::thread::scope(|s| {
        let handles: Vec<_> = ids
            .iter()
            .map(|id| {
                s.spawn(move || {
                    services::dearrow_branding(id).map(|b| (id.clone(), b))
                })
            })
            .collect();
        handles
            .into_iter()
            .filter_map(|h| h.join().ok().flatten())
            .collect()
    });
    for it in items.iter_mut() {
        if let Item::Video(v) = it {
            if let Some(b) = brandings.get(&v.id) {
                if titles {
                    if let Some(t) = b.title.as_ref().filter(|t| !t.is_empty()) {
                        v.title = t.clone();
                    }
                }
                if thumbs {
                    if let Some(ts) = b.thumbnail_time {
                        v.thumbnail = services::dearrow_thumbnail_url(&v.id, ts);
                    }
                }
            }
        }
    }
}

fn filter_items(items: Vec<Item>, s: &store::Settings) -> Vec<Item> {
    items
        .into_iter()
        .filter(|it| match it {
            Item::Video(v) => !store::is_filtered(v, s),
            _ => true,
        })
        .collect()
}

fn build_playback_plan(
    info: &StreamInfo,
    max_height: u32,
    codec_pref: &str,
    audio_lang: Option<&str>,
    audio_only: bool,
) -> PlaybackPlan {
    let captions = info
        .captions
        .iter()
        .map(|c| PlaybackCaptionDto {
            lang: c.lang.clone(),
            name: c.name.clone(),
            url: proxy::local_pass(&c.url),
            auto: c.auto,
        })
        .collect();
    let audio_choice = streams::hls_audio_choice(info, audio_lang);

    match streams::playback_stream(info, max_height, codec_pref, audio_lang, audio_only) {
        Some(streams::PlaybackStream::Hls(hls_raw)) => {
            let picked = streams::pick_formats(info, max_height, codec_pref, audio_lang).0;
            let proxied = proxy::local_hls(hls_raw, max_height, audio_choice.as_deref());
            PlaybackPlan {
                mode: "hls",
                video_url: None,
                audio_url: None,
                hls_url: Some(proxied),
                quality_label: picked
                    .map(|v| v.quality_label.clone())
                    .filter(|q| !q.is_empty())
                    .unwrap_or_else(|| {
                        if max_height == 0 {
                            "Auto".into()
                        } else {
                            format!("{max_height}p")
                        }
                    }),
                codecs: picked.map(|v| v.codecs.clone()).unwrap_or_default(),
                width: picked.map(|v| v.width).unwrap_or(0),
                height: picked.map(|v| v.height).unwrap_or(max_height),
                fps: picked.map(|v| v.fps).unwrap_or(0),
                video_bitrate: picked.map(|v| v.bitrate).unwrap_or(0),
                audio_bitrate: 0,
                audio_codec: String::new(),
                source: info.source,
                captions,
            }
        }
        Some(streams::PlaybackStream::Adaptive(video, audio)) => {
            let video_url = video.map(|v| proxy::local_url(&v.url, v.content_length, &v.mime));
            let audio_url = audio.map(|a| proxy::local_url(&a.url, a.content_length, &a.mime));
            PlaybackPlan {
                mode: "adaptive",
                video_url,
                audio_url,
                hls_url: None,
                quality_label: if audio_only {
                    "Audio only".into()
                } else {
                    video
                        .map(|v| {
                            if v.fps > 30 {
                                format!("{}p{}", streams::fmt_class(v), v.fps)
                            } else if !v.quality_label.is_empty() {
                                v.quality_label.clone()
                            } else {
                                format!("{}p", streams::fmt_class(v))
                            }
                        })
                        .unwrap_or_else(|| "Auto".into())
                },
                codecs: video.map(|v| v.codecs.clone()).unwrap_or_default(),
                width: video.map(|v| v.width).unwrap_or(0),
                height: video.map(|v| v.height).unwrap_or(0),
                fps: video.map(|v| v.fps).unwrap_or(0),
                video_bitrate: video.map(|v| v.bitrate).unwrap_or(0),
                audio_bitrate: audio.map(|a| a.bitrate).unwrap_or(0),
                audio_codec: audio.map(|a| a.codecs.clone()).unwrap_or_default(),
                source: info.source,
                captions,
            }
        }
        Some(streams::PlaybackStream::Muxed(m)) => PlaybackPlan {
            mode: "muxed",
            video_url: Some(proxy::local_url(&m.url, m.content_length, &m.mime)),
            audio_url: None,
            hls_url: None,
            quality_label: if m.quality_label.is_empty() {
                format!("{}p", m.height)
            } else {
                m.quality_label.clone()
            },
            codecs: m.codecs.clone(),
            width: m.width,
            height: m.height,
            fps: m.fps,
            video_bitrate: m.bitrate,
            audio_bitrate: 0,
            audio_codec: String::new(),
            source: info.source,
            captions,
        },
        None => PlaybackPlan {
            mode: "none",
            video_url: None,
            audio_url: None,
            hls_url: None,
            quality_label: String::new(),
            codecs: String::new(),
            width: 0,
            height: 0,
            fps: 0,
            video_bitrate: 0,
            audio_bitrate: 0,
            audio_codec: String::new(),
            source: info.source,
            captions,
        },
    }
}

fn recently_uploaded() -> Result<Vec<Item>> {
    let mut videos: Vec<VideoItem> = Vec::new();
    let mut add = |p: Result<Page>| {
        if let Ok(p) = p {
            videos.extend(p.items.into_iter().filter_map(|i| match i {
                Item::Video(v) => Some(v),
                _ => None,
            }));
        }
    };
    if web::connected() {
        let first = web::home();
        let next = first.as_ref().ok().and_then(|p| p.continuation.clone());
        add(first);
        if let Some(t) = next {
            add(web::browse_continue(&t));
        }
    } else if auth::signed_in() {
        let first = auth::home();
        let next = first.as_ref().ok().and_then(|p| p.continuation.clone());
        add(first);
        if let Some(t) = next {
            add(auth::browse_continue(&t));
        }
    } else if recommend::available() {
        add(Ok(Page {
            items: recommend::for_you(),
            continuation: None,
        }));
    }
    if auth::signed_in() {
        add(auth::subscriptions_feed());
    }
    if !store::subscriptions().is_empty() {
        let (feed, _) = feed::cached_feed(None);
        videos.extend(feed.into_iter().take(150));
    }
    let since = yt_json::now() - 14 * 86400;
    let mut seen = HashSet::new();
    let mut recent: Vec<(i64, VideoItem)> = videos
        .into_iter()
        .filter(|v| !v.is_short && !v.is_upcoming && !v.is_live)
        .filter_map(|v| {
            let ts = v
                .published_ts
                .or_else(|| v.published_text.as_deref().and_then(yt_json::parse_relative_time))?;
            (ts >= since && seen.insert(v.id.clone())).then_some((ts, v))
        })
        .collect();
    recent.sort_by(|a, b| b.0.cmp(&a.0));
    Ok(recent
        .into_iter()
        .take(120)
        .map(|(ts, mut v)| {
            v.published_text = Some(yt_json::relative_from_ts(ts));
            Item::Video(v)
        })
        .collect())
}

fn anonymous_explore_mix() -> Result<Page> {
    // Combine Music, Gaming and Learning explore feeds when the user has zero watch history yet
    let (r1, r2, r3) = std::thread::scope(|s| {
        let h1 = s.spawn(|| innertube::explore(innertube::EXPLORE[0].1));
        let h2 = s.spawn(|| innertube::explore(innertube::EXPLORE[1].1));
        let h3 = s.spawn(|| innertube::explore(innertube::EXPLORE[6].1));
        (
            h1.join().unwrap_or_else(|_| Ok(Page::default())),
            h2.join().unwrap_or_else(|_| Ok(Page::default())),
            h3.join().unwrap_or_else(|_| Ok(Page::default())),
        )
    });
    let mut lists = Vec::new();
    let mut cont = None;
    for r in [r1, r2, r3] {
        if let Ok(p) = r {
            if cont.is_none() {
                cont = p.continuation;
            }
            lists.push(p.items.into_iter());
        }
    }
    let mut out = Vec::new();
    let mut seen = HashSet::new();
    loop {
        let mut any = false;
        for it in lists.iter_mut() {
            for _ in 0..2 {
                if let Some(item) = it.next() {
                    any = true;
                    if let Item::Video(ref v) = item {
                        if seen.insert(v.id.clone()) {
                            out.push(item);
                        }
                    }
                }
            }
        }
        if !any {
            break;
        }
    }
    Ok(Page {
        items: out,
        continuation: cont,
    })
}

fn parse_version(v: &str) -> (u64, u64, u64, u32, u64) {
    let v = v.trim().trim_start_matches(['v', 'V']);
    let (core, pre) = v.split_once('-').unwrap_or((v, ""));
    let mut n = core.split('.').map(|p| p.parse::<u64>().unwrap_or(0));
    let (a, b, c) = (
        n.next().unwrap_or(0),
        n.next().unwrap_or(0),
        n.next().unwrap_or(0),
    );
    let pre = pre.to_ascii_lowercase();
    let stage = if pre.is_empty() {
        9
    } else if pre.starts_with("rc") {
        3
    } else if pre.starts_with("beta") {
        2
    } else if pre.starts_with("alpha") {
        1
    } else {
        0
    };
    let num = pre
        .chars()
        .filter(|c| c.is_ascii_digit())
        .collect::<String>()
        .parse()
        .unwrap_or(0);
    (a, b, c, stage, num)
}

fn file_sha256(path: &std::path::Path) -> Result<String> {
    use sha2::{Digest, Sha256};
    use std::io::Read;
    let mut f = std::fs::File::open(path)?;
    let mut h = Sha256::new();
    let mut buf = vec![0u8; 256 * 1024];
    loop {
        let n = f.read(&mut buf)?;
        if n == 0 {
            break;
        }
        h.update(&buf[..n]);
    }
    Ok(h.finalize().iter().map(|b| format!("{b:02x}")).collect())
}

// ───────────────────────── JNI Exports ─────────────────────────

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeInit(
    mut env: JNIEnv,
    _cls: JClass,
    files_dir: JString,
    cache_dir: JString,
) -> jstring {
    let files = req_str(&mut env, files_dir);
    let cache = req_str(&mut env, cache_dir);
    jni_json(&mut env, || {
        unsafe {
            std::env::set_var("no_proxy", "127.0.0.1,localhost,::1");
            std::env::set_var("NO_PROXY", "127.0.0.1,localhost,::1");
        }
        store::init_android_paths(PathBuf::from(files), PathBuf::from(cache));
        store::init();
        Ok(InitInfo {
            core_version: env!("CARGO_PKG_VERSION"),
            settings: (*store::settings_snapshot()).clone(),
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeGetSettings(
    mut env: JNIEnv,
    _cls: JClass,
) -> jstring {
    jni_json(&mut env, || Ok((*store::settings_snapshot()).clone()))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeUpdateSettings(
    mut env: JNIEnv,
    _cls: JClass,
    settings_json: JString,
) -> jstring {
    let raw_str = req_str(&mut env, settings_json);
    jni_json(&mut env, || {
        let val: Value = serde_json::from_str(&raw_str).context("invalid settings json")?;
        let prev = store::settings_snapshot();
        let next = store::settings_from_value(val);
        let clear_streams = prev.stream_backend != next.stream_backend
            || prev.proxy != next.proxy
            || prev.ipv4_only != next.ipv4_only
            || prev.language != next.language
            || prev.region != next.region;
        store::update_settings(|s| *s = next.clone());
        if clear_streams {
            streams::clear_cache();
        }
        Ok(next)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeGetExploreCategories(
    mut env: JNIEnv,
    _cls: JClass,
) -> jstring {
    jni_json(&mut env, || {
        Ok(innertube::EXPLORE
            .iter()
            .map(|(name, channel_id)| ExploreCategory { name, channel_id })
            .collect::<Vec<_>>())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeGetHomeFeed(
    mut env: JNIEnv,
    _cls: JClass,
    chip: JString,
    continuation: JString,
) -> jstring {
    let chip = req_str(&mut env, chip);
    let cont = opt_str(&mut env, continuation).filter(|s| !s.trim().is_empty());
    jni_json(&mut env, || {
        let s = store::settings_snapshot();
        let mut page = if let Some(tok) = cont {
            if let Some(t) = tok.strip_prefix("web:") {
                let mut p = web::browse_continue(t)?;
                p.continuation = p.continuation.map(|c| format!("web:{c}"));
                p
            } else if let Some(t) = tok.strip_prefix("acct:") {
                let mut p = auth::browse_continue(t)?;
                p.continuation = p.continuation.map(|c| format!("acct:{c}"));
                p
            } else {
                innertube::browse_continue(&tok)?
            }
        } else if chip == "recent" {
            Page {
                items: recently_uploaded()?,
                continuation: None,
            }
        } else if chip == "subscriptions" {
            if auth::signed_in() {
                let mut p = auth::subscriptions_feed()?;
                p.continuation = p.continuation.map(|c| format!("acct:{c}"));
                p
            } else {
                let (vids, _) = feed::cached_feed(None);
                Page {
                    items: vids.into_iter().map(Item::Video).collect(),
                    continuation: None,
                }
            }
        } else if let Some(cid) = chip.strip_prefix("explore:") {
            innertube::explore(cid)?
        } else {
            // "for_you"
            if web::connected() {
                let mut p = web::home()?;
                p.continuation = p.continuation.map(|c| format!("web:{c}"));
                p
            } else if auth::signed_in() {
                let mut p = auth::home()?;
                p.continuation = p.continuation.map(|c| format!("acct:{c}"));
                p
            } else if recommend::available() {
                let items = recommend::for_you();
                if items.is_empty() {
                    anonymous_explore_mix()?
                } else {
                    Page {
                        items,
                        continuation: None,
                    }
                }
            } else {
                anonymous_explore_mix()?
            }
        };

        page.items = filter_items(page.items, &s);
        apply_dearrow_to_items(&mut page.items, s.dearrow_titles, s.dearrow_thumbnails);
        let progress = progress_for_items(&page.items);
        Ok(FeedResponse {
            items: page.items,
            continuation: page.continuation,
            progress,
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeGetShortsFeed(
    mut env: JNIEnv,
    _cls: JClass,
    token: JString,
    seed: JString,
) -> jstring {
    let tok = opt_str(&mut env, token).filter(|s| !s.is_empty());
    let seed = opt_str(&mut env, seed).filter(|s| !s.is_empty());
    jni_json(&mut env, || {
        let (mut videos, cont) = innertube::shorts_feed(tok.as_deref(), seed.as_deref())?;
        // Enrich up to first 6 shorts with title/channel info in parallel
        let enrich_count = videos.len().min(6);
        let metas: Vec<Option<(String, String, Option<String>, Option<String>)>> =
            std::thread::scope(|s| {
                let handles: Vec<_> = videos[..enrich_count]
                    .iter()
                    .map(|v| {
                        let id = v.id.clone();
                        s.spawn(move || {
                            innertube::watch_next(&id, None).ok().map(|w| {
                                (
                                    w.title,
                                    w.channel.name,
                                    (!w.channel.id.is_empty()).then_some(w.channel.id),
                                    w.channel.avatar,
                                )
                            })
                        })
                    })
                    .collect();
                handles
                    .into_iter()
                    .map(|h| h.join().ok().flatten())
                    .collect()
            });
        for (v, meta) in videos[..enrich_count].iter_mut().zip(metas) {
            if let Some((title, cname, cid, avatar)) = meta {
                if !title.is_empty() {
                    v.title = title;
                }
                if !cname.is_empty() {
                    v.channel_name = cname;
                }
                if cid.is_some() {
                    v.channel_id = cid;
                }
                if avatar.is_some() {
                    v.channel_avatar = avatar;
                }
            }
        }
        let items: Vec<Item> = videos.into_iter().map(Item::Video).collect();
        let progress = progress_for_items(&items);
        Ok(FeedResponse {
            items,
            continuation: cont,
            progress,
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeSearch(
    mut env: JNIEnv,
    _cls: JClass,
    query: JString,
    filter_json: JString,
    continuation: JString,
) -> jstring {
    let query = req_str(&mut env, query);
    let filter_str = opt_str(&mut env, filter_json).filter(|s| !s.is_empty());
    let cont = opt_str(&mut env, continuation).filter(|s| !s.is_empty());
    jni_json(&mut env, || {
        let s = store::settings_snapshot();
        let mut page = if let Some(tok) = cont {
            innertube::search_continue(&tok)?
        } else {
            store::search_history_add(&query);
            let filter: innertube::SearchFilter = filter_str
                .as_deref()
                .and_then(|j| serde_json::from_str(j).ok())
                .unwrap_or_default();
            innertube::search(&query, &filter)?
        };
        page.items = filter_items(page.items, &s);
        apply_dearrow_to_items(&mut page.items, s.dearrow_titles, s.dearrow_thumbnails);
        let progress = progress_for_items(&page.items);
        Ok(FeedResponse {
            items: page.items,
            continuation: page.continuation,
            progress,
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeSearchSuggestions(
    mut env: JNIEnv,
    _cls: JClass,
    query: JString,
) -> jstring {
    let q = req_str(&mut env, query);
    jni_json(&mut env, || {
        let mut out = store::search_history(&q, 6);
        let mut seen: HashSet<String> = out.iter().map(|s| s.to_lowercase()).collect();
        if !q.trim().is_empty() {
            if let Ok(suggs) = innertube::suggestions(&q) {
                for s in suggs {
                    if seen.insert(s.to_lowercase()) {
                        out.push(s);
                    }
                    if out.len() >= 12 {
                        break;
                    }
                }
            }
        }
        Ok(out)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeClearSearchHistory(
    mut env: JNIEnv,
    _cls: JClass,
) -> jstring {
    jni_json(&mut env, || {
        store::search_history_clear();
        Ok(true)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeRemoveSearchHistory(
    mut env: JNIEnv,
    _cls: JClass,
    query: JString,
) -> jstring {
    let q = req_str(&mut env, query);
    jni_json(&mut env, || {
        store::search_history_remove(&q);
        Ok(true)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeGetWatchBundle(
    mut env: JNIEnv,
    _cls: JClass,
    video_id: JString,
    playlist_id: JString,
) -> jstring {
    let vid = req_str(&mut env, video_id);
    let pid = opt_str(&mut env, playlist_id).filter(|s| !s.is_empty());
    jni_json(&mut env, || {
        let s = store::settings_snapshot();
        let sb_cats: Vec<String> = s
            .sb_actions
            .iter()
            .filter(|(_, act)| act.as_str() != "off")
            .map(|(cat, _)| cat.clone())
            .collect();

        let (watch_res, stream_res, sb_res, da_res, ryd_res) = std::thread::scope(|scope| {
            let h_watch = scope.spawn(|| innertube::watch_next(&vid, pid.as_deref()));
            let h_streams = scope.spawn(|| streams::get_streams(&vid));
            let h_sb = scope.spawn(|| {
                if s.sponsorblock {
                    services::sponsor_segments(&vid, &s.sponsorblock_server, &sb_cats).ok()
                } else {
                    None
                }
            });
            let h_da = scope.spawn(|| {
                if s.dearrow_titles || s.dearrow_thumbnails {
                    services::dearrow_branding(&vid)
                } else {
                    None
                }
            });
            let h_ryd = scope.spawn(|| {
                if s.ryd {
                    services::ryd_votes(&vid).ok()
                } else {
                    None
                }
            });
            (
                h_watch.join().unwrap_or_else(|_| Err(anyhow!("watch thread panicked"))),
                h_streams.join().unwrap_or_else(|_| Err(anyhow!("streams thread panicked"))),
                h_sb.join().ok().flatten(),
                h_da.join().ok().flatten(),
                h_ryd.join().ok().flatten(),
            )
        });

        let mut watch = watch_res?;
        let stream_info = stream_res?;
        let dearrow = da_res;
        if let Some(ref b) = dearrow {
            if s.dearrow_titles {
                if let Some(ref t) = b.title {
                    if !t.is_empty() {
                        watch.title = t.clone();
                    }
                }
            }
        }
        let dearrow_thumbnail_url = if s.dearrow_thumbnails {
            dearrow
                .as_ref()
                .and_then(|b| b.thumbnail_time)
                .map(|t| services::dearrow_thumbnail_url(&vid, t))
        } else {
            None
        };

        let playback = build_playback_plan(
            &stream_info,
            s.default_quality,
            &s.codec_pref,
            None,
            s.audio_only,
        );

        let segments = sb_res
            .unwrap_or_default()
            .into_iter()
            .map(|seg| {
                let user_action = s
                    .sb_actions
                    .get(&seg.category)
                    .cloned()
                    .unwrap_or_else(|| "show".into());
                SegmentDto {
                    category_name: services::sponsor_category_name(&seg.category),
                    color: services::sponsor_category_color(&seg.category),
                    category: seg.category,
                    action: seg.action,
                    user_action,
                    start: seg.start,
                    end: seg.end,
                    uuid: seg.uuid,
                }
            })
            .collect();

        let resume_secs = if s.resume_playback {
            store::progress_map()
                .get(&vid)
                .map(|(p, d)| {
                    if *d > 10.0 && *p + 10.0 >= *d {
                        0.0
                    } else {
                        *p
                    }
                })
                .unwrap_or(0.0)
        } else {
            0.0
        };
        let channel_id = if watch.channel.id.is_empty() {
            stream_info.channel_id.clone()
        } else {
            watch.channel.id.clone()
        };
        let channel_speed = if s.remember_speed_per_channel && !channel_id.is_empty() {
            store::channel_speed(&channel_id)
        } else {
            None
        };
        let channel_skip_silence = (!channel_id.is_empty())
            .then(|| store::channel_skip_silence(&channel_id))
            .flatten();
        let duration_secs = stream_info.length_secs as f64;
        let highlights = highlights::extract_highlights(&watch.heatmap, &watch.chapters, duration_secs);
        let rating = store::rating(&vid);
        let is_subscribed = !channel_id.is_empty() && store::is_subscribed(&channel_id);
        let local_pls = store::playlists();
        let wl_id = local_pls.iter().find(|p| p.protected && p.name == "Watch later").map(|p| p.id);
        let fav_id = local_pls.iter().find(|p| p.protected && p.name == "Favorites").map(|p| p.id);
        let in_watch_later = wl_id.is_some_and(|id| store::playlist_contains(id, &vid));
        let in_favorites = fav_id.is_some_and(|id| store::playlist_contains(id, &vid));

        Ok(WatchBundleDto {
            watch,
            streams: stream_info,
            playback,
            segments,
            dearrow,
            dearrow_thumbnail_url,
            votes: ryd_res,
            resume_secs,
            channel_speed,
            channel_skip_silence,
            highlights,
            rating,
            is_subscribed,
            in_watch_later,
            in_favorites,
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeResolvePlayback(
    mut env: JNIEnv,
    _cls: JClass,
    video_id: JString,
    max_height: jint,
    codec_pref: JString,
    audio_lang: JString,
    audio_only: jboolean,
) -> jstring {
    let vid = req_str(&mut env, video_id);
    let codec = req_str(&mut env, codec_pref);
    let lang = opt_str(&mut env, audio_lang).filter(|s| !s.is_empty());
    let ao = audio_only != 0;
    let h = max_height.max(0) as u32;
    jni_json(&mut env, || {
        let info = streams::get_streams(&vid)?;
        Ok(build_playback_plan(&info, h, &codec, lang.as_deref(), ao))
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeGetComments(
    mut env: JNIEnv,
    _cls: JClass,
    token: JString,
) -> jstring {
    let tok = req_str(&mut env, token);
    jni_json(&mut env, || innertube::comments(&tok))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeGetRelatedContinue(
    mut env: JNIEnv,
    _cls: JClass,
    token: JString,
) -> jstring {
    let tok = req_str(&mut env, token);
    jni_json(&mut env, || {
        let s = store::settings_snapshot();
        let mut p = innertube::related_continue(&tok)?;
        p.items = filter_items(p.items, &s);
        let progress = progress_for_items(&p.items);
        Ok(FeedResponse {
            items: p.items,
            continuation: p.continuation,
            progress,
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeGetLiveChat(
    mut env: JNIEnv,
    _cls: JClass,
    token: JString,
) -> jstring {
    let tok = req_str(&mut env, token);
    jni_json(&mut env, || innertube::live_chat(&tok))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeSendLiveChat(
    mut env: JNIEnv,
    _cls: JClass,
    channel_id: JString,
    video_id: JString,
    message: JString,
) -> jstring {
    let cid = req_str(&mut env, channel_id);
    let vid = req_str(&mut env, video_id);
    let msg = req_str(&mut env, message);
    jni_json(&mut env, || {
        auth::send_live_chat(&cid, &vid, &msg)?;
        Ok(true)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeReportSponsorViewed(
    mut env: JNIEnv,
    _cls: JClass,
    uuid: JString,
) -> jstring {
    let uuid = req_str(&mut env, uuid);
    jni_json(&mut env, || {
        let server = store::settings_snapshot().sponsorblock_server.clone();
        std::thread::spawn(move || {
            services::sponsor_viewed(&server, &uuid);
        });
        Ok(true)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeVoteSponsorSegment(
    mut env: JNIEnv,
    _cls: JClass,
    uuid: JString,
    up: jboolean,
) -> jstring {
    let uuid = req_str(&mut env, uuid);
    let upvote = up != 0;
    jni_json(&mut env, || {
        let mut s = (*store::settings_snapshot()).clone();
        if s.sb_user_id.is_empty() {
            let uid = services::new_sb_user_id();
            store::update_settings(|cur| cur.sb_user_id = uid.clone());
            s.sb_user_id = uid;
        }
        services::sponsor_vote(&s.sponsorblock_server, &uuid, &s.sb_user_id, upvote)?;
        Ok(true)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeGetChannel(
    mut env: JNIEnv,
    _cls: JClass,
    channel_id: JString,
    tab_params: JString,
) -> jstring {
    let cid = req_str(&mut env, channel_id);
    let params = opt_str(&mut env, tab_params).filter(|s| !s.is_empty());
    jni_json(&mut env, || {
        let ch = innertube::channel(&cid, params.as_deref())?;
        let is_subscribed = store::is_subscribed(&ch.info.id);
        let progress = progress_for_items(&ch.page.items);
        Ok(ChannelResponse {
            channel: ch,
            is_subscribed,
            progress,
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeGetChannelContinue(
    mut env: JNIEnv,
    _cls: JClass,
    token: JString,
) -> jstring {
    let tok = req_str(&mut env, token);
    jni_json(&mut env, || {
        let p = innertube::browse_continue(&tok)?;
        let progress = progress_for_items(&p.items);
        Ok(FeedResponse {
            items: p.items,
            continuation: p.continuation,
            progress,
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeSearchChannel(
    mut env: JNIEnv,
    _cls: JClass,
    channel_id: JString,
    query: JString,
) -> jstring {
    let cid = req_str(&mut env, channel_id);
    let q = req_str(&mut env, query);
    jni_json(&mut env, || {
        let p = innertube::channel_search(&cid, &q)?;
        let progress = progress_for_items(&p.items);
        Ok(FeedResponse {
            items: p.items,
            continuation: p.continuation,
            progress,
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeGetPlaylist(
    mut env: JNIEnv,
    _cls: JClass,
    playlist_id: JString,
) -> jstring {
    let pid = req_str(&mut env, playlist_id);
    jni_json(&mut env, || {
        let playlist = if auth::signed_in() {
            auth::playlist(&pid).or_else(|_| innertube::playlist(&pid))?
        } else {
            innertube::playlist(&pid)?
        };
        let progress = progress_for_videos(&playlist.videos);
        Ok(PlaylistResponse { playlist, progress })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeResolveUrl(
    mut env: JNIEnv,
    _cls: JClass,
    url: JString,
) -> jstring {
    let raw = req_str(&mut env, url);
    jni_json(&mut env, || {
        let norm = links::normalize(&raw);
        let resolved = innertube::parse_youtube_url(&norm)
            .map(Ok)
            .unwrap_or_else(|| innertube::resolve_url(&norm))?;
        Ok(match resolved {
            innertube::ResolvedUrl::Video(id) => ResolvedUrlDto { kind: "video", id },
            innertube::ResolvedUrl::Channel(id) => ResolvedUrlDto { kind: "channel", id },
            innertube::ResolvedUrl::Playlist(id) => ResolvedUrlDto { kind: "playlist", id },
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeGetSubscriptions(
    mut env: JNIEnv,
    _cls: JClass,
) -> jstring {
    jni_json(&mut env, || Ok(store::subscriptions()))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeSubscribe(
    mut env: JNIEnv,
    _cls: JClass,
    channel_id: JString,
    name: JString,
    avatar: JString,
) -> jstring {
    let cid = req_str(&mut env, channel_id);
    let name = req_str(&mut env, name);
    let avatar = opt_str(&mut env, avatar).filter(|s| !s.is_empty());
    jni_json(&mut env, || {
        store::subscribe(&cid, &name, avatar.as_deref());
        if auth::signed_in() {
            let cid2 = cid.clone();
            std::thread::spawn(move || {
                let _ = auth::subscribe(&cid2, true);
            });
        }
        Ok(true)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeUnsubscribe(
    mut env: JNIEnv,
    _cls: JClass,
    channel_id: JString,
) -> jstring {
    let cid = req_str(&mut env, channel_id);
    jni_json(&mut env, || {
        store::unsubscribe(&cid);
        if auth::signed_in() {
            let cid2 = cid.clone();
            std::thread::spawn(move || {
                let _ = auth::subscribe(&cid2, false);
            });
        }
        Ok(true)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeRefreshSubscriptionsFeed(
    mut env: JNIEnv,
    _cls: JClass,
) -> jstring {
    jni_json(&mut env, || {
        let s = store::settings_snapshot();
        let (vids, _failed) = feed::refresh(None, |_, _| {});
        let mut items: Vec<Item> = vids.into_iter().map(Item::Video).collect();
        items = filter_items(items, &s);
        let progress = progress_for_items(&items);
        Ok(FeedResponse {
            items,
            continuation: None,
            progress,
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeGetCachedSubscriptionsFeed(
    mut env: JNIEnv,
    _cls: JClass,
) -> jstring {
    jni_json(&mut env, || {
        let s = store::settings_snapshot();
        let (vids, _) = feed::cached_feed(None);
        let mut items: Vec<Item> = vids.into_iter().map(Item::Video).collect();
        items = filter_items(items, &s);
        let progress = progress_for_items(&items);
        Ok(FeedResponse {
            items,
            continuation: None,
            progress,
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeGetHistory(
    mut env: JNIEnv,
    _cls: JClass,
    query: JString,
    limit: jint,
) -> jstring {
    let q = req_str(&mut env, query);
    let lim = (limit.max(1)) as usize;
    jni_json(&mut env, || Ok(store::history_list(&q, lim)))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeRecordWatchStart(
    mut env: JNIEnv,
    _cls: JClass,
    video_json: JString,
    duration_secs: jdouble,
) -> jstring {
    let v_str = req_str(&mut env, video_json);
    jni_json(&mut env, || {
        let v: VideoItem = serde_json::from_str(&v_str).context("invalid video json")?;
        store::history_add(&v);
        web::report_start(&v.id, duration_secs);
        Ok(true)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeSaveWatchProgress(
    mut env: JNIEnv,
    _cls: JClass,
    video_id: JString,
    progress: jdouble,
    duration: jdouble,
    playing: jboolean,
) -> jstring {
    let vid = req_str(&mut env, video_id);
    let is_playing = playing != 0;
    jni_json(&mut env, || {
        store::history_progress_queued(&vid, progress, duration, false);
        web::report_progress(&vid, progress, is_playing);
        Ok(true)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeReportWatchStop(
    mut env: JNIEnv,
    _cls: JClass,
    video_id: JString,
    progress: jdouble,
    duration: jdouble,
) -> jstring {
    let vid = req_str(&mut env, video_id);
    jni_json(&mut env, || {
        store::history_progress_queued(&vid, progress, duration, true);
        web::report_stop(&vid, progress);
        Ok(true)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeRemoveHistoryItem(
    mut env: JNIEnv,
    _cls: JClass,
    video_id: JString,
) -> jstring {
    let vid = req_str(&mut env, video_id);
    jni_json(&mut env, || {
        store::history_remove(&vid);
        Ok(true)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeClearHistory(
    mut env: JNIEnv,
    _cls: JClass,
) -> jstring {
    jni_json(&mut env, || {
        store::history_clear();
        Ok(true)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeGetLocalPlaylists(
    mut env: JNIEnv,
    _cls: JClass,
) -> jstring {
    jni_json(&mut env, || Ok(store::playlists()))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeGetLocalPlaylistItems(
    mut env: JNIEnv,
    _cls: JClass,
    playlist_id: jlong,
) -> jstring {
    jni_json(&mut env, || {
        let vids = store::playlist_items(playlist_id);
        let items: Vec<Item> = vids.into_iter().map(Item::Video).collect();
        let progress = progress_for_items(&items);
        Ok(FeedResponse {
            items,
            continuation: None,
            progress,
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeCreateLocalPlaylist(
    mut env: JNIEnv,
    _cls: JClass,
    name: JString,
) -> jstring {
    let name = req_str(&mut env, name);
    jni_json(&mut env, || Ok(store::playlist_create(&name)))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeDeleteLocalPlaylist(
    mut env: JNIEnv,
    _cls: JClass,
    playlist_id: jlong,
) -> jstring {
    jni_json(&mut env, || {
        store::playlist_delete(playlist_id);
        Ok(true)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeTogglePlaylistItem(
    mut env: JNIEnv,
    _cls: JClass,
    playlist_id: jlong,
    video_json: JString,
) -> jstring {
    let v_str = req_str(&mut env, video_json);
    jni_json(&mut env, || {
        let v: VideoItem = serde_json::from_str(&v_str).context("invalid video json")?;
        if store::playlist_contains(playlist_id, &v.id) {
            store::playlist_remove(playlist_id, &v.id);
            Ok(false)
        } else {
            store::playlist_add(playlist_id, &v);
            Ok(true)
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeSetVideoRating(
    mut env: JNIEnv,
    _cls: JClass,
    video_id: JString,
    rating: jint,
) -> jstring {
    let vid = req_str(&mut env, video_id);
    let r = rating.clamp(-1, 1) as i8;
    jni_json(&mut env, || {
        store::set_rating(&vid, r);
        if auth::signed_in() {
            let kind = match r {
                1 => "like",
                -1 => "dislike",
                _ => "removelike",
            };
            let vid2 = vid.clone();
            std::thread::spawn(move || {
                let _ = auth::like(&vid2, kind);
            });
        }
        Ok(r)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeSetChannelSpeed(
    mut env: JNIEnv,
    _cls: JClass,
    channel_id: JString,
    speed: jdouble,
) -> jstring {
    let cid = req_str(&mut env, channel_id);
    jni_json(&mut env, || {
        if !cid.is_empty() {
            store::set_channel_speed(&cid, speed);
        }
        Ok(true)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeClearChannelSpeed(
    mut env: JNIEnv,
    _cls: JClass,
    channel_id: JString,
) -> jstring {
    let cid = req_str(&mut env, channel_id);
    jni_json(&mut env, || {
        if !cid.is_empty() {
            store::clear_channel_speed(&cid);
        }
        Ok(true)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeSetChannelSkipSilence(
    mut env: JNIEnv,
    _cls: JClass,
    channel_id: JString,
    skip: jboolean,
) -> jstring {
    let cid = req_str(&mut env, channel_id);
    let enabled = skip != 0;
    jni_json(&mut env, || {
        if !cid.is_empty() {
            store::set_channel_skip_silence(&cid, enabled);
        }
        Ok(true)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeFetchTranscript(
    mut env: JNIEnv,
    _cls: JClass,
    captions_json: JString,
    preferred_lang: JString,
) -> jstring {
    let raw = req_str(&mut env, captions_json);
    let pref = req_str(&mut env, preferred_lang);
    jni_json(&mut env, || {
        let tracks: Vec<CaptionTrack> =
            serde_json::from_str(&raw).context("invalid captions json")?;
        transcript::fetch_transcript(&tracks, &pref)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeSearchTranscript(
    mut env: JNIEnv,
    _cls: JClass,
    segments_json: JString,
    query: JString,
) -> jstring {
    let raw = req_str(&mut env, segments_json);
    let q = req_str(&mut env, query);
    jni_json(&mut env, || {
        let segments: Vec<transcript::TranscriptSegment> =
            serde_json::from_str(&raw).context("invalid segments json")?;
        Ok(transcript::search_segments(&segments, &q, 32))
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeGetAuthStatus(
    mut env: JNIEnv,
    _cls: JClass,
) -> jstring {
    jni_json(&mut env, || {
        Ok(AuthStatusDto {
            tv_signed_in: auth::signed_in(),
            tv_account: auth::account(),
            web_connected: web::connected(),
            web_account_name: web::account_name(),
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeStartTvLogin(
    mut env: JNIEnv,
    _cls: JClass,
) -> jstring {
    jni_json(&mut env, || auth::start_login())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativePollTvLogin(
    mut env: JNIEnv,
    _cls: JClass,
    device_code_json: JString,
) -> jstring {
    let raw = req_str(&mut env, device_code_json);
    jni_json(&mut env, || {
        let dc: auth::DeviceCode = serde_json::from_str(&raw).context("invalid device code")?;
        match auth::poll_login(&dc)? {
            auth::PollResult::Pending => Ok(PollResultDto {
                status: "pending",
                account: None,
            }),
            auth::PollResult::SlowDown => Ok(PollResultDto {
                status: "slow_down",
                account: None,
            }),
            auth::PollResult::Done(acc) => Ok(PollResultDto {
                status: "done",
                account: Some(acc),
            }),
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeSignOutTv(
    mut env: JNIEnv,
    _cls: JClass,
) -> jstring {
    jni_json(&mut env, || {
        auth::sign_out();
        Ok(true)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeSyncTvAccount(
    mut env: JNIEnv,
    _cls: JClass,
) -> jstring {
    jni_json(&mut env, || {
        let (s_res, h_res, q_res) = std::thread::scope(|scope| {
            let h1 = scope.spawn(auth::sync_subscriptions);
            let h2 = scope.spawn(|| auth::sync_history(3));
            let h3 = scope.spawn(auth::sync_search_history);
            (
                h1.join().unwrap_or(Ok(0)),
                h2.join().unwrap_or(Ok(0)),
                h3.join().unwrap_or(Ok(0)),
            )
        });
        Ok(SyncAccountResult {
            subscriptions_added: s_res.unwrap_or(0),
            history_added: h_res.unwrap_or(0),
            searches_added: q_res.unwrap_or(0),
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeImportCookiesTxt(
    mut env: JNIEnv,
    _cls: JClass,
    content: JString,
) -> jstring {
    let text = req_str(&mut env, content);
    jni_json(&mut env, || web::import_cookies_txt_str(&text))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeDisconnectWebSession(
    mut env: JNIEnv,
    _cls: JClass,
) -> jstring {
    jni_json(&mut env, || {
        web::disconnect();
        Ok(true)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeImportContent(
    mut env: JNIEnv,
    _cls: JClass,
    content: JString,
) -> jstring {
    let text = req_str(&mut env, content);
    jni_json(&mut env, || import::import_bytes(text.as_bytes()))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeExportBackupJson(
    mut env: JNIEnv,
    _cls: JClass,
) -> jstring {
    jni_json(&mut env, || {
        let subs = store::subscriptions();
        let history: Vec<Value> = store::history_list("", usize::MAX)
            .into_iter()
            .map(|h| {
                json!({
                    "video": h.video,
                    "watched_at": h.watched_at,
                    "progress": h.progress,
                    "duration": h.duration
                })
            })
            .collect();
        let playlists: Vec<Value> = store::playlists()
            .into_iter()
            .map(|p| json!({ "name": p.name, "videos": store::playlist_items(p.id) }))
            .collect();
        let profiles: Vec<Value> = store::profiles()
            .into_iter()
            .map(|p| json!({ "name": p.name, "color": p.color, "channels": p.channels }))
            .collect();
        let doc = json!({
            "streamtube": 1,
            "subscriptions": subs,
            "history": history,
            "playlists": playlists,
            "profiles": profiles,
            "settings": *store::settings_snapshot(),
        });
        Ok(serde_json::to_string_pretty(&doc)?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeCheckGitHubUpdate(
    mut env: JNIEnv,
    _cls: JClass,
    current_version: JString,
) -> jstring {
    let cur = req_str(&mut env, current_version);
    jni_json(&mut env, || {
        let v = http::get_json(&format!(
            "https://api.github.com/repos/{UPDATE_REPO}/releases?per_page=15"
        ))?;
        let list = v
            .as_array()
            .ok_or_else(|| anyhow!("unexpected reply from GitHub"))?;
        let mut best: Option<(String, &Value, &Value)> = None;
        for r in list {
            if r.get("draft").and_then(Value::as_bool).unwrap_or(false) {
                continue;
            }
            let Some(tag) = r.get("tag_name").and_then(Value::as_str) else {
                continue;
            };
            let Some(assets) = r.get("assets").and_then(Value::as_array) else {
                continue;
            };
            let apk = assets.iter().find(|a| {
                let n = a
                    .get("name")
                    .and_then(Value::as_str)
                    .unwrap_or("")
                    .to_ascii_lowercase();
                n.ends_with(".apk")
            });
            let Some(apk_asset) = apk else { continue };
            let candidate_ver = tag.trim_start_matches("android-tv-");
            if best
                .as_ref()
                .map(|(bt, _, _)| parse_version(candidate_ver) > parse_version(bt.trim_start_matches("android-tv-")))
                .unwrap_or(true)
            {
                best = Some((tag.to_string(), r, apk_asset));
            }
        }
        let Some((tag, r, apk_asset)) = best else {
            return Ok(UpdateCheckDto {
                has_update: false,
                current_version: cur,
                tag: String::new(),
                name: String::new(),
                notes: String::new(),
                page: format!("https://github.com/{UPDATE_REPO}/releases"),
                prerelease: false,
                apk_url: None,
                apk_name: None,
                apk_size: 0,
                sha256: None,
            });
        };
        let normalized_tag = tag.trim_start_matches("android-tv-");
        let has_update = if tag == "android-tv-latest" {
            true
        } else {
            parse_version(normalized_tag) > parse_version(&cur)
        };
        let s = |k: &str| {
            r.get(k)
                .and_then(Value::as_str)
                .unwrap_or_default()
                .to_string()
        };
        let sha256 = apk_asset
            .get("digest")
            .and_then(Value::as_str)
            .and_then(|d| d.strip_prefix("sha256:"))
            .filter(|h| h.len() == 64 && h.bytes().all(|b| b.is_ascii_hexdigit()))
            .map(str::to_ascii_lowercase);
        Ok(UpdateCheckDto {
            has_update,
            current_version: cur,
            tag: tag.clone(),
            name: Some(s("name")).filter(|n| !n.is_empty()).unwrap_or(tag),
            notes: s("body"),
            page: s("html_url"),
            prerelease: r
                .get("prerelease")
                .and_then(Value::as_bool)
                .unwrap_or(false),
            apk_url: apk_asset
                .get("browser_download_url")
                .and_then(Value::as_str)
                .map(str::to_string),
            apk_name: apk_asset
                .get("name")
                .and_then(Value::as_str)
                .map(str::to_string),
            apk_size: apk_asset.get("size").and_then(Value::as_u64).unwrap_or(0),
            sha256,
        })
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_streamtube_tv_core_NativeBridge_nativeDownloadApk(
    mut env: JNIEnv,
    _cls: JClass,
    url: JString,
    dest_path: JString,
    expected_sha256: JString,
) -> jstring {
    let url = req_str(&mut env, url);
    let dest = req_str(&mut env, dest_path);
    let sha = opt_str(&mut env, expected_sha256).filter(|s| !s.trim().is_empty());
    jni_json(&mut env, || {
        let prefix = format!("https://github.com/{UPDATE_REPO}/releases/download/");
        if !url.starts_with(&prefix) {
            return Err(anyhow!("unexpected download URL"));
        }
        let path = PathBuf::from(&dest);
        if let Some(parent) = path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        let tmp = path.with_extension("apk.part");
        {
            let mut f = std::fs::File::create(&tmp)?;
            http::download_to(&url, &mut f, |_, _| true)?;
        }
        if let Some(want) = sha {
            let got = file_sha256(&tmp)?;
            if got != want.to_ascii_lowercase() {
                let _ = std::fs::remove_file(&tmp);
                return Err(anyhow!("APK checksum mismatch"));
            }
        }
        let _ = std::fs::remove_file(&path);
        std::fs::rename(&tmp, &path)?;
        Ok(dest)
    })
}
