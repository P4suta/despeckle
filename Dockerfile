# Dev image for despeckle (Java + Leptonica via FFM).
#
# Ships everything the justfile recipes invoke, so host machines need nothing
# beyond Docker:
#   - Temurin JDK 25 (FFM is final since 22; 25 is the current LTS).
#   - Leptonica (liblept.so.5) — the despeckle core calls it through FFM.
#   - poppler-utils (pdftoppm) + img2pdf — expand scan PDFs in / repack out.
#   - The language-agnostic quality tools the repo already uses
#     (typos, taplo, biome, yamlfmt, actionlint, lefthook).
#
# Gradle itself is NOT installed: the committed wrapper (./gradlew) fetches the
# pinned distribution, so the build is reproducible from the repo alone.

# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:25-jdk-noble AS dev

ARG USER_UID=1000
ARG USER_GID=1000

ENV DEBIAN_FRONTEND=noninteractive

# Fail a RUN if any stage of a pipe fails (e.g. curl | tar), not just the last
# one. Standard Docker hardening (hadolint DL4006); the temurin base ships bash.
SHELL ["/bin/bash", "-eo", "pipefail", "-c"]

# System libraries + scan-pipeline tools. libleptonica-dev pulls in the
# runtime liblept.so.5 that FFM loads at run time.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        git \
        img2pdf \
        libleptonica-dev \
        poppler-utils \
        sudo \
        unzip \
    && rm -rf /var/lib/apt/lists/*

# ----- language-agnostic quality tools (pinned static binaries) -----

# just (command runner).
ARG JUST_VERSION=1.42.4
RUN curl -fsSL "https://github.com/casey/just/releases/download/${JUST_VERSION}/just-${JUST_VERSION}-x86_64-unknown-linux-musl.tar.gz" \
    | tar xz -C /usr/local/bin just

# lefthook (git hooks runner) — .deb release.
ARG LEFTHOOK_VERSION=1.13.6
RUN curl -fsSL -o /tmp/lefthook.deb \
        "https://github.com/evilmartians/lefthook/releases/download/v${LEFTHOOK_VERSION}/lefthook_${LEFTHOOK_VERSION}_amd64.deb" \
    && dpkg -i /tmp/lefthook.deb \
    && rm /tmp/lefthook.deb

# typos (spell-checker).
ARG TYPOS_VERSION=1.47.0
RUN curl -fsSL "https://github.com/crate-ci/typos/releases/download/v${TYPOS_VERSION}/typos-v${TYPOS_VERSION}-x86_64-unknown-linux-musl.tar.gz" \
    | tar xz -C /usr/local/bin ./typos \
    && chmod +x /usr/local/bin/typos

# taplo (TOML formatter) — gzipped single binary.
ARG TAPLO_VERSION=0.9.3
RUN curl -fsSL "https://github.com/tamasfe/taplo/releases/download/${TAPLO_VERSION}/taplo-linux-x86_64.gz" \
    | gunzip > /usr/local/bin/taplo \
    && chmod +x /usr/local/bin/taplo

# biome (JSON formatter) — single binary. Pinned like every other tool here so
# image rebuilds are reproducible; the v2 release tag is `@biomejs/biome@<ver>`.
ARG BIOME_VERSION=2.4.16
RUN curl -fsSL "https://github.com/biomejs/biome/releases/download/@biomejs/biome@${BIOME_VERSION}/biome-linux-x64" \
        -o /usr/local/bin/biome \
    && chmod +x /usr/local/bin/biome

# yamlfmt (YAML formatter).
ARG YAMLFMT_VERSION=0.13.0
RUN curl -fsSL "https://github.com/google/yamlfmt/releases/download/v${YAMLFMT_VERSION}/yamlfmt_${YAMLFMT_VERSION}_Linux_x86_64.tar.gz" \
    | tar xz -C /usr/local/bin yamlfmt

# actionlint (GitHub Actions linter).
ARG ACTIONLINT_VERSION=1.7.12
RUN curl -fsSL "https://github.com/rhysd/actionlint/releases/download/v${ACTIONLINT_VERSION}/actionlint_${ACTIONLINT_VERSION}_linux_amd64.tar.gz" \
    | tar xz -C /usr/local/bin actionlint

# Match host UID so bind-mounted files don't end up root-owned. The temurin
# base already has a user/group at 1000 (`ubuntu`); reuse it when the requested
# IDs collide, otherwise create a fresh `dev` user.
RUN set -eux; \
    if getent group "${USER_GID}" >/dev/null; then \
        groupname="$(getent group "${USER_GID}" | cut -d: -f1)"; \
    else \
        groupname=dev; groupadd --gid "${USER_GID}" dev; \
    fi; \
    if getent passwd "${USER_UID}" >/dev/null; then \
        username="$(getent passwd "${USER_UID}" | cut -d: -f1)"; \
    else \
        username=dev; useradd --uid "${USER_UID}" --gid "${USER_GID}" -m dev; \
    fi; \
    echo "${username} ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers; \
    echo "DEV_USER=${username}" > /etc/despeckle-dev-user

USER ${USER_UID}:${USER_GID}
ENV INSIDE_CONTAINER=1 \
    GRADLE_USER_HOME=/workspace/.gradle-home
WORKDIR /workspace
CMD ["bash"]
