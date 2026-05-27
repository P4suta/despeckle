//! Optional before / overlay / after report writer.
//!
//! When the user passes `--report <DIR>`, every processed page is also
//! rendered as three side-by-side ONGs (before, removed-pixels-in-red,
//! after) and listed in an `index.html`. This is post-hoc inspection,
//! not interactive tuning — the algorithm runs the same with or without
//! it.

use std::fmt::Write as _;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

use anyhow::{Context, Result};
use image::{GrayImage, Rgb, RgbImage};

/// One entry in the HTML index — the relative paths of the three ONGs
/// and the per-page component-removal count.
struct PageEntry {
    relative_stem: PathBuf,
    components_removed: usize,
}

/// Collects per-page artifacts into `<out_dir>/{before,overlay,after}/`
/// and writes `<out_dir>/index.html` on `finish`.
pub(crate) struct Report {
    out_dir: PathBuf,
    pages: Mutex<Vec<PageEntry>>,
}

impl Report {
    pub(crate) fn new(out_dir: PathBuf) -> Result<Self> {
        for sub in ["before", "overlay", "after"] {
            fs::create_dir_all(out_dir.join(sub))
                .with_context(|| format!("failed to create {}/{sub}", out_dir.display()))?;
        }
        Ok(Self {
            out_dir,
            pages: Mutex::new(Vec::new()),
        })
    }

    pub(crate) fn add_page(
        &self,
        relative_stem: &Path,
        before: &GrayImage,
        after: &GrayImage,
        components_removed: usize,
    ) -> Result<()> {
        save_gray_as_png(before, &self.png_path("before", relative_stem))?;
        save_gray_as_png(after, &self.png_path("after", relative_stem))?;
        save_overlay_as_png(before, after, &self.png_path("overlay", relative_stem))?;

        self.pages
            .lock()
            .map_err(|err| anyhow::anyhow!("report mutex poisoned: {err}"))?
            .push(PageEntry {
                relative_stem: relative_stem.into(),
                components_removed,
            });
        Ok(())
    }

    pub(crate) fn finish(self, total_removed: usize) -> Result<()> {
        let mut pages = self
            .pages
            .into_inner()
            .map_err(|err| anyhow::anyhow!("report mutex poisoned: {err}"))?;
        pages.sort_by(|a, b| a.relative_stem.cmp(&b.relative_stem));

        let html = render_index(&pages, total_removed);
        let index_path = self.out_dir.join("index.html");
        fs::write(&index_path, html)
            .with_context(|| format!("failed to write {}", index_path.display()))?;
        Ok(())
    }

    fn png_path(&self, panel: &str, relative_stem: &Path) -> PathBuf {
        let mut p = self.out_dir.join(panel).join(relative_stem);
        p.set_extension("png");
        p
    }
}

fn save_gray_as_png(img: &GrayImage, path: &Path) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .with_context(|| format!("failed to create {}", parent.display()))?;
    }
    img.save(path)
        .with_context(|| format!("failed to write {}", path.display()))?;
    Ok(())
}

fn save_overlay_as_png(before: &GrayImage, after: &GrayImage, path: &Path) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .with_context(|| format!("failed to create {}", parent.display()))?;
    }
    let overlay = render_overlay(before, after);
    overlay
        .save(path)
        .with_context(|| format!("failed to write {}", path.display()))?;
    Ok(())
}

/// Render an RGB dry-run preview: `before` in grayscale, with every
/// pixel that despeckle would remove (black-in-`before` →
/// white-in-`after`) highlighted in red. Eyeballing this image answers
/// "what *would* despeckle delete from this page?" — the input is still
/// visible underneath, so you can see whether a red splotch is
/// genuinely a speckle or a ruby stroke being eaten.
fn render_overlay(before: &GrayImage, after: &GrayImage) -> RgbImage {
    let width = before.width().min(after.width());
    let height = before.height().min(after.height());
    let mut out = RgbImage::new(width, height);
    for y in 0..height {
        for x in 0..width {
            let before_px = before.get_pixel(x, y).0[0];
            let after_px = after.get_pixel(x, y).0[0];
            let rgb = if before_px == 0 && after_px == 255 {
                Rgb([255, 0, 0])
            } else {
                let g = before_px;
                Rgb([g, g, g])
            };
            out.put_pixel(x, y, rgb);
        }
    }
    out
}

fn render_index(pages: &[PageEntry], total_removed: usize) -> String {
    let mut html = String::new();
    html.push_str(
        "<!doctype html><html lang=\"ja\"><head><meta charset=\"utf-8\">\
         <title>despeckle report</title>\
         <style>\
         body{font-family:system-ui,sans-serif;margin:2rem;background:#111;color:#eee}\
         h1{font-size:1.2rem}\
         table{border-collapse:collapse;width:100%}\
         th,td{padding:.4rem .6rem;border-bottom:1px solid #333;vertical-align:top}\
         th{text-align:left;font-weight:600;color:#aaa}\
         .panels{display:grid;grid-template-columns:repeat(3,1fr);gap:.4rem;margin-top:.4rem}\
         .panels img{width:100%;height:auto;background:#fff}\
         .panels figcaption{font-size:.75rem;color:#888;text-align:center}\
         .stem{font-family:ui-monospace,monospace;font-size:.9rem}\
         </style></head><body>",
    );
    let _ = write!(
        html,
        "<h1>despeckle report &mdash; {} page{}, {} component{} removed</h1>",
        pages.len(),
        plural(pages.len()),
        total_removed,
        plural(total_removed),
    );
    html.push_str("<table>");
    html.push_str("<tr><th>page</th><th>removed</th><th>before / overlay / after</th></tr>");
    for page in pages {
        let stem = page.relative_stem.display();
        let _ = write!(
            html,
            "<tr><td class=\"stem\">{stem}</td><td>{removed}</td><td><div class=\"panels\">\
             <figure><img src=\"before/{stem}.png\" loading=\"lazy\"><figcaption>before</figcaption></figure>\
             <figure><img src=\"overlay/{stem}.png\" loading=\"lazy\"><figcaption>overlay</figcaption></figure>\
             <figure><img src=\"after/{stem}.png\" loading=\"lazy\"><figcaption>after</figcaption></figure>\
             </div></td></tr>",
            removed = page.components_removed,
        );
    }
    html.push_str("</table></body></html>");
    html
}

const fn plural(n: usize) -> &'static str {
    if n == 1 { "" } else { "s" }
}
