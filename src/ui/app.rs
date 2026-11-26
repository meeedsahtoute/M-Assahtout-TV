//! Application shell: routing, top bar, sidebar, overlays, keyboard shortcuts.
use super::pages::*;
use crate::i18n::{t, tf};
use super::*;
use crate::api::innertube;
use crate::api::types::VideoItem;
use crate::player::controller::{KeyboardFeedback, Player, player};
use crate::player::view::PlayerView;
use crate::store;
use gpui_kit::component::input::{Input, InputEvent, InputState};

// ───────────────────────── routes ─────────────────────────

#[derive(Clone, Debug, PartialEq)]
pub enum Route {
    Home,
    Explore(usize),
    Shorts(Option<String>),
    Subscriptions,
    Channels,
    History,
    Library,
    LocalPlaylist(i64),
    Playlist(String),
    Search(String),
    Channel(String),
    Watch { id: String, playlist: Option<String>, start: Option<f64> },
    Settings,
    Downloads,
}

impl Route {
    fn title(&self) -> String {
        match self {
            Route::Home => t("Home").into(),
            Route::Explore(i) => innertube::EXPLORE.get(*i).map(|e| t(e.0).to_string()).unwrap_or_default(),
            Route::Shorts(_) => "Shorts".into(),
            Route::Subscriptions => t("Subscriptions").into(),
            Route::Channels => t("Channels").into(),
            Route::History => t("History").into(),
            Route::Library => t("Playlists").into(),
            Route::LocalPlaylist(_) | Route::Playlist(_) => t("Playlist").into(),
            Route::Search(q) => q.clone(),
            Route::Channel(_) => t("Channel").into(),
            Route::Watch { .. } => t("Watch").into(),
            Route::Settings => t("Settings").into(),
            Route::Downloads => t("Downloads").into(),
        }
    }
}

#[derive(Clone, Copy, Default)]
pub struct UiState {
    pub fullscreen: bool,
    /// the Shorts page is showing
    pub shorts: bool,
    /// fullscreen layout inside the window, without OS fullscreen
    pub windowed: bool,
    /// tracks whether entering video-player fullscreen also entered OS fullscreen
    pub video_fs_entered_os: bool,
    pub sidebar_open: bool,
    pub content_width: Pixels,
    pub viewport: Size<Pixels>,
}
impl Global for UiState {}

const COLLAPSED_SIDEBAR_WIDTH: f32 = 88.0;

pub struct AppHandle(pub WeakEntity<AppView>);
impl Global for AppHandle {}

fn shortcut_key(key: &str) -> &str {
    // Match the US key positions when the platform reports Russian characters.
    match key {
        "ф" | "Ф" => "a",
        "и" | "И" => "b",
        "с" | "С" => "c",
        "в" | "В" => "d",
        "у" | "У" => "e",
        "а" | "А" => "f",
        "п" | "П" => "g",
        "р" | "Р" => "h",
        "ш" | "Ш" => "i",
        "о" | "О" => "j",
        "л" | "Л" => "k",
        "д" | "Д" => "l",
        "ь" | "Ь" => "m",
        "т" | "Т" => "n",
        "щ" | "Щ" => "o",
        "з" | "З" => "p",
        "й" | "Й" => "q",
        "к" | "К" => "r",
        "ы" | "Ы" => "s",
        "е" | "Е" => "t",
        "г" | "Г" => "u",
        "м" | "М" => "v",
        "ц" | "Ц" => "w",
        "ч" | "Ч" => "x",
        "н" | "Н" => "y",
        "я" | "Я" => "z",
        "б" => ",",
        "Б" => "<",
        "ю" => ".",
        "Ю" => ">",
        "э" => "'",
        "Э" => "\"",
        _ => key,
    }
}

#[cfg(test)]
mod shortcut_tests {
    use super::shortcut_key;

    #[::core::prelude::v1::test]
    fn accepts_russian_shortcut_keys() {
        assert_eq!(shortcut_key("л"), "k");
        assert_eq!(shortcut_key("Ш"), "i");
        assert_eq!(shortcut_key("е"), "t");
        assert_eq!(shortcut_key("б"), ",");
        assert_eq!(shortcut_key("Ю"), ">");
        assert_eq!(shortcut_key("э"), "'");
        assert_eq!(shortcut_key("left"), "left");
        assert_eq!(shortcut_key("m"), "m");
    }
}

/// The page a YouTube (or `streamtube:`) link opens, if it's one we understand.
pub fn route_from_link(arg: &str) -> Option<Route> {
    let arg = crate::links::normalize(arg);
    let search_arg = arg
        .contains("/results?")
        .then(|| arg.split(['?', '&']).find_map(|kv| kv.strip_prefix("search_query=")))
        .flatten()
        .map(|q| crate::api::http::urldecode(&q.replace('+', " ")))
        .filter(|q| !q.trim().is_empty());
    let param = |key: &str| {
        arg.split(['?', '&', '#']).skip(1).find_map(|kv| kv.strip_prefix(key).and_then(|v| v.strip_prefix('='))).map(str::to_string)
    };
    search_arg.map(Route::Search).or_else(|| {
        innertube::parse_youtube_url(&arg).map(|r| match r {
            // a shared position (t=90, t=1m30s) and the playlist it's played from
            innertube::ResolvedUrl::Video(id) => Route::Watch {
                id,
                playlist: param("list").filter(|l| !l.is_empty()),
                start: param("t").or_else(|| param("start")).and_then(|t| parse_link_time(&t)).filter(|t| *t > 0.0),
            },
            innertube::ResolvedUrl::Channel(id) => Route::Channel(id),
            innertube::ResolvedUrl::Playlist(id) => Route::Playlist(id),
        })
    })
}

/// "90", "90s", "1m30s" or "1h2m3s" in seconds.
fn parse_link_time(t: &str) -> Option<f64> {
    if let Ok(s) = t.trim_end_matches('s').parse::<f64>() {
        return Some(s);
    }
    let (mut total, mut num) = (0.0, String::new());
    for c in t.chars() {
        if c.is_ascii_digit() || c == '.' {
            num.push(c);
        } else {
            let n: f64 = num.parse().ok()?;
            num.clear();
            total += n * match c {
                'h' => 3600.0,
                'm' => 60.0,
                's' => 1.0,
                _ => return None,
            };
        }
    }
    (num.is_empty()).then_some(total)
}

#[cfg(test)]
mod link_tests {
    use super::{Route, parse_link_time, route_from_link};

