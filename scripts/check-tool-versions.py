#!/usr/bin/env python3
"""Check that the dev image's pinned tools are still the latest upstream release.

Reads each `ARG <TOOL>_VERSION=...` pin from the Dockerfile and compares it with
the newest matching GitHub release. The apt-installed tools (qpdf, poppler-utils,
webp) are not pinned here — they track the Ubuntu base and refresh on every image
build — so they are intentionally not checked.

Exit status is non-zero if any tool is behind (so `just tools-latest` and the
scheduled CI job both go red when something needs bumping) or if a lookup fails.

Set GITHUB_TOKEN to raise the API rate limit (optional).
"""
import json
import os
import re
import sys
import urllib.error
import urllib.request

# ARG name -> (GitHub repo, regex whose first group is the version in a release tag).
TOOLS = {
    "JUST_VERSION": ("casey/just", r"^v?(\d.+)$"),
    "LEFTHOOK_VERSION": ("evilmartians/lefthook", r"^v?(\d.+)$"),
    "TYPOS_VERSION": ("crate-ci/typos", r"^v?(\d.+)$"),
    "TAPLO_VERSION": ("tamasfe/taplo", r"^v?(\d.+)$"),
    "BIOME_VERSION": ("biomejs/biome", r"^@biomejs/biome@(\d.+)$"),
    "YAMLFMT_VERSION": ("google/yamlfmt", r"^v?(\d.+)$"),
    "ACTIONLINT_VERSION": ("rhysd/actionlint", r"^v?(\d.+)$"),
    "JBIG2ENC_VERSION": ("agl/jbig2enc", r"^v?(\d.+)$"),
}

DOCKERFILE = os.path.join(os.path.dirname(__file__), os.pardir, "Dockerfile")


def pinned_versions():
    with open(DOCKERFILE, encoding="utf-8") as f:
        text = f.read()
    pins = {}
    for arg in TOOLS:
        match = re.search(rf"^ARG {arg}=(.+)$", text, re.MULTILINE)
        if match:
            pins[arg] = match.group(1).strip()
    return pins


def api_get(url):
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "User-Agent": "despeckle-tool-version-check",
        },
    )
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def version_key(version):
    return [int(part) for part in re.findall(r"\d+", version)]


def latest_version(repo, pattern):
    """Newest non-draft, non-prerelease release tag matching pattern, or None."""
    releases = api_get(f"https://api.github.com/repos/{repo}/releases?per_page=100")
    best = None
    for release in releases:
        if release.get("draft") or release.get("prerelease"):
            continue
        match = re.match(pattern, release.get("tag_name", ""))
        if match and (best is None or version_key(match.group(1)) > version_key(best)):
            best = match.group(1)
    return best


def main():
    pins = pinned_versions()
    outdated = errors = 0
    print(f"{'tool':<20} {'pinned':<10} {'latest':<10} status")
    print("-" * 54)
    for arg, (repo, pattern) in TOOLS.items():
        pinned = pins.get(arg, "?")
        try:
            latest = latest_version(repo, pattern)
        except urllib.error.HTTPError as exc:
            print(f"{arg:<20} {pinned:<10} {'?':<10} ERROR {exc.code} ({repo})")
            errors += 1
            continue
        if latest is None:
            print(f"{arg:<20} {pinned:<10} {'?':<10} no matching release ({repo})")
            errors += 1
        elif version_key(latest) > version_key(pinned):
            print(f"{arg:<20} {pinned:<10} {latest:<10} OUTDATED ({repo})")
            outdated += 1
        else:
            print(f"{arg:<20} {pinned:<10} {latest:<10} ok")
    print()
    if outdated:
        print(f"{outdated} tool(s) behind latest — bump the ARG in the Dockerfile.")
    if errors:
        print(f"{errors} tool(s) could not be checked.")
    if not outdated and not errors:
        print("all pinned tools are at the latest release.")
    return 1 if outdated else (2 if errors else 0)


if __name__ == "__main__":
    sys.exit(main())
