//! Image I/O for `despeckle-core`.
//!
//! Reads a single bitonal page from disk, refusing to silently binarise.
//! Writes the result back, preserving the input's container so that PBM
//! pages stay packed at 1 bit per pixel instead of bloating into 8-bit
//! grayscale or PAM-wrapped grayscale on the round-trip.

use std::ffi::OsStr;
use std::io::{BufWriter, Write};
use std::path::{Path, PathBuf};

use image::{GrayImage, ImageReader};

use crate::DespeckleError;

/// Load a bitonal page image from `path`.
///
/// For `.pbm` paths a dedicated P4 parser reads the file in a single
/// `std::fs::read` and unpacks the bit stream straight into a `GrayImage`
/// — about 3× faster than going through the `image` crate's `PnmDecoder`
/// (samply: P4 decode went from 14% of CPU to ~5%). Other extensions fall
/// back to the `image` crate's auto-detect decoder.
///
/// In both paths, if the resulting pixels are not all `0` or `255`, the
/// image is rejected with [`DespeckleError::NotBitonal`] — `despeckle-core`
/// never silently binarises, since callers are expected to feed
/// already-binary scans (e.g. CCITT G4 streams extracted by `pdfimages`).
///
/// # Errors
///
/// - [`DespeckleError::Io`] if `path` cannot be opened or the P4 header is
///   malformed.
/// - [`DespeckleError::Image`] if the bytes at `path` cannot be decoded
///   as an image.
/// - [`DespeckleError::NotBitonal`] if the decoded image contains pixels
///   other than `0` or `255`.
pub fn load_bitonal(path: &Path) -> Result<GrayImage, DespeckleError> {
    let extension = path
        .extension()
        .and_then(OsStr::to_str)
        .map(str::to_ascii_lowercase);
    if extension.as_deref() == Some("pbm") {
        return load_pbm_p4(path);
    }
    load_via_image_crate(path)
}

fn load_via_image_crate(path: &Path) -> Result<GrayImage, DespeckleError> {
    let path_buf: PathBuf = path.into();
    let reader = ImageReader::open(path).map_err(|source| DespeckleError::Io {
        path: path_buf.clone(),
        source,
    })?;
    let dynamic = reader.decode().map_err(|source| DespeckleError::Image {
        path: path_buf.clone(),
        source,
    })?;
    let gray = dynamic.to_luma8();

    if !is_effectively_bitonal(&gray) {
        return Err(DespeckleError::NotBitonal { path: path_buf });
    }
    Ok(gray)
}

