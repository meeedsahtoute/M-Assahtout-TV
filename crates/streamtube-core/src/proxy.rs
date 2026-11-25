//! Local streaming proxy for googlevideo URLs.
//!
//! YouTube refuses open-ended range requests on stream URLs (HTTP 403) and
//! throttles large single requests. mpv asks for whole files, so it talks to
//! this proxy instead, which serves mpv's (possibly open-ended) ranges by
//! fetching bounded chunks from YouTube, like yt-dlp's chunked downloader.
use parking_lot::Mutex;
use std::collections::HashMap;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::OnceLock;

const FIRST_CHUNK: u64 = 1 << 20; // small first chunk: fast start and seeks
const CHUNK: u64 = 8 << 20;

struct Entry {
    url: String,
    len: Option<u64>,
    mime: String,
}

struct Proxy {
    port: u16,
    /// Any local process can reach a loopback port, so every URL carries this
    /// secret first and requests without it are refused.
    token: String,
    streams: Mutex<HashMap<u64, Entry>>,
    next: Mutex<u64>,
}

fn proxy() -> Option<&'static Proxy> {
    static P: OnceLock<Option<Proxy>> = OnceLock::new();
    P.get_or_init(|| {
        let listener = TcpListener::bind("127.0.0.1:0").ok()?;
        let port = listener.local_addr().ok()?.port();
        std::thread::Builder::new()
            .name("stream-proxy".into())
            .spawn(move || {
                for conn in listener.incoming().flatten() {
                    std::thread::spawn(move || {
                        let _ = serve(conn);
                    });
                }
            })
            .ok()?;
        let token = {
            use rand::Rng;
            let mut rng = rand::rng();
            (0..24).map(|_| format!("{:02x}", rng.random::<u8>())).collect()
        };
        Some(Proxy { port, token, streams: Mutex::new(HashMap::new()), next: Mutex::new(0) })
    })
    .as_ref()
}

impl Proxy {
    /// Where every local URL starts: the port and the secret.
    fn base(&self) -> String {
        format!("http://127.0.0.1:{}/{}", self.port, self.token)
    }
}

/// The request path after the secret, or None when the secret is missing or wrong.
fn strip_token<'a>(path: &'a str, token: &str) -> Option<&'a str> {
    let rest = path.strip_prefix('/')?;
    let given = rest.get(..token.len())?;
    let same = given.bytes().zip(token.bytes()).fold(0u8, |acc, (a, b)| acc | (a ^ b)) == 0;
    let rest = &rest[token.len()..];
    (same && rest.starts_with('/')).then_some(rest)
}

/// Marks entries served as HLS playlists / plain pass-through instead of ranged media.
const HLS: &str = "#hls";
const PASS: &str = "#pass";

fn register(p: &Proxy, url: &str, len: Option<u64>, mime: &str) -> u64 {
    let id = {
        let mut n = p.next.lock();
        *n += 1;
        *n
    };
    let mut s = p.streams.lock();
    // keep the table bounded: old entries belong to videos no longer playing.
    // HLS registers one entry per segment, so the window is generous.
    if s.len() > 20_000 {
        let min_keep = id.saturating_sub(10_000);
        s.retain(|k, _| *k >= min_keep);
    }
    s.insert(id, Entry { url: url.to_string(), len, mime: mime.to_string() });
    id
}

/// Returns a local URL that serves `url` to mpv. Non-googlevideo URLs pass through.
pub fn local_url(url: &str, len: Option<u64>, mime: &str) -> String {
    if !url.contains("googlevideo.com") {
        return url.to_string();
    }
    let Some(p) = proxy() else { return url.to_string() };
    let id = register(p, url, len, mime);
    format!("{}/s/{id}", p.base())
}

/// Serves an HLS master playlist to mpv through the proxy. Every playlist and
/// segment is fetched by the app (so it uses the app's network settings, e.g.
/// IPv4 only, which the stream URLs are bound to), and variants taller than
/// `max_height` (0 = no limit) are left out so mpv plays the chosen quality.
pub fn local_hls(url: &str, max_height: u32, audio: Option<&str>) -> String {
    let Some(p) = proxy() else { return url.to_string() };
    let id = register(p, url, None, HLS);
    match audio {
        Some(a) => format!("{}/h/{id}?max={max_height}&audio={}", p.base(), crate::api::http::urlencode(a)),
        None => format!("{}/h/{id}?max={max_height}", p.base()),
    }
}

