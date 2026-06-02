# despeckle

Automatic dust / speckle removal for bitonal Japanese-novel scans.

`despeckle` post-processes self-scanned PDF books so every page looks
clean: it removes the random pepper-noise a scanner sprinkles across the
page while protecting fragile typography — **ruby (振り仮名), 句読点
(「。」「、」), and dakuten/handakuten (「゛」「゜」)** — that a naive
size filter would also erase.

It is a thin, careful wrapper around [Leptonica](http://www.leptonica.org/)'s
`pixSelectBySize`, called through the JDK Foreign Function & Memory API.
The image science is Leptonica's; despeckle supplies the conservative,
DPI-aware policy, the directory/parallel driver, and the inspection report.

## Boundaries

- **Image in / image out, never PDF.** Pair with `pdfimages` on the way in
  and, on the way out, the `just to-pdf` recipe — which repacks the cleaned
  pages as **lossless JBIG2** (smaller than the source scan, bit-exact),
  emits PDF 1.7, and inherits the original's metadata. All tooling is
  bundled in the dev image.
- **Dust removal only.** Deskew, margin-cropping and contrast are out of
  scope.
- **Conservative by design.** A connected component survives if its
  bounding box is larger than the speck size in *either* width or height,
  so punctuation, ruby and even a thin vertical stroke are kept; only
  things tiny on *both* axes (scanner dust) are dropped.

## How it works (one page)

```
read (Leptonica) → keep components larger than k, 8-connected
                 → optionally fill pin-holes (invert → same filter → invert)
                 → write (Leptonica)
```

`k` (the speck size) defaults to `dpi / 100` — about 3 px at 300 dpi, 6 px
at 600. The resolution is read from each page's own tag when `--dpi` is
omitted (TIFFs extracted by `just extract` are stamped with the scan's true
ppi), so a 600-dpi book needs no flag; the resolution honored is written back
onto the cleaned output. The PBM round-trip is pixel-identical: a page with no
specks comes back unchanged.

## Quick start

Everything runs inside the dev container, so the host only needs Docker:

```sh
just bootstrap     # build/pull the dev image, install git hooks
just build         # ./gradlew build (compile, format, static analysis, tests)
just test          # JUnit suite
just run-sample    # process samples/ → artifacts/sample-out + HTML report
```

Given a real scan PDF:

```sh
just extract mybook.pdf scans/mybook          # pdftoppm -mono -r 300
just run scans/mybook out/mybook --report report/mybook --force
just to-pdf out/mybook out/mybook.pdf mybook.pdf  # PDF 1.7, inherits source metadata
```

## CLI

```
despeckle <INPUT_DIR> <OUTPUT_DIR>
  [--report <DIR>]         # before/overlay/after ONGs + index.html
  [--jobs <N>]             # worker threads (default: CPUs)
  [--format pbm|png|same]  # output format (default: same as input)
  [--glob <PATTERN>]       # default: "*.{pbm,png,tiff,tif}"
  [--force]                # overwrite a non-empty output directory
  [--dpi <N>]              # scan resolution, sizes the filter
                           #   (default: each page's embedded resolution, else 300)
  [--speck-size <PX>]      # override the speck size directly
  [--[no-]fill-holes]      # fill pin-holes inside strokes (default: on)
  [--remove-isolated-dust] # also drop isolated specks on clean background
  [--isolated-dust-size <PX>] # max isolated-speck size; implies the above
                           #   (default: dpi/40, ~15 px at 600 dpi)
```

The report's overlay paints every removed pixel red over the original
page, so you can confirm at a glance that only dust was taken.

### Isolated-dust pass

The base filter only drops specks tiny on *both* axes, so a medium speck
that is still smaller than a glyph survives — visible on an otherwise
clean margin. `--remove-isolated-dust` adds a second pass that removes
those, but **only where they are isolated**: a speck within
`isolated-dust-size + speck-size` pixels of real text is kept. Punctuation,
dakuten and ruby always hug a glyph, so they fall inside that neighborhood
and are never removed; only specks out on clean background are. This makes
the pass safe to run far more aggressively than a global size bump, which
would eat dakuten. It is opt-in; the overlay shows exactly what it took.

## Architecture

A single Gradle module under `io.github.p4suta.despeckle`:

| package   | role                                                          |
| --------- | ------------------------------------------------------------- |
| `core`    | `Leptonica` (the one FFM binding island), `Pix` (RAII handle), `Despeckler` (the pipeline) |
| `runner`  | directory walk, fixed thread pool, over-removal guardrail     |
| `report`  | before/overlay/after ONGs + `index.html`                      |
| `cli`     | picocli front end                                             |

`core` performs no directory or thread work, so a future GUI can reuse it
unchanged.

## Requirements

- **Leptonica** (`liblept.so`) at run time — the dev image installs
  `libleptonica-dev`. Override the resolved path with
  `-Ddespeckle.leptonica.path=/path/to/liblept.so` if needed.
- **JDK 25** (FFM is final since JDK 22; the build pins the 25 toolchain).
- Run with `--enable-native-access=ALL-UNNAMED` — the `application`,
  `test` and `run` tasks already pass it.

## Quality gates

`./gradlew build` runs the full gate, mirrored by CI:

- **Spotless** + google-java-format (AOSP, 100 columns)
- **Error Prone** on every compile, `-Werror`
- **SpotBugs** at max effort
- **JUnit** — FFM smoke, pixel-identical round-trip, the polarity /
  connectivity pin (a tall thin stroke is kept, a 2×2 speck is dropped),
  hole-filling, and a directory end-to-end run.

## License

Dual-licensed under either of

- MIT license ([LICENSE-MIT](LICENSE-MIT))
- Apache License 2.0 ([LICENSE-APACHE](LICENSE-APACHE))

at your option.
