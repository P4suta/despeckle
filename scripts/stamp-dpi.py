#!/usr/bin/env python3
"""Stamp the true scan resolution onto extracted TIFF pages.

`pdfimages -tiff` decodes the embedded 1-bit images faithfully but writes a
default 72-dpi resolution tag — the real resolution lives only in the PDF page
geometry (image pixels / page inches), which `pdfimages -list` reports as
x-ppi. This reads that, picks the dominant value across the book, and writes it
into every page's TIFF resolution tag so the despeckler can size its filter from
the image alone (no --dpi needed) and the cleaned output stays correctly tagged.

Tag-only intent: pixels are never resampled or re-thresholded — only the
XResolution/YResolution tags change.

    stamp-dpi.py <source.pdf> <tiff_dir>   # prints the dpi it stamped
"""
import collections
import glob
import os
import subprocess
import sys

DEFAULT_DPI = 300


def dominant_ppi(pdf):
    """The most common rounded x-ppi across the PDF's images, or DEFAULT_DPI."""
    listing = subprocess.run(
        ["pdfimages", "-list", pdf],
        capture_output=True,
        text=True,
        check=False,
    ).stdout
    counts = collections.Counter()
    for line in listing.splitlines()[2:]:  # skip the two header rows
        fields = line.split()
        if len(fields) < 13 or fields[2] != "image":
            continue
        try:
            counts[round(float(fields[12]))] += 1  # x-ppi column
        except ValueError:
            continue
    if not counts:
        return DEFAULT_DPI
    ppi = counts.most_common(1)[0][0]
    return ppi if ppi > 0 else DEFAULT_DPI


def stamp_exiftool(pages, ppi):
    """Set the TIFF resolution tags in place — no pixel re-encode (the fast path).

    Rewrites only the IFD tags (XResolution/YResolution/ResolutionUnit), never the
    pixel strips. ResolutionUnit=inches → the TIFF unit enum 2 that PIL's dpi= also
    writes, so Leptonica reads back the same value. One exiftool process stamps the
    whole book.
    """
    subprocess.run(
        [
            "exiftool",
            "-overwrite_original",  # no *_original sidecars (would double disk)
            "-q",
            f"-XResolution={ppi}",
            f"-YResolution={ppi}",
            "-ResolutionUnit=inches",
            *pages,
        ],
        check=True,
    )


def stamp_pil(pages, ppi):
    """Fallback (DESPECKLE_STAMP=pil): rewrite each TIFF via Pillow.

    Re-encodes the pixels, but is the long-proven path if a libtiff build ever fails
    to read exiftool's RATIONAL resolution tags.
    """
    from PIL import Image  # deferred: only this path needs Pillow

    for path in pages:
        with Image.open(path) as im:
            im.load()
            im.save(path, dpi=(ppi, ppi))


def main():
    if len(sys.argv) != 3:
        sys.exit("usage: stamp-dpi.py <source.pdf> <tiff_dir>")
    pdf, tiff_dir = sys.argv[1], sys.argv[2]

    ppi = dominant_ppi(pdf)
    pages = sorted(glob.glob(os.path.join(tiff_dir, "*.tif")))
    if not pages:
        print(f"no TIFF pages in {tiff_dir}")
        return
    if os.environ.get("DESPECKLE_STAMP") == "pil":
        stamp_pil(pages, ppi)
    else:
        stamp_exiftool(pages, ppi)
    print(f"stamped {len(pages)} TIFF page(s) in {tiff_dir} @ {ppi} dpi")


if __name__ == "__main__":
    main()