/// Serves a single HTTP(S) resource (such as a caption track) through the
/// authenticated local proxy so mpv uses the app's configured SOCKS/HTTP proxy.
pub fn local_pass(url: &str) -> String {
    if !url.starts_with("http://") && !url.starts_with("https://") {
        return url.to_string();
    }
    let Some(p) = proxy() else { return url.to_string() };
    let id = register(p, url, None, PASS);
    format!("{}/p/{id}", p.base())
}

pub fn hls_attr(line: &str, key: &str) -> Option<String> {
    // match KEY= at an attribute boundary (so AUDIO= doesn't hit "VIDEO-RANGE=..."-like names)
    let mut search = line;
    loop {
        let i = search.find(&format!("{key}="))?;
        let before = search[..i].chars().last();
        let rest = &search[i + key.len() + 1..];
        if matches!(before, Some(':') | Some(',')) {
            return Some(match rest.strip_prefix('"') {
                Some(q) => q.split('"').next()?.to_string(),
                None => rest.split(',').next()?.to_string(),
            });
        }
        search = rest;
    }
}

/// Reduces a master playlist to the one variant mpv should play (best fit for
/// `max_height` and the codec preference) plus its default audio rendition.
/// ffmpeg opens every playlist a master lists before starting, so serving the
/// full master (often 30+ playlists) delays the start by many seconds.
pub fn select_master(text: &str, max_height: u32, audio_name: Option<&str>) -> Option<String> {
    let lines: Vec<&str> = text.lines().map(str::trim).filter(|l| !l.is_empty()).collect();
    let mut header = Vec::new();
    let mut medias = Vec::new();
    let mut variants: Vec<(&str, &str)> = Vec::new();
    let mut i = 0;
    while i < lines.len() {
        let l = lines[i];
        if l.starts_with("#EXT-X-STREAM-INF") {
            if let Some(u) = lines.get(i + 1).filter(|u| !u.starts_with('#')) {
                variants.push((l, u));
                i += 2;
                continue;
            }
        } else if l.starts_with("#EXT-X-MEDIA") {
            medias.push(l);
        } else if l.starts_with("#EXT-X-I-FRAME-STREAM-INF") || !l.starts_with('#') {
            // i-frame playlists aren't needed for playback
        } else {
            header.push(l);
        }
        i += 1;
    }
    let class = |l: &str| -> u32 {
        hls_attr(l, "RESOLUTION")
            .and_then(|r| r.split_once('x').and_then(|(w, h)| Some(crate::api::streams::quality_class(w.parse().ok()?, h.parse().ok()?))))
            .unwrap_or(0)
    };
    let pref = crate::store::settings_snapshot().codec_pref.clone();
    let codec_rank = |l: &str| -> u32 {
        let c = hls_attr(l, "CODECS").unwrap_or_default().to_lowercase();
        let kind = if c.contains("av01") { "av1" } else if c.contains("vp09") || c.contains("vp9") { "vp9" } else { "h264" };
        if kind == pref { 3 } else if kind == "h264" { 2 } else { 1 }
    };
    let bw = |l: &str| hls_attr(l, "BANDWIDTH").and_then(|b| b.parse::<u64>().ok()).unwrap_or(0);
    let fits: Vec<&(&str, &str)> = variants.iter().filter(|(l, _)| max_height == 0 || class(l) <= max_height).collect();
    let chosen = if fits.is_empty() {
        variants.iter().min_by_key(|(l, _)| (class(l), bw(l)))
    } else {
        fits.into_iter().max_by_key(|(l, _)| (class(l), !crate::api::streams::is_hdr_codecs(&hls_attr(l, "CODECS").unwrap_or_default()), codec_rank(l), bw(l)))
    };
    let (inf, uri) = chosen?;
    let mut out = String::new();
    for h in header {
        out.push_str(h);
        out.push('\n');
    }
    if let Some(group) = hls_attr(inf, "AUDIO") {
        let in_group: Vec<&&str> = medias
            .iter()
            .filter(|m| hls_attr(m, "TYPE").as_deref() == Some("AUDIO") && hls_attr(m, "GROUP-ID").as_deref() == Some(group.as_str()))
            .collect();
        // the requested track, else the original, else the default, else the first
        let name_of = |m: &str| crate::api::streams::hls_media_name(m).unwrap_or_default();
        let audio = audio_name
            .and_then(|want| in_group.iter().find(|m| name_of(m) == want))
            .or_else(|| in_group.iter().find(|m| m.contains("acont=original") || m.contains("acont%3Doriginal") || crate::api::streams::is_original_audio_label(&name_of(m))))
            .or_else(|| in_group.iter().find(|m| hls_attr(m, "DEFAULT").as_deref() == Some("YES")))
            .or_else(|| in_group.first())
            .copied();
        if let Some(m) = audio {
            out.push_str(m);
            out.push('\n');
        }
    }
    out.push_str(inf);
    out.push('\n');
    out.push_str(uri);
    out.push('\n');
    Some(out)
}

