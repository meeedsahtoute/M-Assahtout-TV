#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
pub use streamtube_core::{api, feed, import, links, locale, perf, recommend, store};

mod assets;
mod display;
mod downloads;
mod i18n;
mod player;
mod ui;
mod updater;

use gpui_kit::*;

/// Always open in borderless fullscreen on primary display bounds on startup.
fn saved_window_bounds(cx: &App) -> WindowBounds {
    let display_bounds = cx
        .primary_display()
        .map(|d| d.bounds())
        .unwrap_or_else(|| Bounds::centered(None, size(px(1600.), px(1000.)), cx));
    WindowBounds::Fullscreen(display_bounds)
}

pub fn save_window_bounds(window: &Window) {
    let (b, maximized) = match window.window_bounds() {
        WindowBounds::Windowed(b) => (b, false),
        WindowBounds::Maximized(b) => (b, true),
        WindowBounds::Fullscreen(b) => (b, false),
    };
    let v = serde_json::json!({
        "x": f32::from(b.origin.x), "y": f32::from(b.origin.y),
        "w": f32::from(b.size.width), "h": f32::from(b.size.height),
        "maximized": maximized,
    });
    store::kv_set("window", &v.to_string());
}

fn main() {
    perf::mark("main");
    // the local stream proxy must never be reached through the user's proxy
    // SAFETY: set before any other thread starts
    unsafe {
        std::env::set_var("no_proxy", "127.0.0.1,localhost,::1");
        std::env::set_var("NO_PROXY", "127.0.0.1,localhost,::1");
    }
    perf::init();
    store::init();
    perf::mark("store");
    display::choose();
    perf::mark("display");
    // one running app: a second launch (e.g. a clicked streamtube: link) hands over and exits
    let arg = std::env::args().nth(1).unwrap_or_default();
    if !display::relaunched() && links::forward_to_running(&arg) {
        return;
    }
    let incoming = links::listen();
    perf::mark("links");
    gpui_kit::application()
        .with_assets(assets::Assets)
        .with_http_client(ui::UreqHttp::new())
        .run(|cx: &mut App| {
            perf::mark("gpui running");
            gpui_kit::init(cx);
            i18n::apply_component_locale();
            perf::mark("gpui_kit init");
            player::preview::init(cx);
            perf::mark("preview init");
            cx.set_global(ui::Pal::new(true, store::settings_snapshot().accent));
            ui::apply_theme(None, cx);
            player::controller::init(cx);
            perf::mark("player init");
            let opts = WindowOptions {
                // Without this the Wayland/X11 window has no identity, so docks and
                // taskbars can't match it to streamtube.desktop (StartupWMClass).
                app_id: Some("streamtube".into()),
                window_bounds: Some(saved_window_bounds(cx)),
                titlebar: Some(TitlebarOptions { title: Some("M. ASSAHTOUT TV".into()), appears_transparent: true, ..Default::default() }),
                window_min_size: Some(size(px(720.), px(480.))),
                ..Default::default()
            };
            let main_win = gpui_kit::open_window(opts, cx, |window, cx| {
                ui::apply_theme(Some(window), cx);
                cx.new(|cx| ui::app::AppView::new(window, cx))
            })
            .expect("failed to open window");
            // Ensure Windows enters borderless fullscreen and hides the taskbar cleanly on launch.
            let main_handle: AnyWindowHandle = main_win.into();
            cx.spawn(async move |cx| {
                for _ in 0..10 {
                    cx.background_executor().timer(std::time::Duration::from_millis(20)).await;
                    let done = main_handle
                        .update(cx, |_, window, cx| {
                            if !window.is_fullscreen() {
                                window.toggle_fullscreen();
                                false
                            } else {
                                ui::app::ensure_fullscreen_taskbar_hidden(window);
                                cx.refresh_windows();
                                true
                            }
                        })
                        .unwrap_or(true);
                    if done {
                        break;
                    }
                }
            })
            .detach();
            perf::mark("window open");
            ui::app_icon::apply(cx);
            links::register_scheme();
            perf::mark("icon");
            if let Some(mut rx) = incoming {
                cx.spawn(async move |cx| {
                    use futures::StreamExt;
                    while let Some(arg) = rx.next().await {
                        let _ = cx.update(|cx| {
                            let pip = ui::pip::handle(cx);
                            let Some(main) = cx.windows().into_iter().find(|w| Some(*w) != pip) else { return };
                            let _ = main.update(cx, |_, window, cx| {
                                ui::app::restore_window(window);
                                window.activate_window();
                                if let Some(route) = ui::app::route_from_link(&arg) {
                                    ui::app::navigate(route, window, cx);
                                }
                            });
                        });
                    }
                })
                .detach();
            }
            cx.on_window_closed(|cx, id| {
                ui::pip::window_closed(id, cx);
                // the picture-in-picture window alone doesn't keep the app running
                let pip = ui::pip::handle(cx);
                if cx.windows().iter().all(|w| Some(*w) == pip) {
                    player::controller::player(cx).update(cx, |p, _| p.save_progress(true));
                    cx.quit();
                }
            })
            .detach();
        });
}