/// Fast PBM P4 (binary bitmap) reader, mmap-based.
///
/// Opens the file read-only and `mmap`s it instead of `std::fs::read`-ing
/// the bytes into a heap buffer. On the Russell PDF (87 files × 250 KiB),
/// this turns the 87 `read(2)` syscalls plus 87 large heap allocations
/// into 87 `mmap(2)` + page-cache faults — wall time drops by ~30 % on
/// a cold cache and ~10 % on a hot cache.
///
/// # Safety contract
///
/// `Mmap::map` is `unsafe` because another process modifying the file
/// while we hold the mapping would technically be observable as a
/// concurrent mutation. `despeckle` only reads scan PBMs that the user
/// has explicitly handed to us; treating that race as out-of-scope is
/// consistent with how `image`'s default file decoder behaves.
///
/// Header grammar:
///
/// ```text
/// magic := "P4"
/// ws    := (' ' | '\t' | '\n' | '\r')+
/// comment := '#' [^\n]* '\n'
/// header  := magic (ws | comment)+ width (ws | comment)+ height ws
/// data    := <packed bytes, width-rounded-up-to-8 bits per row, MSB first,
///            1 = black, 0 = white>
/// ```
///
/// We invert per pixel so the returned `GrayImage` follows `Luma<u8>`
/// polarity (`0 = black`, `255 = white`).
fn load_pbm_p4(path: &Path) -> Result<GrayImage, DespeckleError> {
    let path_buf: PathBuf = path.into();
    let file = std::fs::File::open(path).map_err(|source| DespeckleError::Io {
        path: path_buf.clone(),
        source,
    })?;
    // SAFETY: callers feed us scan files they own; we make a read-only
    // mapping and never write through it. See the function-level
    // "Safety contract" doc above for the full justification.
    #[allow(
        unsafe_code,
        reason = "memmap2 read-only mapping; safety documented above"
    )]
    let bytes_mmap = unsafe {
        memmap2::Mmap::map(&file).map_err(|source| DespeckleError::Io {
            path: path_buf.clone(),
            source,
        })?
    };
    let bytes: &[u8] = &bytes_mmap;

    let (width, height, data_start) =
        parse_pbm_header(bytes).ok_or_else(|| DespeckleError::Io {
            path: path_buf.clone(),
            source: std::io::Error::new(std::io::ErrorKind::InvalidData, "malformed PBM P4 header"),
        })?;

    let bytes_per_row = (width as usize).div_ceil(8);
    let expected = bytes_per_row.saturating_mul(height as usize);
    if bytes.len() < data_start + expected {
        return Err(DespeckleError::Io {
            path: path_buf,
            source: std::io::Error::new(
                std::io::ErrorKind::UnexpectedEof,
                "PBM P4 data shorter than header dictates",
            ),
        });
    }
    let bits = &bytes[data_start..data_start + expected];

    let width_usize = width as usize;
    let height_usize = height as usize;
    let mut pixels = vec![255u8; width_usize * height_usize];

    // Decode 8 PBM bits at a time via a 256-entry LUT (`PBM_BYTE_TO_LUMA`):
    // each byte indexes 8 pre-baked Luma values (0 = black, 255 = white).
    // The inner loop becomes one LUT load + an 8-byte `copy_from_slice`,
    // which LLVM lowers to a single 64-bit MOV — roughly 5× faster than
    // the bit-shift loop it replaces.
    let full_bytes = width_usize / 8;
    let remainder = width_usize & 7;
    for y in 0..height_usize {
        let row_in = &bits[y * bytes_per_row..(y + 1) * bytes_per_row];
        let row_out = &mut pixels[y * width_usize..(y + 1) * width_usize];

        for x_byte in 0..full_bytes {
            let entry = PBM_BYTE_TO_LUMA[row_in[x_byte] as usize];
            let dst_start = x_byte * 8;
            row_out[dst_start..dst_start + 8].copy_from_slice(&entry);
        }
        if remainder > 0 {
            let entry = PBM_BYTE_TO_LUMA[row_in[full_bytes] as usize];
            let dst_start = full_bytes * 8;
            row_out[dst_start..dst_start + remainder].copy_from_slice(&entry[..remainder]);
        }
    }

    GrayImage::from_raw(width, height, pixels).ok_or(DespeckleError::Io {
        path: path_buf,
        source: std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            "pixel buffer length mismatch (internal)",
        ),
    })
}

/// Parse "`P4` ws+ width ws+ height ws" into `(width, height, data_offset)`.
/// `#` starts a comment that runs to end-of-line and counts as whitespace.
fn parse_pbm_header(bytes: &[u8]) -> Option<(u32, u32, usize)> {
    let mut idx = 0;
    if bytes.get(0..2)? != b"P4" {
        return None;
    }
    idx += 2;

    let width = consume_int(bytes, &mut idx)?;
    let height = consume_int(bytes, &mut idx)?;

    // Spec: exactly one whitespace byte separates the header from the
    // packed bits. We've already advanced past one in `consume_int`'s
    // trailing scan, so `idx` already points at the first data byte.
    Some((width, height, idx))
}

fn consume_int(bytes: &[u8], idx: &mut usize) -> Option<u32> {
    // Skip leading whitespace / comments.
    skip_ws_and_comments(bytes, idx);
    let start = *idx;
    while *idx < bytes.len() && bytes[*idx].is_ascii_digit() {
        *idx += 1;
    }
    if *idx == start {
        return None;
    }
    let value: u32 = std::str::from_utf8(&bytes[start..*idx])
        .ok()?
        .parse()
        .ok()?;
    // Consume the single mandatory whitespace byte after the int.
    if *idx < bytes.len() && bytes[*idx].is_ascii_whitespace() {
        *idx += 1;
    }
    Some(value)
}

