//! Timed-text transcript indexer, Smart Topic Chips extractor, and instant in-video search.
//! Fetches YouTube `captionTracks` (JSON3 / WebVTT / XML) off the UI thread with bounded memory.

use crate::api::http;
use crate::api::types::CaptionTrack;
use anyhow::{Context, Result, anyhow};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::{HashMap, HashSet};
use url::Url;

const MAX_SEGMENTS: usize = 12_000;
const MAX_CHIPS: usize = 8;

/// Normalized caption cue stored in memory while a video is open.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct TranscriptSegment {
    pub start_ms: u64,
    pub duration_ms: u64,
    pub text: String,
}

/// Search match returned to desktop / Android TV search overlays.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct TranscriptSearchHit {
    pub start_ms: u64,
    pub duration_ms: u64,
    pub text: String,
    pub score: i32,
}

/// Loaded caption index plus extracted Smart Topic Chips.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct TranscriptIndex {
    pub language: String,
    pub is_auto: bool,
    pub segments: Vec<TranscriptSegment>,
    pub chips: Vec<String>,
}

/// Selects the highest-priority caption track (manual > ASR, preferred lang > EN/AR/FR).
pub fn pick_best_track<'a>(
    tracks: &'a [CaptionTrack],
    preferred_lang: &str,
) -> Option<&'a CaptionTrack> {
    if tracks.is_empty() {
        return None;
    }
    let pref = preferred_lang.trim().to_ascii_lowercase();
    tracks.iter().max_by_key(|t| {
        let lang = t.lang.to_ascii_lowercase();
        let manual_score = if t.auto { 0 } else { 100 };
        let lang_score = if !pref.is_empty() && lang == pref {
            50
        } else if !pref.is_empty() && lang.starts_with(pref.split('-').next().unwrap_or(&pref)) {
            40
        } else if lang.starts_with("en") {
            30
        } else if lang.starts_with("ar") || lang.starts_with("fr") {
            20
        } else {
            10
        };
        manual_score + lang_score
    })
}

/// Fetches and indexes captions for the selected track into segments + Smart Topic Chips.
pub fn fetch_transcript(
    tracks: &[CaptionTrack],
    preferred_lang: &str,
) -> Result<TranscriptIndex> {
    let track = pick_best_track(tracks, preferred_lang)
        .ok_or_else(|| anyhow!("No captions available for this video"))?;

    let mut segments = Vec::new();
    let json3_url = rewrite_fmt(&track.url, "json3");
    if let Ok(raw) = http::get_text(&json3_url) {
        segments = parse_json3(&raw);
    }
    if segments.is_empty() {
        let body = http::get_text(&track.url).context("fetch timedtext")?;
        segments = if body.trim_start().starts_with('{') {
            parse_json3(&body)
        } else if body.trim_start().starts_with('<') {
            parse_xml(&body)
        } else {
            parse_vtt(&body)
        };
    }

    if segments.len() > MAX_SEGMENTS {
        segments.truncate(MAX_SEGMENTS);
    }

    let chips = extract_topic_chips(&segments, MAX_CHIPS);
    Ok(TranscriptIndex {
        language: if track.name.trim().is_empty() {
            track.lang.clone()
        } else {
            track.name.clone()
        },
        is_auto: track.auto,
        segments,
        chips,
    })
}

fn rewrite_fmt(raw_url: &str, fmt: &str) -> String {
    let Ok(mut parsed) = Url::parse(raw_url) else {
        return raw_url.to_string();
    };
    let pairs: Vec<(String, String)> = parsed
        .query_pairs()
        .filter(|(k, _)| k != "fmt")
        .map(|(k, v)| (k.into_owned(), v.into_owned()))
        .collect();
    {
        let mut q = parsed.query_pairs_mut();
        q.clear();
        for (k, v) in pairs {
            q.append_pair(&k, &v);
        }
        q.append_pair("fmt", fmt);
    }
    parsed.into()
}

