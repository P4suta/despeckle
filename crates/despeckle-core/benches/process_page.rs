#![allow(
    missing_docs,
    reason = "criterion_group / criterion_main expand into pub items"
)]
#![allow(
    clippy::print_stderr,
    reason = "bench harness: announce a missing optional sample on stderr"
)]

//! Criterion bench for `process_page` on a real Russell scan page.
//!
//! The sample file lives outside the repo (it is real scan data and
//! `.gitignore`d). To run the bench locally, expand a sample PDF first:
//!
//! ```sh
//! mkdir -p private/scans/russell2
//! pdftoppm -mono -r 300 path/to/your.pdf private/scans/russell2/page
//! cargo bench -p despeckle-core
//! ```

use std::path::PathBuf;

use criterion::{Criterion, criterion_group, criterion_main};
use despeckle_core::{
    auto_thresholds, build_column_mask, classify, label, load_bitonal, process_page,
};
use mimalloc::MiMalloc;

#[global_allocator]
static GLOBAL: MiMalloc = MiMalloc;

fn load_sample() -> Option<image::GrayImage> {
    let candidates = [
        PathBuf::from("../../private/scans/russell2/page-01.pbm"),
        PathBuf::from("private/scans/russell2/page-01.pbm"),
    ];
    candidates.iter().find_map(|p| load_bitonal(p).ok())
}

fn end_to_end(c: &mut Criterion) {
    let Some(img) = load_sample() else {
        eprintln!("skipping bench: no sample page at private/scans/russell2/page-01.pbm");
        return;
    };
    let (w, h) = img.dimensions();
    let mut group = c.benchmark_group("process_page");
    group.sample_size(20);
    group.bench_function(format!("russell_page_01_{w}x{h}"), |b| {
        b.iter(|| process_page(img.clone()));
    });
    group.finish();
}

fn sub_steps(c: &mut Criterion) {
    let Some(img) = load_sample() else { return };
    let labelling = label(&img);
    let mask = build_column_mask(&img);
    let thresholds = auto_thresholds(&labelling.components);

    let mut group = c.benchmark_group("process_page_parts");
    group.sample_size(20);

    group.bench_function("label_ccl", |b| b.iter(|| label(&img)));
    group.bench_function("build_column_mask", |b| b.iter(|| build_column_mask(&img)));
    group.bench_function("auto_thresholds", |b| {
        b.iter(|| auto_thresholds(&labelling.components));
    });
    group.bench_function("classify", |b| {
        b.iter(|| classify(&labelling.components, &mask, thresholds));
    });

    group.finish();
}

criterion_group!(benches, end_to_end, sub_steps);
criterion_main!(benches);
