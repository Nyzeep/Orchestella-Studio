#!/usr/bin/env bash
# Ticket 26 CI: local validation set for .github/workflows/qemu-android.yml.
#
# Gates:
#   1. repository-path existence: every owned-tree path the workflow references
#      (tools/qemu-android/..., /pkgbuild/... which maps to tools/qemu-android/...,
#      and guest-assets/... outside the build-output prefix) must exist;
#   2. YAML parses;
#   3. every `run:` block passes `bash -n` after ${{ }} masking.
#
# Read-only. Fail-closed: any dangling reference or parse failure exits non-zero.
set -euo pipefail

TOOLS_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
REPOSITORY_DIRECTORY="$(cd -- "$TOOLS_DIRECTORY/../.." && pwd -P)"
WORKFLOW="$REPOSITORY_DIRECTORY/.github/workflows/qemu-android.yml"

[[ -f "$WORKFLOW" ]] || { printf 'workflow missing: %s\n' "$WORKFLOW" >&2; exit 1; }

if command -v cygpath >/dev/null 2>&1; then
  WORKFLOW_ARG="$(cygpath -w "$WORKFLOW")"
  REPOSITORY_ARG="$(cygpath -w "$REPOSITORY_DIRECTORY")"
else
  WORKFLOW_ARG="$WORKFLOW"
  REPOSITORY_ARG="$REPOSITORY_DIRECTORY"
fi
PYTHON=""
for candidate in python3 python; do
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c 'pass' >/dev/null 2>&1; then
    PYTHON="$candidate"
    break
  fi
done
[[ -n "$PYTHON" ]] || { printf 'no usable python interpreter\n' >&2; exit 1; }
"$PYTHON" - "$WORKFLOW_ARG" "$REPOSITORY_ARG" <<'PY'
import re
import sys
import tempfile
import subprocess
from pathlib import Path

workflow = Path(sys.argv[1])
repo = Path(sys.argv[2])
text = workflow.read_text(encoding="utf-8")
failures = []

# Gate 1: owned-tree path existence.
refs = set()
for match in re.finditer(r"(?:tools/qemu-android|guest-assets)/[A-Za-z0-9._/-]+", text):
    refs.add(match.group(0))
for match in re.finditer(r"/pkgbuild/[A-Za-z0-9._/-]+", text):
    refs.add("tools/qemu-android" + match.group(0)[len("/pkgbuild"):])
for ref in sorted(refs):
    if ref == "guest-assets/out" or ref.startswith("guest-assets/out/"):
        continue  # CI build output, not a repository path
    ref = ref.rstrip("/")
    ref = ref.rstrip(".,;:)")
    if not (repo / ref).exists():
        failures.append(f"WORKFLOW_PATH_MISSING {ref}")

# Gate 2: YAML parses.
try:
    import yaml
    yaml.safe_load(text)
except Exception as exc:  # noqa: BLE001 - report and fail closed
    failures.append(f"WORKFLOW_YAML_PARSE_FAIL {exc}")

# Gate 3: bash -n on masked run blocks (fed via stdin to avoid path translation).
run_blocks = re.findall(r"run: \|\n((?:[ ]{10,}.*\n|\n)+)", text)
for index, block in enumerate(run_blocks):
    masked = re.sub(r"\$\{\{[^}]*\}\}", "MASKED", block)
    result = subprocess.run(
        ["bash", "-n"],
        input=masked,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if result.returncode != 0:
        detail = (result.stderr or "").strip()
        failures.append(f"WORKFLOW_RUN_BLOCK_SYNTAX_FAIL run-{index}: {detail}")

print(f"WORKFLOW_LOCAL_VALIDATION refs={len(refs)} run_blocks={len(run_blocks)}")
for failure in failures:
    print(failure, file=sys.stderr)
sys.exit(1 if failures else 0)
PY
printf 'DROIDLAYER_T26_WORKFLOW_LOCAL_VALIDATION_OK\n'
