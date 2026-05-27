//! Error type produced by the `despeckle-core` API.
//!
//! Boundary callers (the CLI, tests, future GUIs) translate this into their
//! own ergonomics; we keep it a `thiserror` enum to stay structured.

use std::io;
use std::path::PathBuf;

/// Errors that can occur while processing a page.
#[derive(Debug, thiserror::Error)]
#[non_exhaustive]
pub enum DespeckleError {
    /// I/O failure while reading or writing a page image.
    #[error("io error at {path}: {source}")]
    Io {
        /// The path being read or written when the error occurred.
        path: PathBuf,
        /// The underlying I/O error.
        #[source]
        source: io::Error,
    },

    /// The input page is not a 1-bit bitonal image. `despeckle-core` refuses
    /// to silently binarise — the caller is expected to feed already-binary
    /// scans (e.g. CCITT G4 extracted by `pdfimages`).
    #[error("input image is not bitonal: {path}")]
    NotBitonal {
        /// The path that contains the non-bitonal image.
        path: PathBuf,
    },

    /// Error returned by the `image` crate while decoding or encoding.
    #[error("image error at {path}: {source}")]
    Image {
        /// The path being read or written when the error occurred.
        path: PathBuf,
        /// The underlying error from the `image` crate.
        #[source]
        source: image::ImageError,
    },
}