    #[test]
    fn links_keep_time_and_playlist() {
        let r = route_from_link("streamtube://open?url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3DdQw4w9WgXcQ%26t%3D1m30s%26list%3DPL123");
        assert!(matches!(r, Some(Route::Watch { ref id, playlist: Some(ref l), start: Some(s) }) if id == "dQw4w9WgXcQ" && l == "PL123" && s == 90.0), "{r:?}");
        let r = route_from_link("https://youtu.be/dQw4w9WgXcQ?t=42");
        assert!(matches!(r, Some(Route::Watch { start: Some(s), playlist: None, .. }) if s == 42.0), "{r:?}");
        let r = route_from_link("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        assert!(matches!(r, Some(Route::Watch { start: None, playlist: None, .. })), "{r:?}");
        assert_eq!(parse_link_time("1h2m3s"), Some(3723.0));
        assert_eq!(parse_link_time("abc"), None);
    }
}

pub fn navigate(route: Route, window: &mut Window, cx: &mut App) {
    // Ctrl+click (Cmd+click) on any card or link opens it in a new tab, like a browser
    if window.modifiers().secondary(cx) {
        open_in_new_tab(route, false, window, cx);
        return;
    }
    if let Some(app) = cx.try_global::<AppHandle>().and_then(|h| h.0.upgrade()) {
        app.update(cx, |a, cx| a.go(route, window, cx));
    }
}

/// Opens a page in a new tab, in the background unless `switch` is set.
pub fn open_in_new_tab(route: Route, switch: bool, window: &mut Window, cx: &mut App) {
    if let Some(app) = cx.try_global::<AppHandle>().and_then(|h| h.0.upgrade()) {
        app.update(cx, |a, cx| a.new_tab(route, switch, window, cx));
    }
}

pub fn go_back(window: &mut Window, cx: &mut App) {
    if let Some(app) = cx.try_global::<AppHandle>().and_then(|h| h.0.upgrade()) {
        app.update(cx, |a, cx| a.back(window, cx));
    }
}

/// Leaves the watch page while keeping playback (mini player).
pub fn go_back_keep_playing(window: &mut Window, cx: &mut App) {
    exit_fullscreen(window, cx);
    if let Some(app) = cx.try_global::<AppHandle>().and_then(|h| h.0.upgrade()) {
        app.update(cx, |a, cx| {
            if a.history.is_empty() {
                a.go(Route::Home, window, cx);
            } else {
                a.back(window, cx);
            }
        });
    }
}

pub fn toggle_fullscreen(window: &mut Window, cx: &mut App) {
    let mut need_hide_taskbar = false;
    let ui = cx.global_mut::<UiState>();
    if ui.windowed {
        // windowed fullscreen -> real fullscreen, the layout stays the same
        ui.windowed = false;
        if !window.is_fullscreen() {
            ui.video_fs_entered_os = true;
            window.toggle_fullscreen();
            need_hide_taskbar = true;
        }
    } else if !ui.fullscreen {
        ui.fullscreen = true;
        if !window.is_fullscreen() {
            ui.video_fs_entered_os = true;
            window.toggle_fullscreen();
            need_hide_taskbar = true;
        }
    } else {
        ui.fullscreen = false;
        if std::mem::take(&mut ui.video_fs_entered_os) && window.is_fullscreen() {
            window.toggle_fullscreen();
        }
    }
    if need_hide_taskbar {
        schedule_fullscreen_taskbar_hide(window, cx);
    }
    cx.refresh_windows();
}

pub fn toggle_theater_mode(cx: &mut App) {
    store::update_settings(|settings| settings.theater_mode = !settings.theater_mode);
    cx.refresh_windows();
}

/// The player fills the whole window (no top bar, sidebar or page) without going
/// to OS fullscreen, like the "Windowed FullScreen" browser extension.
pub fn toggle_windowed_fullscreen(window: &mut Window, cx: &mut App) {
    let ui = cx.global_mut::<UiState>();
    if ui.windowed {
        ui.windowed = false;
        ui.fullscreen = false;
    } else if ui.fullscreen {
        // real fullscreen -> windowed fullscreen
        if std::mem::take(&mut ui.video_fs_entered_os) && window.is_fullscreen() {
            window.toggle_fullscreen();
        }
        ui.windowed = true;
    } else {
        ui.fullscreen = true;
        ui.windowed = true;
    }
    cx.refresh_windows();
}

/// Leaves either kind of fullscreen.
pub fn exit_fullscreen(window: &mut Window, cx: &mut App) {
    let ui = cx.global_mut::<UiState>();
    if ui.windowed {
        ui.windowed = false;
        ui.fullscreen = false;
        cx.refresh_windows();
    } else if ui.fullscreen {
        toggle_fullscreen(window, cx);
    }
}

/// Called by the player when the queue advances so the watch page follows.
pub fn sync_watch_route(id: String, cx: &mut App) {
    if let Some(app) = cx.try_global::<AppHandle>().and_then(|h| h.0.upgrade()) {
        app.update(cx, |a, cx| {
            if let Route::Watch { .. } = a.route {
                a.pending_watch_sync = Some(id);
                cx.notify();
            }
        });
    }
}

pub fn is_current_page(page: EntityId, cx: &App) -> bool {
    cx.try_global::<AppHandle>().and_then(|h| h.0.upgrade()).is_some_and(|app| app.read(cx).page.entity_id() == page)
}

pub fn has_autoplay_next(cx: &App) -> bool {
    cx.try_global::<AutoplayNext>().map(|a| a.0.is_some()).unwrap_or(false)
}

/// The video the watch page proposes to autoplay next (first related).
#[derive(Default)]
pub struct AutoplayNext(pub Option<VideoItem>);
impl Global for AutoplayNext {}

pub fn play_next(window: &mut Window, cx: &mut App) {
    let p = player(cx);
    let (pos, len) = {
        let pl = p.read(cx);
        (pl.queue_pos, pl.queue.len())
    };
    if let Some(i) = pos.filter(|i| i + 1 < len) {
        p.update(cx, |p, cx| p.play_queue_index(i + 1, cx));
        return;
    }
    if let Some(v) = cx.default_global::<AutoplayNext>().0.clone() {
        navigate(Route::Watch { id: v.id, playlist: None, start: None }, window, cx);
    }
}

// ───────────────────────── menus & dialogs ─────────────────────────

#[derive(Clone)]
pub struct MenuItem {
    pub icon: &'static str,
    pub label: SharedString,
    pub action: Arc<dyn Fn(&mut Window, &mut App)>,
}

impl MenuItem {
    pub fn new(icon: &'static str, label: impl Into<SharedString>, f: impl Fn(&mut Window, &mut App) + 'static) -> Self {
        Self { icon, label: label.into(), action: Arc::new(f) }
    }
}

#[derive(Default)]
pub struct OpenMenu(pub Option<(Point<Pixels>, Vec<MenuItem>)>);
impl Global for OpenMenu {}

pub fn open_menu(pos: Point<Pixels>, items: Vec<MenuItem>, _window: &mut Window, cx: &mut App) {
    cx.set_global(OpenMenu(Some((pos, items))));
    cx.refresh_windows();
}

pub enum Dialog {
    SignIn,
    /// signing in to youtube.com in a browser, so watches reach the account
    WebSignIn,
    SaveToPlaylist(VideoItem),
    TextPrompt { title: String, placeholder: String, on_submit: Arc<dyn Fn(String, &mut Window, &mut App)> },
    Confirm { title: String, body: String, confirm: String, on_confirm: Arc<dyn Fn(&mut Window, &mut App)> },
    /// a newer version is on GitHub
    Update,
}

/// Pulls the account's watch history (videos watched on other devices, with resume
/// positions) into the local history, then refreshes progress bars.
pub fn sync_account_history(cx: &mut App) {
    if !crate::api::auth::signed_in() || !store::settings_snapshot().history_enabled {
        return;
    }
    cx.spawn(async move |cx| {
        let res = blocking(|| {
            let _ = crate::api::auth::sync_search_history();
            crate::api::auth::sync_history(3)
        })
        .await;
        if res.is_ok() {
            let _ = cx.update(|cx| {
                cx.set_global(crate::ui::cards::Watched(store::progress_map()));
                cx.refresh_windows();
            });
        }
    })
    .detach();
}

/// State of the TV device-code sign-in shown in the sign-in dialog.
#[derive(Default)]
pub struct LoginFlow {
    code: Option<crate::api::auth::DeviceCode>,
    error: Option<String>,
    attempt: u64,
}

/// State of the browser sign-in dialog.
#[derive(Default)]
pub struct WebLoginFlow {
    error: Option<String>,
    browser: Option<String>,
    cancel: Option<Arc<std::sync::atomic::AtomicBool>>,
    attempt: u64,
}

pub fn open_web_sign_in(cx: &mut App) {
    if let Some(app) = cx.try_global::<AppHandle>().and_then(|h| h.0.upgrade()) {
        app.update(cx, |a, cx| a.start_web_sign_in(cx));
    }
}

/// Connects the browser session from a cookies.txt file the user picks.
pub fn import_web_cookies(cx: &mut App) {
    let rx = cx.prompt_for_paths(PathPromptOptions { files: true, directories: false, multiple: false, prompt: Some("Use cookies".into()) });
    cx.spawn(async move |cx| {
        let Ok(Ok(Some(paths))) = rx.await else { return };
        let Some(path) = paths.into_iter().next() else { return };
        let res = blocking(move || crate::api::web::import_cookies_txt(&path)).await;
        let _ = cx.update(|cx| {
            if let Some(app) = cx.try_global::<AppHandle>().and_then(|h| h.0.upgrade()) {
                app.update(cx, |a, cx| {
                    if matches!(a.dialog, Some(Dialog::WebSignIn)) {
                        if let Some(c) = a.web_login.cancel.take() {
                            c.store(true, std::sync::atomic::Ordering::Relaxed);
                        }
                        a.dialog = None;
                    }
                    cx.notify();
                });
            }
            match res {
                Ok(name) => toast(cx, tf!("Connected as {0}. What you watch now shows in your YouTube history.", name)),
                Err(e) => toast(cx, crate::ui::friendly_error(&format!("{e:#}"))),
            }
            cx.refresh_windows();
        });
    })
    .detach();
}

pub fn disconnect_web(cx: &mut App) {
    crate::api::web::disconnect();
    toast(cx, t("Browser sign-in disconnected"));
    cx.refresh_windows();
}

pub fn open_sign_in(_window: &mut Window, cx: &mut App) {
    if let Some(app) = cx.try_global::<AppHandle>().and_then(|h| h.0.upgrade()) {
        app.update(cx, |a, cx| a.start_sign_in(cx));
    }
}

pub fn sign_out(cx: &mut App) {
    crate::api::auth::sign_out();
    toast(cx, t("Signed out"));
    if let Some(app) = cx.try_global::<AppHandle>().and_then(|h| h.0.upgrade()) {
        app.update(cx, |a, cx| {
            a.reload_page = true;
            cx.notify();
        });
    }
}

pub fn open_save_dialog(v: VideoItem, window: &mut Window, cx: &mut App) {
    open_dialog(Dialog::SaveToPlaylist(v), window, cx);
    // signed in: the account's own YouTube playlists are offered too
    if !crate::api::auth::signed_in() {
        return;
    }
    if let Some(app) = cx.try_global::<AppHandle>().and_then(|h| h.0.upgrade()) {
        app.update(cx, |a, cx| {
            a.yt_playlists = None;
            cx.spawn(async move |this, cx| {
                let list = blocking(crate::api::auth::my_playlists).await.unwrap_or_default();
                // Liked videos can't take additions
                let list: Vec<crate::api::types::PlaylistItem> = list.into_iter().filter(|p| p.id != "LL").collect();
                let _ = this.update(cx, |a, cx| {
                    a.yt_playlists = Some(list);
                    cx.notify();
                });
            })
            .detach();
        });
    }
}

pub fn open_dialog(d: Dialog, window: &mut Window, cx: &mut App) {
    if let Some(app) = cx.try_global::<AppHandle>().and_then(|h| h.0.upgrade()) {
        app.update(cx, |a, cx| {
            a.dialog_input.update(cx, |i, cx| i.set_value("", window, cx));
            if let Dialog::TextPrompt { placeholder, .. } = &d {
                a.dialog_input.update(cx, |input, cx| input.set_placeholder(t(placeholder).to_string(), window, cx));
                let fh = a.dialog_input.read(cx).focus_handle(cx);
                window.focus(&fh, cx);
            }
            a.dialog = Some(d);
            cx.notify();
        });
    }
}

pub fn prompt_text(title: &str, placeholder: &str, window: &mut Window, cx: &mut App, f: impl Fn(String, &mut Window, &mut App) + 'static) {
    open_dialog(
        Dialog::TextPrompt { title: title.into(), placeholder: placeholder.into(), on_submit: Arc::new(f) },
        window,
        cx,
    );
}

pub fn confirm(title: &str, body: &str, confirm: &str, window: &mut Window, cx: &mut App, f: impl Fn(&mut Window, &mut App) + 'static) {
    open_dialog(
        Dialog::Confirm { title: title.into(), body: body.into(), confirm: confirm.into(), on_confirm: Arc::new(f) },
        window,
        cx,
    );
}

// ───────────────────────── AppView ─────────────────────────

/// A tab that isn't the one shown. The shown tab lives in AppView's own
/// route/page/history fields; switching swaps them with one of these.
#[derive(Clone)]
struct Tab {
    id: u64,
    route: Route,
    /// built the first time the tab is shown, so a video opened in the
    /// background doesn't start playing on its own
    page: Option<AnyView>,
    history: Vec<(Route, AnyView)>,
    forward: Vec<(Route, AnyView)>,
    /// the queue and position of the video this tab was playing when it was
    /// left, so coming back resumes it with its queue (one player is shared)
    playback: Option<(String, Vec<crate::api::types::VideoItem>, Option<usize>, f64)>,
}

/// At most this many tabs; more would only hold pages nobody looks at.
const MAX_TABS: usize = 12;

pub struct AppView {
    /// every tab in order; the entry at `active_tab` is a placeholder for the
    /// tab shown through the fields below
    tabs: Vec<Tab>,
    active_tab: usize,
    next_tab_id: u64,
    pub route: Route,
    /// space is down: released quickly it toggles pause, held it plays at 2x
    space_down: bool,
    page: AnyView,
    pub history: Vec<(Route, AnyView)>,
    forward: Vec<(Route, AnyView)>,
    search: Entity<InputState>,
    suggestions: Vec<String>,
    history_suggestions: Vec<String>,
    show_suggestions: bool,
    suggestion_serial: u64,
    sel_suggestion: Option<usize>,
    mini_view: Entity<PlayerView>,
    /// Mini player placement: distance from the window's right and bottom edges, and width
    mini_geom: (f32, f32, f32),
    mini_drag: Option<MiniDrag>,
    /// the last press on the mini player's title moved it (so its click doesn't open the video)
    mini_dragged: bool,
    main_view: Entity<PlayerView>,
    preview_view: Entity<crate::player::preview::PreviewView>,
    /// bounded cache for every thumbnail and avatar in this window
    images: Entity<crate::ui::image_cache::LruImageCache>,
    had_video: bool,
    last_bounds_save: std::time::Instant,
    dialog: Option<Dialog>,
    /// the signed-in account's playlists for the Save dialog (None while loading)
    yt_playlists: Option<Vec<crate::api::types::PlaylistItem>>,
    dialog_input: Entity<InputState>,
    focus: FocusHandle,
    pending_watch_sync: Option<String>,
    login: LoginFlow,
    web_login: WebLoginFlow,
    reload_page: bool,
    /// slide-over sidebar on the watch page
    drawer_open: bool,
    /// sidebar subscriptions list: all channels instead of the first few
    subs_expanded: bool,
    /// downloads dropdown under the topbar button
    dl_panel: bool,
    /// flagship 3.2s optical cinema startup splash overlay
    pub(super) splash: Option<super::splash::SplashState>,
    _subs: Vec<Subscription>,
}

impl AppView {
    pub fn new(window: &mut Window, cx: &mut Context<Self>) -> Self {
        cx.set_global(AppHandle(cx.entity().downgrade()));
        cx.set_global(UiState { sidebar_open: true, ..Default::default() });
        cx.set_global(OpenMenu::default());
        crate::downloads::load_history(cx);
        cx.set_global(AutoplayNext::default());
        cx.set_global(crate::ui::cards::Watched(store::progress_map()));
        let search = cx.new(|cx| InputState::new(window, cx).placeholder(t("Search or paste a YouTube link")));
        let dialog_input = cx.new(|cx| InputState::new(window, cx));
        let mut subs = Vec::new();
        subs.push(cx.subscribe_in(&search, window, |this: &mut Self, state, ev: &InputEvent, window, cx| match ev {
            InputEvent::PressEnter { .. } => {
                let q = if let Some(i) = this.sel_suggestion {
                    this.all_suggestions().get(i).cloned().unwrap_or_default()
                } else {
                    state.read(cx).value().to_string()
                };
                this.submit_search(q, window, cx);
            }
            InputEvent::Change => {
                this.sel_suggestion = None;
                let q = state.read(cx).value().to_string();
                this.update_suggestions(q, cx);
            }
            InputEvent::Focus => {
                this.show_suggestions = true;
                let q = state.read(cx).value().to_string();
                this.update_suggestions(q, cx);
            }
            InputEvent::Blur => {
                // delay so clicks on suggestions register
                cx.spawn(async move |this, cx| {
                    cx.background_executor().timer(std::time::Duration::from_millis(200)).await;
                    let _ = this.update(cx, |a, cx| {
                        a.show_suggestions = false;
                        cx.notify();
                    });
                })
                .detach();
            }
        }));
        subs.push(cx.subscribe_in(&dialog_input, window, |this: &mut Self, state, ev: &InputEvent, window, cx| {
            if let InputEvent::PressEnter { .. } = ev {
                let v = state.read(cx).value().to_string();
                if let Some(Dialog::TextPrompt { on_submit, .. }) = this.dialog.take() {
                    if !v.trim().is_empty() {
                        on_submit(v.trim().to_string(), window, cx);
                    }
                }
                cx.notify();
            }
        }));
        let p = player(cx);
        // Only re-render the shell when the mini player appears/disappears; the
        // player views re-render themselves for every frame.
        subs.push(cx.observe(&p, |this: &mut Self, p, cx| {
            let has = p.read(cx).video.is_some();
            if has != this.had_video {
                this.had_video = has;
                cx.notify();
            }
        }));
        subs.push(cx.observe_window_bounds(window, |this: &mut Self, window, _| {
            // persist placement at most twice a second while the user drags/resizes
            if this.last_bounds_save.elapsed() > std::time::Duration::from_millis(500) {
                this.last_bounds_save = std::time::Instant::now();
                crate::save_window_bounds(window);
            }
        }));
        // "Background play" off: pause when the window is minimized, resume when restored
        // a hold-for-2x can't outlive the window losing focus (the key/button release goes elsewhere)
        subs.push(cx.observe_window_activation(window, |this: &mut Self, window, cx| {
            if !window.is_window_active() {
                this.space_down = false;
                player(cx).update(cx, |p, cx| {
                    p.end_boost();
                    cx.notify();
                });
            }
        }));
        subs.push(window.observe_window_visibility({
            let paused_by_us = std::rc::Rc::new(std::cell::Cell::new(false));
            move |vis, window, cx| {
                let pl = player(cx);
                if !vis.is_visible() {
                    if store::settings_snapshot().background_play || crate::ui::pip::handle(cx).is_some() || !window_minimized(window) {
                        return;
                    }
                    if !pl.read(cx).paused && pl.read(cx).video.is_some() {
                        pl.update(cx, |p, cx| {
                            p.set_paused(true);
                            cx.notify();
                        });
                        paused_by_us.set(true);
                    }
                } else if paused_by_us.replace(false) {
                    pl.update(cx, |p, cx| {
                        p.set_paused(false);
                        cx.notify();
                    });
                }
            }
        }));
        subs.push(cx.observe_window_appearance(window, |_, window, cx| {
            if store::settings_snapshot().theme == "system" {
                apply_theme(Some(window), cx);
            }
        }));
        window.on_window_should_close(cx, |window, _| {
            crate::save_window_bounds(window);
            true
        });
        let mini_view = cx.new(|cx| PlayerView::new(p.clone(), true, cx));
        let main_view = cx.new(|cx| PlayerView::new(p.clone(), false, cx));
        let preview_view = cx.new(crate::player::preview::PreviewView::new);
        let images = crate::ui::image_cache::init(cx);
        // re-render (to add or drop the preview layer) only when a preview starts or ends
        subs.push(cx.subscribe(&crate::player::preview::preview(cx), |_, _, _: &crate::player::preview::PreviewEvent, cx| cx.notify()));
        cx.set_global(crate::ui::slot::PlayerSlot::default());
        // a YouTube link passed on the command line opens directly
        let arg_route = route_from_link(&std::env::args().nth(1).unwrap_or_default());
        let start = arg_route.unwrap_or_else(|| match store::settings_snapshot().start_page.as_str() {
            "subscriptions" => Route::Subscriptions,
            "history" => Route::History,
            _ => Route::Home,
        });
        crate::perf::mark("app shell");
        let page = build_page(&start, window, cx);
        crate::perf::mark("start page");
        crate::updater::check_on_startup(cx);
        if let Route::Search(q) = &start {
            let q = q.clone();
            search.update(cx, |st, cx| st.set_value(q, window, cx));
        }
        let focus = cx.focus_handle();
        window.focus(&focus, cx);
        // keep the local subscription list (sidebar, local feed) in step with the
        // account: immediately when it's empty, otherwise once a day
        if crate::api::auth::signed_in() {
            sync_account_history(cx);
            let last = store::kv_get("subs_synced_at").and_then(|s| s.parse::<i64>().ok()).unwrap_or(0);
            if store::subscriptions().is_empty() || crate::api::json::now() - last > 24 * 3600 {
                cx.spawn(async move |this, cx| {
                    let res = blocking(crate::api::auth::sync_subscriptions).await;
                    if res.is_ok() {
                        store::kv_set("subs_synced_at", &crate::api::json::now().to_string());
                    }
                    let _ = this.update(cx, |_, cx| cx.notify());
                })
                .detach();
            }
        }
        let app = Self {
            tabs: vec![Tab { id: 1, route: start.clone(), page: None, history: Vec::new(), forward: Vec::new(), playback: None }],
            active_tab: 0,
            next_tab_id: 2,
            route: start,
            page,
            history: Vec::new(),
            forward: Vec::new(),
            search,
            suggestions: Vec::new(),
            history_suggestions: Vec::new(),
            show_suggestions: false,
            suggestion_serial: 0,
            sel_suggestion: None,
            mini_view,
            mini_geom: load_mini_geom(),
            mini_drag: None,
            mini_dragged: false,
            main_view,
            preview_view,
            images,
            had_video: false,
            last_bounds_save: std::time::Instant::now(),
            dialog: None,
            yt_playlists: None,
            dialog_input,
            focus,
            pending_watch_sync: None,
            login: LoginFlow::default(),
            web_login: WebLoginFlow::default(),
            reload_page: false,
            drawer_open: false,
            subs_expanded: false,
            dl_panel: false,
            space_down: false,
            splash: Some(super::splash::SplashState::start()),
            _subs: subs,
        };
        app.start_splash_loop(cx);
        app
    }

    fn start_splash_loop(&self, cx: &mut Context<Self>) {
        super::splash::spawn_splash_ticker(cx);
    }

    fn skip_splash(&mut self, cx: &mut Context<Self>) {
        if let Some(s) = self.splash.take() {
            s.stop_audio();
            self.space_down = false;
            cx.notify();
        }
    }

    fn start_sign_in(&mut self, cx: &mut Context<Self>) {
        use crate::api::auth::{self, PollResult};
        use std::time::{Duration, Instant};
        self.login.attempt += 1;
        let attempt = self.login.attempt;
        self.login.code = None;
        self.login.error = None;
        self.dialog = Some(Dialog::SignIn);
        cx.notify();
        cx.spawn(async move |this, cx| {
            let fail = |this: &WeakEntity<Self>, cx: &mut AsyncApp, msg: String| {
                let _ = this.update(cx, |t, cx| {
                    if t.login.attempt == attempt {
                        t.login.error = Some(msg);
                        cx.notify();
                    }
                });
            };
            let dc = match blocking(auth::start_login).await {
                Ok(dc) => dc,
                Err(e) => return fail(&this, cx, crate::ui::friendly_error(&e.to_string())),
            };
            let deadline = Instant::now() + Duration::from_secs(dc.expires_in);
            let mut interval = dc.interval.max(3);
            let _ = this.update(cx, |t, cx| {
                if t.login.attempt == attempt {
                    t.login.code = Some(dc.clone());
                    cx.notify();
                }
            });
            let mut errors = 0;
            loop {
                cx.background_executor().timer(Duration::from_secs(interval)).await;
                let waiting = this
                    .update(cx, |t, _| t.login.attempt == attempt && matches!(t.dialog, Some(Dialog::SignIn)))
                    .unwrap_or(false);
                if !waiting {
                    return;
                }
                if Instant::now() > deadline {
                    return fail(&this, cx, t("The code expired. Try again.").into());
                }
                let d = dc.clone();
                match blocking(move || auth::poll_login(&d)).await {
                    Ok(PollResult::Pending) => errors = 0,
                    Ok(PollResult::SlowDown) => interval += 2,
                    Ok(PollResult::Done(acc)) => {
                        let _ = this.update(cx, |t, cx| {
                            t.dialog = None;
                            t.login.code = None;
                            t.reload_page = true;
                            toast(cx, tf!("Signed in as {0}", acc.name));
                            cx.notify();
                        });
                        // bring the account's history and subscriptions to this device
                        let _ = cx.update(|cx| sync_account_history(cx));
                        let res = blocking(auth::sync_subscriptions).await;
                        let _ = this.update(cx, |t, cx| {
                            if let Ok(n) = res {
                                if n > 0 {
                                    toast(cx, tf!("Added {0} subscriptions from your account", n));
                                    t.reload_page = true;
                                    cx.notify();
                                }
                            }
                        });
                        return;
                    }
                    Err(e) => {
                        let msg = e.to_string();
                        // network hiccups don't end the sign-in; a refusal from Google does
                        errors += 1;
                        let refused = msg.contains("expired") || msg.contains("cancelled") || msg.contains("failed");
                        if !refused && errors < 4 {
                            continue;
                        }
                        return fail(&this, cx, crate::ui::friendly_error(&msg));
                    }
                }
            }
        })
        .detach();
    }

    fn start_web_sign_in(&mut self, cx: &mut Context<Self>) {
        use std::sync::atomic::{AtomicBool, Ordering};
        if let Some(c) = self.web_login.cancel.take() {
            c.store(true, Ordering::Relaxed);
        }
        self.web_login.attempt += 1;
        let attempt = self.web_login.attempt;
        let cancel = Arc::new(AtomicBool::new(false));
        self.web_login.cancel = Some(cancel.clone());
        self.web_login.error = None;
        self.web_login.browser = crate::api::web::browser_name();
        self.dialog = Some(Dialog::WebSignIn);
        cx.notify();
        // closing the dialog any way at all stops the sign-in and closes the browser
        let watch = cancel.clone();
        cx.spawn(async move |this, cx| loop {
            cx.background_executor().timer(std::time::Duration::from_millis(700)).await;
            if watch.load(Ordering::Relaxed) {
                return;
            }
            let open = this.update(cx, |t, _| t.web_login.attempt == attempt && matches!(t.dialog, Some(Dialog::WebSignIn))).unwrap_or(false);
            if !open {
                watch.store(true, Ordering::Relaxed);
                return;
            }
        })
        .detach();
        cx.spawn(async move |this, cx| {
            let c2 = cancel.clone();
            let res = blocking(move || crate::api::web::browser_sign_in(c2)).await;
            let _ = this.update(cx, |t, cx| {
                if t.web_login.attempt != attempt {
                    return;
                }
                t.web_login.cancel = None;
                match res {
                    Ok(name) => {
                        if matches!(t.dialog, Some(Dialog::WebSignIn)) {
                            t.dialog = None;
                        }
                        toast(cx, tf!("Connected as {0}. What you watch now shows in your YouTube history.", name));
                        cx.refresh_windows();
                    }
                    Err(e) => {
                        if !cancel.load(Ordering::Relaxed) {
                            t.web_login.error = Some(crate::ui::friendly_error(&format!("{e:#}")));
                        }
                    }
                }
                cx.notify();
            });
        })
        .detach();
    }