/// Parses YouTube `fmt=json3` timedtext JSON into normalized caption segments.
pub fn parse_json3(raw: &str) -> Vec<TranscriptSegment> {
    let Ok(v) = serde_json::from_str::<Value>(raw) else {
        return Vec::new();
    };
    let Some(events) = v.get("events").and_then(Value::as_array) else {
        return Vec::new();
    };
    let mut out = Vec::with_capacity(events.len().min(2048));
    for ev in events {
        let Some(start_ms) = ev.get("tStartMs").and_then(Value::as_u64) else {
            continue;
        };
        let duration_ms = ev.get("dDurationMs").and_then(Value::as_u64).unwrap_or(2_500);
        let Some(segs) = ev.get("segs").and_then(Value::as_array) else {
            continue;
        };
        let mut combined = String::new();
        for seg in segs {
            if let Some(u) = seg.get("utf8").and_then(Value::as_str) {
                combined.push_str(u);
            }
        }
        let cleaned = clean_caption_text(&combined);
        if !cleaned.is_empty() {
            out.push(TranscriptSegment {
                start_ms,
                duration_ms,
                text: cleaned,
            });
        }
    }
    out
}

/// Parses WebVTT captions cleanly into `TranscriptSegment` items.
pub fn parse_vtt(raw: &str) -> Vec<TranscriptSegment> {
    let mut out = Vec::new();
    let mut cur_start: Option<u64> = None;
    let mut cur_end: u64 = 0;
    let mut buf = String::new();

    for line in raw.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            if let Some(s) = cur_start.take() {
                let text = clean_caption_text(&buf);
                if !text.is_empty() {
                    out.push(TranscriptSegment {
                        start_ms: s,
                        duration_ms: cur_end.saturating_sub(s).max(1_000),
                        text,
                    });
                }
            }
            buf.clear();
            continue;
        }
        if let Some((lhs, rhs)) = trimmed.split_once("-->") {
            if let (Some(s), Some(e)) = (parse_timestamp_ms(lhs), parse_timestamp_ms(rhs)) {
                cur_start = Some(s);
                cur_end = e;
                buf.clear();
            }
            continue;
        }
        if cur_start.is_some() {
            if !buf.is_empty() {
                buf.push(' ');
            }
            buf.push_str(trimmed);
        }
    }
    if let Some(s) = cur_start {
        let text = clean_caption_text(&buf);
        if !text.is_empty() {
            out.push(TranscriptSegment {
                start_ms: s,
                duration_ms: cur_end.saturating_sub(s).max(1_000),
                text,
            });
        }
    }
    out
}

/// Parses legacy YouTube XML `<text start="..." dur="...">...</text>` captions.
pub fn parse_xml(raw: &str) -> Vec<TranscriptSegment> {
    let mut out = Vec::new();
    let mut rest = raw;
    while let Some(tag_start) = rest.find("<text ") {
        let after_tag = &rest[tag_start..];
        let Some(gt) = after_tag.find('>') else {
            break;
        };
        let attrs = &after_tag[..gt];
        let body_and_tail = &after_tag[gt + 1..];
        let Some(close) = body_and_tail.find("</text>") else {
            break;
        };
        let body = &body_and_tail[..close];
        rest = &body_and_tail[close + 7..];

        let start_s = extract_attr(attrs, "start")
            .and_then(|s| s.parse::<f64>().ok())
            .unwrap_or(-1.0);
        if start_s < 0.0 {
            continue;
        }
        let dur_s = extract_attr(attrs, "dur")
            .and_then(|s| s.parse::<f64>().ok())
            .unwrap_or(2.5);
        let text = clean_caption_text(body);
        if !text.is_empty() {
            out.push(TranscriptSegment {
                start_ms: (start_s * 1000.0).round() as u64,
                duration_ms: (dur_s * 1000.0).round() as u64,
                text,
            });
        }
    }
    out
}

fn extract_attr<'a>(attrs: &'a str, key: &str) -> Option<&'a str> {
    let needle = format!("{key}=\"");
    let idx = attrs.find(&needle)?;
    let val = &attrs[idx + needle.len()..];
    val.find('"').map(|end| &val[..end])
}

