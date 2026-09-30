#!/usr/bin/env bash
set -euo pipefail

repo="${CI_REPO:?CI_REPO must identify the checked-out repository}"
[[ -d "$repo" && ! -L "$repo" ]] || { printf 'NATIVE_PREBUILD_REJECTED invalid repository root\n' >&2; exit 66; }
tool="$repo/tools/qemu-android"
bash "$tool/scripts/verify-qemu-jni-lock.sh" "$tool/qemu-jni.lock"
python3 "$tool/scripts/verify-build-record.py" --repo "$repo"
printf 'NATIVE_PREBUILD_READY full_lock=verified build_record=verified\n'