    fn render_web_sign_in(&self, cx: &mut Context<Self>) -> AnyElement {
        let p = pal(cx);
        let browser = self.web_login.browser.clone().unwrap_or_else(|| "browser".into());
        let mut col = div().flex().flex_col().gap_4().child(
            div()
                .flex()
                .items_center()
                .gap_3()
                .child(icon("history").size_6().text_color(p.text))
                .child(div().text_lg().font_weight(FontWeight::SEMIBOLD).text_color(p.text).child(t("Connect your YouTube history"))),
        );
        let cookies_link = div()
            .id("wsi-cookies")
            .text_xs()
            .text_color(p.link)
            .cursor_pointer()
            .hover(|s| s.underline())
            .child(t("Use a cookies.txt file instead"))
            .on_click(|_, _, cx| import_web_cookies(cx));
        let cancel = pill("wsi-cancel", None, "Cancel", cx).on_click(cx.listener(|this, _, _, cx| {
            if let Some(c) = this.web_login.cancel.take() {
                c.store(true, std::sync::atomic::Ordering::Relaxed);
            }
            this.dialog = None;
            cx.notify();
        }));
        if let Some(err) = &self.web_login.error {
            col = col.child(div().text_sm().text_color(p.text2).child(err.clone())).child(
                div()
                    .flex()
                    .items_center()
                    .justify_between()
                    .child(cookies_link)
                    .child(
                        div()
                            .flex()
                            .gap_2()
                            .child(cancel)
                            .child(pill("wsi-retry", Some("refresh-cw"), "Try again", cx).on_click(cx.listener(|this, _, _, cx| this.start_web_sign_in(cx)))),
                    ),
            );
        } else {
            col = col
                .child(
                    div()
                        .flex()
                        .flex_col()
                        .gap_2()
                        .text_sm()
                        .text_color(p.text2)
                        .child(tf!("Sign in to YouTube in the {0} window that opened. It closes by itself when you're done.", browser))
                        .child(
                            div()
                                .text_xs()
                                .text_color(p.text3)
                                .child(t("Videos you watch in M. ASSAHTOUT TV then show in your YouTube history, and your phone and TV resume them where you stopped. The window uses its own empty profile, which is deleted afterwards.")),
                        ),
                )
                .child(
                    div()
                        .flex()
                        .items_center()
                        .justify_between()
                        .child(div().flex().items_center().gap_2().child(spinner(cx)).child(div().text_xs().text_color(p.text3).child(t("Waiting for sign-in…"))))
                        .child(cancel),
                )
                .child(cookies_link);
        }
        col.into_any_element()
    }

    fn render_sign_in(&self, cx: &mut Context<Self>) -> AnyElement {
        let p = pal(cx);
        let mut col = div().flex().flex_col().gap_4().child(
            div()
                .flex()
                .items_center()
                .gap_3()
                .child(icon("circle-user").size_6().text_color(p.text))
                .child(div().text_lg().font_weight(FontWeight::SEMIBOLD).text_color(p.text).child(t("Sign in to YouTube"))),
        );
        if let Some(err) = &self.login.error {
            col = col.child(div().text_sm().text_color(p.text2).child(err.clone())).child(
                div()
                    .flex()
                    .justify_end()
                    .gap_2()
                    .child(pill("si-cancel", None, "Cancel", cx).on_click(cx.listener(|this, _, _, cx| {
                        this.dialog = None;
                        cx.notify();
                    })))
                    .child(pill("si-retry", Some("refresh-cw"), "Try again", cx).on_click(cx.listener(|this, _, _, cx| this.start_sign_in(cx)))),
            );
        } else if let Some(dc) = &self.login.code {
            let code = dc.user_code.clone();
            let url = dc.verification_url.clone();
            let shown_url = url.trim_start_matches("https://").trim_start_matches("http://").trim_start_matches("www.").to_string();
            let code2 = code.clone();
            let url2 = url.clone();
            col = col
                .child(
                    div()
                        .flex()
                        .flex_col()
                        .gap_1()
                        .text_sm()
                        .text_color(p.text2)
                        .child(
                            div().flex().gap_1().child(t("1. On any device, go to")).child(
                                div()
                                    .id("si-url")
                                    .text_color(p.link)
                                    .cursor_pointer()
                                    .hover(|s| s.underline())
                                    .child(shown_url)
                                    .on_click(move |_, _, _| {
                                        let _ = open::that(&url2);
                                    }),
                            ),
                        )
                        .child(t("2. Enter this code and pick your account")),
                )
                .child(
                    div()
                        .id("si-code")
                        .flex()
                        .items_center()
                        .justify_center()
                        .py_4()
                        .rounded_lg()
                        .border_1()
                        .border_color(p.border)
                        .bg(p.bg)
                        .cursor_pointer()
                        .hover(move |s| s.bg(p.hover))
                        .text_2xl()
                        .font_weight(FontWeight::BOLD)
                        .text_color(p.text)
                        .child(code.clone())
                        .on_click(move |_, _, cx| copy_to_clipboard(code2.clone(), cx)),
                )
                .child(
                    div()
                        .flex()
                        .items_center()
                        .justify_between()
                        .child(div().text_xs().text_color(p.text3).child(t("Waiting for approval…")))
                        .child(
                            div()
                                .flex()
                                .gap_2()
                                .child(pill("si-cancel", None, "Cancel", cx).on_click(cx.listener(|this, _, _, cx| {
                                    this.dialog = None;
                                    cx.notify();
                                })))
                                .child(pill("si-open", Some("external-link"), "Copy code & open", cx).bg(p.link).text_color(white()).on_click(
                                    move |_, _, cx| {
                                        copy_to_clipboard(code.clone(), cx);
                                        let _ = open::that(&url);
                                    },
                                )),
                        ),
                );
        } else {
            col = col.child(
                div()
                    .flex()
                    .items_center()
                    .gap_3()
                    .py_4()
                    .child(spinner(cx))
                    .child(div().text_sm().text_color(p.text2).child(t("Getting a code…"))),
            );
        }
        col.into_any_element()
    }

    fn account_button(&mut self, cx: &mut Context<Self>) -> AnyElement {
        let p = pal(cx);
        if !crate::api::auth::signed_in() {
            return div()
                .id("sign-in")
                .flex()
                .flex_none()
                .items_center()
                .gap_2()
                .h_9()
                .px_3()
                .ml_2()
                .rounded_full()
                .border_1()
                .border_color(p.border)
                .text_sm()
                .font_weight(FontWeight::MEDIUM)
                .text_color(p.link)
                .cursor_pointer()
                .hover(move |s| s.bg(p.hover))
                .child(icon("circle-user").size_5().text_color(p.link))
                .child(t("Sign in"))
                .on_click(cx.listener(|this, _, _, cx| this.start_sign_in(cx)))
                .into_any_element();
        }
        let acc = crate::api::auth::account().unwrap_or_default();
        div()
            .id("account")
            .flex()
            .flex_none()
            .items_center()
            .justify_center()
            .size_10()
            .ml_1()
            .rounded_full()
            .cursor_pointer()
            .hover(move |s| s.bg(p.hover))
            .child(crate::ui::avatar(acc.avatar.as_deref(), px(30.), &acc.name, cx))
            .on_click(move |ev, window, cx| {
                let label = match &acc.handle {
                    Some(h) => format!("{}  ·  {}", acc.name, h),
                    None => acc.name.clone(),
                };
                let handle = acc.handle.clone();
                let items = vec![
                    MenuItem::new("circle-user", label, |_, _| {}),
                    MenuItem::new("square-user", t("Your channel"), move |_, cx| {
                        let Some(h) = handle.clone() else {
                            toast(cx, t("Channel handle unknown"));
                            return;
                        };
                        let url = format!("https://www.youtube.com/{}", if h.starts_with('@') { h } else { format!("@{h}") });
                        cx.spawn(async move |cx| {
                            let res = blocking(move || innertube::resolve_url(&url)).await;
                            let _ = cx.update(|cx| match res {
                                Ok(innertube::ResolvedUrl::Channel(id)) => {
                                    if let Some(w) = cx.active_window() {
                                        let _ = w.update(cx, |_, window, cx| navigate(Route::Channel(id), window, cx));
                                    }
                                }
                                _ => toast(cx, t("Could not open your channel")),
                            });
                        })
                        .detach();
                    }),
                    MenuItem::new("history", t("History"), |window, cx| navigate(Route::History, window, cx)),
                    MenuItem::new("list-video", t("Playlists"), |window, cx| navigate(Route::Library, window, cx)),
                    MenuItem::new("download", t("Downloads"), |window, cx| navigate(Route::Downloads, window, cx)),
                    MenuItem::new("settings", t("Settings"), |window, cx| navigate(Route::Settings, window, cx)),
                    MenuItem::new("refresh-cw", t("Sync subscriptions"), |_, cx| {
                        cx.spawn(async move |cx| {
                            let res = blocking(crate::api::auth::sync_subscriptions).await;
                            let _ = cx.update(|cx| {
                                match res {
                                    Ok(0) => toast(cx, t("Subscriptions are up to date")),
                                    Ok(n) => toast(cx, tf!("Added {0} subscriptions", n)),
                                    Err(e) => toast(cx, crate::ui::friendly_error(&e.to_string())),
                                }
                                cx.refresh_windows();
                            });
                        })
                        .detach();
                    }),
                    MenuItem::new("users", t("Switch account"), |_, cx| {
                        sign_out(cx);
                        if let Some(app) = cx.try_global::<AppHandle>().and_then(|h| h.0.upgrade()) {
                            app.update(cx, |a, cx| a.start_sign_in(cx));
                        }
                    }),
                    MenuItem::new("log-out", t("Sign out"), |_, cx| sign_out(cx)),
                ];
                open_menu(ev.position(), items, window, cx);
            })
            .into_any_element()
    }

    pub fn go(&mut self, route: Route, window: &mut Window, cx: &mut Context<Self>) {
        // items without a known channel/video/playlist have nothing to open
        match &route {
            Route::Channel(id) | Route::Playlist(id) | Route::Watch { id, .. } if id.is_empty() => return,
            _ => {}
        }
        cx.set_global(OpenMenu(None));
        crate::player::preview::stop(cx);
        crate::ui::image_cache::on_navigate(cx);
        self.drawer_open = false;
        if route == self.route {
            // re-navigating to the same place refreshes it (like clicking Home again)
            if !matches!(route, Route::Watch { .. }) {
                crate::ui::slot::clear_slot(cx);
                self.page = build_page(&route, window, cx);
                cx.notify();
            }
            return;
        }
        crate::ui::slot::clear_slot(cx);
        if cx.global::<UiState>().fullscreen && !matches!(route, Route::Watch { .. } | Route::Shorts(_)) {
            exit_fullscreen(window, cx);
        }
        let page = build_page(&route, window, cx);
        let old_route = std::mem::replace(&mut self.route, route);
        let old_page = std::mem::replace(&mut self.page, page);
        // Don't keep stale watch pages in history for the same video
        self.history.push((old_route, old_page));
        if self.history.len() > 40 {
            self.history.remove(0);
        }
        self.forward.clear();
        window.set_window_title(&format!("{} - M. ASSAHTOUT TV", self.route.title()));
        window.focus(&self.focus, cx);
        cx.notify();
    }

    // ───── tabs ─────

    pub fn new_tab(&mut self, route: Route, switch: bool, window: &mut Window, cx: &mut Context<Self>) {
        match &route {
            Route::Channel(id) | Route::Playlist(id) | Route::Watch { id, .. } if id.is_empty() => return,
            _ => {}
        }
        cx.set_global(OpenMenu(None));
        if self.tabs.len() >= MAX_TABS {
            toast(cx, tf!("You can have up to {0} tabs open", MAX_TABS));
            return;
        }
        let id = self.next_tab_id;
        self.next_tab_id += 1;
        // right after the current tab, like a browser
        let at = self.active_tab + 1;
        self.tabs.insert(at, Tab { id, route, page: None, history: Vec::new(), forward: Vec::new(), playback: None });
        if switch {
            self.switch_tab(at, window, cx);
        } else {
            toast(cx, t("Opened in a new tab"));
            cx.notify();
        }
    }

    pub fn switch_tab(&mut self, index: usize, window: &mut Window, cx: &mut Context<Self>) {
        if index >= self.tabs.len() || index == self.active_tab {
            return;
        }
        cx.set_global(OpenMenu(None));
        crate::player::preview::stop(cx);
        crate::ui::image_cache::on_navigate(cx);
        self.drawer_open = false;
        // park the shown tab
        let cur = &mut self.tabs[self.active_tab];
        cur.route = self.route.clone();
        cur.page = Some(self.page.clone());
        cur.history = std::mem::take(&mut self.history);
        cur.forward = std::mem::take(&mut self.forward);
        cur.playback = match &cur.route {
            Route::Watch { id, .. } => {
                let pl = player(cx).read(cx);
                pl.video.as_ref().filter(|v| &v.id == id).map(|v| (v.id.clone(), pl.queue.clone(), pl.queue_pos, pl.time))
            }
            _ => None,
        };
        // and show the other one
        self.active_tab = index;
        let next = self.tabs[index].clone();
        self.route = next.route;
        self.history = next.history;
        self.forward = next.forward;
        // a watch page for another video than the one playing is built again,
        // which starts its video (the same as going back to it)
        let playing = player(cx).read(cx).video.as_ref().map(|v| v.id.clone());
        let stale = matches!(&self.route, Route::Watch { id, .. } if playing.as_deref() != Some(id.as_str()));
        if stale {
            // put back this tab's queue (the watch page keeps a queue its video is in)
            // and carry on where it left off
            if let (Route::Watch { id, start, .. }, Some((vid, queue, pos, time))) = (&mut self.route, next.playback) {
                if *id == vid {
                    if queue.iter().any(|v| v.id == vid) {
                        player(cx).update(cx, |p, _| {
                            p.queue = queue;
                            p.queue_pos = pos;
                        });
                    }
                    if time > 1.0 {
                        *start = Some(time);
                    }
                }
            }
        }
        self.page = match next.page {
            Some(p) if !stale => p,
            _ => build_page(&self.route.clone(), window, cx),
        };
        crate::ui::slot::clear_slot(cx);
        if cx.global::<UiState>().fullscreen && !matches!(self.route, Route::Watch { .. } | Route::Shorts(_)) {
            exit_fullscreen(window, cx);
        }
        window.set_window_title(&format!("{} - M. ASSAHTOUT TV", self.route.title()));
        window.focus(&self.focus, cx);
        cx.notify();
    }

    pub fn close_tab(&mut self, index: usize, window: &mut Window, cx: &mut Context<Self>) {
        if self.tabs.len() < 2 || index >= self.tabs.len() {
            return;
        }
        if index == self.active_tab {
            // show the neighbour first, then drop this one
            let to = if index + 1 < self.tabs.len() { index + 1 } else { index - 1 };
            self.switch_tab(to, window, cx);
        }
        self.tabs.remove(index);
        if index < self.active_tab {
            self.active_tab -= 1;
        }
        cx.notify();
    }

    fn cycle_tab(&mut self, step: isize, window: &mut Window, cx: &mut Context<Self>) {
        let n = self.tabs.len() as isize;
        if n > 1 {
            let i = (self.active_tab as isize + step).rem_euclid(n) as usize;
            self.switch_tab(i, window, cx);
        }
    }

    /// A short name for a tab's page.
    fn tab_label(route: &Route, cx: &App) -> String {
        match route {
            Route::Watch { id, .. } => {
                let p = player(cx).read(cx);
                p.video.as_ref().filter(|v| &v.id == id && !v.title.is_empty()).map(|v| v.title.clone()).unwrap_or_else(|| t("Video").into())
            }
            Route::Channel(id) => store::subscriptions().into_iter().find(|s| &s.channel_id == id).map(|s| s.name).unwrap_or_else(|| t("Channel").into()),
            r => r.title(),
        }
    }

    fn tab_icon(route: &Route) -> &'static str {
        match route {
            Route::Home | Route::Explore(_) => "house",
            Route::Shorts(_) => "zap",
            Route::Subscriptions | Route::Channels => "tv",
            Route::History => "history",
            Route::Library | Route::LocalPlaylist(_) | Route::Playlist(_) => "list-video",
            Route::Search(_) => "search",
            Route::Channel(_) => "circle-user",
            Route::Watch { .. } => "play",
            Route::Settings => "settings",
            Route::Downloads => "download",
        }
    }

