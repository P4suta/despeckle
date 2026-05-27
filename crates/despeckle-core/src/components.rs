//! Connected-component extraction.
//!
//! Given a bitonal page, label every 4-connected black region and report
//! each region's bounding box, pixel area, and centroid alongside the
//! label map. Downstream classification reads these features to decide
//! which components are protected typography (本文・ルビ・句読点・濁点)
//! and which are dust; the label map lets the pipeline mask out exactly
//! the removed pixels later.
//!
//! Implementation: a hand-tuned two-pass 4-connectivity labeler with
//! union-find + path compression — pass 1 propagates `min(up, left)`
//! labels and unions equivalent ones, pass 2 resolves every provisional
//! label to its root, renumbers densely (`1..=max_label`), and folds
//! per-component bbox / area / centroid into the same sweep. It is
//! GrayImage-specific (no generic-pixel dispatch) and 4-connectivity-
//! only, both of which compile away into branchless inner loops.

use image::{GrayImage, ImageBuffer, Luma};

/// Axis-aligned bounding box of a connected component, in pixel
/// coordinates (inclusive on both extremes).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct BoundingBox {
    /// Minimum X (inclusive).
    pub x0: u32,
    /// Minimum Y (inclusive).
    pub y0: u32,
    /// Maximum X (inclusive).
    pub x1: u32,
    /// Maximum Y (inclusive).
    pub y1: u32,
}

impl BoundingBox {
    /// Width in pixels.
    #[must_use]
    pub const fn width(self) -> u32 {
        self.x1 - self.x0 + 1
    }

    /// Height in pixels.
    #[must_use]
    pub const fn height(self) -> u32 {
        self.y1 - self.y0 + 1
    }
}

/// A single connected component on the page.
#[derive(Debug, Clone, Copy, PartialEq)]
#[non_exhaustive]
pub struct Component {
    /// Bounding box of the component.
    pub bbox: BoundingBox,
    /// Number of black pixels in the component.
    pub area: u32,
    /// Centroid `(cx, cy)` in pixel coordinates.
    pub centroid: (f32, f32),
}

/// Per-pixel CCL output. `0` is background, anything else is a label
/// belonging to one of the [`Component`]s in `components`.
pub type LabelMap = ImageBuffer<Luma<u32>, Vec<u32>>;

/// Connected-component labelling output: the parallel `components` and
/// `labels` vectors share an index (`components[i]` lives at label
/// `labels[i]` in `label_map`).
#[derive(Debug)]
#[non_exhaustive]
pub struct Labelling {
    /// One entry per connected component, in label order.
    pub components: Vec<Component>,
    /// `label_map` value identifying each component (parallel to
    /// `components`).
    pub labels: Vec<u32>,
    /// Per-pixel label map; `0` is background, other values match
    /// `labels`.
    pub label_map: LabelMap,
    /// Largest label value present in `label_map` (0 if no components).
    pub max_label: u32,
}

/// Label every 4-connected black region of `img`.
///
/// Background is fixed at white (`Luma([255])`); pixel value `0` is
/// foreground.
///
/// # Panics
///
/// Never in practice — the internal `ImageBuffer::from_raw` is fed a
/// buffer sized to exactly `width × height` `u32`s by construction.
#[must_use]
pub fn label(img: &GrayImage) -> Labelling {
    let width_u32 = img.width();
    let height_u32 = img.height();
    let width = width_u32 as usize;
    let height = height_u32 as usize;
    let pixels = img.as_raw();
    let total = pixels.len();
    debug_assert_eq!(total, width * height);

    let mut provisional: Vec<u32> = vec![0; total];
    // `parents[i]` is the representative of label `i` in the union-find;
    // index 0 is reserved for the background.
    let mut parents: Vec<u32> = vec![0];

    // ----- Pass 1: assign provisional labels and union neighbors -----
    // Carry the just-written label in `left_label` so the inner loop
    // never re-reads `provisional[idx - 1]`. The up-neighbor comes from
    // the row directly above (`prov[idx - width]`), which the prefetcher
    // pulls in for free as we sweep row-major.
    let mut row_start = 0usize;
    for y in 0..height {
        let row = &pixels[row_start..row_start + width];
        let has_up = y > 0;
        let mut left_label: u32 = 0;
        for (x, &pixel) in row.iter().enumerate() {
            if pixel != 0 {
                left_label = 0;
                continue;
            }
            let up = if has_up {
                provisional[row_start + x - width]
            } else {
                0
            };

            let label_id = match (up, left_label) {
                (0, 0) => {
                    #[allow(
                        clippy::cast_possible_truncation,
                        reason = "parents grows by 1 per foreground pixel; image pixel count fits in u32 by GrayImage's API"
                    )]
                    let new_id = parents.len() as u32;
                    parents.push(new_id);
                    new_id
                },
                (a, 0) | (0, a) => a,
                (a, b) if a == b => a,
                (a, b) => {
                    uf_union(&mut parents, a, b);
                    a.min(b)
                },
            };
            provisional[row_start + x] = label_id;
            left_label = label_id;
        }
        row_start += width;
    }

    // ----- Pass 2: resolve to roots, renumber densely, aggregate -----
    // Same trick as pass 1: iterate row slices so the prefetcher gets a
    // straight forward access pattern over `provisional`, and avoid the
    // double-index per pixel.
    let mut renumber: Vec<u32> = vec![0; parents.len()];
    let mut accs: Vec<Accumulator> = Vec::new();
    let mut next_id: u32 = 1;

    let mut row_start = 0usize;
    for y in 0..height_u32 {
        let row_end = row_start + width;
        let row = &mut provisional[row_start..row_end];
        for (x_offset, prov_slot) in row.iter_mut().enumerate() {
            let prov = *prov_slot;
            if prov == 0 {
                continue;
            }
            let root = uf_find(&mut parents, prov);
            let final_id = match renumber[root as usize] {
                0 => {
                    let id = next_id;
                    renumber[root as usize] = id;
                    accs.push(Accumulator::default());
                    next_id = next_id.saturating_add(1);
                    id
                },
                existing => existing,
            };
            *prov_slot = final_id;
            #[allow(
                clippy::cast_possible_truncation,
                reason = "x_offset < width which fits in u32 by GrayImage's API"
            )]
            accs[(final_id - 1) as usize].push(x_offset as u32, y);
        }
        row_start = row_end;
    }

    let max_label = next_id - 1;
    let labels: Vec<u32> = (1..=max_label).collect();
    let components: Vec<Component> = accs.into_iter().map(Accumulator::finalize).collect();

    #[allow(
        clippy::expect_used,
        reason = "provisional length is exactly width × height by construction (Pass 1 sweep)"
    )]
    let label_map: LabelMap = ImageBuffer::from_raw(width_u32, height_u32, provisional)
        .expect("label buffer length matches dimensions by construction");

    Labelling {
        components,
        labels,
        label_map,
        max_label,
    }
}

