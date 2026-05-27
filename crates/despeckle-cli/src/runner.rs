//! Directory walker, parallel dispatcher, and progress reporter.
//!
//! Everything that the pure-function core deliberately avoids — directory
//! traversal, thread-pool configuration, progress bars, structured logging —
//! lives here so that `despeckle-core` can be reused from tests, examples,
//! and future GUI / WASM frontends without dragging this machinery along.
//!
//! Parallelism strategy: `files` is split into one contiguous chunk per
//! worker thread (default = logical CPU count) and dispatched through
//! `std::thread::scope`. Each thread then drains its chunk sequentially,
//! reading / processing / writing each page back-to-back. This deletes
//! rayon's per-iteration scheduler overhead (~22 % of CPU on
//! `bridge_producer_consumer` before this change) while keeping the
//! parallel speed-up — workloads with hundreds of similarly-sized pages
//! get equal load across cores out of the box.

use std::num::NonZeroUsize;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::thread;

use anyhow::{Context, Result};
use despeckle_core::{
    ProcessOptions, ProcessResult, load_bitonal, process_page_with, save_bitonal,
};
use globset::{Glob, GlobMatcher};
use indicatif::{ProgressBar, ProgressStyle};
use walkdir::WalkDir;

use crate::cli::{Args, OutputFormat};
use crate::report::Report;

/// Walk `input_dir`, process every matching image across a fixed pool of
/// worker threads, and write the results into `output_dir`.
pub(crate) fn run(args: &Args) -> Result<()> {
    prepare_output_dir(&args.output_dir, args.force)?;

    let matcher = compile_glob(&args.glob)?;
    let files = collect_files(&args.input_dir, &matcher);

    tracing::info!(
        input = %args.input_dir.display(),
        output = %args.output_dir.display(),
        pages = files.len(),
        "starting despeckle"
    );

    if files.is_empty() {
        tracing::warn!("no images matched in input directory");
        return Ok(());
    }

    let bar = build_progress_bar(files.len())?;

    let report = args
        .report
        .as_ref()
        .map(|dir| Report::new(dir.clone()))
        .transpose()?
        .map(Arc::new);

    let num_threads = thread_count(args.jobs);

    // Lock-free work queue: each worker atomically claims the next
    // file index. Replaces a static-chunk split that left the tail
    // worker idle while the others finished. `fetch_add(Relaxed)` is
    // cheaper than a mutex by ~100×, and the page count (typically
    // <1000) means contention is negligible.
    let cursor = AtomicUsize::new(0);
    let files_ref: &[PathBuf] = &files;
    let cursor_ref = &cursor;
    let bar_ref = &bar;
    let report_ref = report.as_deref();
    let input_root = args.input_dir.as_path();
    let output_root = args.output_dir.as_path();
    let format = args.format;
    let opts = ProcessOptions {
        fill_holes: !args.no_fill_holes,
        smooth_edges: !args.no_smooth,
    };

    let total_removed: usize = thread::scope(|s| -> usize {
        // `collect` is mandatory here — without it the `Iterator::map`
        // chain would spawn each worker, immediately join it, and only
        // then spawn the next one, which serializes the whole pipeline.
        #[allow(
            clippy::needless_collect,
            reason = "materialize spawns before joins or the threads run sequentially"
        )]
        let handles: Vec<_> = (0..num_threads)
            .map(|_| {
                s.spawn(move || -> usize {
                    let mut local = 0usize;
                    loop {
                        let idx = cursor_ref.fetch_add(1, Ordering::Relaxed);
                        let Some(src) = files_ref.get(idx) else { break };
                        match process_one(src, input_root, output_root, format, opts, report_ref) {
                            Ok(n) => local += n,
                            Err(err) => tracing::warn!("page failed: {err:#}"),
                        }
                        bar_ref.inc(1);
                    }
                    local
                })
            })
            .collect();
        handles.into_iter().map(|h| h.join().unwrap_or(0)).sum()
    });

    bar.finish_and_clear();

    if let Some(report) = report {
        let owned = Arc::into_inner(report)
            .context("internal: report Arc still has multiple owners after scope")?;
        owned
            .finish(total_removed)
            .context("failed to finalize report")?;
    }

    tracing::info!(
        pages = files.len(),
        components_removed = total_removed,
        "despeckle done"
    );
    Ok(())
}