    fn render_tabs(&mut self, cx: &mut Context<Self>) -> Option<AnyElement> {
        if self.tabs.len() < 2 {
            return None;
        }
        let p = pal(cx);
        let mut strip = div()
            .id("tabs")
            .flex()
            .flex_none()
            .items_center()
            .gap_1()
            .h(px(38.))
            .px_3()
            .bg(p.bg)
            .border_b_1()
            .border_color(p.border)
            .overflow_x_scroll();
        for i in 0..self.tabs.len() {
            let active = i == self.active_tab;
            let route = if active { self.route.clone() } else { self.tabs[i].route.clone() };
            let id = self.tabs[i].id;
            let label = Self::tab_label(&route, cx);
            strip = strip.child(
                div()
                    .id(("tab", id))
                    .flex()
                    .flex_none()
                    .items_center()
                    .gap_2()
                    .h(px(30.))
                    .pl_3()
                    .pr_1()
                    .w(px(200.))
                    .rounded_lg()
                    .cursor_pointer()
                    .when(active, |d| d.bg(p.surface2))
                    .when(!active, |d| d.hover(|d| d.bg(p.hover)))
                    .on_click(cx.listener(move |this, _, window, cx| {
                        if let Some(i) = this.tabs.iter().position(|t| t.id == id) {
                            this.switch_tab(i, window, cx);
                        }
                    }))
                    // middle click closes, like a browser tab
                    .on_mouse_down(
                        MouseButton::Middle,
                        cx.listener(move |this, _, window, cx| {
                            if let Some(i) = this.tabs.iter().position(|t| t.id == id) {
                                this.close_tab(i, window, cx);
                            }
                        }),
                    )
                    .child(icon(Self::tab_icon(&route)).size_4().text_color(if active { p.accent } else { p.text2 }))
                    .child(
                        div()
                            .flex_1()
                            .min_w_0()
                            .overflow_hidden()
                            .whitespace_nowrap()
                            .text_ellipsis()
                            .text_sm()
                            .text_color(if active { p.text } else { p.text2 })
                            .child(label),
                    )
                    .child(
                        div()
                            .id(("tab-close", id))
                            .flex()
                            .flex_none()
                            .items_center()
                            .justify_center()
                            .size(px(22.))
                            .rounded_md()
                            .hover(|d| d.bg(p.hover))
                            .child(icon("x").size_3p5().text_color(p.text2))
                            .on_click(cx.listener(move |this, _, window, cx| {
                                cx.stop_propagation();
                                if let Some(i) = this.tabs.iter().position(|t| t.id == id) {
                                    this.close_tab(i, window, cx);
                                }
                            })),
                    ),
            );
        }
        strip = strip.child(
            div()
                .id("tab-new")
                .flex()
                .flex_none()
                .items_center()
                .justify_center()
                .size(px(30.))
                .rounded_lg()
                .cursor_pointer()
                .hover(|d| d.bg(p.hover))
                .child(icon("plus").size_4().text_color(p.text2))
                .on_click(cx.listener(|this, _, window, cx| this.new_tab(Route::Home, true, window, cx))),
        );
        Some(strip.into_any_element())
    }

    pub fn back(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if let Some((r, p)) = self.history.pop() {
            crate::ui::slot::clear_slot(cx);
            let cur_r = std::mem::replace(&mut self.route, r);
            let cur_p = std::mem::replace(&mut self.page, p);
            self.forward.push((cur_r, cur_p));
            if cx.global::<UiState>().fullscreen && !matches!(self.route, Route::Watch { .. } | Route::Shorts(_)) {
                toggle_fullscreen(window, cx);
            }
            // If we return to a watch page for a different video than is playing, rebuild it
            if let Route::Watch { id, .. } = &self.route {
                let playing = player(cx).read(cx).video.as_ref().map(|v| v.id.clone());
                if playing.as_deref() != Some(id.as_str()) {
                    self.page = build_page(&self.route.clone(), window, cx);
                }
            }
            window.set_window_title(&format!("{} - M. ASSAHTOUT TV", self.route.title()));
            cx.notify();
        }
    }

    pub fn forward(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if let Some((r, p)) = self.forward.pop() {
            crate::ui::slot::clear_slot(cx);
            let cur_r = std::mem::replace(&mut self.route, r);
            let cur_p = std::mem::replace(&mut self.page, p);
            self.history.push((cur_r, cur_p));
            if cx.global::<UiState>().fullscreen && !matches!(self.route, Route::Watch { .. } | Route::Shorts(_)) {
                toggle_fullscreen(window, cx);
            }
            window.set_window_title(&format!("{} - M. ASSAHTOUT TV", self.route.title()));
            cx.notify();
        }
    }

    fn all_suggestions(&self) -> Vec<String> {
        let mut v = self.history_suggestions.clone();
        for s in &self.suggestions {
            if !v.contains(s) {
                v.push(s.clone());
            }
        }
        v.truncate(14);
        v
    }

    fn update_suggestions(&mut self, q: String, cx: &mut Context<Self>) {
        self.show_suggestions = true;
        self.history_suggestions = store::search_history(&q, if q.is_empty() { 10 } else { 4 });
        self.suggestion_serial += 1;
        let serial = self.suggestion_serial;
        if q.trim().is_empty() || q.contains("youtu") {
            self.suggestions.clear();
            cx.notify();
            return;
        }
        cx.spawn(async move |this, cx| {
            cx.background_executor().timer(std::time::Duration::from_millis(150)).await;
            let still = this.update(cx, |a, _| a.suggestion_serial == serial).unwrap_or(false);
            if !still {
                return;
            }
            let res = blocking(move || innertube::suggestions(&q).unwrap_or_default()).await;
            let _ = this.update(cx, |a, cx| {
                if a.suggestion_serial == serial {
                    a.suggestions = res;
                    cx.notify();
                }
            });
        })
        .detach();
        cx.notify();
    }

    fn submit_search(&mut self, q: String, window: &mut Window, cx: &mut Context<Self>) {
        let q = q.trim().to_string();
        if q.is_empty() {
            return;
        }
        self.show_suggestions = false;
        self.sel_suggestion = None;
        self.search.update(cx, |s, cx| s.set_value(q.clone(), window, cx));
        window.focus(&self.focus, cx);
        // Direct links
        if let Some(r) = innertube::parse_youtube_url(&q) {
            let route = match r {
                innertube::ResolvedUrl::Video(id) => {
                    let t = q.split(['?', '&']).find_map(|kv| kv.strip_prefix("t=")).and_then(|t| t.trim_end_matches('s').parse::<f64>().ok());
                    let list = q.split(['?', '&']).find_map(|kv| kv.strip_prefix("list=")).map(str::to_string);
                    Route::Watch { id, playlist: list, start: t }
                }
                innertube::ResolvedUrl::Channel(id) => Route::Channel(id),
                innertube::ResolvedUrl::Playlist(id) => Route::Playlist(id),
            };
            self.go(route, window, cx);
            return;
        }
        if q.contains("youtube.com/") || q.starts_with('@') {
            let url = if q.starts_with('@') { format!("https://www.youtube.com/{q}") } else { q.clone() };
            cx.spawn_in(window, async move |this, cx| {
                let r = blocking(move || innertube::resolve_url(&url)).await;
                let _ = this.update_in(cx, |a, window, cx| match r {
                    Ok(innertube::ResolvedUrl::Channel(id)) => a.go(Route::Channel(id), window, cx),
                    Ok(innertube::ResolvedUrl::Playlist(id)) => a.go(Route::Playlist(id), window, cx),
                    Ok(innertube::ResolvedUrl::Video(id)) => a.go(Route::Watch { id, playlist: None, start: None }, window, cx),
                    Err(e) => toast(cx, tf!("Could not open link: {0}", crate::ui::friendly_error(&e.to_string()))),
                });
            })
            .detach();
            return;
        }
        store::search_history_add(&q);
        self.go(Route::Search(q), window, cx);
    }

    /// Reloads the current page (the watch page keeps the player going).
    pub fn refresh(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        cx.set_global(OpenMenu(None));
        if let Route::Watch { id, playlist, .. } = self.route.clone() {
            let t = player(cx).read(cx).time;
            self.route = Route::Watch { id, playlist, start: Some(t) };
        }
        self.page = build_page(&self.route, window, cx);
        cx.notify();
    }

    fn render_dl_button(&mut self, cx: &mut Context<Self>) -> AnyElement {
        let p = pal(cx);
        let (active, prog) = crate::downloads::summary(cx);
        let open = self.dl_panel;
        let mut btn = div()
            .id("dl")
            .relative()
            .flex()
            .flex_none()
            .items_center()
            .justify_center()
            .size_10()
            .rounded_full()
            .cursor_pointer()
            .when(open, |d| d.bg(p.hover))
            .hover(move |s| s.bg(p.hover))
            .on_click(cx.listener(|this, _, _, cx| {
                this.dl_panel ^= true;
                cx.notify();
            }));
        if active > 0 {
            btn = btn
                .child(
                    icon("arrow-down-to-line").size_5().text_color(p.accent).with_animation(
                        "dl-bob",
                        Animation::new(std::time::Duration::from_millis(1100)).repeat().with_easing(pulsating_between(0.35, 1.0)),
                        |svg, d| svg.opacity(d),
                    ),
                )
                // progress track under the icon
                .child(
                    div()
                        .absolute()
                        .left(px(9.))
                        .right(px(9.))
                        .bottom(px(5.))
                        .h(px(3.))
                        .rounded_full()
                        .bg(p.border)
                        .child(div().h_full().rounded_full().bg(p.accent).w(relative(prog.clamp(0.04, 1.0)))),
                )
                .child(
                    div()
                        .absolute()
                        .top(px(1.))
                        .right(px(0.))
                        .min_w(px(16.))
                        .h(px(16.))
                        .px(px(4.))
                        .flex()
                        .items_center()
                        .justify_center()
                        .rounded_full()
                        .bg(p.accent)
                        .text_size(px(10.))
                        .font_weight(FontWeight::BOLD)
                        .text_color(white())
                        .child(active.to_string())
                        .with_animation(
                            SharedString::from(format!("dl-badge-{active}")),
                            Animation::new(std::time::Duration::from_millis(350)).with_easing(ease_out_quint()),
                            |d, t| d.opacity(t),
                        ),
                );
        } else {
            btn = btn.child(icon("download").text_color(p.text));
        }
        btn.into_any_element()
    }

    fn render_dl_panel(&mut self, cx: &mut Context<Self>) -> Option<AnyElement> {
        if !self.dl_panel {
            return None;
        }
        use crate::downloads::{self, JobStatus};
        let p = pal(cx);
        let mut jobs = cx.default_global::<downloads::Downloads>().jobs.lock().clone();
        jobs.reverse();
        let small_btn = |id: SharedString, ic: &'static str, tip: &'static str| {
            let _ = tip;
            div()
                .id(id)
                .flex()
                .flex_none()
                .items_center()
                .justify_center()
                .size_7()
                .rounded_full()
                .cursor_pointer()
                .hover(move |s| s.bg(p.hover))
                .child(icon(ic).size_4().text_color(p.text2))
        };
        let mut list = div().id("dlp-list").flex().flex_col().max_h(px(420.)).overflow_y_scroll().py_1();
        if jobs.is_empty() {
            list = list.child(
                div()
                    .flex()
                    .flex_col()
                    .items_center()
                    .gap_2()
                    .py_8()
                    .child(icon("download").size_8().text_color(p.text3))
                    .child(div().text_sm().text_color(p.text2).child(t("No downloads yet"))),
            );
        }
        for j in jobs.iter().take(50) {
            let id = j.id;
            let title = if j.video.title.is_empty() { j.video.id.clone() } else { j.video.title.clone() };
            let (line, color, running) = match &j.status {
                JobStatus::Queued => (t("Waiting…").to_string(), p.text2, true),
                JobStatus::Running => (format!("{:.0}%{}", j.progress * 100.0, j.detail.split("at").nth(1).map(|s| format!(", {}", s.trim())).unwrap_or_default()), p.text2, true),
                JobStatus::Done(path) => (
                    std::fs::metadata(path).map(|m| tf!("{0} MB, {1}", format_args!("{:.1}", m.len() as f64 / 1e6), j.format)).unwrap_or_else(|_| t("File missing").into()),
                    p.text2,
                    false,
                ),
                JobStatus::Failed(e) => (tf!("Failed: {0}", e), rgb(0xff4e45).into(), false),
                JobStatus::Cancelled => (t("Cancelled").into(), p.text2, false),
            };
            let done_path = if let JobStatus::Done(pth) = &j.status { Some(pth.clone()) } else { None };
            let open_path = done_path.clone();
            let mut row = div()
                .id(SharedString::from(format!("dlp-{id}")))
                .flex()
                .items_center()
                .gap_3()
                .px_3()
                .py_2()
                .mx_1()
                .rounded_lg()
                .hover(move |s| s.bg(p.hover))
                .when(open_path.is_some(), |d| d.cursor_pointer())
                .on_click(move |_, _, cx| {
                    if let Some(pth) = &open_path {
                        downloads::open_file(pth, cx);
                    }
                })
                .child(
                    div()
                        .flex_none()
                        .w(px(72.))
                        .h(px(40.))
                        .rounded_md()
                        .overflow_hidden()
                        .bg(p.chip)
                        .child(img(j.video.thumbnail.clone()).size_full().object_fit(ObjectFit::Cover)),
                )
                .child(
                    div()
                        .flex()
                        .flex_col()
                        .flex_1()
                        .min_w_0()
                        .gap_0p5()
                        .child(div().text_sm().font_weight(FontWeight::MEDIUM).text_color(p.text).truncate().child(title))
                        .child(div().text_xs().text_color(color).truncate().child(line))
                        .when(running, |d| {
                            d.child(
                                div()
                                    .mt_1()
                                    .h(px(3.))
                                    .w_full()
                                    .rounded_full()
                                    .bg(p.border)
                                    .child(div().h_full().rounded_full().bg(p.accent).w(relative(j.progress.clamp(0.02, 1.0)))),
                            )
                        }),
                );
            if running {
                row = row.child(small_btn(SharedString::from(format!("dlp-c-{id}")), "x", "Cancel").on_click(move |_, _, cx| {
                    cx.stop_propagation();
                    downloads::cancel(id, cx)
                }));
            } else {
                if let Some(pth) = done_path.clone() {
                    row = row.child(small_btn(SharedString::from(format!("dlp-f-{id}")), "folder-open", t("Show in folder")).on_click(move |_, _, cx| {
                        cx.stop_propagation();
                        reveal_in_folder(&pth, cx);
                    }));
                    row = row.child(small_btn(SharedString::from(format!("dlp-d-{id}")), "trash-2", t("Delete file")).on_click(move |_, _, cx| {
                        cx.stop_propagation();
                        downloads::delete_file(id, cx)
                    }));
                }
                row = row.child(small_btn(SharedString::from(format!("dlp-r-{id}")), "x", t("Remove from list")).on_click(move |_, _, cx| {
                    cx.stop_propagation();
                    downloads::remove(id, cx)
                }));
            }
            list = list.child(row);
        }
        let footer_btn = |id: &'static str, label: &'static str| {
            div()
                .id(id)
                .px_3()
                .py_1p5()
                .rounded_full()
                .text_sm()
                .font_weight(FontWeight::MEDIUM)
                .text_color(p.link)
                .cursor_pointer()
                .hover(move |s| s.bg(p.hover))
                .child(t(label))
        };
        let has_finished = jobs.iter().any(|j| !matches!(j.status, JobStatus::Running | JobStatus::Queued));
        Some(
            deferred(
                div()
                    .absolute()
                    .inset_0()
                    .child(
                        div().id("dlp-scrim").absolute().inset_0().occlude().on_mouse_down(
                            MouseButton::Left,
                            cx.listener(|this, _, _, cx| {
                                this.dl_panel = false;
                                cx.notify();
                            }),
                        ),
                    )
                    .child(
                        div()
                            .id("dlp")
                            .occlude()
                            .absolute()
                            .top(px(52.))
                            .right(px(150.))
                            .w(px(400.))
                            .rounded_xl()
                            .bg(p.surface2)
                            .border_1()
                            .border_color(p.border)
                            .shadow_2xl()
                            .flex()
                            .flex_col()
                            .child(
                                div()
                                    .flex()
                                    .items_center()
                                    .justify_between()
                                    .px_4()
                                    .pt_3()
                                    .pb_2()
                                    .child(div().text_base().font_weight(FontWeight::SEMIBOLD).text_color(p.text).child(t("Downloads")))
                                    .when(has_finished, |d| {
                                        d.child(footer_btn("dlp-clear", "Clear list").on_click(|_, _, cx| downloads::clear_history(cx)))
                                    }),
                            )
                            .child(list)
                            .child(
                                div()
                                    .flex()
                                    .items_center()
                                    .justify_between()
                                    .px_2()
                                    .py_2()
                                    .border_t_1()
                                    .border_color(p.border)
                                    .child(footer_btn("dlp-folder", "Open folder").on_click(|_, _, _| {
                                        let d = store::settings_snapshot().download_dir.clone();
                                        let _ = std::fs::create_dir_all(&d);
                                        let _ = open::that(d);
                                    }))
                                    .child(footer_btn("dlp-page", "Open downloads page").on_click(cx.listener(|this, _, window, cx| {
                                        this.dl_panel = false;
                                        this.go(Route::Downloads, window, cx)
                                    }))),
                            ),
                    ),
            )
            .with_priority(3)
            .into_any_element(),
        )
    }

