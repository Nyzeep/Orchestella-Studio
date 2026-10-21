#!/usr/bin/env python3
"""Build the sealed toolbox twice and compare canonical tree digests.

The digest covers every path, file mode, size and SHA256 under the toolbox
root, so a difference in the generated manifest or in any applet wrapper is
visible even when the upstream tarball is unchanged. The build itself is
deterministic: there are no timestamps in the manifest and the zip epoch is
fixed, so identical inputs must produce identical bytes.
"""

from __future__ import annotations

import argparse
import json
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import fastlane_build as fb  # noqa: E402


def _files(root: Path) -> dict[str, str]:
    return {
        path.relative_to(root).as_posix(): fb.sha256_file(path)
        for path in sorted(root.rglob("*"))
        if path.is_file() and not path.is_symlink()
    }


def compare_trees(left: Path, right: Path) -> str:
    """Return the shared tree digest or raise with the first differing path."""
    left_digest = fb.tree_digest(left)
    right_digest = fb.tree_digest(right)
    if left_digest != right_digest:
        left_files = _files(left)
        right_files = _files(right)
        changed = sorted(
            path
            for path in set(left_files) | set(right_files)
            if left_files.get(path) != right_files.get(path)
        )
        detail = ", ".join(changed[:5]) if changed else "metadata-only difference"
        raise fb.BuildError(
            f"non-reproducible toolbox: {left_digest} != {right_digest} ({detail})"
        )
    return left_digest


def reproduce(
    manifest: Path,
    cache_dir: Path | None,
    fixture: bool,
    runs: int,
    source_date_epoch: int | None,
) -> dict:
    if runs < 2:
        raise fb.BuildError("reproducibility needs at least two builds")
    reports = []
    with tempfile.TemporaryDirectory(prefix="fastlane-reproduce-") as temporary:
        root = Path(temporary)
        for index in range(runs):
            out = root / f"toolbox-{index}"
            runtime = fb.build_toolbox(
                manifest,
                out,
                cache_dir=cache_dir,
                fixture=fixture,
                source_date_epoch=source_date_epoch,
            )
            report = fb.verify_toolbox(out)
            reports.append(
                {
                    "treeDigest": report["treeDigest"],
                    "totalSize": runtime["totalSize"],
                    "totalBytes": report["totalBytes"],
                    "entries": report["entries"],
                }
            )
        first = reports[0]
        for index, report in enumerate(reports[1:], start=1):
            if report["treeDigest"] != first["treeDigest"]:
                raise fb.BuildError(
                    f"build {index} differs from build 0: "
                    f"{report['treeDigest']} != {first['treeDigest']}"
                )
        return {
            "ok": True,
            "runs": runs,
            "treeDigest": first["treeDigest"],
            "totalSize": first["totalSize"],
            "totalBytes": first["totalBytes"],
            "entries": first["entries"],
            "sourceDateEpoch": fb.resolve_source_date_epoch(None, source_date_epoch),
            "fixture": fixture,
        }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", default="fastlane-toolbox/manifest.json")
    parser.add_argument("--cache")
    parser.add_argument("--fixture", action="store_true", help="use a local shell fixture (tests only)")
    parser.add_argument("--runs", type=int, default=2)
    parser.add_argument("--source-date-epoch", type=int)
    args = parser.parse_args(argv)
    try:
        report = reproduce(
            Path(args.manifest),
            Path(args.cache) if args.cache else None,
            args.fixture,
            args.runs,
            args.source_date_epoch,
        )
    except fb.BuildError as failure:
        print(f"FAIL {failure}", file=sys.stderr)
        return 1
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
