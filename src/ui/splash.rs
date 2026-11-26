//! Flagship 3.2-second 60fps optical cinema splash screen + 48 kHz stereo audio,
//! replicating the Android TV `SplashScreen.kt` launch experience.
use gpui_kit::*;
use std::f32::consts::PI;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

pub const TOTAL_SPLASH_MS: f32 = 3200.0;
const DIVE_START_MS: f32 = 2400.0;
const BOKEH_COUNT: usize = 24;
const RIBBON_COUNT: usize = 14;

/// Exact 48 kHz 16-bit stereo theater-mastered WAV embedded into the binary.
const INTRO_CINEMA_WAV: &[u8] = include_bytes!("../../android/app/src/main/res/raw/intro_cinema.wav");

#[derive(Clone, Copy)]
struct BokehMote {
    norm_x: f32,
    norm_y: f32,
    vel_x: f32,
    vel_y: f32,
    radius: f32,
    depth: f32,
    phase: f32,
    kind: u8,
}

#[derive(Clone, Copy)]
struct SpectrumRibbon {
    start_norm_x: f32,
    spread_vel: f32,
    width_px: f32,
    alpha: f32,
    kind: u8,
}

struct SplashPool {
    bokeh: [BokehMote; BOKEH_COUNT],
    ribbons: [SpectrumRibbon; RIBBON_COUNT],
}

fn lcg_next(state: &mut u64) -> f32 {
    *state = state.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
    ((*state >> 40) as u32) as f32 / ((1u32 << 24) as f32)
}

static POOL: std::sync::LazyLock<SplashPool> = std::sync::LazyLock::new(|| {
    let mut rng = 480600u64;
    let mut bokeh = [BokehMote {
        norm_x: 0.5,
        norm_y: 0.5,
        vel_x: 0.0,
        vel_y: 0.0,
        radius: 16.0,
        depth: 1.0,
        phase: 0.0,
        kind: 0,
    }; BOKEH_COUNT];

    for (i, m) in bokeh.iter_mut().enumerate() {
        let rx = lcg_next(&mut rng);
        let ry = lcg_next(&mut rng);
        let vx = lcg_next(&mut rng);
        let vy = lcg_next(&mut rng);
        let rd = lcg_next(&mut rng);
        let rp = lcg_next(&mut rng);
        let depth = 0.35 + rd * 1.15;
        *m = BokehMote {
            norm_x: 0.08 + rx * 0.84,
            norm_y: 0.08 + ry * 0.84,
            vel_x: (vx - 0.5) * 0.022,
            vel_y: -(0.012 + vy * 0.026),
            radius: 10.0 + depth * 28.0,
            depth,
            phase: rp * (2.0 * PI),
            kind: (i % 3) as u8,
        };
    }

    const PATTERNS: [f32; RIBBON_COUNT] = [
        -0.015, 0.015, -0.042, 0.042, -0.075, 0.075, -0.115, 0.115, -0.165, 0.165, -0.220, 0.220, -0.285, 0.285,
    ];
    const WIDTHS: [f32; RIBBON_COUNT] = [
        24.0, 22.0, 44.0, 40.0, 68.0, 64.0, 34.0, 36.0, 82.0, 78.0, 48.0, 52.0, 96.0, 90.0,
    ];
    const KINDS: [u8; RIBBON_COUNT] = [0, 2, 1, 0, 0, 1, 2, 0, 1, 2, 0, 1, 0, 2];

    let mut ribbons = [SpectrumRibbon {
        start_norm_x: 0.0,
        spread_vel: 0.5,
        width_px: 32.0,
        alpha: 0.5,
        kind: 0,
    }; RIBBON_COUNT];

    for i in 0..RIBBON_COUNT {
        let base_off = PATTERNS[i];
        let dir = if base_off < 0.0 { -1.0 } else { 1.0 };
        let tier = (i / 2 + 1) as f32;
        let r1 = lcg_next(&mut rng);
        let r2 = lcg_next(&mut rng);
        ribbons[i] = SpectrumRibbon {
            start_norm_x: base_off * 0.35,
            spread_vel: dir * (0.42 + tier * 0.19 + r1 * 0.08),
            width_px: WIDTHS[i],
            alpha: 0.32 + r2 * 0.42,
            kind: KINDS[i],
        };
    }

    SplashPool { bokeh, ribbons }
});

pub struct SplashState {
    started_at: Instant,
    audio_stop: Arc<AtomicBool>,
}