    // ───── keyboard ─────

    fn on_key_up(&mut self, ev: &KeyUpEvent, _window: &mut Window, cx: &mut Context<Self>) {
        if ev.keystroke.key != "space" || !std::mem::take(&mut self.space_down) {
            return;
        }
        player(cx).update(cx, |pl, cx| {
            if pl.boost.is_some() {
                pl.end_boost();
            } else {
                pl.toggle_pause();
            }
            cx.notify();
        });
    }

    fn on_key(&mut self, ev: &KeyDownEvent, window: &mut Window, cx: &mut Context<Self>) {
        let k = shortcut_key(&ev.keystroke.key);
        let m = &ev.keystroke.modifiers;
        if k == "f11" && !m.control && !m.alt && !m.platform {
            toggle_fullscreen_maximized(window, cx);
            return;
        }
        if self.splash.is_some() {
            if matches!(k, "escape" | "space") {
                self.skip_splash(cx);
            }
            return;
        }
        let search_focused = self.search.read(cx).focus_handle(cx).is_focused(window);
        let dialog_focused = self.dialog_input.read(cx).focus_handle(cx).is_focused(window);
        if k == "f5" || (m.control && k == "r" && !search_focused && !dialog_focused) {
            self.refresh(window, cx);
            return;
        }
        // tabs: Ctrl+T new, Ctrl+W close, Ctrl+Tab / Ctrl+Shift+Tab next / previous, Ctrl+1..9 pick
        if m.control && !m.alt {
            match k {
                "t" if !m.shift => {
                    self.new_tab(Route::Home, true, window, cx);
                    return;
                }
                "w" if !m.shift && self.tabs.len() > 1 => {
                    self.close_tab(self.active_tab, window, cx);
                    return;
                }
                "tab" => {
                    self.cycle_tab(if m.shift { -1 } else { 1 }, window, cx);
                    return;
                }
                "pageup" | "pagedown" if self.tabs.len() > 1 => {
                    self.cycle_tab(if k == "pageup" { -1 } else { 1 }, window, cx);
                    return;
                }
                d if self.tabs.len() > 1 && matches!(d, "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9") => {
                    let n: usize = d.parse().unwrap_or(1);
                    // Ctrl+9 is the last tab, as in browsers
                    let i = if n == 9 { self.tabs.len() - 1 } else { (n - 1).min(self.tabs.len() - 1) };
                    self.switch_tab(i, window, cx);
                    return;
                }
                _ => {}
            }
        }
        if k == "escape" && self.dl_panel {
            self.dl_panel = false;
            cx.notify();
            return;
        }
        if k == "escape" {
            if self.dialog.is_some() {
                self.dialog = None;
                cx.notify();
                return;
            }
            if cx.global::<OpenMenu>().0.is_some() {
                cx.set_global(OpenMenu(None));
                cx.notify();
                return;
            }
            if search_focused {
                self.show_suggestions = false;
                window.focus(&self.focus, cx);
                cx.notify();
                return;
            }
            if cx.global::<UiState>().fullscreen {
                exit_fullscreen(window, cx);
                return;
            }
        }
        if search_focused || dialog_focused {
            if search_focused && (k == "down" || k == "up") {
                let n = self.all_suggestions().len();
                if n > 0 {
                    self.sel_suggestion = Some(match (self.sel_suggestion, k) {
                        (None, "down") => 0,
                        (None, _) => n - 1,
                        (Some(i), "down") => (i + 1) % n,
                        (Some(i), _) => (i + n - 1) % n,
                    });
                    cx.notify();
                }
            }
            return;
        }
        if m.alt && k == "left" {
            self.back(window, cx);
            return;
        }
        if m.alt && k == "right" {
            self.forward(window, cx);
            return;
        }
        if m.control && (k == "l" || k == "k") || k == "/" {
            let fh = self.search.read(cx).focus_handle(cx);
            window.focus(&fh, cx);
            return;
        }
        // Ctrl+Left / Ctrl+Right: previous / next chapter (not while typing)
        if m.control && !m.shift && (k == "left" || k == "right") && window.focused(cx).map(|f| f == self.focus).unwrap_or(true) {
            let p = player(cx);
            if p.read(cx).video.is_some() {
                p.update(cx, |pl: &mut Player, cx| {
                    if pl.seek_chapter(if k == "right" { 1 } else { -1 }) {
                        cx.notify();
                    }
                });
            }
            return;
        }
        if m.control || m.platform || m.alt {
            return;
        }
        if matches!(self.route, Route::Shorts(_)) {
            if let Some(page) = self.page.clone().downcast::<shorts::ShortsPage>().ok() {
                if k == "down" || k == "up" {
                    page.update(cx, |s, cx| s.step(if k == "down" { 1 } else { -1 }, cx));
                    return;
                }
            }
        }
        // typing in a text field (chat, filters, channel search...) isn't a shortcut
        if window.focused(cx).map(|f| f != self.focus).unwrap_or(false) {
            return;
        }
        // scrolling from the keyboard: PageUp/PageDown everywhere; the arrows,
        // Home/End and Space too on pages without a big player (on the watch page
        // they seek, change the volume and pause)
        let watching = matches!(self.route, Route::Watch { .. } | Route::Shorts(_));
        let scroll_key = matches!(k, "pageup" | "pagedown")
            || (!watching && matches!(k, "up" | "down" | "home" | "end"))
            || (!watching && k == "space" && player(cx).read(cx).video.is_none());
        if scroll_key && crate::ui::widgets::scroll_page_key(k, m.shift, window) {
            return;
        }
        let p = player(cx);
        if p.read(cx).video.is_none() {
            return;
        }
        let step = store::settings_snapshot().seek_step;
        let playback_key = match ev.keystroke.key_char.as_deref() {
            Some(key @ ("-" | "+" | "<" | ">")) => key,
            _ => k,
        };
        let held = ev.is_held;
        if k == "space" {
            self.space_down = true;
        }
        let handled = p.update(cx, |pl: &mut Player, cx| {
            let handled = match (playback_key, m.shift) {
                ("space", _) => {
                    // acted on at release (tap) or while held (2x)
                    if held {
                        pl.start_boost();
                        cx.notify();
                    }
                    true
                }
                ("k", false) => {
                    pl.toggle_pause();
                    true
                }
                ("i", true) => {
                    pl.show_stats = !pl.show_stats;
                    cx.notify();
                    true
                }
                ("j", false) => {
                    pl.seek_by(-10.0);
                    true
                }
                ("l", false) => {
                    pl.seek_by(10.0);
                    true
                }
                ("left" | "right", _) => {
                    let before = pl.time;
                    pl.seek_by(if k == "left" { -step } else { step });
                    pl.show_keyboard_feedback(KeyboardFeedback::Seek(pl.time - before), cx);
                    true
                }
                ("up" | "down", _) => {
                    let delta = if k == "up" { 5.0 } else { -5.0 };
                    pl.set_volume(pl.volume + delta);
                    pl.show_keyboard_feedback(KeyboardFeedback::Volume(pl.volume), cx);
                    true
                }
                ("m", false) => {
                    pl.toggle_mute();
                    true
                }
                ("c", false) => {
                    pl.toggle_captions(cx);
                    true
                }
                (",", false) if pl.paused => {
                    pl.frame_step(false);
                    true
                }
                (".", false) if pl.paused => {
                    pl.frame_step(true);
                    true
                }
                ("," | "." | "=", true) | ("<" | ">" | "-" | "+", _) => {
                    pl.step_speed(matches!(playback_key, "." | "=" | ">" | "+"));
                    crate::ui::toast(cx, tf!("Speed {0}x", format_args!("{:.2}", pl.speed)));
                    true
                }
                ("home", _) => {
                    pl.seek_to(0.0);
                    true
                }
                ("end", _) => {
                    let d = pl.duration;
                    pl.seek_to(d - 1.0);
                    true
                }
                (d, false) if d.len() == 1 && d.chars().all(|c| c.is_ascii_digit()) => {
                    let n: f64 = d.parse().unwrap();
                    let dur = pl.duration;
                    pl.seek_to(dur * n / 10.0);
                    true
                }
                ("p", true) => {
                    if let Some(i) = pl.queue_pos.filter(|i| *i > 0) {
                        pl.play_queue_index(i - 1, cx);
                    }
                    true
                }
                ("a", false) => {
                    let a = !pl.audio_only;
                    pl.set_audio_only(a, cx);
                    true
                }
                // aspect ratio: Original, Auto, Zoom to fill, Stretch, 21:9...
                ("z", false) => {
                    let next = crate::player::aspect::next_mode(&store::settings_snapshot().aspect_mode);
                    crate::player::view::set_aspect_mode(next, cx);
                    crate::ui::toast(cx, tf!("Aspect ratio: {0}", t(crate::player::aspect::label(next))));
                    true
                }
                _ => false,
            };
            cx.notify();
            handled
        });
        if handled {
            return;
        }
        match (k, m.shift) {
            ("f", false) => toggle_fullscreen(window, cx),
            ("'", _) => toggle_windowed_fullscreen(window, cx),
            ("t", false) => toggle_theater_mode(cx),
            ("i", false) => {
                if matches!(self.route, Route::Watch { .. } | Route::Shorts(_)) {
                    if self.history.is_empty() {
                        self.go(Route::Home, window, cx);
                    } else {
                        self.back(window, cx);
                    }
                } else if let Some(v) = p.read(cx).video.clone() {
                    let route = if v.is_short && !store::settings_snapshot().shorts_in_player {
                        Route::Shorts(Some(v.id))
                    } else {
                        Route::Watch { id: v.id, playlist: None, start: None }
                    };
                    self.go(route, window, cx);
                }
            }
            ("n", true) => play_next(window, cx),
            _ => {}
        }
    }

    // ───── chrome ─────

    fn render_topbar(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        localize_placeholder(&self.search, "Search or paste a YouTube link", window, cx);
        let p = pal(cx);
        let can_back = !self.history.is_empty();
        let suggestions = if self.show_suggestions { self.all_suggestions() } else { Vec::new() };
        let hist: std::collections::HashSet<String> = self.history_suggestions.iter().cloned().collect();
        let sel = self.sel_suggestion;
        let maximized = window.is_fullscreen();
        let bar = div()
            .id("topbar")
            .flex()
            .flex_none()
            .items_center()
            .h(px(56.))
            .pl_4()
            .bg(p.bg)
            .child(
                div()
                    .flex()
                    .items_center()
                    .gap_2()
                    .child(icon_button("menu", "menu", cx).on_click(cx.listener(|this, _, _, cx| {
                        if matches!(this.route, Route::Watch { .. }) {
                            this.drawer_open ^= true;
                        } else {
                            cx.global_mut::<UiState>().sidebar_open ^= true;
                        }
                        cx.refresh_windows();
                    })))
                    .child(
                        icon_button("back", "arrow-left", cx)
                            .when(!can_back, |d| d.opacity(0.35))
                            .on_click(cx.listener(|this, _, window, cx| this.back(window, cx))),
                    )
                    .child(icon_button("refresh", "rotate-cw", cx).on_click(cx.listener(|this, _, window, cx| this.refresh(window, cx))))
                    .child(
                        div()
                            .id("logo")
                            .flex()
                            .items_center()
                            .gap_1()
                            .ml_2()
                            .cursor_pointer()
                            .on_click(cx.listener(|this, _, window, cx| this.go(Route::Home, window, cx)))
                            .child(crate::ui::logo(px(28.)))
                            .child(crate::ui::wordmark(cx)),
                    ),
            )
            // Native caption hit testing must leave the controls clickable.
            .child(drag_gap("drag-l").flex_1().min_w(px(24.)))
            .child(
                div()
                    .relative()
                    .flex()
                    .items_center()
                    // YouTube-width search box that gives way on narrow windows
                    .flex_shrink(1.)
                    .min_w(px(220.))
                    .w(px(640.))
                    .child(
                        div()
                            .flex()
                            .flex_1()
                            .h_10()
                            .items_center()
                            .pl_4()
                            .rounded_l_full()
                            .border_1()
                            .border_color(p.border)
                            .bg(if p.dark { rgb(0x121212).into() } else { p.bg })
                            .child(Input::new(&self.search).appearance(false).cleanable(true)),
                    )
                    .child(
                        div()
                            .id("search-btn")
                            .flex()
                            .items_center()
                            .justify_center()
                            .w(px(64.))
                            .h_10()
                            .rounded_r_full()
                            .border_1()
                            .border_l_0()
                            .border_color(p.border)
                            .bg(p.chip)
                            .cursor_pointer()
                            .hover(move |s| s.bg(p.hover))
                            .child(icon("search").text_color(p.text))
                            .on_click(cx.listener(|this, _, window, cx| {
                                let q = this.search.read(cx).value().to_string();
                                this.submit_search(q, window, cx);
                            })),
                    )
                    .when(!suggestions.is_empty(), |d| {
                        d.child(
                            deferred(
                                div()
                                    .id("suggestions")
                                    .when(cfg!(target_os = "linux"), |d| d.occlude())
                                    .absolute()
                                    .top(px(44.))
                                    .left_0()
                                    .right(px(64.))
                                    .py_2()
                                    .rounded_xl()
                                    .bg(p.surface2)
                                    .border_1()
                                    .border_color(p.border)
                                    .shadow_lg()
                                    .children(suggestions.into_iter().enumerate().map(|(i, s)| {
                                        let is_hist = hist.contains(&s);
                                        let s2 = s.clone();
                                        let s3 = s.clone();
                                        div()
                                            .id(SharedString::from(format!("sug-{i}")))
                                            .flex()
                                            .items_center()
                                            .gap_3()
                                            .px_4()
                                            .py_1p5()
                                            .cursor_pointer()
                                            .when(sel == Some(i), |d| d.bg(p.hover))
                                            .hover(move |st| st.bg(p.hover))
                                            .child(icon(if is_hist { "history" } else { "search" }).size_4().text_color(p.text2))
                                            .child(div().flex_1().text_color(p.text).font_weight(FontWeight::MEDIUM).child(s))
                                            .when(is_hist, |d| {
                                                d.child(
                                                    div()
                                                        .id(SharedString::from(format!("sugx-{i}")))
                                                        .text_xs()
                                                        .text_color(p.link)
                                                        .child(t("Remove"))
                                                        .on_mouse_down(MouseButton::Left, cx.listener(move |this, _, _, cx| {
                                                            cx.stop_propagation();
                                                            store::search_history_remove(&s3);
                                                            let q = this.search.read(cx).value().to_string();
                                                            this.update_suggestions(q, cx);
                                                        })),
                                                )
                                            })
                                            .on_mouse_down(MouseButton::Left, cx.listener(move |this, _, window, cx| {
                                                this.submit_search(s2.clone(), window, cx);
                                            }))
                                    })),
                            )
                            .with_priority(2),
                        )
                    }),
            )
            .child(drag_gap("drag-r").flex_1().min_w(px(24.)))
            .child(
                div()
                    .flex()
                    .items_center()
                    .gap_1()
                    .child(self.render_dl_button(cx))
                    .child(icon_button("settings", "settings", cx).on_click(cx.listener(|this, _, window, cx| this.go(Route::Settings, window, cx))))
                    .child(self.account_button(cx))
                    .child(drag_gap("drag-c").w_3())
                    .child(window_controls(maximized, cx)),
            );
        #[cfg(target_os = "linux")]
        let bar = super::topbar_drag::wrap(bar, self.search.clone(), window, cx);
        bar
    }

    fn render_sidebar(&mut self, cx: &mut Context<Self>) -> AnyElement {
        let p = pal(cx);
        let ui = *cx.global::<UiState>();
        let route = self.route.clone();
        let is_watch = matches!(route, Route::Watch { .. });
        let s = store::settings_snapshot();
        if !ui.sidebar_open && !is_watch {
            // compact rail
            let rail = |id: &'static str, ic: &'static str, label: &'static str, r: Route, active: bool, cx: &mut Context<Self>| {
                div()
                    .id(id)
                    .flex()
                    .flex_col()
                    .items_center()
                    .gap_1p5()
                    .py_3p5()
                    .w_full()
                    .px_1()
                    .rounded_xl()
                    .cursor_pointer()
                    .when(active, |d| d.bg(p.chip))
                    .hover(move |s| s.bg(p.hover))
                    .child(icon(ic).size_5().text_color(if active { p.accent } else { p.text }))
                    .child(div().w_full().truncate().text_center().text_size(px(10.)).text_color(p.text).child(label))
                    .on_click(cx.listener(move |this, _, window, cx| this.go(r.clone(), window, cx)))
            };
            return div()
                .flex()
                .flex_col()
                .flex_none()
                .gap_1()
                .w(px(COLLAPSED_SIDEBAR_WIDTH))
                .px_1()
                .pt_1()
                .child(rail("r-home", "house", "Home", Route::Home, route == Route::Home, cx))
                .when(!s.hide_shorts, |d| d.child(rail("r-shorts", "zap", "Shorts", Route::Shorts(None), matches!(route, Route::Shorts(_)), cx)))
                .child(rail("r-subs", "tv", "Subscriptions", Route::Subscriptions, route == Route::Subscriptions, cx))
                .child(rail("r-you", "circle-user", "You", Route::Library, route == Route::Library, cx))
                .child(rail("r-dl", "download", "Downloads", Route::Downloads, route == Route::Downloads, cx))
                .into_any_element();
        }
        // one row: icon (or avatar) + label, YouTube-like density
        let row = |id: SharedString, lead: AnyElement, label: String, active: bool| {
            div()
                .id(id)
                .flex()
                .flex_none()
                .items_center()
                .gap_5()
                .px_3()
                .h(px(38.))
                .rounded_lg()
                .cursor_pointer()
                .text_sm()
                .text_color(p.text)
                .when(active, |d| d.bg(p.chip).font_weight(FontWeight::MEDIUM))
                .hover(move |s| s.bg(p.hover))
                .child(div().flex().flex_none().items_center().justify_center().size(px(24.)).child(lead))
                .child(div().flex_1().min_w_0().truncate().child(label))
        };
        let ic = |name: &str, active: bool| -> AnyElement {
            icon(name).size_5().text_color(if active { p.accent } else { p.text }).into_any_element()
        };
        let entry = |id: &'static str, name: &'static str, label: &str, r: Route, active: bool, cx: &mut Context<Self>| {
            row(id.into(), ic(name, active), t(label).to_string(), active)
                .on_click(cx.listener(move |this, _, window, cx| this.go(r.clone(), window, cx)))
        };
        let heading = |title: &str| {
            div()
                .flex()
                .items_center()
                .gap_1()
                .px_3()
                .pt_1()
                .pb_1()
                .text_sm()
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(p.text)
                .child(t(title).to_string())
        };
        let sep = || div().flex_none().mx_3().my_3().h(px(1.)).bg(p.border);