/// Rewrites one playlist so all its URLs point back at the proxy. Whether a
/// URI is another playlist or media is decided by the tag it belongs to.
fn rewrite_playlist(p: &Proxy, base: &str, text: &str, max_height: u32) -> String {
    let local = |u: &str, playlist: bool| -> String {
        let Some(url) = playlist_url(base, u) else {
            return format!("{}/invalid", p.base());
        };
        if playlist {
            format!("{}/h/{}?max=0", p.base(), register(p, &url, None, HLS))
        } else {
            format!("{}/p/{}", p.base(), register(p, &url, None, PASS))
        }
    };
    let lines: Vec<&str> = text.lines().collect();
    // which variants fit the quality limit (if none do, keep them all)
    let height_of = |l: &str| -> Option<u32> {
        let r = l.split("RESOLUTION=").nth(1)?.split(',').next()?;
        let (w, h) = r.split_once('x')?;
        Some(crate::api::streams::quality_class(w.parse().ok()?, h.parse().ok()?))
    };
    let any_fits = max_height == 0
        || lines.iter().any(|l| l.starts_with("#EXT-X-STREAM-INF") && height_of(l).map(|h| h <= max_height).unwrap_or(true));
    let mut out = String::with_capacity(text.len());
    let mut skip_next_uri = false;
    let mut next_is_playlist = false;
    for line in lines {
        let t = line.trim();
        if t.is_empty() {
            continue;
        }
        if t.starts_with("#EXT-X-STREAM-INF") {
            if any_fits && max_height > 0 && height_of(t).map(|h| h > max_height).unwrap_or(false) {
                skip_next_uri = true;
                continue;
            }
            next_is_playlist = true;
        }
        if !t.starts_with('#') {
            if skip_next_uri {
                skip_next_uri = false;
                continue;
            }
            out.push_str(&local(t, next_is_playlist));
            next_is_playlist = false;
            out.push('\n');
            continue;
        }
        // tags carrying URIs: renditions / i-frame lists are playlists, EXT-X-MAP and keys are media
        if let Some(i) = t.find("URI=\"") {
            let rest = &t[i + 5..];
            if let Some(j) = rest.find('"') {
                let u = &rest[..j];
                let playlist = t.starts_with("#EXT-X-MEDIA") || t.starts_with("#EXT-X-I-FRAME-STREAM-INF");
                out.push_str(&t[..i + 5]);
                out.push_str(&local(u, playlist));
                out.push_str(&rest[j..]);
                out.push('\n');
                continue;
            }
        }
        out.push_str(t);
        out.push('\n');
    }
    out
}

pub fn playlist_url(base: &str, uri: &str) -> Option<String> {
    if uri.starts_with("https://") || uri.starts_with("http://") {
        return Some(uri.to_owned());
    }
    let resolved = url::Url::parse(base).ok()?.join(uri).ok()?;
    matches!(resolved.scheme(), "https" | "http").then(|| resolved.into())
}

fn serve_playlist(conn: &mut TcpStream, p: &Proxy, url: &str, max_height: u32, audio: Option<&str>) -> std::io::Result<()> {
    let cached = crate::shorts_preload::cached(url);
    let mut text = cached.and_then(|media| std::str::from_utf8(&media.bytes).ok().map(str::to_owned));
    for attempt in 0..3u64 {
        if text.is_some() {
            break;
        }
        if let Ok(mut r) = agent().get(url).header("User-Agent", crate::api::http::BROWSER_UA).call() {
            if r.status().as_u16() < 400 {
                text = r.body_mut().with_config().limit(16 << 20).read_to_string().ok();
                break;
            }
        }
        std::thread::sleep(std::time::Duration::from_millis(300 * (attempt + 1)));
    }
    let Some(text) = text else {
        conn.write_all(b"HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")?;
        return Ok(());
    };
    let selected = text.contains("#EXT-X-STREAM-INF").then(|| select_master(&text, max_height, audio)).flatten();
    let body = rewrite_playlist(p, url, selected.as_deref().unwrap_or(&text), max_height);
    let head = format!(
        "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        body.len()
    );
    conn.write_all(head.as_bytes())?;
    conn.write_all(body.as_bytes())?;
    conn.flush()
}