impl SplashState {
    pub fn start() -> Self {
        let audio_stop = Arc::new(AtomicBool::new(false));
        play_intro_audio(audio_stop.clone());
        Self { started_at: Instant::now(), audio_stop }
    }

    pub fn elapsed_ms(&self) -> f32 {
        self.started_at.elapsed().as_secs_f32() * 1000.0
    }

    pub fn finished(&self) -> bool {
        self.elapsed_ms() >= TOTAL_SPLASH_MS
    }

    pub fn stop_audio(&self) {
        self.audio_stop.store(true, Ordering::Relaxed);
        stop_intro_audio();
    }
}

impl Drop for SplashState {
    fn drop(&mut self) {
        self.audio_stop.store(true, Ordering::Relaxed);
    }
}

#[cfg(windows)]
fn play_intro_audio(_stop: Arc<AtomicBool>) {
    #[link(name = "winmm")]
    unsafe extern "system" {
        fn PlaySoundW(psz_sound: *const u8, hmod: isize, fdw_sound: u32) -> i32;
    }
    const SND_ASYNC: u32 = 0x0001;
    const SND_NODEFAULT: u32 = 0x0002;
    const SND_MEMORY: u32 = 0x0004;
    // Play directly from static memory so startup latency is < 1ms and synced to frame 0.
    unsafe {
        PlaySoundW(INTRO_CINEMA_WAV.as_ptr(), 0, SND_MEMORY | SND_ASYNC | SND_NODEFAULT);
    }
}

#[cfg(windows)]
fn stop_intro_audio() {
    #[link(name = "winmm")]
    unsafe extern "system" {
        fn PlaySoundW(psz_sound: *const u8, hmod: isize, fdw_sound: u32) -> i32;
    }
    unsafe {
        PlaySoundW(std::ptr::null(), 0, 0);
    }
}

#[cfg(not(windows))]
fn play_intro_audio(stop: Arc<AtomicBool>) {
    std::thread::Builder::new()
        .name("splash-audio".into())
        .spawn(move || {
            let path = std::env::temp_dir().join(format!("m_assahtout_tv_intro_{}.wav", std::process::id()));
            if std::fs::write(&path, INTRO_CINEMA_WAV).is_ok() {
                crate::player::mpv::play_oneshot_wav(&path, stop);
                let _ = std::fs::remove_file(&path);
            }
        })
        .ok();
}

#[cfg(not(windows))]
fn stop_intro_audio() {}

/// Evaluates cubic Bezier y(t) where x(u) = t using Newton/bisection refinement.
fn cubic_bezier(p1x: f32, p1y: f32, p2x: f32, p2y: f32, t: f32) -> f32 {
    let t = t.clamp(0.0, 1.0);
    if t <= 0.0 || t >= 1.0 {
        return t;
    }
    let bezier = |u: f32, p1: f32, p2: f32| -> f32 {
        let inv = 1.0 - u;
        3.0 * inv * inv * u * p1 + 3.0 * inv * u * u * p2 + u * u * u
    };
    let mut lo = 0.0f32;
    let mut hi = 1.0f32;
    let mut u = t;
    for _ in 0..10 {
        let x = bezier(u, p1x, p2x);
        if (x - t).abs() < 1e-4 {
            break;
        }
        if x < t {
            lo = u;
        } else {
            hi = u;
        }
        u = 0.5 * (lo + hi);
    }
    bezier(u, p1y, p2y).clamp(0.0, 1.0)
}

fn silk_ease(t: f32) -> f32 {
    cubic_bezier(0.16, 1.0, 0.3, 1.0, t)
}

fn dive_ease(t: f32) -> f32 {
    cubic_bezier(0.45, 0.0, 0.2, 1.0, t)
}

/// Renders a horizontal tracked text row so letter spacing smoothly animates at 60fps.
fn tracked_text(text: &str, font_px: f32, weight: FontWeight, color: Hsla, gap_px: f32) -> Div {
    let mut row = div().flex().items_center().justify_center().gap(px(gap_px));
    for ch in text.chars() {
        let mut buf = [0u8; 4];
        let s: String = ch.encode_utf8(&mut buf).to_string();
        row = row.child(
            div()
                .text_size(px(font_px))
                .font_weight(weight)
                .text_color(color)
                .child(s),
        );
    }
    row
}