fn thread_count(requested: Option<usize>) -> usize {
    requested.and_then(NonZeroUsize::new).map_or_else(
        || thread::available_parallelism().map_or(1, NonZeroUsize::get),
        NonZeroUsize::get,
    )
}

fn prepare_output_dir(dir: &Path, force: bool) -> Result<()> {
    if dir.exists() {
        let non_empty = std::fs::read_dir(dir)
            .with_context(|| format!("failed to read output directory {}", dir.display()))?
            .next()
            .is_some();
        if non_empty && !force {
            anyhow::bail!(
                "output directory {} is non-empty; pass --force to overwrite",
                dir.display()
            );
        }
    } else {
        std::fs::create_dir_all(dir)
            .with_context(|| format!("failed to create output directory {}", dir.display()))?;
    }
    Ok(())
}

fn compile_glob(pattern: &str) -> Result<GlobMatcher> {
    let glob = Glob::new(pattern).with_context(|| format!("invalid glob pattern: {pattern}"))?;
    Ok(glob.compile_matcher())
}

fn collect_files(root: &Path, matcher: &GlobMatcher) -> Vec<PathBuf> {
    WalkDir::new(root)
        .into_iter()
        .filter_map(Result::ok)
        .filter(|entry| entry.file_type().is_file())
        .filter(|entry| {
            entry
                .path()
                .file_name()
                .is_some_and(|name| matcher.is_match(name))
        })
        .map(walkdir::DirEntry::into_path)
        .collect()
}

fn build_progress_bar(total: usize) -> Result<ProgressBar> {
    let style =
        ProgressStyle::with_template("{elapsed_precise} [{bar:40.cyan/blue}] {pos}/{len} {msg}")
            .context("invalid progress bar template")?
            .progress_chars("=>-");
    Ok(ProgressBar::new(total as u64).with_style(style))
}

fn process_one(
    src: &Path,
    input_root: &Path,
    output_root: &Path,
    format: OutputFormat,
    opts: ProcessOptions,
    report: Option<&Report>,
) -> Result<usize> {
    let before = load_bitonal(src).with_context(|| format!("failed to load {}", src.display()))?;
    let before_for_report = report.map(|_| before.clone());
    let ProcessResult {
        image,
        components_removed,
        holes_filled,
        smoothed_pixels,
        ..
    } = process_page_with(before, opts);
    tracing::debug!(
        page = %src.display(),
        components_removed,
        holes_filled,
        smoothed_pixels,
        "page processed"
    );

    let dest = mirror_destination(src, input_root, output_root, format)?;
    if let Some(parent) = dest.parent() {
        std::fs::create_dir_all(parent)
            .with_context(|| format!("failed to create {}", parent.display()))?;
    }
    save_bitonal(&image, &dest).with_context(|| format!("failed to save {}", dest.display()))?;

    if let Some(report) = report
        && let Some(before_img) = before_for_report
    {
        let rel = src
            .strip_prefix(input_root)
            .with_context(|| format!("file outside input root: {}", src.display()))?;
        let stem = strip_extension(rel);
        report.add_page(&stem, &before_img, &image, components_removed)?;
    }

    Ok(components_removed)
}

fn strip_extension(path: &Path) -> PathBuf {
    let mut owned = path.to_path_buf();
    owned.set_extension("");
    owned
}

fn mirror_destination(
    src: &Path,
    input_root: &Path,
    output_root: &Path,
    format: OutputFormat,
) -> Result<PathBuf> {
    let rel = src
        .strip_prefix(input_root)
        .with_context(|| format!("file outside input root: {}", src.display()))?;
    let mut dest = output_root.join(rel);
    match format {
        OutputFormat::Same => {},
        OutputFormat::Pbm => {
            dest.set_extension("pbm");
        },
        OutputFormat::Png => {
            dest.set_extension("png");
        },
    }
    Ok(dest)
}
