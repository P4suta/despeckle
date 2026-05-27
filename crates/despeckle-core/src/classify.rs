//! Per-page threshold derivation and component classification.
//!
//! `auto_thresholds` is a pure function: it inspects the area distribution
//! of one page's connected components and picks the speckle-cutoff numbers
//! the rest of the pipeline uses. `classify` then turns each component
//! into a `Decision` (keep or remove) based on its position relative to
//! the column mask, its area, and its isolation from neighbors.
//!
//! No I/O, no global state — the whole module is one expression-shaped
//! sequence of pure functions.

use crate::{Component, mask::ColumnMask};

/// Threshold set derived from one page's component distribution.
#[derive(Debug, Clone, Copy)]
#[non_exhaustive]
pub struct Thresholds {
    /// In-column small-component cutoff (area ≤ this is a removal
    /// candidate, subject to also being isolated).
    pub tiny_area: u32,
    /// Out-of-column unconditional cutoff (area ≤ this is removed without
    /// further checks when the component sits in the margin / gutter).
    pub gutter_area: u32,
    /// Minimum nearest-neighbor distance, in pixels, for an in-column
    /// small component to qualify as isolated dust.
    pub iso_distance: f32,
    /// `true` if the speckle-peak histogram analysis failed and the
    /// percentile fallback supplied `tiny_area`. Reports surface this so
    /// pages relying on the fallback can be eyeballed quickly.
    pub fallback_used: bool,
}

/// Gutter area is a small multiple of the tiny cutoff — out-of-text noise
/// can be removed more aggressively because typography never lives there.
const GUTTER_MULTIPLIER: u32 = 3;

/// Isolation distance as a fraction of the median character height.
///
/// Conservative on purpose (0.5 ≈ half a glyph): a stray 1-2 px chip
/// off a real glyph stroke usually has another glyph component well
/// within half a character height, so the protection rule keeps it,
/// while a genuinely isolated speckle in the gutter is way farther
/// than that from anything.
const ISO_RATIO: f32 = 0.5;

/// Fallback percentile (0.5 %) used when no histogram valley is detectable.
const FALLBACK_PERCENTILE: f64 = 0.005;

/// Search range (in pixels of area) for the speckle-peak bucket. Real
/// speckles cluster at 1–3 px²; anything beyond that is already a printed
/// glyph stroke.
const SPECKLE_SEARCH_MAX: u32 = 3;

/// Derive thresholds from one page's components.
///
/// `auto_thresholds` is pure: same input → same output, no side-effects.
#[must_use]
pub fn auto_thresholds(components: &[Component]) -> Thresholds {
    let (tiny_area, fallback_used) = derive_tiny_area(components);
    let gutter_area = tiny_area.saturating_mul(GUTTER_MULTIPLIER);
    let iso_distance = derive_iso_distance(components);
    Thresholds {
        tiny_area,
        gutter_area,
        iso_distance,
        fallback_used,
    }
}

fn derive_tiny_area(components: &[Component]) -> (u32, bool) {
    if components.is_empty() {
        return (0, true);
    }

    let max_area = components.iter().map(|c| c.area).max().unwrap_or(0);
    let hist_len = usize::try_from(max_area)
        .unwrap_or(usize::MAX)
        .saturating_add(1);
    let mut hist = vec![0u32; hist_len];
    for component in components {
        let bucket = usize::try_from(component.area)
            .unwrap_or(usize::MAX)
            .min(hist_len - 1);
        hist[bucket] = hist[bucket].saturating_add(1);
    }

    if let Some(valley) = speckle_valley(&hist)
        && let Ok(area) = u32::try_from(valley)
    {
        return (area, false);
    }

    (percentile_fallback(components), true)
}

/// Find the first histogram valley after the speckle peak (a bucket whose
/// count drops below the previous bucket). Returns `None` if no valley is
/// detected — the caller then falls back to the percentile path.
fn speckle_valley(hist: &[u32]) -> Option<usize> {
    let upper = usize::try_from(SPECKLE_SEARCH_MAX)
        .unwrap_or(usize::MAX)
        .min(hist.len() - 1);
    let peak = (1..=upper).max_by_key(|&i| hist[i])?;

    let mut prev = hist.get(peak).copied()?;
    for (i, &count) in hist.iter().enumerate().skip(peak + 1) {
        if count < prev {
            return Some(i);
        }
        prev = count;
    }
    None
}

#[allow(
    clippy::cast_precision_loss,
    clippy::cast_possible_truncation,
    clippy::cast_sign_loss,
    reason = "small integer count → f64 percentile index → small usize is a safe round-trip"
)]
fn percentile_fallback(components: &[Component]) -> u32 {
    let mut areas: Vec<u32> = components.iter().map(|c| c.area).collect();
    areas.sort_unstable();
    let idx = (FALLBACK_PERCENTILE * areas.len() as f64).round() as usize;
    areas.get(idx).copied().unwrap_or(0)
}