/// Plain pass-through (HLS segments): forwards the request and streams the reply.
fn serve_pass(conn: &mut TcpStream, url: &str, range: Option<(u64, Option<u64>)>, head_only: bool) -> std::io::Result<()> {
    if let Some(media) = crate::shorts_preload::cached(url) {
        let total = media.total;
        let (start, end) = range.unwrap_or((0, None));
        let end = end.unwrap_or(total - 1).min(total - 1);
        if start <= end && end < media.bytes.len() as u64 {
            if range.is_some() {
                write!(conn, "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes {start}-{end}/{total}\r\n")?;
            } else {
                conn.write_all(b"HTTP/1.1 200 OK\r\n")?;
            }
            write!(conn, "Content-Type: {}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n", media.mime, end - start + 1)?;
            if !head_only {
                conn.write_all(&media.bytes[start as usize..=end as usize])?;
                media.record_served(end - start + 1);
            }
            return conn.flush();
        }
    }
    let mut resp = None;
    for attempt in 0..3u64 {
        let mut req = agent().get(url).header("User-Agent", crate::api::http::BROWSER_UA);
        let rg;
        if let Some((a, b)) = range {
            rg = match b {
                Some(b) => format!("bytes={a}-{b}"),
                None => format!("bytes={a}-"),
            };
            req = req.header("Range", &rg);
        }
        match req.call() {
            Ok(r) if r.status().as_u16() < 400 => {
                resp = Some(r);
                break;
            }
            _ => std::thread::sleep(std::time::Duration::from_millis(300 * (attempt + 1))),
        }
    }
    let Some(r) = resp else {
        conn.write_all(b"HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")?;
        return Ok(());
    };
    let status = r.status().as_u16();
    let hdr = |k: &str| r.headers().get(k).and_then(|v| v.to_str().ok()).map(str::to_string);
    let mut head = format!(
        "HTTP/1.1 {status} {}\r\nContent-Type: {}\r\nConnection: close\r\n",
        if status == 206 { "Partial Content" } else { "OK" },
        hdr("content-type").unwrap_or_else(|| "application/octet-stream".into())
    );
    if let Some(l) = hdr("content-length") {
        head.push_str(&format!("Content-Length: {l}\r\n"));
    }
    if let Some(cr) = hdr("content-range") {
        head.push_str(&format!("Content-Range: {cr}\r\n"));
    }
    head.push_str("\r\n");
    conn.write_all(head.as_bytes())?;
    if head_only {
        return Ok(());
    }
    let mut body = r.into_body().into_reader();
    std::io::copy(&mut body, conn)?;
    conn.flush()
}

pub fn agent() -> ureq::Agent {
    static AGENT: crate::api::http::NetworkAgent = crate::api::http::NetworkAgent::new();
    AGENT.get(&crate::store::settings_snapshot(), |builder| {
        builder
            .http_status_as_error(false)
            .timeout_connect(Some(std::time::Duration::from_secs(10)))
            .timeout_recv_body(Some(std::time::Duration::from_secs(30)))
    })
}

/// Learns the total size with a 1-byte request when the API didn't provide it.
fn probe_len(url: &str) -> Option<u64> {
    let r = agent().get(url).header("User-Agent", crate::api::http::BROWSER_UA).header("Range", "bytes=0-0").call().ok()?;
    let cr = r.headers().get("content-range")?.to_str().ok()?.to_string();
    cr.rsplit('/').next()?.trim().parse().ok()
}