fn parse_timestamp_ms(s: &str) -> Option<u64> {
    let token = s.split_whitespace().next()?;
    let (hms, ms_part) = token.split_once('.').or_else(|| token.split_once(','))?;
    let ms: u64 = ms_part.chars().take(3).collect::<String>().parse().ok()?;
    let parts: Vec<u64> = hms.split(':').filter_map(|p| p.parse::<u64>().ok()).collect();
    let secs = match parts.as_slice() {
        [m, s] => m * 60 + s,
        [h, m, s] => h * 3600 + m * 60 + s,
        _ => return None,
    };
    Some(secs * 1000 + ms)
}

fn clean_caption_text(input: &str) -> String {
    let mut no_tags = String::with_capacity(input.len());
    let mut in_tag = false;
    for ch in input.chars() {
        match ch {
            '<' => in_tag = true,
            '>' if in_tag => in_tag = false,
            _ if !in_tag => no_tags.push(ch),
            _ => {}
        }
    }
    let decoded = no_tags
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace('\n', " ")
        .replace('\r', " ");

    let normalized: String = decoded.split_whitespace().collect::<Vec<_>>().join(" ");
    let trimmed = normalized.trim();
    if trimmed.is_empty()
        || (trimmed.starts_with('[') && trimmed.ends_with(']') && trimmed.len() <= 24)
    {
        return String::new();
    }
    trimmed.to_string()
}

/// Extracts top meaningful topic chips (single terms / proper nouns), filtering EN/FR/AR stopwords.
pub fn extract_topic_chips(segments: &[TranscriptSegment], max_chips: usize) -> Vec<String> {
    let mut freq: HashMap<String, (usize, String, bool)> = HashMap::new();
    for seg in segments {
        let mut seen_in_seg: HashSet<String> = HashSet::new();
        for raw_tok in seg
            .text
            .split(|c: char| !c.is_alphanumeric() && c != '\'' && c != '-')
        {
            let tok = raw_tok.trim_matches(|c: char| c == '\'' || c == '-');
            if tok.chars().count() < 3 || tok.chars().all(|c| c.is_ascii_digit()) {
                continue;
            }
            let lower = tok.to_lowercase();
            if is_stopword(&lower) || !seen_in_seg.insert(lower.clone()) {
                continue;
            }
            let is_proper = tok.chars().next().is_some_and(|c| c.is_uppercase());
            let entry = freq
                .entry(lower)
                .or_insert_with(|| (0, tok.to_string(), is_proper));
            entry.0 += 1;
            if is_proper && !entry.2 {
                entry.1 = tok.to_string();
                entry.2 = true;
            }
        }
    }

    let mut ranked: Vec<(String, i64, String)> = freq
        .into_iter()
        .filter(|(_, (count, _, _))| *count >= 2)
        .map(|(key, (count, display, is_proper))| {
            let bonus = if is_proper { 2 } else { 0 } + (display.chars().count().min(8) as i64 / 3);
            (key, (count as i64) * 3 + bonus, display)
        })
        .collect();

    // Fall back gracefully on very short videos with few repeated tokens.
    if ranked.is_empty() {
        return Vec::new();
    }

    ranked.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(&b.0)));

    let mut chips = Vec::with_capacity(max_chips);
    for (_, _, display) in ranked {
        let dl = display.to_lowercase();
        if chips.iter().any(|existing: &String| {
            let el = existing.to_lowercase();
            el == dl || (dl.len() >= 4 && el.starts_with(&dl))
        }) {
            continue;
        }
        chips.push(format_chip_label(&display));
        if chips.len() >= max_chips {
            break;
        }
    }
    chips
}

fn format_chip_label(tok: &str) -> String {
    let mut chars = tok.chars();
    let Some(first) = chars.next() else {
        return String::new();
    };
    if first.is_ascii_lowercase() {
        let mut s = String::with_capacity(tok.len());
        for c in first.to_uppercase() {
            s.push(c);
        }
        s.push_str(chars.as_str());
        s
    } else {
        tok.to_string()
    }
}