        let mut col = div()
            .id("sidebar")
            .flex()
            .flex_col()
            .flex_none()
            .w(px(232.))
            .h_full()
            .px_2()
            .pt_1()
            .pb_4()
            .overflow_y_scroll()
            .child(entry("s-home", "house", "Home", Route::Home, route == Route::Home, cx))
            .when(!s.hide_shorts, |d| d.child(entry("s-shorts", "zap", "Shorts", Route::Shorts(None), matches!(route, Route::Shorts(_)), cx)))
            .child(entry("s-subs", "tv", "Subscriptions", Route::Subscriptions, route == Route::Subscriptions, cx))
            .child(sep())
            .child(
                heading("You").id("s-you").cursor_pointer().rounded_lg().h(px(38.)).hover(move |s| s.bg(p.hover)).child(icon("chevron-right").size_4().text_color(p.text)).on_click(
                    cx.listener(|this, _, window, cx| this.go(Route::Library, window, cx)),
                ),
            )
            .child(entry("s-hist", "history", "History", Route::History, route == Route::History, cx))
            .child(entry("s-pl", "list-video", "Playlists", Route::Library, route == Route::Library, cx));
        for pl in store::playlists().into_iter().filter(|p| p.protected) {
            let name = if pl.name == "Watch later" { "clock" } else { "thumbs-up" };
            let active = route == Route::LocalPlaylist(pl.id);
            let id = pl.id;
            col = col.child(
                row(SharedString::from(format!("s-lp-{id}")), ic(name, active), t(&pl.name).to_string(), active)
                    .on_click(cx.listener(move |this, _, window, cx| this.go(Route::LocalPlaylist(id), window, cx))),
            );
        }
        col = col.child(entry("s-dl", "download", "Downloads", Route::Downloads, route == Route::Downloads, cx));

