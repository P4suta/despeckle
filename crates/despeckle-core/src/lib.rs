//! Core image-processing primitives for **despeckle**.
//!
//! This crate owns the pure functions that, given a bitonal page image,
//! decide which connected components are dust and remove them while
//! protecting Japanese novel typography (ruby, 句読点, 濁点). It performs
//! no I/O of its own beyond what the [`image`] crate provides — directory
//! traversal, parallelism, progress reporting, and logging configuration
//! all live in `despeckle-cli`.

// `unsafe_code` is denied everywhere except the memmap-based PBM reader
// in `io.rs`, which carries its own narrowly-scoped `allow` and the
// safety justification needed to call `memmap2::Mmap::map`.
#![deny(unsafe_code)]

mod classify;
mod components;
mod error;
mod io;
mod mask;
mod pipeline;
mod refine;

pub use crate::classify::{Decision, Thresholds, auto_thresholds, classify};
pub use crate::components::{BoundingBox, Component, LabelMap, Labelling, label};
pub use crate::error::DespeckleError;
pub use crate::io::{load_bitonal, save_bitonal};
pub use crate::mask::{ColumnBand, ColumnMask, build_column_mask};
pub use crate::pipeline::{ProcessOptions, ProcessResult, process_page, process_page_with};
pub use crate::refine::{fill_holes, smooth_edges};
