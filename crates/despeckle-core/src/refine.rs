//! Two-step post-pass that runs after `despeckle` proper.
//!
//! Both helpers operate on the *despeckled* output and remain in the
//! 1-bit color space, so the downstream PBM / TIFF round-trip and the
//! `img2pdf`-driven CCITT-G4 PDF embed are unaffected:
//!
//! - [`fill_holes`] mirrors despeckle: it flags isolated white holes
//!   *inside* black glyph strokes (typically scanner pin-holes 1–2 px
//!   wide) and paints them black. Reuses the entire CCL → thresholds →
//!   classify infrastructure by feeding the labeler an inverted image.
//! - [`smooth_edges`] does a 3 × 3 majority filter — straightforward
//!   median smoothing. Single-pixel bumps on an otherwise straight edge
//!   get pulled flush; cleanly-bordered shapes are left alone.

use image::GrayImage;

use crate::classify::{Decision, auto_thresholds, classify};
use crate::components::{Labelling, label};
use crate::mask::build_column_mask;

/// Same guard `process_page` uses: don't try to "fix" a page with too
/// few connected components, the heuristics need a population to work.
const MIN_COMPONENTS_FOR_REFINE: usize = 8;

/// Fill isolated white holes inside black glyph strokes.
///
/// Returns `(output, holes_filled)`. The image is consumed and returned
/// modified in place.
#[must_use]
pub fn fill_holes(input: GrayImage) -> (GrayImage, usize) {
    let mut inverted = input.clone();
    invert_inplace(&mut inverted);

    let labelling = label(&inverted);
    drop(inverted);

    if labelling.components.len() < MIN_COMPONENTS_FOR_REFINE {
        return (input, 0);
    }

    let mask = build_column_mask(&input);
    let thresholds = auto_thresholds(&labelling.components);
    let decisions = classify(&labelling.components, &mask, thresholds);

    let Labelling {
        labels,
        label_map,
        max_label,
        ..
    } = labelling;

    let mut fill_lut = vec![0u8; (max_label as usize) + 1];
    let mut holes_filled = 0usize;
    for (decision, label_id) in decisions.iter().zip(&labels) {
        if *decision == Decision::Remove {
            fill_lut[*label_id as usize] = 255;
            holes_filled += 1;
        }
    }

    let mut output = input;
    if holes_filled > 0 {
        paint_black(&mut output, &label_map, &fill_lut);
    }

    (output, holes_filled)
}

/// 3 × 3 majority-vote median filter.
///
/// Each interior pixel is set to whichever color (black / white) holds
/// the majority of the 9 pixels in its 3 × 3 neighborhood. Single-pixel
/// staircase bumps on an otherwise straight glyph edge flip to match
/// the surrounding stroke; the image's larger structure is preserved.
///
/// Returns `(output, smoothed_pixels)` — the count of pixels that
/// actually changed value, useful for the report overlay.
#[must_use]
pub fn smooth_edges(input: GrayImage) -> (GrayImage, usize) {
    let width = input.width();
    let height = input.height();
    if width < 3 || height < 3 {
        return (input, 0);
    }
    let width_usize = width as usize;
    let height_usize = height as usize;

    let src = input.as_raw().clone();
    let mut dst = src.clone();
    let mut changed = 0usize;

    for y in 1..height_usize - 1 {
        let row_above = (y - 1) * width_usize;
        let row_this = y * width_usize;
        let row_below = (y + 1) * width_usize;
        for x in 1..width_usize - 1 {
            // Count black pixels in the 3x3 neighborhood. Branch-free
            // sum so LLVM can keep it in registers.
            let black_count = u8::from(src[row_above + x - 1] == 0)
                + u8::from(src[row_above + x] == 0)
                + u8::from(src[row_above + x + 1] == 0)
                + u8::from(src[row_this + x - 1] == 0)
                + u8::from(src[row_this + x] == 0)
                + u8::from(src[row_this + x + 1] == 0)
                + u8::from(src[row_below + x - 1] == 0)
                + u8::from(src[row_below + x] == 0)
                + u8::from(src[row_below + x + 1] == 0);
            let new_pixel = if black_count >= 5 { 0u8 } else { 255u8 };
            let idx = row_this + x;
            if dst[idx] != new_pixel {
                dst[idx] = new_pixel;
                changed += 1;
            }
        }
    }

    let output = GrayImage::from_raw(width, height, dst).unwrap_or(input);
    (output, changed)
}

/// Flip every pixel: black ↔ white. Pre-condition: bitonal (only 0 / 255).
fn invert_inplace(img: &mut GrayImage) {
    for pixel in img.as_mut() {
        *pixel = 255 - *pixel;
    }
}

/// Mirror of `pipeline::paint_white` — clears every pixel whose label
/// is flagged in `fill_lut` to **black** (`0`).
fn paint_black(output: &mut GrayImage, label_map: &crate::components::LabelMap, fill_lut: &[u8]) {
    let labels = label_map.as_raw();
    let pixels = output.as_mut();
    debug_assert_eq!(labels.len(), pixels.len());
    let lut_len = fill_lut.len();
    for (pixel, &label_id) in pixels.iter_mut().zip(labels.iter()) {
        let idx = label_id as usize;
        if idx < lut_len && fill_lut[idx] != 0 {
            *pixel = 0;
        }
    }
}
