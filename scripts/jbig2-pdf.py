#!/usr/bin/env python3
"""Pack a directory of cleaned bitonal pages into a lossless-JBIG2 PDF.

JBIG2 packs bitonal text far tighter than the CCITT G4 img2pdf would use, but
its dramatic gains come from *lossy* symbol substitution. despeckle is a
fidelity tool, so this only ever uses jbig2enc's generic region coding (no
`-s`), which is bit-exact — verified by round-tripping the result back through
pdfimages.

    jbig2-pdf.py <image_dir> <out.pdf> [source.pdf] [dpi]

The optional source PDF's metadata is inherited; the optional dpi (else the
image's own tag, else 300) sets the physical page size.
"""
import concurrent.futures
import glob
import os
import subprocess
import sys

import pikepdf
from PIL import Image
from pikepdf import Dictionary, Name, Stream

import pdfmeta


def encode_page(path):
    """Lossless generic-region JBIG2 stream for one page, ready to embed."""
    # -p: emit a PDF-ready embedded stream. No -s: symbol mode is lossy.
    return subprocess.run(
        ["jbig2", "-p", path], check=True, capture_output=True
    ).stdout


def page_dpi(im, override):
    if override:
        return override
    tag = im.info.get("dpi")
    dpi = float(tag[0]) if tag else 0.0
    return dpi if dpi > 0 else 300.0


def encode_one(path, dpi_override):
    """Geometry + lossless JBIG2 for one page — the unit of parallel work.

    Image.open reads only the TIFF header (size/dpi tags); no im.load(), so it is
    cheap and each thread holds its own handle. The heavy part, the `jbig2`
    subprocess, runs outside the GIL, so threads give real parallelism here.
    """
    with Image.open(path) as im:
        width, height = im.size
        dpi = page_dpi(im, dpi_override)
    return encode_page(path), width, height, dpi


def workers():
    """Encode concurrency: DESPECKLE_JOBS if set, else all cores (cf. the Java --jobs)."""
    requested = os.environ.get("DESPECKLE_JOBS")
    if requested and requested.isdigit() and int(requested) > 0:
        return int(requested)
    return os.cpu_count() or 1


def main():
    if len(sys.argv) < 3:
        sys.exit("usage: jbig2-pdf.py <image_dir> <out.pdf> [source.pdf] [dpi]")
    image_dir, out = sys.argv[1], sys.argv[2]
    source = sys.argv[3] if len(sys.argv) > 3 and sys.argv[3] else None
    dpi_override = float(sys.argv[4]) if len(sys.argv) > 4 and sys.argv[4] else None

    pages = [p for p in sorted(glob.glob(os.path.join(image_dir, "*"))) if os.path.isfile(p)]
    if not pages:
        sys.exit(f"no images found in {image_dir}")

    # Encode every page in parallel — jbig2 runs outside the GIL — then assemble the
    # PDF single-threaded in page order (pikepdf.Pdf is not safe for concurrent
    # mutation). pool.map preserves input order, so page order is guaranteed and a
    # failing page re-raises its CalledProcessError here, aborting as before.
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers()) as pool:
        encoded = list(pool.map(lambda p: encode_one(p, dpi_override), pages))

    pdf = pikepdf.Pdf.new()
    for blob, width, height, dpi in encoded:
        image = Stream(pdf, blob)
        image.Type = Name.XObject
        image.Subtype = Name.Image
        image.Width = width
        image.Height = height
        image.ColorSpace = Name.DeviceGray
        image.BitsPerComponent = 1
        image.Filter = Name.JBIG2Decode
        w_pt = round(width / dpi * 72, 4)
        h_pt = round(height / dpi * 72, 4)
        page = pdf.add_blank_page(page_size=(w_pt, h_pt))
        page.Contents = pdf.make_stream(f"q {w_pt} 0 0 {h_pt} 0 0 cm /Im0 Do Q".encode())
        page.Resources = Dictionary(XObject=Dictionary(Im0=image))

    pdfmeta.save_like_source(pdf, out, source)
    extra = f", metadata + version from {source}" if source else ""
    print(f"wrote {out}: {len(pages)} page(s), lossless JBIG2{extra}")


if __name__ == "__main__":
    main()
