# despeckle — task entry points. Routes through Docker unless INSIDE_CONTAINER=1.
#
# Every recipe runs inside the dev container (Temurin JDK 25 + Leptonica + the
# language-agnostic quality tools), so the host needs nothing but Docker. The
# dev image sets INSIDE_CONTAINER=1, which makes the same recipes call the tools
# directly instead of re-entering Docker.

inside := env_var_or_default("INSIDE_CONTAINER", "0")

dev_running := `docker compose ps --status running --services 2>/dev/null | grep -c '^dev$' 2>/dev/null || true`
docker_run := if dev_running == "0" { "docker compose run --rm dev" } else { "docker compose exec dev" }

gradlew := if inside == "1" { "./gradlew" } else { docker_run + " ./gradlew" }
typos := if inside == "1" { "typos" } else { docker_run + " typos" }
taplo := if inside == "1" { "taplo" } else { docker_run + " taplo" }
biome := if inside == "1" { "biome" } else { docker_run + " biome" }
yamlfmt := if inside == "1" { "yamlfmt" } else { docker_run + " yamlfmt" }
actionlint := if inside == "1" { "actionlint" } else { docker_run + " actionlint" }
lefthook := if inside == "1" { "lefthook" } else { docker_run + " lefthook" }
pdfimages := if inside == "1" { "pdfimages" } else { docker_run + " pdfimages" }
sh := if inside == "1" { "bash -lc" } else { docker_run + " bash -lc" }

# Gradle: no daemon (containers are ephemeral), plain console, and never
# auto-download a JDK — the image's Temurin 25 is the toolchain. Identical to
# the flags CI runs, so `just build` and CI cannot drift.
gradle_flags := "--no-daemon --console=plain -Dorg.gradle.java.installations.auto-download=false"

default:
    @just --list

# ----- first-run bootstrap -----

bootstrap:
    @echo "==> 1/3 fetch dev image (try ghcr.io, fall back to local build)"
    @docker compose pull 2>/dev/null && echo "  (pulled prebuilt image from ghcr.io)" \
        || (echo "  (no published image, building locally with GITHUB_TOKEN if available)" && \
            GITHUB_TOKEN="${GITHUB_TOKEN:-$(gh auth token 2>/dev/null || true)}" docker compose build)
    @echo "==> 2/3 docker compose up -d dev (persistent dev container)"
    docker compose up -d dev
    @echo "==> 3/3 lefthook install (pre-commit / pre-push hooks)"
    {{lefthook}} install
    @just doctor
    @echo
    @echo "🎉 bootstrap done. Try: just build / just test / just run-sample"

doctor:
    @echo "==> despeckle doctor"
    @{{docker_run}} bash -c 'set -e; \
        check() { name="$1"; shift; printf "  %-12s " "$name"; \
            if out=$("$@" 2>&1); then printf "ok    %s\n" "$(head -n1 <<<"$out")"; \
            else printf "MISSING\n"; exit 1; fi; }; \
        check java       java -version; \
        check typos      typos --version; \
        check taplo      taplo --version; \
        check biome      biome --version; \
        check yamlfmt    yamlfmt --version; \
        check actionlint actionlint -version; \
        check lefthook   lefthook version; \
        check just       just --version; \
        check pdfimages  pdfimages -v; \
        check img2pdf    img2pdf --version; \
        check jbig2      jbig2 --version; \
        check qpdf       qpdf --version; \
        check exiftool   exiftool -ver; \
        check pikepdf    python3 -c "import pikepdf; print(pikepdf.__version__)"; \
    '
    @echo "==> doctor: ok"

# ----- one-shot environment -----

docker-build:
    @echo "==> docker compose build (GITHUB_TOKEN auto-loaded from gh CLI if available)"
    GITHUB_TOKEN="${GITHUB_TOKEN:-$(gh auth token 2>/dev/null || true)}" docker compose build

shell:
    {{docker_run}} bash

clean-docker:
    @echo "==> docker compose down (volumes + local images)"
    docker compose down --volumes --rmi local

dev-up:
    @echo "==> docker compose up -d dev"
    docker compose up -d dev
    @echo "dev container is up — `just <recipe>` now uses docker exec (faster)."

dev-down:
    docker compose stop dev

# ----- build / test -----

