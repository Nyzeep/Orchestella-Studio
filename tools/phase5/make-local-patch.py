#!/usr/bin/env python3
"""Create a file-level Phase 5 patch against captured dependencies without touching Git's index."""
import argparse
import difflib
import hashlib
import json
from pathlib import Path
import re
import subprocess


BASE = "8d0e8c75d769ecc645ea102b90a304c8657344a1"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--workspace", required=True, type=Path)
    parser.add_argument("--before-images", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    root = args.workspace
    def git(arguments, check=True):
        return subprocess.run(["git", "-c", "core.autocrlf=false", "-C", str(root)] + arguments,
                              capture_output=True, check=check)
    if git(["rev-parse", "HEAD"]).stdout.decode().strip() != BASE:
        raise SystemExit("Unexpected integration HEAD")
    if git(["diff", "--cached", "--name-only"]).stdout.strip():
        raise SystemExit("The index is not empty; this script never changes it")
    tracked = git(["diff", "--name-only", "--diff-filter=AM"]).stdout.decode("utf-8").splitlines()
    added = git(["ls-files", "--others", "--exclude-standard"]).stdout.decode("utf-8").splitlines()
    allowed = ("app/src/main/", "app/src/test/", "runtime/", "guest-assets/", "tools/phase5/", "prototypes/phase5-")
    files = set(path for path in tracked + added if path.startswith(allowed) or path == "app/build.gradle.kts")
    for folder in (
        root / "docs/reports/orchestration",
        root / "docs/research",
        root / "docs/reports/archive/2026-10-phase5-quality/orchestration",
        root / "docs/research/archive/2026-10-phase5",
    ):
        files.update(file.relative_to(root).as_posix() for file in folder.glob("*phase5*.md"))
    for family in ("tickets", "reviews"):
        for folder in (root / ("docs/reports/" + family)).glob("phase5*"):
            files.update(file.relative_to(root).as_posix() for file in folder.rglob("*.md"))
        archived = root / "docs/reports/archive/2026-10-phase5-quality" / family
        if archived.is_dir():
            files.update(file.relative_to(root).as_posix() for file in archived.rglob("*.md"))
    files.update(file.relative_to(root).as_posix() for file in (root / ".scratch/orchestella-phase5").rglob("*.md"))
    patch = []
    manifest = []
    excluded = []
    for relative in sorted(files):
        path = root / relative
        if not path.is_file() or any(part in {".git", ".env", ".toolchains", "build", ".gradle", "__pycache__"} for part in path.parts):
            excluded.append(relative)
            continue
        if re.search(r"(?:\.keystore|\.jks|\.apk|\.class|\.pyc|\.so|\.cpio|\.gz)$", relative):
            excluded.append(relative)
            continue
        current = path.read_bytes()
        captured = args.before_images / relative
        if captured.is_file():
            previous = captured.read_bytes()
            origin = "captured-live-production-dependency"
            exists = True
        else:
            result = git(["show", BASE + ":" + relative], check=False)
            exists = result.returncode == 0
            previous = result.stdout if exists else b""
            origin = "tracked-HEAD" if exists else "new-Phase5-file"
        if previous == current:
            continue
        before = previous.decode("utf-8").replace("\r\n", "\n")
        after = current.decode("utf-8").replace("\r\n", "\n")
        if before == after:
            continue
        if not after.endswith("\n") or exists and before and not before.endswith("\n"):
            raise SystemExit("Patch source requires final newline: " + relative)
        patch.append("diff --git a/" + relative + " b/" + relative + "\n")
        if not exists:
            patch.append("new file mode 100644\n")
        patch.extend(difflib.unified_diff(before.splitlines(keepends=True), after.splitlines(keepends=True),
                                         fromfile="a/" + relative if exists else "/dev/null", tofile="b/" + relative, n=3))
        manifest.append({"path": relative, "baseline": origin, "beforeSha256": digest(previous) if exists else None,
                         "afterSha256": digest(current), "normalizedAfterSha256": digest(after.encode()),
                         "bytes": len(current), "new": not exists})
    patch_path = args.output / "phase5-integration.patch"
    patch_path.write_text("".join(patch), encoding="utf-8", newline="")
    record = {"baseHEAD": BASE, "indexChanged": False, "commitCreated": False,
              "scope": "named Phase5 source/report/decision closure; no Git objects, secrets, binaries, cache or instance data",
              "files": manifest, "excluded": excluded, "patchSha256": digest(patch_path.read_bytes())}
    (args.output / "phase5-source-manifest.json").write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
    result = git(["apply", "--check", "--reverse", "--whitespace=error-all", str(patch_path)], check=False)
    (args.output / "patch-reverse-check.log.txt").write_bytes(result.stdout + result.stderr)
    if result.returncode:
        raise SystemExit("File-level patch reverse check failed; preserve the diagnostics")
    print(json.dumps({"files": len(manifest), "patchSha256": record["patchSha256"], "reverseCheck": "PASS"}))


if __name__ == "__main__":
    main()