/// Fast substring + multi-term + adjacent-cue search returning chronological matches.
pub fn search_segments(
    segments: &[TranscriptSegment],
    query: &str,
    max_hits: usize,
) -> Vec<TranscriptSearchHit> {
    let q = query.trim().to_lowercase();
    if q.is_empty() || segments.is_empty() {
        return Vec::new();
    }
    let tokens: Vec<&str> = q.split_whitespace().filter(|t| !t.is_empty()).collect();
    if tokens.is_empty() {
        return Vec::new();
    }

    let mut scored: Vec<(usize, TranscriptSearchHit)> = Vec::new();
    for (idx, seg) in segments.iter().enumerate() {
        let text_l = seg.text.to_lowercase();
        let next_l = segments
            .get(idx + 1)
            .map(|n| format!("{text_l} {}", n.text.to_lowercase()));

        let score = if text_l.contains(&q) {
            100
        } else if tokens.len() > 1 && tokens.iter().all(|t| text_l.contains(t)) {
            75
        } else if next_l.as_ref().is_some_and(|comb| comb.contains(&q)) {
            60
        } else {
            0
        };

        if score > 0 {
            let snippet = build_snippet(segments, idx);
            scored.push((
                idx,
                TranscriptSearchHit {
                    start_ms: seg.start_ms,
                    duration_ms: seg.duration_ms,
                    text: snippet,
                    score,
                },
            ));
        }
    }

    // Light fuzzy fallback when query >= 4 chars and exact/token search yielded few results.
    if scored.len() < 4 && q.chars().count() >= 4 {
        let already: HashSet<usize> = scored.iter().map(|(i, _)| *i).collect();
        for (idx, seg) in segments.iter().enumerate() {
            if already.contains(&idx) {
                continue;
            }
            let text_l = seg.text.to_lowercase();
            let fuzzy_hit = text_l
                .split_whitespace()
                .any(|w| w.chars().count() >= 4 && edit_distance_at_most_one(w, &q));
            if fuzzy_hit {
                scored.push((
                    idx,
                    TranscriptSearchHit {
                        start_ms: seg.start_ms,
                        duration_ms: seg.duration_ms,
                        text: build_snippet(segments, idx),
                        score: 30,
                    },
                ));
            }
        }
    }

    scored.sort_by(|a, b| b.1.score.cmp(&a.1.score).then_with(|| a.0.cmp(&b.0)));
    scored.truncate(max_hits);
    // Present selected top hits chronologically so jumping feels natural.
    scored.sort_by_key(|(_, h)| h.start_ms);
    scored.into_iter().map(|(_, h)| h).collect()
}

fn build_snippet(segments: &[TranscriptSegment], idx: usize) -> String {
    let cur = &segments[idx].text;
    if cur.chars().count() < 55 {
        if let Some(next) = segments.get(idx + 1) {
            if next.start_ms <= segments[idx].start_ms + segments[idx].duration_ms + 2_000 {
                return format!("{cur} {}", next.text);
            }
        }
    }
    cur.clone()
}

fn edit_distance_at_most_one(a: &str, b: &str) -> bool {
    let ac: Vec<char> = a.chars().collect();
    let bc: Vec<char> = b.chars().collect();
    if ac.len().abs_diff(bc.len()) > 1 {
        return false;
    }
    let (mut i, mut j, mut edits) = (0, 0, 0);
    while i < ac.len() && j < bc.len() {
        if ac[i] == bc[j] {
            i += 1;
            j += 1;
        } else {
            edits += 1;
            if edits > 1 {
                return false;
            }
            if ac.len() > bc.len() {
                i += 1;
            } else if bc.len() > ac.len() {
                j += 1;
            } else {
                i += 1;
                j += 1;
            }
        }
    }
    if i < ac.len() || j < bc.len() {
        edits += 1;
    }
    edits <= 1
}