pub fn render_splash(splash: &SplashState, window: &Window) -> AnyElement {
    let elapsed_ms = splash.elapsed_ms().clamp(0.0, TOTAL_SPLASH_MS);
    let vp = window.viewport_size();
    let vw: f32 = f32::from(vp.width).max(720.0);
    let vh: f32 = f32::from(vp.height).max(480.0);
    let cx = vw * 0.5;
    let cy = vh * 0.44;
    let ui_scale = (vh / 860.0).clamp(0.68, 1.25);

    // Phase 4 Dive-Through parameters (2400ms - 3200ms)
    let dive_p = ((elapsed_ms - DIVE_START_MS) / (TOTAL_SPLASH_MS - DIVE_START_MS)).clamp(0.0, 1.0);
    let dive_curve = dive_ease(dive_p);
    let backdrop_alpha = (1.0 - silk_ease(dive_p)).clamp(0.0, 1.0);

    // Phase 2 Logo emergence (480ms - 1450ms)
    let emerge_raw = ((elapsed_ms - 480.0) / 720.0).clamp(0.0, 1.0);
    let emerge_curve = silk_ease(emerge_raw);
    let focus_pull = silk_ease(((elapsed_ms - 480.0) / 420.0).clamp(0.0, 1.0));
    let emerge_scale = 0.84 + 0.16 * emerge_curve;
    let logo_scale = emerge_scale * (1.0 + 9.0 * dive_curve);
    let base_logo_px = 232.0 * ui_scale;
    let logo_px = base_logo_px * logo_scale;

    let logo_alpha = if elapsed_ms < 480.0 {
        0.0
    } else if elapsed_ms < 760.0 {
        silk_ease(((elapsed_ms - 480.0) / 240.0).clamp(0.0, 1.0))
    } else if elapsed_ms >= DIVE_START_MS {
        (1.0 - dive_curve * 1.15).clamp(0.0, 1.0)
    } else {
        1.0
    };

    // Phase 3 Luxury Typography (1150ms - 2400ms)
    let text_ease = silk_ease(((elapsed_ms - 1150.0) / 1050.0).clamp(0.0, 1.0));
    let text_slide_y = 12.0 * ui_scale * (1.0 - text_ease);
    let text_alpha = ((elapsed_ms - 1150.0) / 340.0).clamp(0.0, 1.0) * (1.0 - dive_curve * 2.1).clamp(0.0, 1.0);
    let sub_alpha = ((elapsed_ms - 1340.0) / 380.0).clamp(0.0, 1.0) * (1.0 - dive_curve * 2.3).clamp(0.0, 1.0);
    let beam_progress = silk_ease(((elapsed_ms - 1220.0) / 860.0).clamp(0.0, 1.0));

    let mut root = div()
        .id("splash-overlay")
        .absolute()
        .inset_0()
        .overflow_hidden()
        .occlude()
        .bg(rgba(0x000000, backdrop_alpha));

    // Atmospheric Ruby-Crimson Radial Backlight (300ms - 3200ms)
    let backlight_build = if elapsed_ms < 300.0 {
        0.0
    } else {
        silk_ease(((elapsed_ms - 300.0) / 480.0).clamp(0.0, 1.0))
    };
    let pulse = 0.90 + 0.10 * (elapsed_ms * 0.0028).sin();
    let backlight_alpha = backlight_build * pulse * (1.0 - dive_curve * 0.85).clamp(0.0, 1.0);
    if backlight_alpha > 0.005 {
        let glow_mult = 1.0 + 0.55 * dive_curve;
        for (rad, hex, op) in [
            (360.0 * ui_scale * glow_mult, 0x450A0Au32, 0.28),
            (230.0 * ui_scale * glow_mult, 0x991B1B, 0.34),
            (130.0 * ui_scale * glow_mult, 0xFF0033, 0.36),
        ] {
            let a = (backlight_alpha * op).clamp(0.0, 1.0);
            root = root.child(
                div()
                    .absolute()
                    .left(px(cx - rad))
                    .top(px(cy - rad))
                    .size(px(rad * 2.0))
                    .rounded_full()
                    .bg(rgba(hex, a)),
            );
        }
    }

    // 24 Slow-Drifting PS5-Style Bokeh Light Motes (0ms - 3200ms)
    let t_sec = elapsed_ms / 1000.0;
    let bokeh_global = (elapsed_ms / 320.0).clamp(0.0, 1.0) * (1.0 - dive_curve * 1.15).clamp(0.0, 1.0);
    if bokeh_global > 0.01 {
        for m in &POOL.bokeh {
            let bx = (m.norm_x + m.vel_x * t_sec) * vw;
            let by = (m.norm_y + m.vel_y * t_sec) * vh;
            let r = m.radius * ui_scale;
            let shimmer = 0.55 + 0.45 * (t_sec * 1.9 + m.phase).sin();
            let alpha = bokeh_global * shimmer * (0.42 + 0.36 * (m.depth / 1.5));
            if alpha > 0.01 {
                let hex = match m.kind {
                    0 => 0xFF2E55,
                    1 => 0xF5F5F7,
                    _ => 0xFFD6DE,
                };
                root = root
                    .child(
                        div()
                            .absolute()
                            .left(px(bx - r))
                            .top(px(by - r))
                            .size(px(r * 2.0))
                            .rounded_full()
                            .bg(rgba(hex, alpha * 0.16)),
                    )
                    .child(
                        div()
                            .absolute()
                            .left(px(bx - r * 0.42))
                            .top(px(by - r * 0.42))
                            .size(px(r * 0.84))
                            .rounded_full()
                            .bg(rgba(hex, alpha * 0.34)),
                    );
            }
        }
    }

    // Phase 1 (0ms - 640ms): Optical Prism Slit + Vertical Volumetric Bloom
    if elapsed_ms < 640.0 {
        let open_ease = silk_ease(((elapsed_ms - 20.0) / 360.0).clamp(0.0, 1.0));
        let slit_fade = if elapsed_ms > 360.0 {
            1.0 - silk_ease(((elapsed_ms - 360.0) / 260.0).clamp(0.0, 1.0))
        } else {
            (elapsed_ms / 110.0).clamp(0.0, 1.0)
        };
        let slit_w = (440.0 * ui_scale * open_ease).min(vw * 0.72);
        if slit_w > 2.0 && slit_fade > 0.01 {
            let bloom_w = slit_w * 1.15;
            let bloom_h = (56.0 + 160.0 * open_ease) * ui_scale;
            root = root.child(
                div()
                    .absolute()
                    .left(px(cx - bloom_w * 0.5))
                    .top(px(cy - bloom_h * 0.5))
                    .w(px(bloom_w))
                    .h(px(bloom_h))
                    .rounded_full()
                    .bg(rgba(0xFF1E42, slit_fade * 0.24)),
            );

            let core_h = (2.0 + 1.5 * open_ease) * ui_scale;
            let half_w = slit_w * 0.5;
            root = root
                .child(
                    div()
                        .absolute()
                        .left(px(cx - half_w))
                        .top(px(cy - core_h * 0.5))
                        .w(px(slit_w))
                        .h(px(core_h))
                        .flex()
                        .child(div().w(px(half_w)).h_full().bg(linear_gradient(
                            90.0,
                            linear_color_stop(rgba(0x991B1B, 0.0).into(), 0.0),
                            linear_color_stop(rgba(0xFF0033, slit_fade * 0.92).into(), 1.0),
                        )))
                        .child(div().w(px(half_w)).h_full().bg(linear_gradient(
                            90.0,
                            linear_color_stop(rgba(0xFF0033, slit_fade * 0.92).into(), 0.0),
                            linear_color_stop(rgba(0x991B1B, 0.0).into(), 1.0),
                        ))),
                )
                .child(
                    div()
                        .absolute()
                        .left(px(cx - slit_w * 0.28))
                        .top(px(cy - 0.75 * ui_scale))
                        .w(px(slit_w * 0.56))
                        .h(px(1.5 * ui_scale))
                        .rounded_full()
                        .bg(rgba(0xFFFFFF, slit_fade * 0.94)),
                );
        }
    }

    // Phase 2 & 4: Circular 4K Logo + Focus-Pull Aura + Specular Glass Sweep + Orbiting Rim Glint
    if logo_alpha > 0.005 {
        // Focus pull halo before lens snaps sharp (480ms - 900ms)
        if focus_pull < 0.99 {
            let halo_a = (1.0 - focus_pull) * logo_alpha * 0.42;
            let halo_sz = logo_px * (1.14 - 0.14 * focus_pull);
            root = root.child(
                div()
                    .absolute()
                    .left(px(cx - halo_sz * 0.5))
                    .top(px(cy - halo_sz * 0.5))
                    .size(px(halo_sz))
                    .rounded_full()
                    .bg(rgba(0xFF0033, halo_a)),
            );
        }

        let mut logo_wrap = div()
            .id("splash-logo-wrap")
            .absolute()
            .left(px(cx - logo_px * 0.5))
            .top(px(cy - logo_px * 0.5))
            .size(px(logo_px))
            .rounded_full()
            .overflow_hidden()
            .opacity(logo_alpha)
            .child(img(SharedString::from("brand/logo.png")).size_full().object_fit(ObjectFit::Contain));

        // Angled specular glass reflection gliding across M and A (680ms - 1830ms)
        let sheen_p = (elapsed_ms - 680.0) / 1150.0;
        if (-0.25..=1.25).contains(&sheen_p) && dive_p < 0.75 {
            let sweep_w = logo_px * 0.34;
            let sweep_x = -sweep_w + sheen_p * (logo_px + sweep_w * 1.6);
            let sw_half = sweep_w * 0.5;
            logo_wrap = logo_wrap.child(
                div()
                    .absolute()
                    .left(px(sweep_x))
                    .top(px(0.0))
                    .w(px(sweep_w))
                    .h(px(logo_px))
                    .flex()
                    .child(div().w(px(sw_half)).h_full().bg(linear_gradient(
                        90.0,
                        linear_color_stop(rgba(0xFFFFFF, 0.0).into(), 0.0),
                        linear_color_stop(rgba(0xF5F5F7, 0.34 * logo_alpha).into(), 1.0),
                    )))
                    .child(div().w(px(sw_half)).h_full().bg(linear_gradient(
                        90.0,
                        linear_color_stop(rgba(0xF5F5F7, 0.34 * logo_alpha).into(), 0.0),
                        linear_color_stop(rgba(0xFF4D6D, 0.0).into(), 1.0),
                    ))),
            );
        }

        // Orbiting 360-degree specular highlight travelling around circular metallic bezel (520ms - 2340ms)
        if elapsed_ms >= 520.0 && dive_p < 0.85 {
            let rim_p = ((elapsed_ms - 520.0) / 1820.0).clamp(0.0, 1.2);
            let rim_alpha = ((elapsed_ms - 520.0) / 180.0).clamp(0.0, 1.0) * (1.0 - dive_curve * 1.4).clamp(0.0, 1.0);
            let angle_rad = (-135.0 + rim_p * 360.0) * (PI / 180.0);
            let bezel_r = logo_px * 0.478;
            let gx = logo_px * 0.5 + angle_rad.cos() * bezel_r;
            let gy = logo_px * 0.5 + angle_rad.sin() * bezel_r;
            let g_sz = 26.0 * ui_scale * logo_scale;
            let g_core = 9.0 * ui_scale * logo_scale;
            logo_wrap = logo_wrap
                .child(
                    div()
                        .absolute()
                        .left(px(gx - g_sz * 0.5))
                        .top(px(gy - g_sz * 0.5))
                        .size(px(g_sz))
                        .rounded_full()
                        .bg(rgba(0xFF4D6D, 0.45 * rim_alpha)),
                )
                .child(
                    div()
                        .absolute()
                        .left(px(gx - g_core * 0.5))
                        .top(px(gy - g_core * 0.5))
                        .size(px(g_core))
                        .rounded_full()
                        .bg(rgba(0xFFFFFF, 0.90 * rim_alpha)),
                );
        }

        root = root.child(logo_wrap);
    }

    // Phase 3 Editorial Luxury Typography ("M. ASSAHTOUT" + beam + "TV EDITION")
    if text_alpha > 0.01 {
        let dive_zoom = 1.0 + 1.8 * dive_curve;
        let title_font = 27.0 * ui_scale * dive_zoom;
        let title_gap = (4.2 + 6.8 * text_ease) * ui_scale * dive_zoom;
        let beam_max_w = 340.0 * ui_scale * dive_zoom;
        let beam_w = beam_max_w * beam_progress;
        let sub_font = 12.0 * ui_scale * dive_zoom;
        let sub_gap = (2.6 + 3.8 * text_ease) * ui_scale * dive_zoom;
        let top_y = cy + base_logo_px * 0.5 + 26.0 * ui_scale + text_slide_y;

        let mut col = div()
            .absolute()
            .left(px(0.0))
            .right(px(0.0))
            .top(px(top_y))
            .flex()
            .flex_col()
            .items_center()
            .child(div().opacity(text_alpha).child(tracked_text(
                "M. ASSAHTOUT",
                title_font,
                FontWeight::BOLD,
                rgba(0xF5F5F7, text_alpha).into(),
                title_gap,
            )));

        if beam_w > 2.0 {
            let half_b = beam_w * 0.5;
            col = col.child(
                div()
                    .mt(px(10.0 * ui_scale))
                    .w(px(beam_w))
                    .h(px(1.5 * ui_scale))
                    .opacity(text_alpha)
                    .flex()
                    .child(div().w(px(half_b)).h_full().bg(linear_gradient(
                        90.0,
                        linear_color_stop(rgba(0xFF0033, 0.0).into(), 0.0),
                        linear_color_stop(rgba(0xF5F5F7, 0.95).into(), 1.0),
                    )))
                    .child(div().w(px(half_b)).h_full().bg(linear_gradient(
                        90.0,
                        linear_color_stop(rgba(0xF5F5F7, 0.95).into(), 0.0),
                        linear_color_stop(rgba(0xFF0033, 0.0).into(), 1.0),
                    ))),
            );
        }

        if sub_alpha > 0.01 {
            col = col.child(div().mt(px(9.0 * ui_scale)).opacity(sub_alpha).child(tracked_text(
                "TV EDITION",
                sub_font,
                FontWeight::MEDIUM,
                rgba(0xA8A8B3, sub_alpha).into(),
                sub_gap,
            )));
        }

        root = root.child(col);
    }

    // Phase 4 (2400ms - 3200ms): 14 Translucent Crimson / Ruby / Silver Optical Spectrum Ribbons
    if elapsed_ms >= DIVE_START_MS {
        let ribbon_in = ((elapsed_ms - DIVE_START_MS) / 140.0).clamp(0.0, 1.0);
        let ribbon_out = 1.0 - ((dive_p - 0.68) / 0.32).clamp(0.0, 1.0);
        let global_a = ribbon_in * ribbon_out;
        if global_a > 0.01 {
            for r in &POOL.ribbons {
                let x = cx + (r.start_norm_x + r.spread_vel * dive_curve * 1.35) * vw;
                let w = r.width_px * ui_scale * (0.75 + 1.25 * dive_curve);
                if x + w * 0.5 >= 0.0 && x - w * 0.5 <= vw {
                    let a = (r.alpha * global_a).clamp(0.0, 1.0);
                    let (edge_hex, core_hex) = match r.kind {
                        0 => (0x991B1B, 0xFF0033),
                        1 => (0x7F1D1D, 0xDC2626),
                        _ => (0xA8A8B3, 0xF5F5F7),
                    };
                    let half_w = w * 0.5;
                    root = root.child(
                        div()
                            .absolute()
                            .left(px(x - half_w))
                            .top(px(0.0))
                            .w(px(w))
                            .h(px(vh))
                            .flex()
                            .child(div().w(px(half_w)).h_full().bg(linear_gradient(
                                90.0,
                                linear_color_stop(rgba(edge_hex, 0.0).into(), 0.0),
                                linear_color_stop(rgba(core_hex, a * 0.85).into(), 1.0),
                            )))
                            .child(div().w(px(half_w)).h_full().bg(linear_gradient(
                                90.0,
                                linear_color_stop(rgba(core_hex, a * 0.85).into(), 0.0),
                                linear_color_stop(rgba(edge_hex, 0.0).into(), 1.0),
                            ))),
                    );
                }
            }
        }
    }

    // Subtle skip hint during Phases 1-3
    if dive_p < 0.35 {
        let hint_a = (1.0 - dive_p * 3.0).clamp(0.0, 1.0) * 0.50;
        root = root.child(
            div()
                .absolute()
                .right(px(24.0))
                .bottom(px(18.0))
                .text_size(px(11.0))
                .text_color(rgba(0xA8A8B3, hint_a))
                .child("Esc / Space to skip"),
        );
    }

    root.into_any_element()
}

/// Starts a 60fps tick loop driving `AppView` until the 3.2s splash completes or is skipped.
pub fn spawn_splash_ticker(cx: &mut Context<super::app::AppView>) {
    cx.spawn(async move |this, cx| loop {
        cx.background_executor().timer(Duration::from_millis(16)).await;
        let keep_going = this
            .update(cx, |app, cx| {
                let done = match &app.splash {
                    Some(s) => s.finished(),
                    None => true,
                };
                if done {
                    app.splash = None;
                    cx.notify();
                    false
                } else {
                    cx.notify();
                    true
                }
            })
            .unwrap_or(false);
        if !keep_going {
            break;
        }
    })
    .detach();
}
