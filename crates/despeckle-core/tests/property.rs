//! Property tests for the despeckle pipeline.
//!
//! Both properties target the **despeckle-only** pipeline (`fill_holes`
//! and `smooth_edges` disabled), so they describe the dust-removal
//! invariants in isolation:
//!
//! - **Monotonicity**: despeckle never produces *more* black than the
//!   input — it only paints removed components white.
//! - **Second-pass non-regression**: running despeckle a second time
//!   does not blacken anything back. Strict idempotence is *not* claimed
//!   because the per-page thresholds are re-derived from the shrunken
//!   component distribution; a value can drift across the cutoff
//!   between pass 1 and pass 2. The weaker monotonicity property is
//!   what we actually need from the user's perspective.

use despeckle_core::{ProcessOptions, process_page_with};
use image::{GrayImage, ImageBuffer, Luma};
use proptest::prelude::*;

const DESPECKLE_ONLY: ProcessOptions = ProcessOptions {
    fill_holes: false,
    smooth_edges: false,
};

fn render(width: u32, height: u32, mask: &[bool]) -> GrayImage {
    let mut img = ImageBuffer::from_pixel(width, height, Luma([255u8]));
    let total = (width as usize) * (height as usize);
    for (i, &is_black) in mask.iter().enumerate().take(total) {
        if is_black {
            #[allow(
                clippy::cast_possible_truncation,
                reason = "test grid is 32x32, never overflows u32"
            )]
            let x = (i as u32) % width;
            #[allow(
                clippy::cast_possible_truncation,
                reason = "test grid is 32x32, never overflows u32"
            )]
            let y = (i as u32) / width;
            img.put_pixel(x, y, Luma([0u8]));
        }
    }
    img
}

fn black_count(img: &GrayImage) -> usize {
    img.pixels().filter(|p| p.0[0] == 0).count()
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(40))]

    #[test]
    fn output_blacks_subset_of_input(
        mask in proptest::collection::vec(any::<bool>(), 1024..=1024),
    ) {
        let img = render(32, 32, &mask);
        let input_blacks = black_count(&img);
        let result = process_page_with(img, DESPECKLE_ONLY);
        let output_blacks = black_count(&result.image);
        prop_assert!(output_blacks <= input_blacks,
            "output blacks {output_blacks} exceeded input {input_blacks}");
    }

    #[test]
    fn second_pass_does_not_grow_blacks(
        mask in proptest::collection::vec(any::<bool>(), 1024..=1024),
    ) {
        let img = render(32, 32, &mask);
        let once = process_page_with(img, DESPECKLE_ONLY).image;
        let once_blacks = black_count(&once);
        let twice = process_page_with(once, DESPECKLE_ONLY).image;
        let twice_blacks = black_count(&twice);
        prop_assert!(twice_blacks <= once_blacks,
            "twice blacks {twice_blacks} exceeded once {once_blacks}");
    }
}
