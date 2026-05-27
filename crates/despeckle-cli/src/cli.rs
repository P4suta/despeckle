//! Command-line argument parsing.

use std::path::PathBuf;

use clap::{Parser, ValueEnum};

/// Automatically remove dust from bitonal Japanese-novel scan images.
///
/// Walks `<INPUT_DIR>`, processes every matching image in parallel, and
/// writes cleaned outputs into `<OUTPUT_DIR>` (mirroring the input
/// directory structure).
#[derive(Debug, Parser)]
#[command(name = "despeckle", version, about, long_about = None)]
pub(crate) struct Args {
    /// Directory containing bitonal page images (read recursively).
    pub(crate) input_dir: PathBuf,

    /// Directory to write cleaned images to. Mirrors the input layout.
    pub(crate) output_dir: PathBuf,

    /// Directory to write the before/after HTML report into. Off by default.
    #[arg(long)]
    pub(crate) report: Option<PathBuf>,

    /// Number of worker threads. Defaults to the logical CPU count.
    #[arg(short = 'j', long)]
    pub(crate) jobs: Option<usize>,

    /// Output image format.
    #[arg(long, value_enum, default_value_t = OutputFormat::Same)]
    pub(crate) format: OutputFormat,

    /// Glob pattern for selecting input file names.
    #[arg(long, default_value = "*.{pbm,png,tiff,tif}")]
    pub(crate) glob: String,

    /// Overwrite output directory contents if non-empty.
    #[arg(long)]
    pub(crate) force: bool,

    /// Skip the hole-fill post-pass (default: enabled).
    #[arg(long)]
    pub(crate) no_fill_holes: bool,

    /// Skip the 3×3 median smoothing post-pass (default: enabled).
    #[arg(long)]
    pub(crate) no_smooth: bool,
}

/// Output image format selection.
#[derive(Debug, Clone, Copy, ValueEnum)]
pub(crate) enum OutputFormat {
    /// Keep the input file's extension and format.
    Same,
    /// Write every output as PBM (P4 binary).
    Pbm,
    /// Write every output as PNG.
    Png,
}