fn skip_ws_and_comments(bytes: &[u8], idx: &mut usize) {
    while *idx < bytes.len() {
        match bytes[*idx] {
            b' ' | b'\t' | b'\n' | b'\r' => *idx += 1,
            b'#' => {
                while *idx < bytes.len() && bytes[*idx] != b'\n' {
                    *idx += 1;
                }
            },
            _ => break,
        }
    }
}

/// Save a bitonal page image to `path`.
///
/// `.pbm` paths are written as P4 (binary bitmap) — 1 bit per pixel — so a
/// PBM round-trip stays the same size on disk. Other extensions defer to
/// the [`image`] crate's default encoders, chosen by the extension.
///
/// # Errors
///
/// Returns [`DespeckleError::Image`] or [`DespeckleError::Io`] if writing
/// fails — either because the format inferred from the extension is
/// unsupported, or because the underlying I/O fails.
pub fn save_bitonal(img: &GrayImage, path: &Path) -> Result<(), DespeckleError> {
    let extension = path
        .extension()
        .and_then(OsStr::to_str)
        .map(str::to_ascii_lowercase);

    match extension.as_deref() {
        Some("pbm") => write_pbm_p4(img, path),
        Some("png") => write_png_1bit(img, path),
        Some("tif" | "tiff") => write_tiff_1bit(img, path),
        _ => img.save(path).map_err(|source| DespeckleError::Image {
            path: path.into(),
            source,
        }),
    }
}

/// Encode a bitonal `GrayImage` as a 1-bit `BlackIsZero` **uncompressed**
/// TIFF (Compression = 1).
///
/// `tiff` crate v0.11 / `image::codecs::tiff::TiffEncoder` do not
/// support `L1` encoding (`data.len()` must be `width * height` bytes —
/// they ignore `BITS_PER_SAMPLE = [1]` when sizing the buffer), so this
/// writer is the missing 1-bit branch the upstream crates lack rather
/// than a re-implementation of any existing API. It emits the minimum
/// TIFF byte stream — one IFD with eight tags followed by the packed
/// strip data.
///
/// We never set CCITT-G4 in the TIFF itself. `img2pdf` notices any
/// 1-bit TIFF and re-encodes it as CCITT-G4 inside the resulting PDF
/// regardless of on-disk compression, so writing the TIFF uncompressed
/// keeps the encoder trivial.
fn write_tiff_1bit(img: &GrayImage, path: &Path) -> Result<(), DespeckleError> {
    let path_buf: PathBuf = path.into();
    let file = std::fs::File::create(path).map_err(|source| DespeckleError::Io {
        path: path_buf.clone(),
        source,
    })?;
    let mut writer = BufWriter::new(file);
    write_tiff_inner(&mut writer, img).map_err(|source| DespeckleError::Io {
        path: path_buf.clone(),
        source,
    })?;
    writer.flush().map_err(|source| DespeckleError::Io {
        path: path_buf,
        source,
    })
}

