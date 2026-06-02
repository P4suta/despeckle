#!/usr/bin/env python3
"""Pack a directory of cleaned bitonal pages into a lossless-JBIG2 PDF 1.7.

JBIG2 packs bitonal text far tighter than the CCITT G4 img2pdf would use, but
its dramatic gains come from *lossy* symbol substitution. despeckle is a
fidelity tool, so this only ever uses jbig2enc's generic region coding (no
`-s`), which is bit-exact — verified by round-tripping the result back through
pdfimages.

    jbig2-pdf.py <image_dir> <out.pdf> [source.pdf] [dpi]

The optional source PDF's metadata is inherited; the optional dpi (else the
image's own tag, else 300) sets the physical page size.
"""
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


def main():
    if len(sys.argv) < 3:
        sys.exit("usage: jbig2-pdf.py <image_dir> <out.pdf> [source.pdf] [dpi]")
    image_dir, out = sys.argv[1], sys.argv[2]
    source = sys.argv[3] if len(sys.argv) > 3 and sys.argv[3] else None
    dpi_override = float(sys.argv[4]) if len(sys.argv) > 4 and sys.argv[4] else None

    pages = [p for p in sorted(glob.glob(os.path.join(image_dir, "*"))) if os.path.isfile(p)]
    if not pages:
        sys.exit(f"no images found in {image_dir}")

    pdf = pikepdf.Pdf.new()
    for path in pages:
        with Image.open(path) as im:
            width, height = im.size
            dpi = page_dpi(im, dpi_override)
        image = Stream(pdf, encode_page(path))
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

    pdfmeta.save_pdf17(pdf, out, source)
    extra = f", metadata from {source}" if source else ""
    print(f"wrote {out}: {len(pages)} page(s), lossless JBIG2, PDF 1.7{extra}")


if __name__ == "__main__":
    main()
