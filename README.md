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
  pages as **lossless JBIG2** (smaller than the source scan, bit-exact) and,
  since despeckle is image-in/image-out, mirrors the source PDF: same
  metadata, same PDF version, linearized for Fast Web View. All tooling is
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
                 → (optionally) drop isolated specks on clean background
                 → (optionally) fill pin-holes that sit in solid ink
                 → write (Leptonica)
```

Hole-filling is thickness-aware: a white pin-hole is closed only when the ink
ringing it is solid (survives an opening by ~half the speck size). The fine
gaps inside small or complex glyphs are ringed by *thin* strokes, so they are
left alone — small running heads and the fine strokes inside complex kanji
stay crisp instead of filling in.

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
just to-pdf out/mybook out/mybook.pdf mybook.pdf  # JBIG2; mirrors source metadata + version
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
  [--[no-]remove-isolated-dust] # drop isolated specks on clean bg (default: on)
  [--isolated-dust-size <PX>] # max isolated-speck size; implies the above
                           #   (default: dpi/40, ~15 px at 600 dpi)
```

The report's overlay paints every removed pixel red over the original
page, so you can confirm at a glance that only dust was taken.

### Isolated-dust pass

The base filter only drops specks tiny on *both* axes, so a medium speck
that is still smaller than a glyph survives — visible on an otherwise
clean margin. A second pass (on by default; disable with
`--no-remove-isolated-dust`) removes those, but **only where they are
isolated**: a speck within `isolated-dust-size + speck-size` pixels of real
text is kept. Punctuation, dakuten and ruby always hug a glyph, so they fall
inside that neighborhood and are never removed; only specks out on clean
background are. This makes the pass safe to run far more aggressively than a
global size bump, which would eat dakuten. The overlay shows exactly what it
took.

## Architecture

Six Gradle modules under `io.github.p4suta.despeckle`, a hexagonal (ports &
adapters) graph in which a layer violation does not compile — the boundary is
the *absence* of a `project()` dependency, not a runtime check:

| module            | role                                                                        |
| ----------------- | --------------------------------------------------------------------------- |
| `:domain`         | pure value types + logic: `ProcessOptions`/`ProcessResult`, `OutputFormat`, the listing/naming parsers |
| `:port`           | the adapter interfaces — `PageCleaner`, `Reporter`, `PdfImageExtractor`, `Jbig2Assembler`, `PdfLinearizer` |
| `:application`    | orchestration over the ports: `DespeckleService`, `PdfPipelineService`, `PdfBatchService`, `Jbig2PackService` |
| `:infrastructure` | the adapters: `Leptonica` (FFM island) + `Pix`, PDFBox + `jbig2`/`qpdf`, the WebP `HtmlReporter` |
| `:observability`  | exit-code mapping + the fatal uncaught handler                              |
| `:app`            | Apache Commons CLI front end, `Main`, the composition root, distribution, the ArchUnit suite |

`:domain`/`:port`/`:application` never see `:infrastructure`, so PDFBox, the FFM
binding and AWT are confined to `:infrastructure` (and Commons CLI to `:app`) by
construction — a future GUI can depend on `:application` plus the ports
unchanged. Shared build logic lives in the `build-logic` included build (three
convention plugins); versions stay in `gradle/libs.versions.toml`.

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
- **NullAway** (JSpecify, `@NullMarked`) at ERROR on both main and test sources
- **SpotBugs** at max effort
- **ArchUnit** (in `:app`, whose test classpath sees every module) — the
  boundaries the module graph cannot: FFM / `MethodHandle` confinement to the
  Leptonica island, `domain`/`port` filesystem-freedom, standard streams only in
  the CLI front ends, no package cycles, and JSpecify-only nullness annotations
- **JaCoCo** — per-module coverage floors (strict on `:domain`, looser on the
  adapter-heavy `:infrastructure`); **PIT** mutation testing on `:domain`
- **JUnit** — FFM smoke, pixel-identical round-trip, the polarity /
  connectivity pin (a tall thin stroke is kept, a 2×2 speck is dropped),
  hole-filling, a directory end-to-end run, fake-port service tests, and the
  ArchUnit architecture suite.

`just coverage` (`./gradlew testCodeCoverageReport` + `scripts/CoverageSummary.java`)
merges every module's JaCoCo data into one cross-module report and prints a
per-module / per-class summary — the aggregated view credits a class for coverage
from any module's tests, so the adapters that only the end-to-end tests exercise
show as covered there.

`./gradlew rewriteRun` (or `just rewrite` / `just rewrite-check`) runs an
**OpenRewrite** advisory pass — curated static-analysis, JUnit 5, SLF4J and
JDK-modernization recipes. It is deliberately out of `build` so it never blocks
a commit; CI surfaces its suggested patch as a non-blocking artifact.

## License

Dual-licensed under either of

- MIT license ([LICENSE-MIT](LICENSE-MIT))
- Apache License 2.0 ([LICENSE-APACHE](LICENSE-APACHE))

at your option.
