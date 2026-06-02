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


def main():
    if len(sys.argv) != 3:
        sys.exit("usage: stamp-dpi.py <source.pdf> <tiff_dir>")
    pdf, tiff_dir = sys.argv[1], sys.argv[2]

    from PIL import Image  # deferred: only the stamping path needs Pillow

    ppi = dominant_ppi(pdf)
    pages = sorted(glob.glob(os.path.join(tiff_dir, "*.tif")))
    for path in pages:
        with Image.open(path) as im:
            im.load()
            im.save(path, dpi=(ppi, ppi))
    print(f"stamped {len(pages)} TIFF page(s) in {tiff_dir} @ {ppi} dpi")


if __name__ == "__main__":
    main()