fn is_stopword(w: &str) -> bool {
    matches!(
        w,
        // English
        "the" | "and" | "for" | "that" | "with" | "this" | "you" | "are" | "was" | "were"
            | "have" | "has" | "had" | "not" | "but" | "can" | "will" | "just" | "like"
            | "what" | "when" | "where" | "which" | "who" | "how" | "why" | "all" | "from"
            | "they" | "them" | "their" | "there" | "here" | "then" | "than" | "into" | "out"
            | "about" | "your" | "our" | "his" | "her" | "its" | "one" | "two" | "more"
            | "very" | "much" | "really" | "actually" | "gonna" | "going" | "want" | "know"
            | "think" | "right" | "okay" | "yeah" | "well" | "also" | "even" | "because"
            | "some" | "could" | "would" | "should" | "been" | "being" | "does" | "did"
            | "doing" | "don't" | "didn't" | "it's" | "that's" | "you're" | "we're"
            | "they're" | "i'm" | "i've" | "can't" | "won't" | "there's" | "get" | "got"
            | "make" | "made" | "take" | "look" | "see" | "now" | "way" | "thing" | "things"
            | "music" | "applause"
            // French
            | "les" | "des" | "une" | "est" | "sont" | "pour" | "dans" | "avec" | "sur"
            | "par" | "pas" | "plus" | "mais" | "qui" | "que" | "quoi" | "cette" | "c'est"
            | "tout" | "tous" | "nous" | "vous" | "ils" | "elles" | "etre" | "être" | "avoir"
            | "fait" | "faire" | "comme" | "aussi" | "très" | "bien" | "alors" | "donc"
            | "quand" | "entre" | "chez" | "sans" | "sous" | "aux"
            // Arabic
            | "من" | "في" | "على" | "إلى" | "الى" | "عن" | "مع" | "هذا" | "هذه" | "ذلك"
            | "تلك" | "التي" | "الذي" | "الذين" | "كان" | "كانت" | "يكون" | "ليس" | "لكن"
            | "أو" | "او" | "ثم" | "حتى" | "عند" | "منذ" | "قبل" | "بعد" | "بين" | "كل"
            | "بعض" | "غير" | "كما" | "لأن" | "لان" | "أن" | "ان" | "إن" | "ما" | "لا"
            | "نعم" | "جدا" | "جداً" | "هنا" | "هناك" | "كيف" | "لماذا" | "متى" | "أين"
            | "هو" | "هي" | "هم" | "نحن" | "أنت" | "انت" | "أنا" | "انا"
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_json3_extracts_chips_and_searches() {
        let json3 = r#"{
            "events": [
                { "tStartMs": 1000, "dDurationMs": 3000, "segs": [{"utf8": "Welcome to Rust GPU "}, {"utf8": "rendering benchmarks"}] },
                { "tStartMs": 5000, "dDurationMs": 2500, "segs": [{"utf8": "First let's check Vulkan shader latency"}] },
                { "tStartMs": 9000, "dDurationMs": 2500, "segs": [{"utf8": "Rust memory usage stays low with Vulkan"}] }
            ]
        }"#;
        let segs = parse_json3(json3);
        assert_eq!(segs.len(), 3);
        let chips = extract_topic_chips(&segs, 6);
        assert!(chips.iter().any(|c| c.eq_ignore_ascii_case("Rust")));
        assert!(chips.iter().any(|c| c.eq_ignore_ascii_case("Vulkan")));

        let hits = search_segments(&segs, "vulkan", 10);
        assert_eq!(hits.len(), 2);
        assert_eq!(hits[0].start_ms, 5000);
        assert_eq!(hits[1].start_ms, 9000);

        let fuzzy = search_segments(&segs, "vulkam", 10);
        assert!(!fuzzy.is_empty());
    }

    #[test]
    fn parses_arabic_and_vtt_captions() {
        let vtt = "WEBVTT\n\n00:00:02.000 --> 00:00:05.500\n<b>الذكاء</b> الاصطناعي في معالجة الفيديو\n\n00:00:06.000 --> 00:00:09.000\nمعالجة الفيديو بسرعة عالية الذكاء";
        let segs = parse_vtt(vtt);
        assert_eq!(segs.len(), 2);
        assert_eq!(segs[0].start_ms, 2000);
        let chips = extract_topic_chips(&segs, 6);
        assert!(chips.iter().any(|c| c.contains("الفيديو") || c.contains("معالجة")));
    }
}