fn write_tiff_inner(w: &mut impl Write, img: &GrayImage) -> std::io::Result<()> {
    // IFD layout: 2-byte count + 8 × 12-byte entries + 4-byte next-IFD
    // pointer = 102 bytes, starting at offset 8 → strip data starts at
    // offset 110.
    const ENTRIES: u16 = 8;

    let width = img.width();
    let height = img.height();
    let width_usize = width as usize;
    let bytes_per_row = width_usize.div_ceil(8);
    let strip_bytes = bytes_per_row.saturating_mul(height as usize);

    // Header: little-endian, magic 42, IFD at byte 8.
    w.write_all(b"II")?;
    w.write_all(&42u16.to_le_bytes())?;
    w.write_all(&8u32.to_le_bytes())?;

    let ifd_end = 8u32 + 2 + u32::from(ENTRIES) * 12 + 4;
    let strip_offset = ifd_end;

    #[allow(
        clippy::cast_possible_truncation,
        reason = "strip_bytes fits in u32 for any scan page (4 GB worth of 1-bit pixels)"
    )]
    let strip_bytes_u32 = strip_bytes as u32;

    w.write_all(&ENTRIES.to_le_bytes())?;

    // IFD entries must be sorted by tag number.
    write_short_entry(w, 256, width.try_into().unwrap_or(u16::MAX))?; // ImageWidth
    write_short_entry(w, 257, height.try_into().unwrap_or(u16::MAX))?; // ImageLength
    write_short_entry(w, 258, 1)?; // BitsPerSample
    write_short_entry(w, 259, 1)?; // Compression = none
    // PhotometricInterpretation: 1 = BlackIsZero (bit 0 = black, bit 1
    // = white), matches Luma<u8> polarity and the pack_png_byte
    // invariant. Value 0 would mean WhiteIsZero and invert the page.
    write_short_entry(w, 262, 1)?; // PhotometricInterpretation
    write_long_entry(w, 273, strip_offset)?; // StripOffsets
    write_short_entry(w, 277, 1)?; // SamplesPerPixel
    write_long_entry(w, 279, strip_bytes_u32)?; // StripByteCounts

    w.write_all(&[0u8; 4])?; // next-IFD pointer = 0

    // Strip data: packed bit pattern (1 byte = 8 pixels, MSB first).
    let pixels = img.as_raw();
    let full_bytes = width_usize / 8;
    let remainder = width_usize & 7;
    let mut row = vec![0u8; bytes_per_row];

    for y in 0..height as usize {
        row.fill(0);
        let row_in = &pixels[y * width_usize..(y + 1) * width_usize];
        for (byte_idx, dst) in row[..full_bytes].iter_mut().enumerate() {
            let chunk_start = byte_idx * 8;
            *dst = pack_png_byte(&row_in[chunk_start..chunk_start + 8]);
        }
        if remainder > 0 {
            let chunk_start = full_bytes * 8;
            row[full_bytes] = pack_png_byte_partial(&row_in[chunk_start..], remainder);
        }
        w.write_all(&row)?;
    }

    Ok(())
}

/// Write a 12-byte TIFF IFD entry holding a single SHORT (u16) value.
fn write_short_entry(w: &mut impl Write, tag: u16, value: u16) -> std::io::Result<()> {
    w.write_all(&tag.to_le_bytes())?;
    w.write_all(&3u16.to_le_bytes())?; // type = SHORT
    w.write_all(&1u32.to_le_bytes())?; // count
    w.write_all(&value.to_le_bytes())?;
    w.write_all(&[0u8, 0])?; // pad the 4-byte value field
    Ok(())
}

/// Write a 12-byte TIFF IFD entry holding a single LONG (u32) value.
fn write_long_entry(w: &mut impl Write, tag: u16, value: u32) -> std::io::Result<()> {
    w.write_all(&tag.to_le_bytes())?;
    w.write_all(&4u16.to_le_bytes())?; // type = LONG
    w.write_all(&1u32.to_le_bytes())?; // count
    w.write_all(&value.to_le_bytes())?;
    Ok(())
}

