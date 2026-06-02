#!/usr/bin/env python3
"""Turn img2pdf's bare output into a proper PDF: version 1.7, inherited metadata.

img2pdf writes a minimal PDF 1.3 whose only metadata is its own Producer and the
current date. This rewrites it to PDF 1.7 and, when a source PDF is given, copies
that file's document Info dictionary and XMP packet across verbatim, so the
cleaned book keeps the original's title, author, dates, etc.

Only metadata and the version header change; the page content streams are
preserved.

    finalize-pdf.py <img2pdf.pdf> <output.pdf> [source.pdf]
"""
import sys

import pikepdf


def main():
    if len(sys.argv) < 3:
        sys.exit("usage: finalize-pdf.py <input.pdf> <output.pdf> [source.pdf]")
    inp, out = sys.argv[1], sys.argv[2]
    source = sys.argv[3] if len(sys.argv) > 3 and sys.argv[3] else None

    with pikepdf.open(inp) as pdf:
        if source:
            with pikepdf.open(source) as src:
                # Inherit the document Info dictionary verbatim.
                info = pdf.docinfo
                for key in list(info.keys()):
                    del info[key]
                for key, value in src.docinfo.items():
                    info[key] = pikepdf.String(str(value))
                # Inherit the XMP metadata packet, if the source carries one.
                src_xmp = src.Root.get("/Metadata")
                if src_xmp is not None:
                    pdf.Root.Metadata = pdf.copy_foreign(src_xmp)
                note = f" with inherited metadata from {source}"
        else:
            note = ""

        pdf.save(out, force_version="1.7")
    print(f"wrote {out} as PDF 1.7{note}")


if __name__ == "__main__":
    main()
