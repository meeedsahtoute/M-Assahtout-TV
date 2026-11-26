//! Auto-Highlight Reel ("Most Replayed" heatmap & chapter peak extractor).
//! Shared between Windows Desktop (`src/`) and Android TV JNI (`crates/streamtube-android/`).

use crate::api::types::Chapter;
use serde::{Deserialize, Serialize};

/// A high-interest video interval suitable for "Highlights / الزبدة" jump playback.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct HighlightPeak {
    pub start_ms: u64,
    pub end_ms: u64,
    pub intensity: f32,
    /// 1-based highlight index (`1..=total`).
    pub index: usize,
    pub total: usize,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub label: Option<String>,
}

const MAX_HIGHLIGHTS: usize = 8;
const LEAD_IN_SECS: f64 = 3.0;
const MIN_CLIP_SECS: f64 = 12.0;
const MERGE_GAP_MS: u64 = 3_500;

/// Extracts peak highlight intervals from YouTube's "Most Replayed" heatmap,
/// falling back cleanly to chapter transitions when heatmap data is absent.
pub fn extract_highlights(
    heatmap: &[(f64, f64, f32)],
    chapters: &[Chapter],
    duration_secs: f64,
) -> Vec<HighlightPeak> {
    let max_dur_ms = if duration_secs.is_finite() && duration_secs > 1.0 {
        (duration_secs * 1000.0).round() as u64
    } else {
        heatmap
            .iter()
            .map(|(s, d, _)| ((s + d).max(0.0) * 1000.0).round() as u64)
            .max()
            .unwrap_or(0)
    };

    let mut raw = extract_from_heatmap(heatmap, max_dur_ms);
    if raw.is_empty() && chapters.len() >= 2 {
        raw = extract_from_chapters(chapters, max_dur_ms);
    }

    let total = raw.len();
    for (idx, peak) in raw.iter_mut().enumerate() {
        peak.index = idx + 1;
        peak.total = total;
        if peak.label.is_none() {
            peak.label = chapter_at(chapters, peak.start_ms);
        }
    }
    raw
}

fn extract_from_heatmap(heatmap: &[(f64, f64, f32)], max_dur_ms: u64) -> Vec<HighlightPeak> {
    let valid: Vec<(f64, f64, f32)> = heatmap
        .iter()
        .copied()
        .filter(|(s, d, i)| s.is_finite() && d.is_finite() && i.is_finite() && *s >= 0.0 && *i > 0.0)
        .collect();
    if valid.is_empty() {
        return Vec::new();
    }

    let max_i = valid.iter().map(|v| v.2).fold(0.0_f32, f32::max);
    if max_i <= 0.0 {
        return Vec::new();
    }

    let mut sorted_scores: Vec<f32> = valid.iter().map(|v| v.2).collect();
    sorted_scores.sort_by(|a, b| a.total_cmp(b));
    let p75 = sorted_scores[(sorted_scores.len() * 3 / 4).min(sorted_scores.len() - 1)];
    let threshold = (max_i * 0.56).max(p75 * 0.92).max(0.32);

    // Pick local maxima above threshold (plus top fallback if flat/sharp).
    let mut candidates: Vec<(usize, f32)> = Vec::new();
    for i in 0..valid.len() {
        let cur = valid[i].2;
        if cur < threshold {
            continue;
        }
        let prev = if i > 0 { valid[i - 1].2 } else { 0.0 };
        let next = if i + 1 < valid.len() { valid[i + 1].2 } else { 0.0 };
        if cur >= prev && cur >= next {
            candidates.push((i, cur));
        }
    }
    if candidates.is_empty() {
        let mut by_score: Vec<(usize, f32)> = valid.iter().enumerate().map(|(i, v)| (i, v.2)).collect();
        by_score.sort_by(|a, b| b.1.total_cmp(&a.1));
        by_score.truncate(MAX_HIGHLIGHTS / 2);
        candidates = by_score;
    }

    // Prioritize strongest peaks, cap count before chronological merge.
    candidates.sort_by(|a, b| b.1.total_cmp(&a.1));
    let mut selected: Vec<(u64, u64, f32)> = Vec::new();
    for (idx, intensity) in candidates {
        let (start_s, dur_s, _) = valid[idx];
        let start_ms = ((start_s - LEAD_IN_SECS).max(0.0) * 1000.0).round() as u64;
        let raw_end_s = (start_s + dur_s.max(MIN_CLIP_SECS)).max(start_s + MIN_CLIP_SECS);
        let mut end_ms = (raw_end_s * 1000.0).round() as u64;
        if max_dur_ms > 0 {
            end_ms = end_ms.min(max_dur_ms);
        }
        if end_ms <= start_ms + 2_000 {
            continue;
        }
        // Skip duplicate peak windows nearly identical to an already-selected stronger peak.
        let overlaps_stronger = selected.iter().any(|&(s, e, _)| {
            let overlap = end_ms.min(e).saturating_sub(start_ms.max(s));
            overlap > (end_ms - start_ms) / 2
        });
        if overlaps_stronger {
            continue;
        }
        selected.push((start_ms, end_ms, intensity));
        if selected.len() >= MAX_HIGHLIGHTS {
            break;
        }
    }

    merge_chronological(selected)
}

