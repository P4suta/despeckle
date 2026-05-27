//! Unit tests for the post-despeckle refinement helpers.

use despeckle_core::{fill_holes, smooth_edges};
use image::{GrayImage, ImageBuffer, Luma};

fn make_img(width: u32, height: u32, blacks: &[(u32, u32)]) -> GrayImage {
    let mut img = ImageBuffer::from_pixel(width, height, Luma([255u8]));
    for &(x, y) in blacks {
        img.put_pixel(x, y, Luma([0u8]));
    }
    img
}

fn all_black(width: u32, height: u32) -> Vec<(u32, u32)> {
    (0..height)
        .flat_map(|y| (0..width).map(move |x| (x, y)))
        .collect()
}

#[test]
fn fill_holes_no_op_on_too_few_components() {
    // A near-empty page (< MIN_COMPONENTS_FOR_REFINE = 8) is left
    // untouched — the degenerate-page guard kicks in before any work.
    let img = make_img(50, 50, &[(25, 25)]);
    let original = img.clone();
    let (out, filled) = fill_holes(img);
    assert_eq!(filled, 0);
    assert_eq!(out.as_raw(), original.as_raw());
}

#[test]
fn smooth_edges_removes_single_pixel_bump() {
    // 5×5, all black except a single white pixel at (2,1). The 3×3
    // neighborhood of (2,1) has 8 black + 1 white → majority black,
    // flip to black.
    let mut blacks = all_black(5, 5);
    blacks.retain(|&(x, y)| !(x == 2 && y == 1));
    let img = make_img(5, 5, &blacks);
    let (out, changed) = smooth_edges(img);
    assert!(
        changed >= 1,
        "expected at least one pixel flip, got {changed}"
    );
    assert_eq!(
        out.get_pixel(2, 1).0[0],
        0,
        "the white bump at (2,1) should be flipped to black"
    );
}

#[test]
fn smooth_edges_idempotent_on_solid_rectangle() {
    // A fully black 10×10 has no interior bumps for the 3×3 majority
    // vote to flip — every interior pixel is surrounded by black.
    let img = make_img(10, 10, &all_black(10, 10));
    let original = img.clone();
    let (out, changed) = smooth_edges(img);
    assert_eq!(changed, 0);
    assert_eq!(out.as_raw(), original.as_raw());
}

#[test]
fn smooth_edges_idempotent_on_blank() {
    let img = make_img(10, 10, &[]);
    let (out, changed) = smooth_edges(img);
    assert_eq!(changed, 0);
    assert!(out.pixels().all(|p| p.0[0] == 255));
}

#[test]
fn smooth_edges_handles_tiny_images() {
    // < 3 in either dimension: no interior pixel to operate on.
    let img = make_img(2, 2, &[(0, 0)]);
    let (out, changed) = smooth_edges(img);
    assert_eq!(changed, 0);
    assert_eq!(out.get_pixel(0, 0).0[0], 0);
}