# Full quality gate: compile (Error Prone, -Werror), Spotless check, SpotBugs,
# and the JUnit suite. Identical to what CI runs.
build:
    @echo "==> ./gradlew build"
    {{gradlew}} build {{gradle_flags}}

# Compile + assemble the runnable jar, skipping checks (fast inner loop).
assemble:
    {{gradlew}} assemble {{gradle_flags}}

# JUnit suite only.
test:
    @echo "==> ./gradlew test"
    {{gradlew}} test {{gradle_flags}}

clean:
    {{gradlew}} clean {{gradle_flags}}

# ----- run -----

# Process a directory of bitonal images. Extra args pass straight to the CLI:
#   just run scans/book out/book --report report/book --force
run input output *args:
    {{gradlew}} run {{gradle_flags}} --args="{{input}} {{output}} {{args}}"

# Smoke check: process the bundled samples/ into artifacts/ with an HTML report.
run-sample:
    {{gradlew}} run {{gradle_flags}} \
        --args="samples artifacts/sample-out --report artifacts/sample-report --force"

# ----- format / lint (mirrors CI + the lefthook gates) -----

# Auto-format everything in place (spelling included).
fmt:
    {{gradlew}} spotlessApply {{gradle_flags}}
    {{taplo}} fmt
    {{biome}} format --write .
    {{yamlfmt}} .
    {{typos}} --write-changes

# Verify formatting without writing (what CI checks).
fmt-check:
    {{gradlew}} spotlessCheck {{gradle_flags}}
    {{taplo}} fmt --check
    {{biome}} format .
    {{yamlfmt}} --lint .

# Spell-check and fix in place — the default. CI and the gates use typos-check.
typos:
    {{typos}} --write-changes

# Spell-check without writing (what CI and the pre-push gate run).
typos-check:
    {{typos}}