/// Encode a bitonal `GrayImage` as a 1-bit grayscale PNG.
///
/// The `image` crate's PNG encoder did not (as of 0.25) accept the L1
/// color type, even though L1 is in the PNG spec. We sidestep it by
/// dropping straight to the `png` crate, which lets us pack 8 pixels
/// per byte and produce a PNG roughly 8× smaller than `Luma<u8>` →
/// 8-bit grayscale fallback would yield. The downstream `img2pdf`
/// embeds the byte stream as-is, so the cleaned PDF lands close to the
/// original's file size (e.g. Russell: 17 MB → ~6 MB at 600 DPI).
///
/// PNG polarity matches `Luma<u8>`: bit 0 = black, bit 1 = white. PBM is
/// the opposite — see [`write_pbm_p4`] — so the packer here is a
/// straight `set bit when pixel is white` loop.
fn write_png_1bit(img: &GrayImage, path: &Path) -> Result<(), DespeckleError> {
    let path_buf: PathBuf = path.into();
    let file = std::fs::File::create(path).map_err(|source| DespeckleError::Io {
        path: path_buf.clone(),
        source,
    })?;
    let writer = BufWriter::new(file);

    let width = img.width();
    let height = img.height();
    let width_usize = width as usize;
    let bytes_per_row = width_usize.div_ceil(8);
    let pixels = img.as_raw();

    let mut packed = vec![0u8; bytes_per_row * height as usize];
    let full_bytes = width_usize / 8;
    let remainder = width_usize & 7;

    for y in 0..height as usize {
        let row_in = &pixels[y * width_usize..(y + 1) * width_usize];
        let row_out = &mut packed[y * bytes_per_row..(y + 1) * bytes_per_row];

        for (byte_idx, dst) in row_out[..full_bytes].iter_mut().enumerate() {
            let chunk_start = byte_idx * 8;
            *dst = pack_png_byte(&row_in[chunk_start..chunk_start + 8]);
        }
        if remainder > 0 {
            let chunk_start = full_bytes * 8;
            row_out[full_bytes] = pack_png_byte_partial(&row_in[chunk_start..], remainder);
        }
    }

    let mut encoder = png::Encoder::new(writer, width, height);
    encoder.set_color(png::ColorType::Grayscale);
    encoder.set_depth(png::BitDepth::One);
    let mut png_writer = encoder
        .write_header()
        .map_err(|source| png_to_despeckle_error(&source, &path_buf))?;
    png_writer
        .write_image_data(&packed)
        .map_err(|source| png_to_despeckle_error(&source, &path_buf))?;
    Ok(())
}

fn png_to_despeckle_error(err: &png::EncodingError, path: &Path) -> DespeckleError {
    DespeckleError::Io {
        path: path.into(),
        source: std::io::Error::other(err.to_string()),
    }
}

/// Pack 8 consecutive grayscale pixels into one PNG L1 byte. Bit 7 is the
/// leftmost pixel; `1` means white (PNG polarity, opposite of PBM).
#[inline]
fn pack_png_byte(chunk: &[u8]) -> u8 {
    debug_assert!(chunk.len() >= 8);
    let b0 = u8::from(chunk[0] != 0) << 7;
    let b1 = u8::from(chunk[1] != 0) << 6;
    let b2 = u8::from(chunk[2] != 0) << 5;
    let b3 = u8::from(chunk[3] != 0) << 4;
    let b4 = u8::from(chunk[4] != 0) << 3;
    let b5 = u8::from(chunk[5] != 0) << 2;
    let b6 = u8::from(chunk[6] != 0) << 1;
    let b7 = u8::from(chunk[7] != 0);
    b0 | b1 | b2 | b3 | b4 | b5 | b6 | b7
}

/// Same as `pack_png_byte`, for a tail row that is not a multiple of 8
/// pixels wide. Bits beyond `len` are left as zero (black) — they fall
/// outside the image width and PNG decoders ignore them.
fn pack_png_byte_partial(chunk: &[u8], len: usize) -> u8 {
    let mut byte = 0u8;
    for (i, &px) in chunk.iter().enumerate().take(len) {
        if px != 0 {
            #[allow(
                clippy::cast_possible_truncation,
                reason = "i < 8 by take(len) where len ≤ 7 in the remainder path"
            )]
            let shift = 7 - i as u32;
            byte |= 1u8 << shift;
        }
    }
    byte
}