#[inline]
fn uf_find(parents: &mut [u32], mut x: u32) -> u32 {
    // Iterative path-compression: every step short-circuits to its
    // grandparent, so subsequent finds on the same chain are O(1) amortized.
    loop {
        let parent = parents[x as usize];
        if parent == x {
            return x;
        }
        let grand = parents[parent as usize];
        parents[x as usize] = grand;
        x = grand;
    }
}

#[inline]
fn uf_union(parents: &mut [u32], a: u32, b: u32) {
    let ra = uf_find(parents, a);
    let rb = uf_find(parents, b);
    if ra != rb {
        let (small, large) = if ra < rb { (ra, rb) } else { (rb, ra) };
        parents[large as usize] = small;
    }
}

#[derive(Default)]
struct Accumulator {
    seen: bool,
    x_min: u32,
    y_min: u32,
    x_max: u32,
    y_max: u32,
    area: u32,
    cx_sum: u64,
    cy_sum: u64,
}

impl Accumulator {
    fn push(&mut self, x: u32, y: u32) {
        if self.seen {
            self.x_min = self.x_min.min(x);
            self.y_min = self.y_min.min(y);
            self.x_max = self.x_max.max(x);
            self.y_max = self.y_max.max(y);
        } else {
            self.x_min = x;
            self.y_min = y;
            self.x_max = x;
            self.y_max = y;
            self.seen = true;
        }
        self.area += 1;
        self.cx_sum += u64::from(x);
        self.cy_sum += u64::from(y);
    }

    fn finalize(self) -> Component {
        Component {
            bbox: BoundingBox {
                x0: self.x_min,
                y0: self.y_min,
                x1: self.x_max,
                y1: self.y_max,
            },
            area: self.area,
            centroid: centroid_of(self.cx_sum, self.cy_sum, self.area),
        }
    }
}

#[allow(
    clippy::cast_precision_loss,
    clippy::cast_possible_truncation,
    clippy::similar_names,
    reason = "pixel coordinates fit in a few thousand: f32 mantissa is precise to a small fraction of a pixel; cx_sum / cy_sum are the natural axis-suffixed names"
)]
fn centroid_of(cx_sum: u64, cy_sum: u64, area: u32) -> (f32, f32) {
    let denom = f64::from(area.max(1));
    (
        (cx_sum as f64 / denom) as f32,
        (cy_sum as f64 / denom) as f32,
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    use image::ImageBuffer;

    fn make_img(width: u32, height: u32, blacks: &[(u32, u32)]) -> GrayImage {
        let mut img = ImageBuffer::from_pixel(width, height, Luma([255u8]));
        for &(x, y) in blacks {
            img.put_pixel(x, y, Luma([0u8]));
        }
        img
    }

    #[test]
    fn single_pixel_is_one_component() {
        let img = make_img(10, 10, &[(5, 5)]);
        let result = label(&img);
        assert_eq!(result.components.len(), 1);
        let c = result.components[0];
        assert_eq!(c.area, 1);
        assert_eq!(
            c.bbox,
            BoundingBox {
                x0: 5,
                y0: 5,
                x1: 5,
                y1: 5
            }
        );
        assert!((c.centroid.0 - 5.0).abs() < 1e-3);
        assert!((c.centroid.1 - 5.0).abs() < 1e-3);
    }

    #[test]
    fn two_separated_pixels_are_two_components() {
        let img = make_img(10, 10, &[(0, 0), (9, 9)]);
        assert_eq!(label(&img).components.len(), 2);
    }

    #[test]
    fn empty_image_has_no_components() {
        let img = make_img(10, 10, &[]);
        assert!(label(&img).components.is_empty());
    }

    #[test]
    fn four_adjacent_pixels_are_one_component() {
        let img = make_img(10, 10, &[(5, 5), (6, 5), (5, 6), (6, 6)]);
        let result = label(&img);
        assert_eq!(result.components.len(), 1);
        assert_eq!(result.components[0].area, 4);
    }

    #[test]
    fn u_shape_with_bottom_bar_is_one_component_via_union() {
        // ##  ##
        // ##  ##
        // ######
        let blacks: Vec<(u32, u32)> = (0..2)
            .flat_map(|y| [(0u32, y), (1, y), (4, y), (5, y)])
            .chain((0..6).map(|x| (x, 2)))
            .collect();
        let img = make_img(6, 3, &blacks);
        let result = label(&img);
        assert_eq!(
            result.components.len(),
            1,
            "U shape must merge via the bottom bar"
        );
        assert_eq!(result.components[0].area, 14);
    }
}
