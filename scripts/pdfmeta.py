"""Shared PDF finishing for the to-pdf passes.

despeckle is image-in / image-out, so the repacked PDF should mirror the source
scan as closely as the new image data allows: same document metadata, same PDF
version, and — like most scan PDFs — linearized for Fast Web View.
"""
import pikepdf


def inherit_metadata(pdf, source_path):
    """Copy the source's Info dict and XMP packet into pdf; return its PDF version."""
    with pikepdf.open(source_path) as src:
        info = pdf.docinfo
        for key in list(info.keys()):
            del info[key]
        for key, value in src.docinfo.items():
            info[key] = pikepdf.String(str(value))
        src_xmp = src.Root.get("/Metadata")
        if src_xmp is not None:
            pdf.Root.Metadata = pdf.copy_foreign(src_xmp)
        return str(src.pdf_version)


def save_like_source(pdf, out_path, source_path=None):
    """Save linearized; inherit the source's metadata and PDF version when given."""
    save_kwargs = {"linearize": True}
    if source_path:
        save_kwargs["force_version"] = inherit_metadata(pdf, source_path)
    pdf.save(out_path, **save_kwargs)
