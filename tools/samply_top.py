#!/usr/bin/env python3
"""Summarize a samply profile (`*.json` or `*.json.gz`) into a top-N table.

Usage:
    python3 tools/samply_top.py <profile.json[.gz]> <binary> [--top N] [--inclusive]

The script walks the Gecko-profile sample table, batches every unique
foreground-thread frame address through `addr2line -C -f -e <binary>`, and
prints both self-time and inclusive-time rankings as Markdown so the output
can be redirected straight into a perf report.

Only frames that the binary's DWARF can resolve are kept — system libraries
without debuginfo collapse into a single `[external]` bucket. That keeps
the noise down without dropping any work the binary actually did.
"""

from __future__ import annotations

import argparse
import collections
import gzip
import json
import pathlib
import subprocess
import sys
import textwrap


def open_profile(path: pathlib.Path):
    if path.suffix == ".gz":
        with gzip.open(path, "rt") as f:
            return json.load(f)
    return json.loads(path.read_text())


def gather_addresses(threads: list[dict]) -> set[int]:
    addrs: set[int] = set()
    for thread in threads:
        frames = thread.get("frameTable", {})
        for raw in frames.get("address", []):
            if isinstance(raw, int) and raw >= 0:
                addrs.add(raw)
    return addrs


def resolve_symbols(binary: pathlib.Path, addrs: set[int]) -> dict[int, str]:
    if not addrs:
        return {}
    sorted_addrs = sorted(addrs)
    hex_args = [f"0x{a:x}" for a in sorted_addrs]
    # Chunk to keep the argv reasonable (Linux: ARG_MAX ~ 2 Mi by default).
    chunk = 4096
    syms: dict[int, str] = {}
    for start in range(0, len(hex_args), chunk):
        batch = hex_args[start:start + chunk]
        proc = subprocess.run(
            ["addr2line", "-C", "-f", "-e", str(binary), *batch],
            capture_output=True,
            text=True,
            check=True,
        )
        lines = proc.stdout.splitlines()
        # addr2line emits 2 lines per address: function, file:line
        for i, addr in enumerate(sorted_addrs[start:start + chunk]):
            fn = lines[2 * i].strip() if 2 * i < len(lines) else "??"
            syms[addr] = fn
    return syms


def name_for_frame(
    thread: dict,
    frame_idx: int,
    sym_by_addr: dict[int, str],
) -> str:
    frames = thread.get("frameTable", {})
    addresses = frames.get("address", [])
    funcs = frames.get("func", [])

    addr = addresses[frame_idx] if frame_idx < len(addresses) else -1
    if addr in sym_by_addr and sym_by_addr[addr] != "??":
        return sym_by_addr[addr]

    func_idx = funcs[frame_idx] if frame_idx < len(funcs) else -1
    func_table = thread.get("funcTable", {})
    name_idxs = func_table.get("name", [])
    strings = thread.get("stringArray", [])
    if 0 <= func_idx < len(name_idxs):
        name_idx = name_idxs[func_idx]
        if 0 <= name_idx < len(strings):
            name = strings[name_idx]
            if name and not name.startswith("0x"):
                return name
    return "[external]"


def collect(thread: dict, sym_by_addr: dict[int, str]):
    """Return (self_counts, incl_counts, total_samples)."""
    samples = thread.get("samples", {}).get("stack", [])
    stack_table = thread.get("stackTable", {})
    stack_frame = stack_table.get("frame", [])
    stack_prefix = stack_table.get("prefix", [])

    self_counts: collections.Counter = collections.Counter()
    incl_counts: collections.Counter = collections.Counter()
    total = 0

    for stack_idx in samples:
        if stack_idx is None:
            continue
        total += 1

        # self = leaf frame
        if 0 <= stack_idx < len(stack_frame):
            leaf = name_for_frame(thread, stack_frame[stack_idx], sym_by_addr)
            self_counts[leaf] += 1

        seen: set[str] = set()
        cur = stack_idx
        while cur is not None and 0 <= cur < len(stack_frame):
            name = name_for_frame(thread, stack_frame[cur], sym_by_addr)
            if name not in seen:
                seen.add(name)
                incl_counts[name] += 1
            cur = stack_prefix[cur] if cur < len(stack_prefix) else None

    return self_counts, incl_counts, total


def render(title: str, counts: collections.Counter, total: int, top: int) -> str:
    if total == 0:
        return f"### {title}\n\n_(no samples)_\n"
    lines = [
        f"### {title}",
        "",
        "| samples | share | symbol |",
        "| ------: | ----: | :----- |",
    ]
    for name, count in counts.most_common(top):
        share = 100.0 * count / total
        # Truncate ultra-long generic instantiations for readability.
        short = name if len(name) <= 110 else name[:107] + "..."
        lines.append(f"| {count:>7} | {share:5.1f}% | `{short}` |")
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("profile", type=pathlib.Path)
    parser.add_argument("binary", type=pathlib.Path)
    parser.add_argument("--top", type=int, default=25)
    args = parser.parse_args()

    if not args.profile.exists():
        print(f"profile not found: {args.profile}", file=sys.stderr)
        return 1
    if not args.binary.exists():
        print(f"binary not found: {args.binary}", file=sys.stderr)
        return 1

    data = open_profile(args.profile)
    threads = data.get("threads", [])

    addrs = gather_addresses(threads)
    sym_by_addr = resolve_symbols(args.binary, addrs)

    print(textwrap.dedent(f"""
        # despeckle profile summary

        - profile: `{args.profile}`
        - binary: `{args.binary}`
        - threads sampled: {len(threads)}
        - resolved addresses: {sum(1 for v in sym_by_addr.values() if v != '??')} / {len(sym_by_addr)}
    """).strip())
    print()

    # Aggregate across all threads — for a rayon binary that surfaces
    # both the main thread and the worker pool in one ranking.
    self_total: collections.Counter = collections.Counter()
    incl_total: collections.Counter = collections.Counter()
    total_samples = 0
    for thread in threads:
        s, i, t = collect(thread, sym_by_addr)
        self_total += s
        incl_total += i
        total_samples += t

    print(f"- total samples: {total_samples}")
    print()
    print(render("Top self-time (leaf frames)", self_total, total_samples, args.top))
    print()
    print(render("Top inclusive time (frame anywhere in the stack)",
                 incl_total, total_samples, args.top))

    return 0


if __name__ == "__main__":
    sys.exit(main())