fn extract_from_chapters(chapters: &[Chapter], max_dur_ms: u64) -> Vec<HighlightPeak> {
    let mut usable: Vec<&Chapter> = chapters.iter().filter(|c| c.start.is_finite() && c.start >= 0.0).collect();
    if usable.len() > 2 && usable[0].start < 5.0 {
        usable.remove(0);
    }
    if usable.is_empty() {
        return Vec::new();
    }

    let step = (usable.len() / MAX_HIGHLIGHTS).max(1);
    let mut peaks = Vec::new();
    for (rank, ch) in usable.iter().step_by(step).take(MAX_HIGHLIGHTS).enumerate() {
        let start_ms = (ch.start * 1000.0).round() as u64;
        let mut end_ms = start_ms + 20_000;
        if max_dur_ms > 0 {
            end_ms = end_ms.min(max_dur_ms);
        }
        if end_ms <= start_ms + 3_000 {
            continue;
        }
        peaks.push(HighlightPeak {
            start_ms,
            end_ms,
            intensity: (0.85 - (rank as f32) * 0.04).clamp(0.45, 0.9),
            index: 0,
            total: 0,
            label: (!ch.title.trim().is_empty()).then(|| ch.title.trim().to_string()),
        });
    }
    peaks
}

fn merge_chronological(mut selected: Vec<(u64, u64, f32)>) -> Vec<HighlightPeak> {
    selected.sort_by_key(|v| v.0);
    let mut merged: Vec<(u64, u64, f32)> = Vec::with_capacity(selected.len());
    for (s, e, inten) in selected {
        if let Some(last) = merged.last_mut() {
            if s <= last.1.saturating_add(MERGE_GAP_MS) {
                last.1 = last.1.max(e);
                last.2 = last.2.max(inten);
                continue;
            }
        }
        merged.push((s, e, inten));
    }
    merged
        .into_iter()
        .map(|(start_ms, end_ms, intensity)| HighlightPeak {
            start_ms,
            end_ms,
            intensity,
            index: 0,
            total: 0,
            label: None,
        })
        .collect()
}

fn chapter_at(chapters: &[Chapter], ms: u64) -> Option<String> {
    let secs = (ms as f64 / 1000.0) + 4.0;
    chapters
        .iter()
        .rev()
        .find(|c| c.start <= secs)
        .map(|c| c.title.trim().to_string())
        .filter(|t| !t.is_empty())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn heatmap_extracts_and_merges_peaks() {
        let mut heatmap = Vec::new();
        for i in 0..30 {
            let s = (i * 10) as f64;
            let val = match i {
                5 => 0.95,
                6 => 0.92,
                18 => 0.88,
                _ => 0.15,
            };
            heatmap.push((s, 10.0, val));
        }
        let peaks = extract_highlights(&heatmap, &[], 300.0);
        assert_eq!(peaks.len(), 2);
        assert_eq!(peaks[0].index, 1);
        assert_eq!(peaks[0].total, 2);
        assert_eq!(peaks[0].start_ms, 47_000); // 50s - 3s lead-in
        assert_eq!(peaks[1].start_ms, 177_000); // 180s - 3s lead-in
    }

    #[test]
    fn falls_back_to_chapters_when_heatmap_empty() {
        let chapters = vec![
            Chapter { title: "Intro".into(), start: 0.0, thumbnail: None },
            Chapter { title: "Benchmark".into(), start: 45.0, thumbnail: None },
            Chapter { title: "Conclusion".into(), start: 120.0, thumbnail: None },
        ];
        let peaks = extract_highlights(&[], &chapters, 200.0);
        assert_eq!(peaks.len(), 2);
        assert_eq!(peaks[0].label.as_deref(), Some("Benchmark"));
        assert_eq!(peaks[1].label.as_deref(), Some("Conclusion"));
    }
}
