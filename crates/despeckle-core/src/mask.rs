//! Vertical text-column mask construction.
//!
//! For a Japanese-novel page, text columns run *vertically* (top → bottom);
//! columns themselves are arranged horizontally across the page. We project
//! every black pixel onto the X axis (per-column black counts), pick a
//! cutoff, and treat every contiguous above-cutoff run as a column band.
//!
//! Each band is then dilated horizontally so the ruby (振り仮名) rail
//! riding alongside the body column stays inside the protected zone.

use image::GrayImage;

/// A horizontal slice of the page belonging to a vertical text column,
/// expressed as an inclusive `[x0, x1]` range in pixel coordinates.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ColumnBand {
    /// Minimum X (inclusive).
    pub x0: u32,
    /// Maximum X (inclusive).
    pub x1: u32,
}

/// Set of vertical text-column bands across one page.
#[derive(Debug, Clone)]
pub struct ColumnMask {
    bands: Vec<ColumnBand>,
}

impl ColumnMask {
    /// Read-only access to the bands.
    #[must_use]
    pub fn bands(&self) -> &[ColumnBand] {
        &self.bands
    }

    /// True if `x` falls inside any column band.
    #[must_use]
    pub fn contains_x(&self, x: u32) -> bool {
        self.bands
            .iter()
            .any(|band| (band.x0..=band.x1).contains(&x))
    }
}

/// Numerator of the column-cutoff ratio (cutoff = peak × NUM / DEN).
const THRESHOLD_NUM: u32 = 1;
/// Denominator of the column-cutoff ratio.
const THRESHOLD_DEN: u32 = 4;

/// Numerator of the per-side dilation ratio (each band grows by `w × NUM /
/// DEN` pixels left and right) to include the ruby rail.
const DILATE_NUM: u32 = 1;
/// Denominator of the per-side dilation ratio.
const DILATE_DEN: u32 = 4;

/// Build a column mask for the page.
#[must_use]
pub fn build_column_mask(img: &GrayImage) -> ColumnMask {
    let projection = project_x(img);
    let cutoff = column_cutoff(&projection);
    let raw_bands = extract_bands(&projection, cutoff);
    let dilated = dilate(&raw_bands, img.width());
    ColumnMask { bands: dilated }
}

fn project_x(img: &GrayImage) -> Vec<u32> {
    // Walk the raw byte buffer row by row, accumulating per-column black
    // counts with a branch-free `+=` so LLVM auto-vectorises the inner
    // loop.
    let width = img.width() as usize;
    let mut hist = vec![0u32; width];
    for row in img.as_raw().chunks_exact(width) {
        for (slot, &pixel) in hist.iter_mut().zip(row.iter()) {
            *slot += u32::from(pixel == 0);
        }
    }
    hist
}

fn column_cutoff(hist: &[u32]) -> u32 {
    let peak = hist.iter().copied().max().unwrap_or(0);
    peak.saturating_mul(THRESHOLD_NUM) / THRESHOLD_DEN
}

fn extract_bands(hist: &[u32], cutoff: u32) -> Vec<ColumnBand> {
    let mut bands = Vec::new();
    let mut start: Option<u32> = None;

    for (i, &count) in hist.iter().enumerate() {
        let above = count > cutoff;
        match (above, start) {
            (true, None) => {
                if let Ok(idx) = u32::try_from(i) {
                    start = Some(idx);
                }
            },
            (false, Some(s)) => {
                if let Ok(idx) = u32::try_from(i) {
                    bands.push(ColumnBand {
                        x0: s,
                        x1: idx.saturating_sub(1),
                    });
                }
                start = None;
            },
            _ => {},
        }
    }

    if let Some(s) = start
        && let Ok(end) = u32::try_from(hist.len())
    {
        bands.push(ColumnBand {
            x0: s,
            x1: end.saturating_sub(1),
        });
    }

    bands
}

fn dilate(bands: &[ColumnBand], width: u32) -> Vec<ColumnBand> {
    let max_x = width.saturating_sub(1);
    bands
        .iter()
        .map(|b| {
            let band_width = b.x1.saturating_sub(b.x0).saturating_add(1);
            let pad = band_width.saturating_mul(DILATE_NUM) / DILATE_DEN;
            ColumnBand {
                x0: b.x0.saturating_sub(pad),
                x1: b.x1.saturating_add(pad).min(max_x),
            }
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    use image::{ImageBuffer, Luma};

    #[test]
    fn empty_image_has_no_bands() {
        let img: GrayImage = ImageBuffer::from_pixel(20, 20, Luma([255u8]));
        let mask = build_column_mask(&img);
        assert!(mask.bands().is_empty());
        assert!(!mask.contains_x(5));
    }

    #[test]
    fn single_black_column_yields_band_covering_it() {
        let mut img: GrayImage = ImageBuffer::from_pixel(20, 10, Luma([255u8]));
        for y in 0..10 {
            img.put_pixel(10, y, Luma([0u8]));
        }
        let mask = build_column_mask(&img);
        assert!(!mask.bands().is_empty());
        assert!(mask.contains_x(10));
    }
}
