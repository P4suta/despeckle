# despeckle — task entry points. Routes through Docker unless INSIDE_CONTAINER=1.

inside := env_var_or_default("INSIDE_CONTAINER", "0")

dev_running := `docker compose ps --status running --services 2>/dev/null | grep -c '^dev$' 2>/dev/null || true`
docker_run := if dev_running == "0" { "docker compose run --rm dev" } else { "docker compose exec dev" }

cargo := if inside == "1" { "cargo" } else { docker_run + " cargo" }
rustup := if inside == "1" { "rustup" } else { docker_run + " rustup" }
typos := if inside == "1" { "typos" } else { docker_run + " typos" }
actionlint := if inside == "1" { "actionlint" } else { docker_run + " actionlint" }
lefthook := if inside == "1" { "lefthook" } else { docker_run + " lefthook" }
taplo := if inside == "1" { "taplo" } else { docker_run + " taplo" }
biome := if inside == "1" { "biome" } else { docker_run + " biome" }
yamlfmt := if inside == "1" { "yamlfmt" } else { docker_run + " yamlfmt" }
sh := if inside == "1" { "bash -lc" } else { docker_run + " bash -lc" }

dev_log := env_var_or_default("DESPECKLE_LOG", "info")

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
    @echo "🎉 bootstrap done. Try: just build / just test / just lint"

doctor:
    @echo "==> despeckle doctor"
    @{{docker_run}} bash -c 'set -e; \
        check() { printf "  %-18s " "$1"; out=$($2 2>&1 | head -1) && printf "ok    %s\n" "$out" || { printf "MISSING\n"; exit 1; }; }; \
        check rustc          "rustc --version"; \
        check cargo          "cargo --version"; \
        check cargo-nextest  "cargo nextest --version"; \
        check cargo-deny     "cargo deny --version"; \
        check cargo-audit    "cargo audit --version"; \
        check cargo-llvm-cov "cargo llvm-cov --version"; \
        check cargo-machete  "cargo machete --version"; \
        check cargo-sort     "cargo sort --version"; \
        check cargo-rdme     "cargo rdme --version"; \
        check cargo-modules  "cargo modules --version"; \
        check cargo-depgraph "cargo depgraph --version"; \
        check typos          "typos --version"; \
        check taplo          "taplo --version"; \
        check biome          "biome --version"; \
        check yamlfmt        "yamlfmt --version"; \
        check actionlint     "actionlint -version"; \
        check lefthook       "lefthook version"; \
        check just           "just --version"; \
        check mold           "mold --version"; \
        check clang          "clang --version"; \
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

# ----- Rust workflow -----

build:
    @echo "==> cargo build --workspace --all-targets"
    {{cargo}} build --workspace --all-targets

build-release:
    {{cargo}} build --release --workspace

b:
    {{cargo}} build --workspace

test:
    @echo "==> cargo nextest run --workspace"
    {{cargo}} nextest run --workspace
    @echo "==> cargo test --doc --workspace"
    {{cargo}} test --doc --workspace

t:
    {{cargo}} nextest run --workspace --no-fail-fast

doctest:
    {{cargo}} test --doc --workspace

coverage:
    {{cargo}} llvm-cov --workspace --branch --html --output-dir artifacts/coverage

# ----- profiling (host-only; perf_event_open needs kernel.perf_event_paranoid <= 1) -----
#
# All of these recipes run on the host (not inside the dev container) because
# perf_event_open(2) is blocked by Docker's seccomp profile. To enable on
# the host, once per boot:
#
#   sudo sysctl -w kernel.perf_event_paranoid=1
#
# or persistently:
#
#   echo 'kernel.perf_event_paranoid=1' | sudo tee /etc/sysctl.d/99-perf.conf

sample_input := env_var_or_default("DESPECKLE_SAMPLE_IN", "private/scans/russell2")
sample_output := env_var_or_default("DESPECKLE_SAMPLE_OUT", "artifacts/profile-out")

# Criterion bench: per-page micro and sub-step. HTML report under target/criterion/.
bench:
    cargo bench -p despeckle-core --bench process_page

# cargo-flamegraph SVG (symbolicated via DWARF). Output: artifacts/flame.svg.
flame:
    @mkdir -p artifacts
    cargo flamegraph --profile release-perf -p despeckle-cli \
        --output artifacts/flame.svg -- \
        {{sample_input}} {{sample_output}} --force
    @echo "flame graph written to artifacts/flame.svg — open in a browser"