fn serve(mut conn: TcpStream) -> std::io::Result<()> {
    conn.set_nodelay(true).ok();
    let mut reader = BufReader::new(conn.try_clone()?);
    let mut request_line = String::new();
    reader.read_line(&mut request_line)?;
    let mut range: Option<(u64, Option<u64>)> = None;
    loop {
        let mut line = String::new();
        if reader.read_line(&mut line)? == 0 || line == "\r\n" || line == "\n" {
            break;
        }
        let lower = line.to_ascii_lowercase();
        if let Some(v) = lower.strip_prefix("range:") {
            if let Some(spec) = v.trim().strip_prefix("bytes=") {
                let (a, b) = spec.split_once('-').unwrap_or((spec, ""));
                if let Ok(a) = a.trim().parse::<u64>() {
                    range = Some((a, b.trim().parse::<u64>().ok()));
                }
            }
        }
    }
    let mut parts = request_line.split_whitespace();
    let method = parts.next().unwrap_or("");
    let path = parts.next().unwrap_or("");
    let Some(p) = proxy() else { return Ok(()) };
    let Some(path) = strip_token(path, &p.token) else {
        return conn.write_all(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
    };
    if crate::perf::on() {
        eprintln!("[proxy] {method} {path} range={range:?}");
    }
    let lookup =|rest: &str| -> Option<String> {
        let id: u64 = rest.split('?').next()?.parse().ok()?;
        p.streams.lock().get(&id).map(|e| e.url.clone())
    };
    if let Some(rest) = path.strip_prefix("/h/") {
        let query = rest.split_once('?').map(|q| q.1).unwrap_or("");
        let param = |k: &str| query.split('&').find_map(|kv| kv.strip_prefix(k).and_then(|v| v.strip_prefix('=')));
        let max = param("max").and_then(|m| m.parse().ok()).unwrap_or(0);
        let audio = param("audio").map(crate::api::http::urldecode);
        return match lookup(rest) {
            Some(url) => serve_playlist(&mut conn, p, &url, max, audio.as_deref()),
            None => conn.write_all(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"),
        };
    }
    if let Some(rest) = path.strip_prefix("/p/") {
        return match lookup(rest) {
            Some(url) => serve_pass(&mut conn, &url, range, method == "HEAD"),
            None => conn.write_all(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"),
        };
    }
    let id: Option<u64> = path.strip_prefix("/s/").and_then(|s| s.parse().ok());
    let entry = id.and_then(|id| p.streams.lock().get(&id).map(|e| (e.url.clone(), e.len, e.mime.clone())));
    let Some((url, mut len, mime)) = entry else {
        conn.write_all(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")?;
        return Ok(());
    };
    let cached = crate::shorts_preload::cached(&url);
    if len.is_none() {
        len = cached.as_ref().map(|m| m.total).or_else(|| probe_len(&url));
        if let (Some(id), Some(l)) = (id, len) {
            if let Some(e) = p.streams.lock().get_mut(&id) {
                e.len = Some(l);
            }
        }
    }
    let Some(total) = len else {
        conn.write_all(b"HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")?;
        return Ok(());
    };
    let (start, end) = match range {
        Some((a, b)) => (a.min(total), b.unwrap_or(total - 1).min(total - 1)),
        None => (0, total.saturating_sub(1)),
    };
    if start >= total {
        let h = format!("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */{total}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        conn.write_all(h.as_bytes())?;
        return Ok(());
    }
    let body_len = end - start + 1;
    let head = if range.is_some() {
        format!(
            "HTTP/1.1 206 Partial Content\r\nContent-Type: {mime}\r\nAccept-Ranges: bytes\r\nContent-Range: bytes {start}-{end}/{total}\r\nContent-Length: {body_len}\r\nConnection: close\r\n\r\n"
        )
    } else {
        format!("HTTP/1.1 200 OK\r\nContent-Type: {mime}\r\nAccept-Ranges: bytes\r\nContent-Length: {body_len}\r\nConnection: close\r\n\r\n")
    };
    conn.write_all(head.as_bytes())?;
    if method == "HEAD" {
        return Ok(());
    }
    let mut pos = start;
    if let Some(media) = cached.filter(|media| media.total == total && start < media.bytes.len() as u64) {
        let stop = (end + 1).min(media.bytes.len() as u64);
        conn.write_all(&media.bytes[start as usize..stop as usize])?;
        media.record_served(stop - start);
        pos = stop;
    }
    let mut first = true;
    let mut stalls = 0;
    let mut buf = vec![0u8; 64 * 1024];
    while pos <= end {
        let size = if first { FIRST_CHUNK } else { CHUNK };
        first = false;
        let chunk_end = (pos + size - 1).min(end);
        let mut attempt = 0;
        loop {
            let resp = agent()
                .get(&url)
                .header("User-Agent", crate::api::http::BROWSER_UA)
                .header("Range", &format!("bytes={pos}-{chunk_end}"))
                .call();
            if crate::perf::on() {
                match &resp {
                    Ok(r) => eprintln!("[proxy] {pos}-{chunk_end} -> {}", r.status()),
                    Err(e) => eprintln!("[proxy] {pos}-{chunk_end} -> error {e}"),
                }
            }
            match resp {
                Ok(r) if r.status().as_u16() == 206 || r.status().as_u16() == 200 => {
                    let mut body = r.into_body().into_reader();
                    loop {
                        let n = match body.read(&mut buf) {
                            Ok(n) => n,
                            Err(e) => {
                                if crate::perf::on() {
                                    eprintln!("[proxy] upstream read error at {pos}: {e}");
                                }
                                break;
                            }
                        };
                        if n == 0 {
                            break;
                        }
                        // a write error means mpv closed the connection (e.g. it seeked)
                        if let Err(e) = conn.write_all(&buf[..n]) {
                            if crate::perf::on() {
                                eprintln!("[proxy] client closed at {pos}: {e}");
                            }
                            return Err(e);
                        }
                        pos += n as u64;
                    }
                    break;
                }
                _ if attempt < 3 => {
                    attempt += 1;
                    std::thread::sleep(std::time::Duration::from_millis(300 * attempt));
                }
                _ => return Ok(()),
            }
        }
        if pos <= chunk_end {
            // upstream ended early; retry from where we are (but don't spin forever)
            stalls += 1;
            if stalls > 5 {
                return Ok(());
            }
        }
    }
    conn.flush()
}

#[cfg(test)]
mod token_tests {
    #[test]
    fn requests_need_the_secret() {
        let t = "0123abcd";
        assert_eq!(super::strip_token("/0123abcd/s/5", t), Some("/s/5"));
        assert_eq!(super::strip_token("/s/5", t), None);
        assert_eq!(super::strip_token("/0123abce/s/5", t), None);
        assert_eq!(super::strip_token("/0123abcds/5", t), None);
        assert_eq!(super::strip_token("/01", t), None);
    }

    #[test]
    fn local_pass_proxies_only_http_urls_with_secret() {
        assert_eq!(super::local_pass("/tmp/local.vtt"), "/tmp/local.vtt");
        let proxied = super::local_pass("https://www.youtube.com/api/timedtext?v=abc");
        let rest = proxied.strip_prefix("http://127.0.0.1:").expect("loopback url");
        let path = rest.split_once('/').expect("path").1;
        let (token, tail) = path.split_once('/').expect("token prefix");
        assert_eq!(token.len(), 48, "48-hex-char session secret");
        assert!(tail.starts_with("p/"), "pass route: {tail}");
    }
}

#[cfg(test)]
mod live_proxy_tests {
    use std::io::Read;

    fn get(url: &str) -> (u16, usize) {
        let agent: ureq::Agent = ureq::Agent::config_builder().http_status_as_error(false).build().into();
        let mut r = agent.get(url).header("Range", "bytes=0-65535").call().unwrap();
        let status = r.status().as_u16();
        let mut buf = Vec::new();
        let _ = r.body_mut().as_reader().take(65536).read_to_end(&mut buf);
        (status, buf.len())
    }

    /// A real video through the proxy: served with the secret, refused without it.
    #[test]
    #[ignore = "network"]
    fn serves_real_streams_only_with_the_secret() {
        for id in ["dQw4w9WgXcQ", "bvMPTXaLq5k"] {
            let info = crate::api::streams::get_streams(id).unwrap();
            let url = if let Some(hls) = &info.hls_url {
                super::local_hls(hls, 720, None)
            } else {
                let f = info.video.first().unwrap();
                super::local_url(&f.url, f.content_length, &f.mime)
            };
            assert!(url.starts_with("http://127.0.0.1:"), "{url}");
            let (status, n) = get(&url);
            eprintln!("{id}: {} via {} -> {status}, {n} bytes", info.source, &url[..url.len().min(90)]);
            assert!((200..300).contains(&status) && n > 0, "{id}: {status}");
            // the same path without the secret
            let p = super::proxy().unwrap();
            let bare = url.replacen(&format!("/{}", p.token), "", 1);
            assert_eq!(get(&bare).0, 403);
        }
    }
}