#[allow(
    clippy::cast_precision_loss,
    reason = "median char-height fits in well under f32's mantissa"
)]
fn derive_iso_distance(components: &[Component]) -> f32 {
    if components.is_empty() {
        return 0.0;
    }
    let mut heights: Vec<u32> = components.iter().map(|c| c.bbox.height()).collect();
    heights.sort_unstable();
    let median = heights[heights.len() / 2];
    median as f32 * ISO_RATIO
}

/// Per-component decision produced by [`classify`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Decision {
    /// Keep this component as-is.
    Keep,
    /// Paint this component's pixels with the background color.
    Remove,
}

/// Classify every component given a column mask and a derived threshold set.
///
/// The output is parallel to `components` — `decisions[i]` is the verdict
/// for `components[i]`.
///
/// Logic:
/// - In-column (centroid X inside any band): the component is removed only
///   if it is *both* below `tiny_area` and far enough from any neighbor
///   (>= `iso_distance`). This is what protects ruby / 句読点 / 濁点 from
///   being mistaken for dust.
/// - Out-of-column (margin / gutter): a more aggressive cutoff applies —
///   anything at or below `gutter_area` is removed unconditionally because
///   no typography is expected there.
#[must_use]
pub fn classify(
    components: &[Component],
    mask: &ColumnMask,
    thresholds: Thresholds,
) -> Vec<Decision> {
    let nearest = nearest_neighbor_distances(components);
    components
        .iter()
        .copied()
        .zip(nearest)
        .map(|(component, dist)| decide_one(component, dist, mask, thresholds))
        .collect()
}

#[allow(
    clippy::cast_possible_truncation,
    clippy::cast_sign_loss,
    reason = "centroid X is a non-negative pixel coordinate; round → u32 is exact for the values we see"
)]
fn decide_one(
    component: Component,
    nearest: f32,
    mask: &ColumnMask,
    thresholds: Thresholds,
) -> Decision {
    let centroid_x = component.centroid.0.round() as u32;
    if mask.contains_x(centroid_x) {
        if component.area <= thresholds.tiny_area && nearest >= thresholds.iso_distance {
            Decision::Remove
        } else {
            Decision::Keep
        }
    } else if component.area <= thresholds.gutter_area {
        Decision::Remove
    } else {
        Decision::Keep
    }
}

/// Nearest-neighbor distance (Euclidean) from each centroid to its
/// closest *other* centroid.
///
/// Implementation: build a 2-D `kiddo` `ImmutableKdTree` once from the
/// centroid slice, then query each centroid for its two nearest hits —
/// the first hit is itself with distance 0, the second is the neighbor.
/// `O(n log n)` build + `O(log n)` per query.
///
/// `ImmutableKdTree` is used (instead of the incremental `KdTree`) because
/// it sizes its buckets at build time from the actual point distribution.
/// The incremental tree has a *compile-time* bucket size and panics if
/// more items than that fit on a single axis — a real-world failure mode
/// on novel pages, where vertical typesetting lines up dozens of CC
/// centroids on the same `x` to within float rounding.
fn nearest_neighbor_distances(components: &[Component]) -> Vec<f32> {
    use kiddo::SquaredEuclidean;
    use kiddo::immutable::float::kdtree::ImmutableKdTree;

    let n = components.len();
    if n < 2 {
        return vec![f32::INFINITY; n];
    }

    // `nearest_n` wants the count as `NonZero<usize>`; build it once.
    #[allow(clippy::unwrap_used, reason = "literal 2 is statically non-zero")]
    let two = std::num::NonZero::<usize>::new(2).unwrap();

    let points: Vec<[f32; 2]> = components
        .iter()
        .map(|c| [c.centroid.0, c.centroid.1])
        .collect();
    // The `ImmutableKdTree` sizes its leaves at build time from the actual
    // distribution, so a generous bucket cap (256) accommodates any number
    // of vertically-aligned centroids without panicking and still keeps
    // queries log-time.
    let tree: ImmutableKdTree<f32, u32, 2, 256> = ImmutableKdTree::new_from_slice(&points);

    points
        .iter()
        .map(|point| {
            let hits = tree.nearest_n::<SquaredEuclidean>(point, two);
            // hit 0 is self at distance 0; hit 1 is the nearest neighbor.
            hits.get(1).map_or(f32::INFINITY, |hit| hit.distance.sqrt())
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    use crate::BoundingBox;

    fn comp(area: u32, cx: f32, cy: f32) -> Component {
        Component {
            bbox: BoundingBox {
                x0: 0,
                y0: 0,
                x1: 0,
                y1: 0,
            },
            area,
            centroid: (cx, cy),
        }
    }

    #[test]
    fn empty_components_yields_zero_thresholds() {
        let t = auto_thresholds(&[]);
        assert_eq!(t.tiny_area, 0);
        assert_eq!(t.gutter_area, 0);
        assert!(t.fallback_used);
    }

    #[test]
    fn gutter_is_tiny_times_multiplier() {
        let comps: Vec<Component> = (1u32..=50).map(|i| comp(i, 0.0, 0.0)).collect();
        let t = auto_thresholds(&comps);
        assert_eq!(t.gutter_area, t.tiny_area.saturating_mul(GUTTER_MULTIPLIER));
    }
}
