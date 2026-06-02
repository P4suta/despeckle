#!/usr/bin/env python3
"""Rewrite img2pdf's bare output to PDF 1.7 with the source's metadata.

This is the color path — used for the overlay PDFs, which JBIG2 cannot
represent. Cleaned bitonal pages go through jbig2-pdf.py instead.

img2pdf writes a minimal PDF 1.3 whose only metadata is its own Producer and the
build date. Given a source PDF, that file's document Info dictionary and XMP
packet are copied across verbatim. Only metadata and the version header change;
the page content streams are preserved.

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
        pdfmeta.save_pdf17(pdf, out, source)

    note = f" with inherited metadata from {source}" if source else ""
    print(f"wrote {out} as PDF 1.7{note}")


if __name__ == "__main__":
    main()
