"""Shared PDF metadata helpers for the to-pdf finalize passes.

Both the img2pdf path (finalize-pdf.py) and the JBIG2 path (jbig2-pdf.py) want
the same finish: inherit the original scan's metadata verbatim and write a
proper PDF 1.7.
"""
import pikepdf


def inherit_metadata(pdf, source_path):
    """Copy the document Info dictionary and XMP packet from source_path verbatim."""
    with pikepdf.open(source_path) as src:
        info = pdf.docinfo
        for key in list(info.keys()):
            del info[key]
        for key, value in src.docinfo.items():
            info[key] = pikepdf.String(str(value))
        src_xmp = src.Root.get("/Metadata")
        if src_xmp is not None:
            pdf.Root.Metadata = pdf.copy_foreign(src_xmp)


def save_pdf17(pdf, out_path, source_path=None):
    """Inherit metadata from source_path (if given) and save as PDF 1.7."""
    if source_path:
        inherit_metadata(pdf, source_path)
    pdf.save(out_path, force_version="1.7")