        let subs = store::subscriptions();
        if !subs.is_empty() {
            const SHOWN: usize = 7;
            col = col.child(sep()).child(
                div()
                    .flex()
                    .items_center()
                    .justify_between()
                    .pr_1()
                    .child(heading("Subscriptions"))
                    .child(
                        div()
                            .id("s-manage")
                            .px_2()
                            .py_1()
                            .rounded_md()
                            .text_xs()
                            .text_color(p.text2)
                            .cursor_pointer()
                            .hover(move |s| s.bg(p.hover).text_color(p.text))
                            .child(t("Manage"))
                            .on_click(cx.listener(|this, _, window, cx| this.go(Route::Channels, window, cx))),
                    ),
            );
            let n = if self.subs_expanded { subs.len() } else { subs.len().min(SHOWN) };
            for sub in subs.iter().take(n) {
                let cid = sub.channel_id.clone();
                let active = route == Route::Channel(cid.clone());
                col = col.child(
                    row(SharedString::from(format!("s-sub-{}", sub.channel_id)), avatar(sub.avatar.as_deref(), px(24.), &sub.name, cx), sub.name.clone(), active)
                        .on_click(cx.listener(move |this, _, window, cx| this.go(Route::Channel(cid.clone()), window, cx))),
                );
            }
            if subs.len() > SHOWN {
                let expanded = self.subs_expanded;
                col = col.child(
                    row(
                        "s-subs-more".into(),
                        icon(if expanded { "chevron-up" } else { "chevron-down" }).size_5().text_color(p.text).into_any_element(),
                        if expanded { t("Show fewer").into() } else { tf!("Show {0} more", subs.len() - SHOWN) },
                        false,
                    )
                    .on_click(cx.listener(|this, _, _, cx| {
                        this.subs_expanded ^= true;
                        cx.notify();
                    })),
                );
            }
        }
        if !s.hide_explore {
            col = col.child(sep()).child(heading("Explore"));
            let icons = ["music", "gamepad-2", "newspaper", "trophy", "radio", "shirt", "lightbulb"];
            for (i, (name, _)) in innertube::EXPLORE.iter().enumerate() {
                let active = route == Route::Explore(i);
                col = col.child(
                    row(SharedString::from(format!("s-ex-{i}")), ic(icons.get(i).copied().unwrap_or("compass"), active), t(name).to_string(), active)
                        .on_click(cx.listener(move |this, _, window, cx| this.go(Route::Explore(i), window, cx))),
                );
            }
        }
        col = col
            .child(sep())
            .child(entry("s-set", "settings", "Settings", Route::Settings, route == Route::Settings, cx));
        col.into_any_element()
    }

    /// The sidebar as a slide-over on the watch page.
    fn render_drawer(&mut self, cx: &mut Context<Self>) -> Option<AnyElement> {
        if !self.drawer_open || !matches!(self.route, Route::Watch { .. }) {
            return None;
        }
        let p = pal(cx);
        let side = self.render_sidebar(cx);
        Some(
            div()
                .absolute()
                .left_0()
                .right_0()
                .bottom_0()
                .top(px(56.))
                .flex()
                .child(
                    div()
                        .id("drawer")
                        .h_full()
                        .bg(p.bg)
                        .border_r_1()
                        .border_color(p.border)
                        .shadow_2xl()
                        .occlude()
                        .child(side),
                )
                .child(
                    div()
                        .id("drawer-scrim")
                        .flex_1()
                        .h_full()
                        .bg(hsla(0., 0., 0., 0.45))
                        .occlude()
                        .on_click(cx.listener(|this, _, _, cx| {
                            this.drawer_open = false;
                            cx.notify();
                        })),
                )
                .into_any_element(),
        )
    }

    fn render_mini_player(&mut self, cx: &mut Context<Self>) -> Option<AnyElement> {
        let p = pal(cx);
        let pl = player(cx);
        let video = pl.read(cx).video.clone()?;
        if matches!(self.route, Route::Watch { .. } | Route::Shorts(_)) {
            return None;
        }
        let id = video.id.clone();
        let is_short = video.is_short;
        let (right, bottom, w) = self.mini_geom;
        let h = w * 9.0 / 16.0;
        let start = |resize: bool| {
            cx.listener(move |this: &mut Self, ev: &MouseDownEvent, _, cx| {
                cx.stop_propagation();
                let (right, bottom, w) = this.mini_geom;
                this.mini_drag = Some(MiniDrag { resize, start: ev.position, right, bottom, w, moved: false });
                cx.notify();
            })
        };
        Some(
            div()
                .absolute()
                .right(px(right))
                .bottom(px(bottom))
                .w(px(w))
                .rounded_xl()
                .overflow_hidden()
                .bg(p.surface2)
                .shadow_2xl()
                .border_1()
                .border_color(p.border)
                .child(div().w_full().h(px(h)).child(self.mini_view.clone()))
                .child(
                    div()
                        .id("mini-info")
                        .flex()
                        .flex_col()
                        .px_3()
                        .py_2()
                        // drag to move; a click without dragging opens the video
                        .cursor_grab()
                        .on_mouse_down(MouseButton::Left, start(false))
                        .on_click(cx.listener(move |this, _, window, cx| {
                            if std::mem::take(&mut this.mini_dragged) {
                                return;
                            }
                            let route = if is_short && !store::settings_snapshot().shorts_in_player {
                                Route::Shorts(Some(id.clone()))
                            } else {
                                Route::Watch { id: id.clone(), playlist: None, start: None }
                            };
                            this.go(route, window, cx)
                        }))
                        .child(div().text_sm().font_weight(FontWeight::SEMIBOLD).text_color(p.text).truncate().child(video.title.clone()))
                        .child(div().text_xs().text_color(p.text2).truncate().child(video.channel_name.clone())),
                )
                // resize from the top and left edges (the player is anchored bottom-right);
                // thin strips, so the player's own buttons stay clickable
                .child(div().id("mini-resize-top").absolute().top_0().left_0().right_0().h(px(6.)).cursor(CursorStyle::ResizeUpDown).on_mouse_down(MouseButton::Left, start(true)))
                .child(div().id("mini-resize-left").absolute().top_0().left_0().bottom_0().w(px(6.)).cursor(CursorStyle::ResizeLeftRight).on_mouse_down(MouseButton::Left, start(true)))
                .child(div().id("mini-resize-corner").absolute().top_0().left_0().size(px(12.)).cursor(CursorStyle::ResizeUpLeftDownRight).on_mouse_down(MouseButton::Left, start(true)))
                .into_any_element(),
        )
    }

    /// While the mini player is dragged: a layer over the whole window that follows
    /// the pointer (so nothing underneath reacts) and ends the drag on release.
    fn render_mini_drag(&mut self, cx: &mut Context<Self>) -> Option<AnyElement> {
        self.mini_drag.as_ref()?;
        Some(
            div()
                .id("mini-drag")
                .absolute()
                .inset_0()
                .occlude()
                .cursor(if self.mini_drag.as_ref().is_some_and(|d| d.resize) { CursorStyle::ResizeUpLeftDownRight } else { CursorStyle::ClosedHand })
                .on_mouse_move(cx.listener(|this, ev: &MouseMoveEvent, window, cx| {
                    let Some(d) = this.mini_drag.as_mut() else { return };
                    if ev.pressed_button != Some(MouseButton::Left) {
                        this.end_mini_drag(cx);
                        return;
                    }
                    let dx: f32 = (ev.position.x - d.start.x).into();
                    let dy: f32 = (ev.position.y - d.start.y).into();
                    if dx.abs() + dy.abs() > 4.0 {
                        d.moved = true;
                    }
                    let vp = window.viewport_size();
                    let (vw, vh): (f32, f32) = (vp.width.into(), vp.height.into());
                    let (right, bottom, w) = if d.resize {
                        // the edge moved furthest decides (the top edge in width units)
                        let delta = if dx.abs() >= (dy * 16.0 / 9.0).abs() { dx } else { dy * 16.0 / 9.0 };
                        let w = (d.w - delta).clamp(MINI_MIN_W, (vw - d.right - 8.0).max(MINI_MIN_W).min(MINI_MAX_W));
                        (d.right, d.bottom, w)
                    } else {
                        (d.right - dx, d.bottom - dy, d.w)
                    };
                    this.mini_geom = clamp_mini((right, bottom, w), vw, vh);
                    cx.notify();
                }))
                .on_mouse_up(MouseButton::Left, cx.listener(|this, _, _, cx| this.end_mini_drag(cx)))
                .on_mouse_up_out(MouseButton::Left, cx.listener(|this, _, _, cx| this.end_mini_drag(cx)))
                .into_any_element(),
        )
    }

    fn end_mini_drag(&mut self, cx: &mut Context<Self>) {
        if let Some(d) = self.mini_drag.take() {
            self.mini_dragged = d.moved;
            let (r, b, w) = self.mini_geom;
            store::kv_set("mini_geom", &format!("{r:.0},{b:.0},{w:.0}"));
        }
        cx.notify();
    }

    fn render_menu(&mut self, cx: &mut Context<Self>) -> Option<AnyElement> {
        let p = pal(cx);
        let (pos, items) = cx.global::<OpenMenu>().0.clone()?;
        Some(
            deferred(
                anchored().position(pos).snap_to_window_with_margin(px(8.)).child(
                    div()
                        .id("ctx-menu")
                        .occlude()
                        .min_w(px(240.))
                        .py_2()
                        .rounded_xl()
                        .bg(p.surface2)
                        .border_1()
                        .border_color(p.border)
                        .shadow_xl()
                        .on_mouse_down_out(|_, _, cx| {
                            cx.set_global(OpenMenu(None));
                            cx.refresh_windows();
                        })
                        .children(items.into_iter().enumerate().map(|(i, it)| {
                            let act = it.action.clone();
                            div()
                                .id(SharedString::from(format!("mi-{i}")))
                                .flex()
                                .items_center()
                                .gap_4()
                                .px_4()
                                .h_9()
                                .cursor_pointer()
                                .text_sm()
                                .text_color(p.text)
                                .hover(move |s| s.bg(p.hover))
                                .child(icon(it.icon).size_5().text_color(p.text))
                                .child(it.label.clone())
                                .on_click(move |_, window, cx| {
                                    cx.set_global(OpenMenu(None));
                                    act(window, cx);
                                    cx.refresh_windows();
                                })
                        })),
                ),
            )
            .with_priority(3)
            .into_any_element(),
        )
    }

    fn render_update(&mut self, cx: &mut Context<Self>) -> AnyElement {
        use crate::updater::Status;
        let p = pal(cx);
        let status = cx.default_global::<crate::updater::Updates>().status.clone();
        let (rel, progress) = match status {
            Status::Available(r) => (r, None),
            Status::Downloading(r, f) => (r, Some(f)),
            _ => {
                return div().p_5().text_sm().text_color(p.text2).child(t("No update available")).into_any_element();
            }
        };
        // release notes are Markdown; long ones are cut at a line break
        let mut notes = strip_download_section(&rel.notes.replace("\r\n", "\n"));
        if notes.chars().count() > 1400 {
            let cut: String = notes.chars().take(1400).collect();
            notes = cut.rsplit_once('\n').map(|(a, _)| a.to_string()).unwrap_or(cut) + "\n\n…";
        }
        let has_installer = cfg!(windows) && rel.asset.is_some();
        let size = rel.asset.as_ref().map(|a| a.size).filter(|s| *s > 0).map(|s| format!("{:.0} MB", s as f64 / 1_048_576.0));
        let version = rel.name
            .trim_start_matches("M. ASSAHTOUT TV")
            .trim_start_matches("Stream Tube")
            .trim()
            .trim_start_matches('v')
            .to_string();
        let accent = crate::ui::app_icon::icon_accent(store::settings_snapshot().accent);
        let [c0, _, c2] = crate::ui::app_icon::accent_stops(accent);
        let mut meta = vec![tf!("You have {0}", crate::updater::current_version())];
        if rel.prerelease {
            meta.push(t("Pre-release").into());
        }
        if let Some(sz) = size {
            meta.push(sz);
        }

        // hero: accent gradient, glowing logo, the new version in big type
        let hero = div()
            .relative()
            .flex()
            .flex_col()
            .items_center()
            .gap_2()
            .pt_8()
            .pb_6()
            .px_6()
            .overflow_hidden()
            .bg(linear_gradient(135., linear_color_stop(rgb(c0), 0.), linear_color_stop(rgb(c2), 1.)))
            // soft light spots
            .child(div().absolute().top(px(-60.)).left(px(-40.)).size(px(180.)).rounded_full().bg(hsla(0., 0., 1., 0.12)))
            .child(div().absolute().bottom(px(-80.)).right(px(-30.)).size(px(220.)).rounded_full().bg(hsla(0., 0., 0., 0.12)))
            .child(
                div()
                    .relative()
                    .size(px(84.))
                    .flex()
                    .items_center()
                    .justify_center()
                    .child(
                        div().absolute().inset_0().rounded_full().bg(hsla(0., 0., 1., 0.25)).with_animation(
                            "upd-glow",
                            Animation::new(std::time::Duration::from_millis(1800)).repeat().with_easing(pulsating_between(0.0, 1.0)),
                            |d, t| d.opacity(0.25 + 0.55 * t),
                        ),
                    )
                    .child(crate::ui::logo(px(64.))),
            )
            .child(
                div()
                    .flex()
                    .items_center()
                    .gap_1p5()
                    .mt_2()
                    .px_3()
                    .py_0p5()
                    .rounded_full()
                    .bg(hsla(0., 0., 1., 0.18))
                    .text_xs()
                    .font_weight(FontWeight::SEMIBOLD)
                    .text_color(white())
                    .child(icon("sparkles").size_3p5().text_color(white()))
                    .child(t("New version")),
            )
            .child(div().text_3xl().font_weight(FontWeight::BOLD).text_color(white()).child(format!("M. ASSAHTOUT TV {version}")))
            .child(div().text_sm().text_color(hsla(0., 0., 1., 0.85)).child(meta.join(", ")));

        let mut body = div().flex().flex_col().gap_4().p_6();
        if !notes.trim().is_empty() {
            body = body.child(div().text_xs().font_weight(FontWeight::SEMIBOLD).text_color(p.text2).child(t("WHAT'S NEW"))).child(
                div()
                    .id("upd-notes")
                    .when(notes.len() > 280, |d| d.h(px(230.)))
                    .flex_none()
                    .overflow_y_scroll()
                    .pr_2()
                    .text_sm()
                    .text_color(p.text)
                    .child(gpui_kit::base::TextView::markdown("upd-notes-md", notes).on_link_click(|url, _, _, _| {
                        open_web(url.as_ref());
                    })),
            );
        }
        if let Some(f) = progress {
            let f = f.clamp(0.0, 1.0);
            body = body.child(
                div()
                    .flex()
                    .flex_col()
                    .gap_2()
                    .child(
                        div()
                            .flex()
                            .justify_between()
                            .text_sm()
                            .child(div().text_color(p.text).font_weight(FontWeight::MEDIUM).child(if f >= 0.999 { t("Installing…") } else { t("Downloading…") }))
                            .child(div().text_color(p.text2).child(format!("{:.0}%", f * 100.0))),
                    )
                    .child(
                        div().relative().w_full().h(px(8.)).rounded_full().overflow_hidden().bg(p.hover).child(
                            div()
                                .relative()
                                .h_full()
                                .rounded_full()
                                .w(relative(f.max(0.02)))
                                .bg(linear_gradient(90., linear_color_stop(rgb(c0), 0.), linear_color_stop(rgb(c2), 1.)))
                                .overflow_hidden()
                                // moving shine
                                .child(
                                    div().absolute().top_0().bottom_0().w(px(60.)).bg(linear_gradient(
                                        90.,
                                        linear_color_stop(hsla(0., 0., 1., 0.), 0.),
                                        linear_color_stop(hsla(0., 0., 1., 0.45), 1.),
                                    ))
                                    .with_animation(
                                        "upd-shine",
                                        Animation::new(std::time::Duration::from_millis(1200)).repeat(),
                                        |d, t| d.left(relative(-0.3 + 1.3 * t)),
                                    ),
                                ),
                        ),
                    )
                    .child(div().text_xs().text_color(p.text2).child("M. ASSAHTOUT TV restarts automatically when done.")),
            );
            return div().flex().flex_col().child(hero).child(body).into_any_element();
        }
        body = body.child(
            div()
                .flex()
                .items_center()
                .justify_between()
                .pt_1()
                .child(
                    div()
                        .flex()
                        .gap_4()
                        .text_sm()
                        .text_color(p.text2)
                        .child(div().id("upd-skip").cursor_pointer().hover(|s| s.text_color(p.text)).child(t("Skip this version")).on_click(cx.listener(|this, _, _, cx| {
                            crate::updater::skip(cx);
                            this.dialog = None;
                            cx.notify();
                        })))
                        .child(div().id("upd-later").cursor_pointer().hover(|s| s.text_color(p.text)).child(t("Later")).on_click(cx.listener(|this, _, _, cx| {
                            this.dialog = None;
                            cx.notify();
                        }))),
                )
                .child(
                    div()
                        .id("upd-go")
                        .flex()
                        .items_center()
                        .gap_2()
                        .h(px(44.))
                        .px_6()
                        .rounded_full()
                        .cursor_pointer()
                        .bg(linear_gradient(90., linear_color_stop(rgb(c0), 0.), linear_color_stop(rgb(c2), 1.)))
                        .shadow_lg()
                        .hover(|s| s.opacity(0.92))
                        .active(|s| s.opacity(0.8))
                        .text_color(white())
                        .font_weight(FontWeight::SEMIBOLD)
                        .child(icon(if has_installer { "download" } else { "external-link" }).size_4().text_color(white()))
                        .child(if has_installer { t("Update now") } else { t("Download") })
                        .on_click(cx.listener(move |this, _, _, cx| {
                            if !has_installer {
                                this.dialog = None;
                            }
                            crate::updater::install(cx);
                            cx.notify();
                        })),
                ),
        );
        div().flex().flex_col().child(hero).child(body).into_any_element()
    }

    fn render_dialog(&mut self, cx: &mut Context<Self>) -> Option<AnyElement> {
        let p = pal(cx);
        let d = self.dialog.as_ref()?;
        let wide = matches!(d, Dialog::SignIn | Dialog::WebSignIn);
        let hero = matches!(d, Dialog::Update);
        let body: AnyElement = match d {
            Dialog::SignIn => self.render_sign_in(cx),
            Dialog::WebSignIn => self.render_web_sign_in(cx),
            Dialog::SaveToPlaylist(v) => {
                let v = v.clone();
                let pls = store::playlists();
                div()
                    .flex()
                    .flex_col()
                    .gap_1()
                    .child(div().text_base().font_weight(FontWeight::SEMIBOLD).text_color(p.text).pb_2().child(t("Save video to…")))
                    .children(pls.into_iter().map(|pl| {
                        let contains = store::playlist_contains(pl.id, &v.id);
                        let v2 = v.clone();
                        div()
                            .id(SharedString::from(format!("dlg-pl-{}", pl.id)))
                            .flex()
                            .items_center()
                            .gap_3()
                            .py_2()
                            .px_2()
                            .rounded_md()
                            .cursor_pointer()
                            .hover(move |s| s.bg(p.hover))
                            .child(icon(if contains { "square-check" } else { "square" }).text_color(p.text))
                            .child(div().flex_1().text_sm().text_color(p.text).child(if pl.protected { t(&pl.name).to_string() } else { pl.name.clone() }))
                            .child(icon(if pl.protected { "lock" } else { "globe-lock" }).size_4().text_color(p.text3))
                            .on_click(cx.listener(move |_, _, _, cx| {
                                if store::playlist_contains(pl.id, &v2.id) {
                                    store::playlist_remove(pl.id, &v2.id);
                                    toast(cx, tf!("Removed from {0}", pl.name));
                                } else {
                                    store::playlist_add(pl.id, &v2);
                                    toast(cx, tf!("Saved to playlist “{0}”", pl.name));
                                }
                                cx.notify();
                            }))
                    }))
                    .when(crate::api::auth::signed_in(), |d| {
                        let rows: Vec<AnyElement> = match &self.yt_playlists {
                            None => vec![div().py_2().px_2().text_sm().text_color(p.text2).child(t("Loading your YouTube playlists…")).into_any_element()],
                            Some(list) => list
                                .iter()
                                .map(|pl| {
                                    let (pid, name, vid) = (pl.id.clone(), pl.title.clone(), v.id.clone());
                                    div()
                                        .id(SharedString::from(format!("dlg-yt-{}", pl.id)))
                                        .flex()
                                        .items_center()
                                        .gap_3()
                                        .py_2()
                                        .px_2()
                                        .rounded_md()
                                        .cursor_pointer()
                                        .hover(move |s| s.bg(p.hover))
                                        .child(icon("list-plus").text_color(p.text))
                                        .child(div().flex_1().text_sm().text_color(p.text).truncate().child(pl.title.clone()))
                                        .child(icon("list-video").size_4().text_color(p.text3))
                                        .on_click(cx.listener(move |_, _, _, cx| {
                                            let (pid, name, vid) = (pid.clone(), name.clone(), vid.clone());
                                            cx.spawn(async move |_, cx| {
                                                let res = blocking(move || crate::api::auth::add_to_playlist(&pid, &vid)).await;
                                                cx.update(|cx| match res {
                                                    Ok(()) => toast(cx, tf!("Saved to playlist “{0}”", name)),
                                                    Err(_) => toast(cx, t("Couldn't save to that YouTube playlist")),
                                                });
                                            })
                                            .detach();
                                        }))
                                        .into_any_element()
                                })
                                .collect(),
                        };
                        d.child(div().pt_3().pb_1().px_2().text_xs().font_weight(FontWeight::SEMIBOLD).text_color(p.text2).child(t("Your YouTube playlists")))
                            .children(rows)
                    })
                    .child(
                        div()
                            .id("dlg-new")
                            .flex()
                            .items_center()
                            .gap_3()
                            .mt_2()
                            .py_2()
                            .px_2()
                            .rounded_md()
                            .cursor_pointer()
                            .hover(move |s| s.bg(p.hover))
                            .child(icon("plus").text_color(p.text))
                            .child(div().text_sm().text_color(p.text).child(t("Create new playlist")))
                            .on_click(cx.listener(move |this, _, window, cx| {
                                let v3 = v.clone();
                                this.dialog = None;
                                cx.notify();
                                let v4 = v3.clone();
                                // window.defer, not defer_in: prompt_text updates this same
                                // AppView, which defer_in would still be holding (a crash)
                                window.defer(cx, move |window, cx| {
                                    prompt_text("New playlist", "Playlist name", window, cx, move |name, _, cx| {
                                        let id = store::playlist_create(&name);
                                        store::playlist_add(id, &v4);
                                        toast(cx, tf!("Saved to playlist “{0}”", name));
                                    });
                                });
                            })),
                    )
                    .into_any_element()
            }
            Dialog::TextPrompt { title, .. } => div()
                .flex()
                .flex_col()
                .gap_4()
                .child(div().text_base().font_weight(FontWeight::SEMIBOLD).text_color(p.text).child(t(title).to_string()))
                .child(div().border_b_1().border_color(p.text2).pb_1().child(Input::new(&self.dialog_input).appearance(false)))
                .child(
                    div().flex().justify_end().gap_2().child(pill("dlg-cancel", None, "Cancel", cx).on_click(cx.listener(|this, _, _, cx| {
                        this.dialog = None;
                        cx.notify();
                    }))).child(
                        pill("dlg-ok", None, "Create", cx).bg(p.link).text_color(white()).on_click(cx.listener(|this, _, window, cx| {
                            let v = this.dialog_input.read(cx).value().to_string();
                            if let Some(Dialog::TextPrompt { on_submit, .. }) = this.dialog.take() {
                                if !v.trim().is_empty() {
                                    on_submit(v.trim().to_string(), window, cx);
                                }
                            }
                            cx.notify();
                        })),
                    ),
                )
                .into_any_element(),
            Dialog::Update => self.render_update(cx),
            Dialog::Confirm { title, body, confirm, .. } => div()
                .flex()
                .flex_col()
                .gap_4()
                .child(div().text_base().font_weight(FontWeight::SEMIBOLD).text_color(p.text).child(t(title).to_string()))
                .child(div().text_sm().text_color(p.text2).child(t(body).to_string()))
                .child(
                    div().flex().justify_end().gap_2().child(pill("dlg-cancel", None, "Cancel", cx).on_click(cx.listener(|this, _, _, cx| {
                        this.dialog = None;
                        cx.notify();
                    }))).child(
                        pill("dlg-ok", None, confirm.clone(), cx).bg(rgb(0xcc0000)).text_color(white()).on_click(cx.listener(|this, _, window, cx| {
                            if let Some(Dialog::Confirm { on_confirm, .. }) = this.dialog.take() {
                                on_confirm(window, cx);
                            }
                            cx.notify();
                        })),
                    ),
                )
                .into_any_element(),
        };
        Some(
            deferred(
                div()
                    .id("dialog-backdrop")
                    .absolute()
                    .inset_0()
                    .flex()
                    .items_center()
                    .justify_center()
                    .bg(hsla(0., 0., 0., 0.5))
                    .occlude()
                    .on_click(cx.listener(|this, _, _, cx| {
                        this.dialog = None;
                        cx.notify();
                    }))
                    .child(
                        div()
                            .id("dialog")
                            .w(px(if hero { 520. } else if wide { 420. } else { 380. }))
                            .max_h(px(if hero { 640. } else { 520. }))
                            .when(!hero, |d| d.overflow_y_scroll().p_5())
                            .when(hero, |d| d.overflow_hidden())
                            .rounded(px(if hero { 20. } else { 12. }))
                            .bg(p.surface2)
                            .shadow_2xl()
                            .on_click(|_, _, cx| cx.stop_propagation())
                            .child(body)
                            // open: fade in while rising a little
                            .with_animation(
                                "dialog-in",
                                Animation::new(std::time::Duration::from_millis(220)).with_easing(ease_out_quint()),
                                |d, t| d.opacity(t).mt(px((1. - t) * 18.)),
                            ),
                    ),
            )
            .with_priority(4)
            .into_any_element(),
        )
    }

    fn render_toasts(&mut self, cx: &mut Context<Self>) -> impl IntoElement {
        let toasts = cx.default_global::<Toasts>().items.clone();
        div()
            .absolute()
            .left_4()
            .bottom_4()
            .flex()
            .flex_col()
            .gap_2()
            .children(toasts.into_iter().map(|t| {
                let anim_id = SharedString::from(format!("toast-in-{}", t.id));
                div()
                    .flex()
                    .items_center()
                    .gap_4()
                    .px_4()
                    .py_3()
                    .rounded_lg()
                    .bg(rgb(0x323232))
                    .text_sm()
                    .text_color(white())
                    .shadow_lg()
                    .child(t.text.clone())
                    .when_some(t.action.clone(), |d, (label, f)| {
                        let id = t.id;
                        d.child(
                            div()
                                .id(SharedString::from(format!("toast-{id}")))
                                .text_color(rgb(0x3ea6ff))
                                .font_weight(FontWeight::SEMIBOLD)
                                .cursor_pointer()
                                .child(label)
                                .on_click(move |_, window, cx| {
                                    f(window, cx);
                                    cx.default_global::<Toasts>().items.retain(|x| x.id != id);
                                    cx.refresh_windows();
                                }),
                        )
                    })
                    .children(t.extra.clone().into_iter().enumerate().map(|(i, (label, f))| {
                        let id = t.id;
                        div()
                            .id(SharedString::from(format!("toast-{id}-{i}")))
                            .text_color(rgb(0x3ea6ff))
                            .font_weight(FontWeight::SEMIBOLD)
                            .cursor_pointer()
                            .child(label)
                            .on_click(move |_, window, cx| {
                                f(window, cx);
                                cx.default_global::<Toasts>().items.retain(|x| x.id != id);
                                cx.refresh_windows();
                            })
                    }))
                .with_animation(
                    anim_id,
                    Animation::new(std::time::Duration::from_millis(240)).with_easing(ease_out_quint()),
                    |d, t| d.opacity(t).ml(px((1. - t) * -24.)),
                )
            }))
    }
}

impl Render for AppView {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        crate::perf::add(&crate::perf::APP_RENDERS, 1);
        crate::display::mark_drawn();
        {
            static FIRST: std::sync::Once = std::sync::Once::new();
            FIRST.call_once(|| crate::perf::mark("first frame"));
        }
        if std::mem::take(&mut self.reload_page) {
            // account changed: feeds that depend on it are rebuilt (the watch page keeps playing)
            if matches!(self.route, Route::Home | Route::Subscriptions | Route::Channels) {
                self.page = build_page(&self.route, window, cx);
            }
        }
        if let Some(id) = self.pending_watch_sync.take() {
            if let Route::Watch { id: cur, .. } = &self.route {
                if *cur != id {
                    let r = Route::Watch { id, playlist: None, start: None };
                    let page = build_page(&r, window, cx);
                    let old = std::mem::replace(&mut self.route, r);
                    let old_page = std::mem::replace(&mut self.page, page);
                    self.history.push((old, old_page));
                }
            }
        }
        let p = pal(cx);
        let scale = store::settings_snapshot().ui_scale.clamp(0.75, 1.5);
        if (f32::from(window.rem_size()) - 16.0 * scale).abs() > 0.01 {
            window.set_rem_size(px(16.0 * scale));
        }
        let is_watch = matches!(self.route, Route::Watch { .. });
        let is_shorts = matches!(self.route, Route::Shorts(_));
        let ui = cx.global_mut::<UiState>();
        ui.viewport = window.viewport_size();
        ui.shorts = is_shorts;
        let fullscreen = ui.fullscreen && (is_watch || is_shorts);
        let mut root = div()
            .image_cache(self.images.clone())
            .id("app-root")
            .track_focus(&self.focus)
            .key_context("StreamTube")
            .size_full()
            .relative()
            .flex()
            .flex_col()
            .bg(p.bg)
            .text_color(p.text)
            .font_family(".SystemUIFont")
            .on_key_down(cx.listener(Self::on_key))
            .on_key_up(cx.listener(Self::on_key_up))
            .on_mouse_down(
                MouseButton::Navigate(NavigationDirection::Back),
                cx.listener(|this, _, window, cx| this.back(window, cx)),
            )
            .on_mouse_down(
                MouseButton::Navigate(NavigationDirection::Forward),
                cx.listener(|this, _, window, cx| this.forward(window, cx)),
            );
        if !is_shorts {
            crate::player::shorts_preload::clear();
        }
        let has_slot = matches!(self.route, Route::Watch { .. } | Route::Shorts(_));
        let page_id = self.page.entity_id();
        let page_area = div()
            .relative()
            .overflow_hidden()
            // Measure inside the page's px_6 gutters before the cached page lays out its cards.
            .child(
                canvas(
                    move |bounds, _, cx| {
                        let width = bounds.size.width;
                        if cx.global::<UiState>().content_width != width {
                            cx.global_mut::<UiState>().content_width = width;
                            cx.notify(page_id);
                        }
                    },
                    |_, _, _, _| {},
                )
                .absolute()
                .left_6()
                .right_6()
                .top_0()
                .h_0(),
            )
            .child(
                // each newly shown page fades in; the animation stops after 160 ms
                div()
                    .size_full()
                    .child(self.page.clone().cached(StyleRefinement::default().size_full()))
                    .with_animation(
                        SharedString::from(format!("page-in-{}", self.page.entity_id())),
                        Animation::new(std::time::Duration::from_millis(160)).with_easing(ease_out_quint()),
                        |d, t| d.opacity(0.35 + 0.65 * t),
                    ),
            )
            .when(has_slot, |d| d.child(crate::ui::pip::main_surface(&self.main_view, cx)))
            // only over a thumbnail drawn on this page (the slot is cleared when a
            // preview stops or moves to another video)
            .when(
                crate::player::preview::showing(cx).is_some() && cx.global::<crate::player::preview::PreviewSlot>().0.get().is_some(),
                |d| {
                    let slot = cx.global::<crate::player::preview::PreviewSlot>().0.clone();
                    d.child(crate::ui::slot::SlotFollower::with_slot(slot, AnyView::from(self.preview_view.clone())))
                },
            );
        if fullscreen {
            root = root.child(page_area.size_full());
        } else {
            let sidebar = if is_watch {
                // on watch pages the sidebar is an overlay drawer (like YouTube)
                None
            } else {
                Some(self.render_sidebar(cx))
            };
            root = root.child(self.render_topbar(window, cx)).children(self.render_tabs(cx)).child(
                div()
                    .flex()
                    .flex_1()
                    .min_h_0()
                    .children(sidebar)
                    .child(page_area.flex_1().min_w_0().h_full()),
            );
            {
                // a smaller window keeps the mini player on screen
                let vp = window.viewport_size();
                self.mini_geom = clamp_mini(self.mini_geom, vp.width.into(), vp.height.into());
            }
            root = root.children(self.render_mini_player(cx)).children(self.render_mini_drag(cx));
            root = root.children(self.render_drawer(cx));
        }
        root = root.children(self.render_dl_panel(cx));
        root = root.children(self.render_menu(cx)).children(self.render_dialog(cx)).child(self.render_toasts(cx));
        if self.splash.as_ref().is_some_and(|s| s.finished()) {
            self.splash = None;
        }
        if let Some(ref splash) = self.splash {
            root = root.child(deferred(super::splash::render_splash(splash, window)).with_priority(5));
        }
        root
    }
}