# samply record + open in Firefox Profiler UI (browser must be reachable).
profile:
    cargo build --profile release-perf -p despeckle-cli
    samply record -- ./target/release-perf/despeckle \
        {{sample_input}} {{sample_output}} --force

# samply record to file only (no browser), then print a text top-N summary.
profile-summary:
    @mkdir -p artifacts
    cargo build --profile release-perf -p despeckle-cli
    samply record --save-only -o artifacts/profile.json -- \
        ./target/release-perf/despeckle {{sample_input}} {{sample_output}} --force
    python3 tools/samply_top.py artifacts/profile.json \
        ./target/release-perf/despeckle | tee artifacts/profile-summary.md

# ----- run -----

# Process a directory of bitonal images.
run input output *args:
    DESPECKLE_LOG={{dev_log}} {{cargo}} run -p despeckle-cli -- {{input}} {{output}} {{args}}

run-release input output *args:
    DESPECKLE_LOG={{dev_log}} {{cargo}} run --release -p despeckle-cli -- {{input}} {{output}} {{args}}

# Process the bundled `samples/` directory into `artifacts/sample-out` and
# write an HTML report. Used as a smoke check during development.
run-sample:
    @mkdir -p artifacts
    DESPECKLE_LOG={{dev_log}} {{cargo}} run -p despeckle-cli -- \
        samples artifacts/sample-out --report artifacts/sample-report --force

# ----- lint / quality gates -----

fmt:
    {{cargo}} fmt --all
    {{cargo}} sort --workspace
    {{taplo}} fmt
    {{biome}} format --write .
    {{yamlfmt}} .

fmt-check:
    {{cargo}} fmt --all -- --check
    {{cargo}} sort --workspace --check
    {{taplo}} fmt --check
    {{biome}} format .
    {{yamlfmt}} --lint .

clippy:
    {{cargo}} clippy --workspace --all-targets -- -D warnings

deny:
    {{cargo}} deny check advisories bans licenses sources

audit:
    {{cargo}} audit --deny warnings

typos:
    {{typos}}

typos-fix:
    {{typos}} --write-changes

actionlint:
    {{actionlint}} .github/workflows/*.yml

machete:
    {{cargo}} machete

# ----- auto-generated docs -----

docs-dep-graph:
    {{sh}} "{{cargo}} depgraph --workspace-only | dot -Tsvg > docs/dep-graph.svg"

docs-modules:
    {{sh}} "{{cargo}} modules structure --package despeckle-core > docs/modules/despeckle-core.txt"
    {{sh}} "{{cargo}} modules structure --package despeckle-cli > docs/modules/despeckle-cli.txt"

docs-readme:
    {{sh}} "cd crates/despeckle-core && {{cargo}} rdme --force"

docs: docs-dep-graph docs-modules docs-readme

doc:
    {{cargo}} doc --workspace --no-deps --open

# RUSTDOCFLAGS=-D warnings is also enforced in .github/workflows/docs.yml.
# This recipe matches that so pre-push catches the same drift CI would.
rustdoc-check:
    @echo "==> cargo doc --workspace --no-deps (RUSTDOCFLAGS=-D warnings)"
    @if [ "{{inside}}" = "1" ]; then \
        RUSTDOCFLAGS="-D warnings" cargo doc --workspace --no-deps; \
    elif [ "{{dev_running}}" = "0" ]; then \
        docker compose run --rm -e RUSTDOCFLAGS="-D warnings" dev cargo doc --workspace --no-deps; \
    else \
        docker compose exec -e RUSTDOCFLAGS="-D warnings" dev cargo doc --workspace --no-deps; \
    fi

# Aggregated lint pipeline (mirrors the CI gates that block merges).
lint: fmt-check clippy deny typos actionlint machete

# Local CI replica.
ci: lint test rustdoc-check

# ----- git hooks -----

hooks:
    {{lefthook}} install

# ----- lefthook delegated recipes (do not run directly) -----

_hook-fmt +files:
    {{cargo}} fmt -- {{files}}

_hook-typos-fix +files:
    {{typos}} --write-changes {{files}}

_hook-taplo-fmt +files:
    {{taplo}} fmt {{files}}

_hook-cargo-sort:
    {{cargo}} sort --workspace

_hook-biome-format +files:
    {{biome}} format --write {{files}}

_hook-yamlfmt +files:
    {{yamlfmt}} {{files}}

_hook-actionlint +files:
    {{actionlint}} {{files}}

_hook-docs-drift:
    just docs
    {{sh}} "git diff --quiet docs/ crates/despeckle-core/README.md || (echo 'docs drift detected — run \\`just docs\\` and commit' >&2; exit 1)"
