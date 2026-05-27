# despeckle

Automatic dust / speckle removal for bitonal Japanese-novel scans.

`despeckle` is the first piece of a Rust pipeline that post-processes
self-scanned PDF books so every page looks visually aligned. This tool
focuses on the hardest single operation: removing the random pepper-noise
produced by the scanner while protecting fragile typography — **ruby
(振り仮名), 句読点 (「。」「、」), and dakuten/handakuten (「゛」「゜」)** —
that a naive size-threshold filter would also erase.

## Boundaries

- **Image in / image out, never PDF.** Pair with `pdftoppm -mono` on the
  way in and an image-→-PDF re-packer on the way out (separate tools).
- **Novels only, vertical typesetting.** The column-mask heuristic
  assumes vertical Japanese text. Tables, figures, mixed layouts are out
  of scope.
- **No tuning UI.** Per-page thresholds are derived automatically; the
  goal is one-shot "feels right" output, not a knob to turn.
- **`.pbm` round-trip is byte-identical.** Input `.pbm` (P4) → output
  `.pbm` (P4) preserves the 1-bit packing so file size never bloats.

## How it works (one page)

```
load → label (4-conn CCL) → build column mask (vertical projection + Otsu)
     → auto thresholds (speckle peak / percentile fallback)
     → classify (in-column vs gutter, area + isolation)
     → render (paint removed components white)
```

Every step is a pure function in `despeckle-core`; the binary
(`despeckle-cli`) handles directory walking, rayon parallelism, the
indicatif progress bar, and the optional HTML report.

## Quick start

```sh
just bootstrap              # build dev container, install git hooks
just build                  # cargo build inside the container
just test                   # cargo nextest + doctests
just run input output       # despeckle input → output
just run-sample             # process samples/ → artifacts/sample-out + HTML report
```

Or, given a real PDF, expand it with poppler-utils first:

```sh
mkdir -p scans/mybook
pdftoppm -mono -r 300 mybook.pdf scans/mybook/page
despeckle scans/mybook out/mybook --force --report report/mybook
open report/mybook/index.html
```

## CLI

```
despeckle <INPUT_DIR> <OUTPUT_DIR>
  [--report <DIR>]        # write before/overlay/after ONGs and index.html
  [--jobs <N>]            # rayon thread count (default: logical CPUs)
  [--format pbm|png|same] # output extension (default: same as input)
  [--glob <PATTERN>]      # default: "*.{pbm,png,tiff,tif}"
  [--force]               # overwrite a non-empty output directory
  [-v / -q]               # log level
```

Set `DESPECKLE_LOG=debug` for verbose tracing output.

## Performance

Per-page micro-benchmark on a 1158 × 1732 (~2 Mpx) Russell-『哲学入門』
scan, release profile (`lto = "fat"`, `codegen-units = 1`, mimalloc
allocator), single-threaded:

| step                | time   | share |
| ------------------- | -----: | ----: |
| `label` (CCL)       | 4.0 ms |   58% |
| `paint` (remove)    | ~1 ms  |   14% |
| `build_column_mask` | 0.3 ms |    4% |
| `classify`          | 0.2 ms |    3% |
| **`process_page`**  | **6.9 ms** | 100% |

End-to-end on the 87-page Russell PDF (lock-free `AtomicUsize` work
queue across logical CPUs, mmap-based PBM reader, branch-free paint):
**~0.24 s wall** (`cargo run --release`), file size 21 MB → 21 MB
byte-for-byte preserved on the PBM round-trip.

### Optimization journey

Starting from a naive `imageproc::region_labelling` + `HashSet` paint
implementation at 44.6 ms / page and 1.24 s wall:

| step                                          | per-page  | wall   | delta (page) |
| --------------------------------------------- | --------: | -----: | -----------: |
| baseline                                      |  44.6 ms  | 1.24 s |            — |
| `HashMap` → dense `Vec<Accumulator>` index    |  26.4 ms  |        |        -41 % |
| paint via bitset LUT on raw label slice       |  23.5 ms  |        |        -11 % |
| mimalloc global allocator                     |  13.7 ms  | 0.83 s |        -42 % |
| custom 4-conn union-find CCL (drop imageproc) |   9.7 ms  |        |        -29 % |
| `project_x` branch-free `+=` on raw slices    |   7.4 ms  |        |        -24 % |
| pass-1 carry left-label in a local            |   6.7 ms  | 0.52 s |        -10 % |
| mmap PBM reader + LUT bit-unpack              |   6.9 ms  | 0.28 s |    — / -46 % |
| LUT bit-pack writer                           |   6.9 ms  | 0.27 s |    — / -4 %  |
| `kiddo` KD-tree replaces O(n²) neighbor scan |   6.2 ms  |        |         -9 % |
| `std::thread::scope` + `AtomicUsize` queue    |   6.9 ms  | 0.24 s |    — / -11 % |

Every step was verified by `cargo test` (9 unit + 2 proptest cases) and
a samply flame graph (`just flame` → `artifacts/flame.svg`) before being
kept. See `crates/despeckle-core/benches/process_page.rs` for the
benchmark itself.

### Profiling

The profiling toolchain is automated through `justfile` recipes (host
only — `perf_event_open(2)` is Docker-blocked):

```sh
# one-time, persists across boots
echo 'kernel.perf_event_paranoid=1' | sudo tee /etc/sysctl.d/99-perf.conf
sudo sysctl --system

just bench             # criterion HTML reports → target/criterion/
just flame             # cargo flamegraph SVG → artifacts/flame.svg
just profile           # samply record → opens in Firefox Profiler
just profile-summary   # samply + tools/samply_top.py top-N → artifacts/profile-summary.md
```

`tools/samply_top.py` parses the saved Gecko-profile JSON, resolves
every frame address through `addr2line`, and emits a Markdown table of
top self- and inclusive-time symbols. Use the `release-perf` profile
(line-tables-only) for any address-level work — the default `release`
profile strips symbols.

## Algorithm caveats

- The body / margin distinction relies on a simple x-projection cutoff.
  Page headers (柱), folios (ノンブル), chapter titles outside the
  column band are treated as gutter and **may** be removed if small
  enough. Real scans of novels usually keep these features well above
  the gutter cutoff, but it is a known v1 limitation.
- The column mask is built per page; pages with figures or tables are
  not the target.
- A page with fewer than 8 connected components is left untouched
  (e.g. blank pages, the cover, a single solid mark). The dust
  heuristic needs a histogram of components to work; on a degenerate
  page it would otherwise classify the only component as dust.

## License

Dual-licensed under either of

- MIT license ([LICENSE-MIT](LICENSE-MIT))
- Apache License 2.0 ([LICENSE-APACHE](LICENSE-APACHE))

at your option.