#[cfg(windows)]
fn show_window_cmd(window: &Window, cmd: i32) {
    use raw_window_handle::{HasWindowHandle, RawWindowHandle};
    #[link(name = "user32")]
    unsafe extern "system" {
        fn ShowWindowAsync(hwnd: isize, cmd: i32) -> i32;
    }
    if let Ok(h) = HasWindowHandle::window_handle(window) {
        if let RawWindowHandle::Win32(w) = h.as_raw() {
            unsafe { ShowWindowAsync(w.hwnd.get(), cmd) };
        }
    }
}

/// Ensures Windows removes the NonRudeHWND property and refreshes window z-order so
/// a borderless fullscreen window covers the Windows Taskbar cleanly.
pub fn ensure_fullscreen_taskbar_hidden(window: &Window) {
    #[cfg(windows)]
    {
        use raw_window_handle::{HasWindowHandle, RawWindowHandle};
        #[link(name = "user32")]
        unsafe extern "system" {
            fn RemovePropW(hwnd: isize, lp_string: *const u16) -> isize;
            fn SetWindowPos(hwnd: isize, insert_after: isize, x: i32, y: i32, cx: i32, cy: i32, flags: u32) -> i32;
        }
        if let Ok(h) = HasWindowHandle::window_handle(window) {
            if let RawWindowHandle::Win32(w) = h.as_raw() {
                let hwnd = w.hwnd.get();
                // "NonRudeHWND\0" in UTF-16
                const NON_RUDE: [u16; 12] = [
                    b'N' as u16, b'o' as u16, b'n' as u16, b'R' as u16, b'u' as u16, b'd' as u16,
                    b'e' as u16, b'H' as u16, b'W' as u16, b'N' as u16, b'D' as u16, 0,
                ];
                const HWND_TOP: isize = 0;
                const SWP_NOSIZE: u32 = 0x0001;
                const SWP_NOMOVE: u32 = 0x0002;
                const SWP_FRAMECHANGED: u32 = 0x0020;
                const SWP_SHOWWINDOW: u32 = 0x0040;
                unsafe {
                    RemovePropW(hwnd, NON_RUDE.as_ptr());
                    SetWindowPos(hwnd, HWND_TOP, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_FRAMECHANGED | SWP_SHOWWINDOW);
                }
            }
        }
    }
    #[cfg(not(windows))]
    let _ = window;
}

fn poll_main_window(cx: &mut App, mut step: impl FnMut(&mut Window, &mut App) -> bool + 'static) {
    let pip = crate::ui::pip::handle(cx);
    let Some(handle) = cx.windows().into_iter().find(|w| Some(*w) != pip) else { return };
    cx.spawn(async move |cx| {
        for _ in 0..12 {
            cx.background_executor().timer(std::time::Duration::from_millis(16)).await;
            let done = handle
                .update(cx, |_, window, cx| step(window, cx))
                .unwrap_or(true);
            if done {
                break;
            }
        }
    })
    .detach();
}

fn schedule_fullscreen_taskbar_hide(window: &Window, cx: &mut App) {
    #[cfg(windows)]
    {
        if window.is_fullscreen() {
            ensure_fullscreen_taskbar_hidden(window);
            cx.refresh_windows();
            return;
        }
        poll_main_window(cx, |window, cx| {
            if window.is_fullscreen() {
                ensure_fullscreen_taskbar_hidden(window);
                cx.refresh_windows();
                true
            } else {
                false
            }
        });
    }
    #[cfg(not(windows))]
    let _ = (window, cx);
}

/// Maximizes a non-fullscreen window cleanly on Windows and Linux.
pub fn ensure_window_maximized(window: &mut Window) {
    if !window.is_maximized() {
        window.zoom_window();
        #[cfg(windows)]
        {
            const SW_SHOWMAXIMIZED: i32 = 3;
            show_window_cmd(window, SW_SHOWMAXIMIZED);
        }
    }
}

/// Toggles the desktop window between borderless Fullscreen and Maximized modes.
pub fn toggle_fullscreen_maximized(window: &mut Window, cx: &mut App) {
    let leaving_fullscreen = window.is_fullscreen();
    if cx.try_global::<UiState>().is_some() {
        cx.global_mut::<UiState>().video_fs_entered_os = false;
    }
    window.toggle_fullscreen();
    if leaving_fullscreen {
        if !window.is_fullscreen() {
            ensure_window_maximized(window);
            cx.refresh_windows();
            return;
        }
        // GPUI's toggle_fullscreen mutates style/state asynchronously on Windows; wait until it exits before maximizing.
        poll_main_window(cx, |window, cx| {
            if !window.is_fullscreen() {
                ensure_window_maximized(window);
                cx.refresh_windows();
                true
            } else {
                false
            }
        });
    } else {
        schedule_fullscreen_taskbar_hide(window, cx);
    }
}

/// Maximizes, or restores a maximized window.
pub fn toggle_maximize(window: &mut Window) {
    if cfg!(target_os = "linux") || !window.is_maximized() {
        window.zoom_window();
        return;
    }
    #[cfg(windows)]
    {
        const SW_RESTORE: i32 = 9;
        show_window_cmd(window, SW_RESTORE);
    }
}

/// Release notes without their "Download" section (the app installs the update itself).
fn strip_download_section(notes: &str) -> String {
    let mut out = Vec::new();
    let mut skipping = false;
    for line in notes.lines() {
        if line.starts_with("## ") {
            skipping = line.trim_start_matches('#').trim().eq_ignore_ascii_case("download");
        }
        if !skipping {
            out.push(line);
        }
    }
    out.join("\n").trim().to_string()
}

/// Brings a minimized window back (leaves maximized/normal windows as they are).
pub fn restore_window(window: &mut Window) {
    if !window_minimized(window) {
        return;
    }
    #[cfg(windows)]
    {
        const SW_RESTORE: i32 = 9;
        show_window_cmd(window, SW_RESTORE);
    }
}

/// Empty top-bar space that moves the window (with snap and double-click maximize).
fn drag_gap(id: &'static str) -> Stateful<Div> {
    let gap = div()
        .id(id)
        .h(px(56.))
        .window_control_area(WindowControlArea::Drag);
    #[cfg(target_os = "linux")]
    let gap = gap.on_mouse_down(MouseButton::Left, |event, window, cx| {
        if event.click_count == 2 {
            toggle_maximize(window);
            cx.stop_propagation();
        }
    });
    gap
}

/// Minimize / maximize / close, drawn in the top bar. The OS handles the clicks
/// (WindowControlArea), so Snap Layouts on the maximize button work as usual.
fn window_controls(maximized: bool, cx: &App) -> impl IntoElement {
    let p = pal(cx);
    let btn = |id: &'static str, icon_name: &'static str, area: WindowControlArea, close: bool| {
        div()
            .id(id)
            .flex()
            .flex_none()
            .items_center()
            .justify_center()
            .w(px(46.))
            .h(px(56.))
            .text_color(p.text)
            // Keep Max handled in app code so it cleanly toggles Fullscreen <-> Maximized
            // without Windows WM_NCLBUTTONUP bypassing on_click to restore to a floating window.
            .when(area != WindowControlArea::Max, |d| d.window_control_area(area))
            .when(area == WindowControlArea::Max, |d| d.cursor_pointer())
            .when(close, |d| d.hover(|s| s.bg(rgb(0xc42b1c)).text_color(white())))
            .when(!close, move |d| d.hover(move |s| s.bg(p.hover)))
            .child(icon(icon_name).size_4().text_color(p.text))
            // Handle the click ourselves too: Windows only routes it to the caption button
            // when its hit test caught up with the cursor, which isn't always the case.
            // Stopping propagation keeps Windows from acting on it a second time.
            .on_click(move |_, window, cx| {
                cx.stop_propagation();
                match area {
                    WindowControlArea::Min => window.minimize_window(),
                    WindowControlArea::Max => toggle_fullscreen_maximized(window, cx),
                    WindowControlArea::Close => {
                        crate::save_window_bounds(window);
                        window.remove_window();
                    }
                    WindowControlArea::Drag => {}
                }
            })
    };
    div()
        .flex()
        .h_full()
        .self_start()
        .child(btn("win-min", "minus", WindowControlArea::Min, false))
        .child(btn("win-max", if maximized { "copy" } else { "square" }, WindowControlArea::Max, false))
        .child(btn("win-close", "x", WindowControlArea::Close, true))
}

fn build_page(route: &Route, window: &mut Window, cx: &mut App) -> AnyView {
    match route {
        Route::Home => cx.new(|cx| home::HomePage::new(0, window, cx)).into(),
        Route::Explore(i) => cx.new(|cx| home::HomePage::new(i + 1, window, cx)).into(),
        Route::Shorts(id) => cx.new(|cx| shorts::ShortsPage::new(id.clone(), window, cx)).into(),
        Route::Subscriptions => cx.new(|cx| subscriptions::FeedPage::new(window, cx)).into(),
        Route::Channels => cx.new(|cx| subscriptions::ChannelsPage::new(window, cx)).into(),
        Route::History => cx.new(|cx| library::HistoryPage::new(window, cx)).into(),
        Route::Library => cx.new(|cx| library::LibraryPage::new(window, cx)).into(),
        Route::LocalPlaylist(id) => cx.new(|cx| playlist::PlaylistPage::local(*id, window, cx)).into(),
        Route::Playlist(id) => cx.new(|cx| playlist::PlaylistPage::remote(id.clone(), window, cx)).into(),
        Route::Search(q) => cx.new(|cx| search::SearchPage::new(q.clone(), window, cx)).into(),
        Route::Channel(id) => cx.new(|cx| channel::ChannelPage::new(id.clone(), window, cx)).into(),
        Route::Watch { id, playlist, start } => {
            cx.new(|cx| watch::WatchPage::new(id.clone(), playlist.clone(), *start, window, cx)).into()
        }
        Route::Settings => cx.new(|cx| settings::SettingsPage::new(window, cx)).into(),
        Route::Downloads => cx.new(|cx| downloads::DownloadsPage::new(window, cx)).into(),
    }
}

/// Shows a download in the system file manager.
pub fn reveal_in_folder(path: &str, cx: &mut App) {
    let path = std::path::Path::new(path);
    if !path.exists() {
        toast(cx, t("File not found (moved or deleted)"));
        return;
    }
    if path.is_dir() {
        let _ = open::that(path);
        return;
    }
    #[cfg(windows)]
    {
        // SHOpenFolderAndSelectItems can block and run its own message loop (it shows
        // an error dialog when Explorer can't open the folder). That must not happen on
        // GPUI's thread, so it runs on its own thread with its own COM apartment.
        let path = path.to_path_buf();
        let _ = std::thread::Builder::new().name("reveal-in-folder".into()).spawn(move || {
            if !select_in_explorer(&path) {
                if let Some(folder) = path.parent() {
                    let _ = open::that(folder);
                }
            }
        });
    }
    #[cfg(not(windows))]
    if let Some(folder) = path.parent() {
        let _ = open::that(folder);
    }
}

#[cfg(windows)]
fn select_in_explorer(path: &std::path::Path) -> bool {
    use std::os::windows::ffi::OsStrExt;
    use windows::Win32::System::Com::{COINIT_APARTMENTTHREADED, CoInitializeEx, CoUninitialize};
    use windows::Win32::UI::Shell::{ILCreateFromPathW, ILFree, SHOpenFolderAndSelectItems};
    use windows::core::PCWSTR;

    let Ok(path) = std::path::absolute(path) else { return false };
    let wide: Vec<u16> = path.as_os_str().encode_wide().chain([0]).collect();
    unsafe {
        let com = CoInitializeEx(None, COINIT_APARTMENTTHREADED).is_ok();
        let item = ILCreateFromPathW(PCWSTR(wide.as_ptr()));
        let ok = !item.is_null() && {
            let result = SHOpenFolderAndSelectItems(item, None, 0);
            ILFree(Some(item));
            result.is_ok()
        };
        if com {
            CoUninitialize();
        }
        ok
    }
}

/// Redraws every built page (current, back and forward history). Pages are cached
/// views, so a settings change (density, corners, columns, accent...) only shows
/// after their entities are notified.
pub fn refresh_all_pages(cx: &mut App) {
    let Some(app) = cx.try_global::<AppHandle>().and_then(|h| h.0.upgrade()) else { return };
    let ids: Vec<EntityId> = {
        let a = app.read(cx);
        std::iter::once(a.page.entity_id())
            .chain(a.history.iter().map(|(_, v)| v.entity_id()))
            .chain(a.forward.iter().map(|(_, v)| v.entity_id()))
            .collect()
    };
    for id in ids {
        cx.notify(id);
    }
    app.update(cx, |_, cx| cx.notify());
    cx.refresh_windows();
}

/// True when the window is minimized (iconic). Elsewhere a hidden window counts as minimized.
fn window_minimized(window: &Window) -> bool {
    #[cfg(windows)]
    {
        use raw_window_handle::{HasWindowHandle, RawWindowHandle};
        #[link(name = "user32")]
        unsafe extern "system" {
            fn IsIconic(hwnd: isize) -> i32;
        }
        if let Ok(h) = HasWindowHandle::window_handle(window) {
            if let RawWindowHandle::Win32(w) = h.as_raw() {
                return unsafe { IsIconic(w.hwnd.get()) } != 0;
            }
        }
        false
    }
    #[cfg(not(windows))]
    {
        let _ = window;
        true
    }
}

struct MiniDrag {
    resize: bool,
    start: Point<Pixels>,
    right: f32,
    bottom: f32,
    w: f32,
    moved: bool,
}

const MINI_MIN_W: f32 = 260.0;
const MINI_MAX_W: f32 = 960.0;

/// Keeps the mini player (right, bottom, width) inside a vw x vh window.
fn clamp_mini((right, bottom, w): (f32, f32, f32), vw: f32, vh: f32) -> (f32, f32, f32) {
    let w = w.clamp(MINI_MIN_W, MINI_MAX_W).min((vw - 16.0).max(MINI_MIN_W));
    // video plus the title strip
    let h = w * 9.0 / 16.0 + 52.0;
    let right = right.clamp(8.0, (vw - w - 8.0).max(8.0));
    let bottom = bottom.clamp(8.0, (vh - h - 8.0).max(8.0));
    (right, bottom, w)
}

fn load_mini_geom() -> (f32, f32, f32) {
    store::kv_get("mini_geom")
        .and_then(|s| {
            let v: Vec<f32> = s.split(',').filter_map(|x| x.parse().ok()).collect();
            (v.len() == 3).then(|| (v[0], v[1], v[2]))
        })
        .map(|(r, b, w)| (r.max(8.0), b.max(8.0), w.clamp(MINI_MIN_W, MINI_MAX_W)))
        .unwrap_or((16.0, 16.0, 400.0))
}
