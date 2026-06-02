#!/usr/bin/env python3
"""Re-finish img2pdf's bare output to match the source scan.

This is the color path — used for the overlay PDFs, which JBIG2 cannot
represent. Cleaned bitonal pages go through jbig2-pdf.py instead.

img2pdf writes a minimal PDF whose only metadata is its own Producer and the
build date. Given a source PDF, that file's Info dictionary, XMP packet, and PDF
version are inherited; the result is linearized for Fast Web View either way.
Only metadata and the version header change; the page streams are preserved.

    finalize-pdf.py <input.pdf> <output.pdf> [source.pdf]
"""
import sys

import pikepdf

import pdfmeta


def main():
    if len(sys.argv) < 3:
        sys.exit("usage: finalize-pdf.py <input.pdf> <output.pdf> [source.pdf]")
    inp, out = sys.argv[1], sys.argv[2]
    source = sys.argv[3] if len(sys.argv) > 3 and sys.argv[3] else None

    with pikepdf.open(inp) as pdf:
        pdfmeta.save_like_source(pdf, out, source)

    note = f", metadata + version from {source}" if source else ""
    print(f"wrote {out}{note}")


if __name__ == "__main__":
    main()
