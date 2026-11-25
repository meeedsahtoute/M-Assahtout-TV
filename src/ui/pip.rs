//! Picture-in-picture: the player in a small always-on-top window that stays
//! visible over other apps.
use crate::player::controller::player;
use crate::player::view::PlayerView;
use crate::ui::*;

#[derive(Default)]
struct PipWindow(Option<AnyWindowHandle>);
impl Global for PipWindow {}

pub struct PipView {
    view: Entity<PlayerView>,
}

pub fn handle(cx: &App) -> Option<AnyWindowHandle> {
    cx.try_global::<PipWindow>().and_then(|p| p.0)
}

pub fn open(cx: &mut App) {
    if let Some(h) = cx.try_global::<PipWindow>().and_then(|p| p.0) {
        if h.update(cx, |_, window, _| window.activate_window()).is_ok() {
            return;
        }
    }
    let size = size(px(480.), px(270.));
    let origin = cx
        .primary_display()
        .map(|d| {
            let b = d.bounds();
            point(b.origin.x + b.size.width - size.width - px(24.), b.origin.y + b.size.height - size.height - px(72.))
        })
        .unwrap_or(point(px(80.), px(80.)));
    let opts = WindowOptions {
        window_bounds: Some(WindowBounds::Windowed(Bounds::new(origin, size))),
        titlebar: None,
        kind: WindowKind::PopUp,
        is_resizable: true,
        is_minimizable: false,
        window_min_size: Some(gpui_kit::size(px(256.), px(144.))),
        ..Default::default()
    };
    let res = gpui_kit::open_window(opts, cx, |_, cx| {
        let pl = player(cx);
        let view = cx.new(|cx| {
            let mut v = PlayerView::new(pl, false, cx);
            v.pip = true;
            v
        });
        cx.new(|_| PipView { view })
    });
    match res {
        Ok((h, _)) => cx.set_global(PipWindow(Some(h))),
        Err(e) => toast(cx, crate::ui::friendly_error(&e.to_string())),
    }
    cx.refresh_windows();
}

pub fn close(window: &mut Window, cx: &mut App) {
    cx.set_global(PipWindow(None));
    window.remove_window();
    cx.refresh_windows();
}

/// Starts moving the window under the pointer, as if its title bar was pressed.
/// GPUI's Windows backend has no `start_window_move`, so Windows asks the
/// system directly. The request is posted, not sent: the system's move loop
/// must start from the message loop, not inside this event handler (it calls
/// back into GPUI, which is busy handling the event).
pub(crate) fn begin_window_move(window: &Window) {
    #[cfg(windows)]
    {
        use raw_window_handle::{HasWindowHandle, RawWindowHandle};
        #[repr(C)]
        struct Pt {
            x: i32,
            y: i32,
        }
        #[link(name = "user32")]
        unsafe extern "system" {
            fn ReleaseCapture() -> i32;
            fn GetCursorPos(p: *mut Pt) -> i32;
            fn PostMessageW(hwnd: isize, msg: u32, wparam: usize, lparam: isize) -> i32;
        }
        const WM_NCLBUTTONDOWN: u32 = 0x00A1;
        const HTCAPTION: usize = 2;
        if let Ok(h) = HasWindowHandle::window_handle(window) {
            if let RawWindowHandle::Win32(w) = h.as_raw() {
                // SAFETY: plain Win32 calls on a live window owned by this process
                unsafe {
                    let mut p = Pt { x: 0, y: 0 };
                    GetCursorPos(&mut p);
                    let lparam = ((p.y as u16 as isize) << 16) | (p.x as u16 as isize);
                    ReleaseCapture();
                    PostMessageW(w.hwnd.get(), WM_NCLBUTTONDOWN, HTCAPTION, lparam);
                }
            }
        }
    }
    #[cfg(not(windows))]
    window.start_window_move();
}

/// The PiP window was closed some other way (e.g. Alt+F4): forget it so the
/// main window shows the video again.
pub fn window_closed(id: WindowId, cx: &mut App) {
    if handle(cx).is_some_and(|h| h.window_id() == id) {
        cx.set_global(PipWindow(None));
        cx.refresh_windows();
    }
}

/// What the main window shows where the player sits: the video, or while
/// the PiP window plays it, a placeholder (like YouTube) so the picture
/// isn't drawn twice.
pub(super) fn main_surface(view: &Entity<PlayerView>, cx: &App) -> super::slot::SlotFollower {
    let content = if handle(cx).is_some() { placeholder().into_any_element() } else { view.clone().into_any_element() };
    super::slot::SlotFollower::new(cx, content)
}

fn placeholder() -> impl IntoElement {
    div()
        .size_full()
        .bg(black())
        .text_color(white())
        .flex()
        .flex_col()
        .items_center()
        .justify_center()
        .gap_3()
        .child(icon("picture-in-picture").size_8())
        .child(crate::i18n::t("Playing in picture-in-picture").to_string())
        .child(
            div()
                .id("return-from-pip")
                .px_4()
                .py_2()
                .rounded_full()
                .bg(hsla(0., 0., 1., 0.15))
                .hover(|s| s.bg(hsla(0., 0., 1., 0.25)))
                .cursor_pointer()
                .text_sm()
                .child(crate::i18n::t("Return to player").to_string())
                .on_click(|_, _, cx| {
                    if let Some(h) = handle(cx) {
                        let _ = h.update(cx, |_, window, cx| close(window, cx));
                    }
                }),
        )
}

impl Render for PipView {
    fn render(&mut self, _window: &mut Window, _cx: &mut Context<Self>) -> impl IntoElement {
        div()
            .id("pip")
            .group("pip")
            .relative()
            .size_full()
            .bg(black())
            .child(self.view.clone())
            // top strip: drag to move, close button
            .child(
                div()
                    .absolute()
                    .top_0()
                    .left_0()
                    .right_0()
                    .h(px(32.))
                    .flex()
                    .items_center()
                    .justify_end()
                    .invisible()
                    .group_hover("pip", |s| s.visible())
                    .bg(linear_gradient(180., linear_color_stop(hsla(0., 0., 0., 0.6), 0.), linear_color_stop(hsla(0., 0., 0., 0.), 1.)))
                    .child(div().id("pip-drag").flex_1().h_full().window_control_area(WindowControlArea::Drag))
                    .child(
                        div()
                            .id("pip-close")
                            .flex()
                            .items_center()
                            .justify_center()
                            .size(px(32.))
                            .cursor_pointer()
                            .hover(|s| s.bg(hsla(0., 0., 1., 0.15)))
                            .child(icon("x").size_4().text_color(white()))
                            .on_click(|_, window, cx| {
                                cx.stop_propagation();
                                close(window, cx);
                            }),
                    ),
            )
    }
}
