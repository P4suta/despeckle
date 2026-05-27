//! Page-level processing pipeline.
//!
//! [`process_page`] is the composition of every other module in the crate:
//!
//! `label → build_column_mask → auto_thresholds → classify → render`
//!
//! Each step is a pure function; `process_page` simply threads the values
//! through. The output image is the input with every `Decision::Remove`
//! component's pixels repainted to white.

use image::GrayImage;

use crate::classify::{Decision, auto_thresholds, classify};
use crate::components::{Labelling, label};
use crate::mask::build_column_mask;

/// Outcome of processing a single page.
#[derive(Debug)]
#[non_exhaustive]
pub struct ProcessResult {
    /// The processed output image.
    pub image: GrayImage,
    /// Number of connected components removed as dust.
    pub components_removed: usize,
}

/// Minimum component count for despeckling to even attempt removal.
///
/// On real scan pages this is comfortably exceeded (Russell test PDF
/// averages ~1500 components/page). A 1-component image is degenerate —
/// the histogram has a single bucket so the speckle heuristic ends up
/// classifying the only component as dust and clears the whole page; the
/// degenerate path is short-circuited here.
const MIN_COMPONENTS_FOR_REMOVAL: usize = 8;

/// Process a single page image.
#[must_use]
pub fn process_page(input: GrayImage) -> ProcessResult {
    let labelling = label(&input);
    let component_count = labelling.components.len();
    tracing::debug!(components = component_count, "process_page CCs");
    if component_count < MIN_COMPONENTS_FOR_REMOVAL {
        return ProcessResult {
            image: input,
            components_removed: 0,
        };
    }
    let Labelling {
        components,
        labels,
        label_map,
        max_label,
    } = labelling;

    let mask = build_column_mask(&input);
    let thresholds = auto_thresholds(&components);
    let decisions = classify(&components, &mask, thresholds);

    // Branch-free paint LUT: `255` for labels marked for removal, `0`
    // otherwise. The inner loop is `pixel |= lut[label]`, which ORs 255
    // (→ white) onto removed pixels and 0 (no-op) onto kept pixels.
    // This compiles into a straight gather + OR with no per-pixel
    // condition, freeing LLVM to vectorise.
    let mut remove_lut = vec![0u8; (max_label as usize) + 1];
    let mut components_removed = 0usize;
    for (decision, label_id) in decisions.iter().zip(&labels) {
        if *decision == Decision::Remove {
            remove_lut[*label_id as usize] = 255;
            components_removed += 1;
        }
    }

    let mut output = input;
    if components_removed > 0 {
        paint_white(&mut output, &label_map, &remove_lut);
    }

    ProcessResult {
        image: output,
        components_removed,
    }
}

/// Walk both buffers as raw slices (same row-major layout, same length)
/// and OR the LUT byte onto each pixel — `0` for kept components is a
/// no-op, `255` for removed components paints the pixel white.
fn paint_white(output: &mut GrayImage, label_map: &crate::components::LabelMap, remove_lut: &[u8]) {
    let labels = label_map.as_raw();
    let pixels = output.as_mut();
    debug_assert_eq!(labels.len(), pixels.len());
    let lut_len = remove_lut.len();
    for (pixel, &label_id) in pixels.iter_mut().zip(labels.iter()) {
        let idx = label_id as usize;
        if idx < lut_len {
            *pixel |= remove_lut[idx];
        }
    }
}