actionlint:
    {{actionlint}} .github/workflows/*.yml

# Aggregated lint gate (mirrors CI's lint-peripheral plus Spotless).
lint: fmt-check typos-check actionlint

# Local CI replica: lint + the full build (which also runs the tests).
ci: lint build

# Check the dev image's pinned tools against their latest upstream release.
# The apt tools (qpdf, exiftool, ...) track the Ubuntu base and are not pinned,
# so only the ARG-pinned downloads (just, typos, jbig2enc, ...) are compared.
tools-latest:
    {{docker_run}} python3 scripts/check-tool-versions.py

# ----- git hooks -----

hooks:
    {{lefthook}} install

# ----- lefthook delegated recipes (invoked by git hooks; do not run directly) -----
#
# Each recipe announces the tool and its job, and on failure prints a
# "✗ <tool> failed" line naming the likely cause — so a hook failure tells you
# which tool broke and why, instead of a bare "recipe failed with exit code N".
# The tool's own output still streams above that line.

# Spotless has no reliable single-file mode, so it formats the whole Java tree;
# lefthook re-stages the result (stage_fixed: true).
_hook-spotless-apply:
    #!/usr/bin/env bash
    set -uo pipefail
    echo "▶ spotless / google-java-format — reformatting Java sources"
    {{gradlew}} spotlessApply {{gradle_flags}} && exit 0
    echo "✗ spotless could not run." >&2
    echo "  Likely: the dev image has no JDK, or .gradle-home is not writable by" >&2
    echo "  this user (a root-owned gradle volume corrupts the wrapper). Run 'just doctor'." >&2
    exit 1

_hook-typos-fix +files:
    #!/usr/bin/env bash
    set -uo pipefail
    echo "▶ typos — spell-checking staged files"
    # --force-exclude: honor _typos.toml [files].extend-exclude even though the
    # paths are passed explicitly. Without it typos scans binaries it was told to
    # skip (e.g. gradle-wrapper.jar) and "fixes" random bytes, corrupting them.
    {{typos}} --force-exclude --write-changes {{files}} && exit 0
    echo "✗ typos found misspellings it could not auto-fix (listed above)." >&2
    echo "  Fix the word, or whitelist it in _typos.toml [default.extend-words]." >&2
    exit 1

_hook-taplo-fmt +files:
    #!/usr/bin/env bash
    set -uo pipefail
    echo "▶ taplo — formatting TOML"
    {{taplo}} fmt {{files}} && exit 0
    echo "✗ taplo failed to format TOML (see above)." >&2
    exit 1

_hook-biome-format +files:
    #!/usr/bin/env bash
    set -uo pipefail
    echo "▶ biome — formatting JSON"
    {{biome}} format --write {{files}} && exit 0
    echo "✗ biome failed to format JSON (see above)." >&2
    exit 1

_hook-yamlfmt +files:
    #!/usr/bin/env bash
    set -uo pipefail
    echo "▶ yamlfmt — formatting YAML"
    {{yamlfmt}} {{files}} && exit 0
    echo "✗ yamlfmt failed to format YAML (see above)." >&2
    exit 1

_hook-actionlint +files:
    #!/usr/bin/env bash
    set -uo pipefail
    echo "▶ actionlint — linting GitHub Actions workflows"
    {{actionlint}} {{files}} && exit 0
    echo "✗ actionlint found workflow problems (listed above); fix them before committing." >&2
    exit 1

# ----- scan pipeline (pdfimages in; lossless JBIG2 out for pages, img2pdf for
# color overlays) -----
#
# Bitonal pages carry no reliable DPI (PBM has none; img2pdf would assume 96 and
# stretch the page across ~30 cm), so the physical page size is set from
# DESPECKLE_DPI (default 600). Cleaned pages pack as lossless JBIG2; the color
# overlays, which JBIG2 cannot represent, stay on img2pdf.

dpi := env_var_or_default("DESPECKLE_DPI", "600")

# Extract every embedded 1-bit image from a scan PDF as TIFF, preserving the
# pixel grid exactly (no re-rasterise, no re-threshold). pdfimages decodes the
# source codec; pdftoppm -mono was lossy on JBIG2 scans. The real scan
# resolution lives only in the PDF page geometry, so a second pass stamps it
# (pdfimages -list x-ppi) into each TIFF's resolution tag — then `just run`
# needs no --dpi and the cleaned output stays correctly tagged.
# Example: `just extract path/to/book.pdf private/scans/book`
extract pdf out_dir:
    @mkdir -p {{out_dir}}
    {{pdfimages}} -tiff {{pdf}} {{out_dir}}/page
    {{sh}} 'python3 scripts/stamp-dpi.py "{{pdf}}" "{{out_dir}}"'
    @echo "extracted $(ls {{out_dir}} | wc -l) TIFF pages to {{out_dir}}"

# Roll a directory of cleaned bitonal pages into one PDF for human review, packed
# as lossless JBIG2 (generic region coding — bit-exact, and smaller than the
# source scan's own images), linearized for Fast Web View. Given the original
# scan as the optional third arg, the output mirrors it: its metadata (Info dict
# + XMP) and PDF version are inherited verbatim.
# Example: `just to-pdf out/book out/book.pdf book.pdf`
to-pdf in out source="":
    {{sh}} 'set -euo pipefail; \
        mkdir -p "$(dirname "{{out}}")"; \
        python3 scripts/jbig2-pdf.py "{{in}}" "{{out}}" "{{source}}" "{{dpi}}"'

# Bulk-pack every artifacts/*-cleaned/ directory into artifacts/*-cleaned.pdf
# (lossless JBIG2).
to-all-pdfs:
    {{docker_run}} bash -c "\
        set -euo pipefail; \
        for dir in artifacts/*-cleaned; do \
            [ -d \"\$dir\" ] || continue; \
            out=\"\${dir}.pdf\"; \
            echo \"==> \$dir -> \$out @ {{dpi}} dpi\"; \
            python3 scripts/jbig2-pdf.py \"\$dir\" \"\$out\" \"\" \"{{dpi}}\"; \
        done"

# Pack every artifacts/*-report/overlay/ directory into one PDF so you can scrub
# through and see which pixels despeckle removed (painted red over the original).
to-overlay-pdfs:
    {{docker_run}} bash -c "\
        set -euo pipefail; \
        for dir in artifacts/*-report; do \
            [ -d \"\$dir/overlay\" ] || continue; \
            book=\"\$(basename \"\$dir\" -report)\"; \
            out=\"artifacts/\${book}-overlay.pdf\"; \
            echo \"==> \$dir/overlay -> \$out @ {{dpi}} dpi\"; \
            img2pdf --imgsize \"{{dpi}}dpix{{dpi}}dpi\" \"\$dir/overlay\"/*.png --output \"\$out\"; \
        done"