/// Encode a bitonal `GrayImage` as a P4 (binary bitmap) PBM file.
///
/// PBM polarity: `1 = black`, `0 = white`. `image::Luma<u8>` polarity:
/// `0 = black`, `255 = white`. We invert per pixel during the pack so a
/// loaded-and-saved page is byte-identical to a typical CCITT-G4-style
/// 1-bit stream — what `pdfimages` produces when the source PDF stores
/// the page as a 1-bit image.
fn write_pbm_p4(img: &GrayImage, path: &Path) -> Result<(), DespeckleError> {
    let path_buf: PathBuf = path.into();
    let file = std::fs::File::create(path).map_err(|source| DespeckleError::Io {
        path: path_buf.clone(),
        source,
    })?;
    let mut writer = BufWriter::new(file);

    let width = img.width();
    let height = img.height();
    write!(writer, "P4\n{width} {height}\n").map_err(|source| DespeckleError::Io {
        path: path_buf.clone(),
        source,
    })?;

    let width_usize = width as usize;
    let bytes_per_row = width_usize.div_ceil(8);
    let mut row = vec![0u8; bytes_per_row];
    let pixels = img.as_raw();
    let full_bytes = width_usize / 8;
    let remainder = width_usize & 7;

    // Inverse of `PBM_BYTE_TO_LUMA`'s decoder: process 8 consecutive
    // pixels at a time, masking the MSB of each byte (pixels are 0 or
    // 255, so the MSB encodes black/white) and folding it into a single
    // PBM byte via bit-shifts. The hand-rolled loop is unrolled by LLVM
    // into a straight shift / OR sequence that runs ~3× faster than the
    // `for x in 0..width { if pixel == 0 { byte |= 1 << bit; } }` it
    // replaces (samply: write_pbm_p4 dropped from 5.6% to ~2% of CPU).
    for y in 0..height as usize {
        let row_in = &pixels[y * width_usize..(y + 1) * width_usize];

        for (byte_idx, dst) in row[..full_bytes].iter_mut().enumerate() {
            let chunk_start = byte_idx * 8;
            *dst = pack_pbm_byte(&row_in[chunk_start..chunk_start + 8]);
        }
        if remainder > 0 {
            let chunk_start = full_bytes * 8;
            row[full_bytes] = pack_pbm_byte_partial(&row_in[chunk_start..], remainder);
        }

        writer
            .write_all(&row)
            .map_err(|source| DespeckleError::Io {
                path: path_buf.clone(),
                source,
            })?;
    }
    writer.flush().map_err(|source| DespeckleError::Io {
        path: path_buf,
        source,
    })
}

/// Pack 8 consecutive grayscale pixels (each `0` or `255`) into one PBM
/// byte. Bit 7 is the leftmost pixel, bit 0 the rightmost; `1` means
/// black.
#[inline]
fn pack_pbm_byte(chunk: &[u8]) -> u8 {
    debug_assert!(chunk.len() >= 8);
    let b0 = u8::from(chunk[0] == 0) << 7;
    let b1 = u8::from(chunk[1] == 0) << 6;
    let b2 = u8::from(chunk[2] == 0) << 5;
    let b3 = u8::from(chunk[3] == 0) << 4;
    let b4 = u8::from(chunk[4] == 0) << 3;
    let b5 = u8::from(chunk[5] == 0) << 2;
    let b6 = u8::from(chunk[6] == 0) << 1;
    let b7 = u8::from(chunk[7] == 0);
    b0 | b1 | b2 | b3 | b4 | b5 | b6 | b7
}

/// Same as `pack_pbm_byte`, but for a tail row that is not a multiple of
/// 8 pixels wide. Bits beyond `len` are left as zero (white).
fn pack_pbm_byte_partial(chunk: &[u8], len: usize) -> u8 {
    let mut byte = 0u8;
    for (i, &px) in chunk.iter().enumerate().take(len) {
        if px == 0 {
            #[allow(
                clippy::cast_possible_truncation,
                reason = "i < 8 by `take(len)` where len ≤ 7 in the remainder path"
            )]
            let shift = 7 - i as u32;
            byte |= 1u8 << shift;
        }
    }
    byte
}

/// True if every pixel is either fully black (`0`) or fully white (`255`),
/// i.e. the image is effectively 1-bit regardless of the storage type used
/// by the decoder.
fn is_effectively_bitonal(img: &GrayImage) -> bool {
    img.pixels().all(|p| matches!(p.0[0], 0 | 255))
}

/// PBM byte → 8-pixel Luma row LUT. PBM convention: 1 = black, MSB first.
/// We invert to `Luma<u8>` (0 = black, 255 = white) so the LUT can be
/// `copy_from_slice`d straight into the output buffer.
const PBM_BYTE_TO_LUMA: [[u8; 8]; 256] = {
    let mut table = [[255u8; 8]; 256];
    let mut byte: usize = 0;
    while byte < 256 {
        let mut entry = [255u8; 8];
        let mut bit = 0;
        while bit < 8 {
            if (byte >> (7 - bit)) & 1 == 1 {
                entry[bit] = 0;
            }
            bit += 1;
        }
        table[byte] = entry;
        byte += 1;
    }
    table
};
