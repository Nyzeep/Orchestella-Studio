#!/usr/bin/env bash
set -euo pipefail

repo="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd -P)"
root="${TICKET26_WORK_ROOT:?TICKET26_WORK_ROOT must be the approved external root}"
repo="$(cygpath -u "$repo")"
root="$(cygpath -u "$root")"
[[ -d "$root" && ! -L "$root" ]] || exit 1
fixture="$(mktemp -d "$root/ci-input-chain-XXXXXXXX")"
printf 'CI_INPUT_FIXTURE=%s\n' "$fixture"

python - "$(cygpath -w "$repo/.github/workflows/qemu-android.yml")" <<'PY'
import re
import sys
from pathlib import Path
text = Path(sys.argv[1]).read_text(encoding='utf-8')
for job in ('native-build-two-clean', 'elf-closure-16kb', 'apk-packaging-signature-tamper'):
    section = re.split(r'\n  [a-z][a-z0-9-]*:\s*\n', text.split('\n  ' + job + ':', 1)[1], maxsplit=1)[0]
    assert 'fetch-ci-inputs.sh' in section, f'{job}: missing shared input fetch'
    assert 'build-in-container.sh' in section or 'prepare-qemu-payload.sh' in section, f'{job}: missing build consumer'
    assert 'verify-ci-inputs.sh' in section or 'prepare-qemu-payload.sh' in section, f'{job}: no verifier'
    assert 'github.event.pull_request.head.sha || github.sha' in section, f'{job}: no event head'
    assert 'rev-parse FETCH_HEAD' in section and 'rev-parse HEAD' in section and 'checkout --detach "$TARGET_SHA"' in section, f'{job}: checkout not pinned'
assert text.count('rev-parse FETCH_HEAD') == 5, 'not all checkouts pin the fetched commit'
assert 'Provisioning identical' not in text
print('CI_INPUT_WIRING_OK jobs=3 checkout_pins=5')
PY

python - "$(cygpath -w "$repo/.github/workflows/qemu-android.yml")" "$(cygpath -w "$fixture")" <<'PY'
import os
import re
import subprocess
import sys
import textwrap
import shutil
from pathlib import Path
workflow, fixture = map(Path, sys.argv[1:])
text = workflow.read_text(encoding='utf-8')
mock_bin = fixture / 'bin'
mock_bin.mkdir()
mock_git = mock_bin / 'git'
mock_git.write_text('''#!/usr/bin/env bash
if [[ "$*" == *"rev-parse FETCH_HEAD"* ]]; then printf '%s\\n' "$MOCK_FETCH_SHA";
elif [[ "$*" == *"rev-parse HEAD"* ]]; then printf '%s\\n' "$MOCK_HEAD_SHA";
elif [[ "${1:-}" == clone && "${@: -1}" == repo ]]; then mkdir -p repo;
fi
''', encoding='utf-8')
mock_git.chmod(0o755)
expected = '1' * 40
unexpected = '2' * 40
for job in ('provenance-lock', 'native-build-two-clean', 'elf-closure-16kb',
            'apk-packaging-signature-tamper', 'gpl-delivery'):
    section = re.split(r'\n  [a-z][a-z0-9-]*:\s*\n', text.split('\n  ' + job + ':', 1)[1], maxsplit=1)[0]
    checkout = section.split('- name: Fetch repository\n        run: |\n', 1)[1].split('\n      - name:', 1)[0]
    script = textwrap.dedent(checkout)
    for phrase, replacement in {
        '${{ github.token }}': 'synthetic', '${{ github.repository }}': 'synthetic/project',
        '${{ github.event.pull_request.head.sha || github.sha }}': expected,
        '${{ github.event_name }}': 'pull_request', '${{ github.event.pull_request.number }}': '7',
    }.items():
        script = script.replace(phrase, replacement)
    assert '${{' not in script
    job_dir = fixture / job
    job_dir.mkdir()
    for fetched, expected_rc, marker in ((unexpected, 1, 'FAIL trigger SHA mismatch'),
                                         (expected, 0, expected)):
        posix_bin = subprocess.check_output(['cygpath', '-u', str(mock_bin)], text=True).strip()
        env = {**os.environ, 'PATH': posix_bin + ':/usr/bin:/bin',
               'MOCK_FETCH_SHA': fetched, 'MOCK_HEAD_SHA': expected}
        result = subprocess.run([shutil.which('bash'), '-xeuo', 'pipefail', '-c', script], cwd=job_dir,
                                env=env, capture_output=True, text=True)
        assert result.returncode == expected_rc and marker in result.stdout + result.stderr, (job, result.returncode, result.stdout, result.stderr)
print('CI_CHECKOUT_SYNTHETIC_OK jobs=5 wrong_sha_rejected=5 matching_sha=5')
PY

python - "$(cygpath -w "$root")" "$(cygpath -w "$repo")" "$(cygpath -w "$fixture")" <<'PY'
from pathlib import Path
import os
import sys
root, repo, fixture = map(Path, sys.argv[1:])
inputs = fixture / 'inputs'
deps = inputs / 'qemu-android-deps-20260924'
deps.mkdir(parents=True)
archives = {
    inputs / 'qemu-11.1.1.tar.xz': root / 'downloads/qemu-11.1.1.tar.xz',
    inputs / 'ndk.zip': root / 'downloads/android-ndk-r28c-linux.zip',
}
for name in ('glib-2.80.5.tar.xz', 'pixman-0.42.2.tar.gz', 'zlib-1.3.2.tar.gz',
             'pcre2-10.48.tar.gz', 'libffi-3.8.0.tar.gz', 'libiconv-1.19.tar.gz'):
    archives[deps / name] = root / 'downloads/qemu-android-deps-20260924' / name
for name in ('proxy-libintl-0.4.tar', 'dtc-b6910bec.tar'):
    archives[deps / name] = repo / 'tools/qemu-android/vendor' / name
for destination, source in archives.items():
    assert source.is_file() and not source.is_symlink() and not destination.exists(), str(source)
    os.link(source, destination)
print('CI_INPUT_FIXTURE_READY inputs=10 approved_source_only=1')
PY

verify="$repo/tools/qemu-android/scripts/verify-ci-inputs.sh"
run_verify() {
  CI_REPO="$repo" CI_INPUT_ROOT="$fixture/inputs" bash "$verify" --verify-only
}
run_verify > "$fixture/real-positive.txt" 2>&1
[[ "$(cat "$fixture/real-positive.txt")" == 'CI_INPUTS_VERIFIED deps=8 qemu=1 ndk=1 wheel=1 mode=read-only' ]]
printf 'CI_INPUT_REAL_POSITIVE_OK deps=8 wheel=1\n'

mv -- "$fixture/inputs/qemu-android-deps-20260924/glib-2.80.5.tar.xz" "$fixture/glib-held.tar.xz"
if run_verify > "$fixture/missing-dep.txt" 2>&1; then printf 'FAIL missing dependency accepted\n' >&2; exit 1; fi
grep -q 'CI_INPUT_FAIL missing or invalid glib' "$fixture/missing-dep.txt"
mv -- "$fixture/glib-held.tar.xz" "$fixture/inputs/qemu-android-deps-20260924/glib-2.80.5.tar.xz"
cp -- "$fixture/inputs/qemu-android-deps-20260924/glib-2.80.5.tar.xz" "$fixture/glib-original.tar.xz"
rm -- "$fixture/inputs/qemu-android-deps-20260924/glib-2.80.5.tar.xz"
printf 'tampered' > "$fixture/inputs/qemu-android-deps-20260924/glib-2.80.5.tar.xz"
if run_verify > "$fixture/tampered-dep.txt" 2>&1; then printf 'FAIL changed dependency accepted\n' >&2; exit 1; fi
grep -q 'CI_INPUT_FAIL glib digest mismatch' "$fixture/tampered-dep.txt"
cp -- "$fixture/glib-original.tar.xz" "$fixture/inputs/qemu-android-deps-20260924/glib-2.80.5.tar.xz"

mv -- "$fixture/inputs/qemu-11.1.1.tar.xz" "$fixture/qemu-held.tar.xz"
if run_verify > "$fixture/missing-qemu.txt" 2>&1; then printf 'FAIL missing QEMU accepted\n' >&2; exit 1; fi
grep -q 'CI_INPUT_FAIL missing or invalid QEMU archive' "$fixture/missing-qemu.txt"
mv -- "$fixture/qemu-held.tar.xz" "$fixture/inputs/qemu-11.1.1.tar.xz"
awk -F= 'BEGIN{OFS="="} $1=="meson_tool_sha256"{$2="0000000000000000000000000000000000000000000000000000000000000000"} {print}' \
  "$repo/tools/qemu-android/qemu-jni.lock" > "$fixture/bad-wheel.lock"
if CI_REPO="$repo" CI_INPUT_ROOT="$fixture/inputs" CI_LOCK="$fixture/bad-wheel.lock" bash "$verify" --verify-only > "$fixture/tampered-wheel.txt" 2>&1; then
  printf 'FAIL changed wheel pin accepted\n' >&2; exit 1
fi
grep -q 'CI_INPUT_FAIL meson wheel digest mismatch' "$fixture/tampered-wheel.txt"
run_verify > "$fixture/real-positive-after-negatives.txt" 2>&1
member='qemu-11.1.1/python/wheels/meson-1.11.1-py3-none-any.whl'
mkdir -p "$fixture/qemu-src-ro/qemu-11.1.1/python/wheels"
tar -xOf "$fixture/inputs/qemu-11.1.1.tar.xz" "$member" > "$fixture/qemu-src-ro/$member"
wheel_sha="$(grep '^meson_tool_sha256=' "$repo/tools/qemu-android/qemu-jni.lock" | cut -d= -f2)"
printf '%s  %s\n' "$wheel_sha" "$fixture/qemu-src-ro/$member" | sha256sum --check --status
python - "$(cygpath -w "$repo/tools/qemu-android/ci/build-in-container.sh")" <<'PY'
from pathlib import Path
import sys
text = Path(sys.argv[1]).read_text(encoding='utf-8')
consumer = '/qemu-src-ro/qemu-11.1.1/python/wheels/meson-1.11.1-py3-none-any.whl'
assert text.count(consumer) == 3, 'builder changed the wheel consumer path'
for name in ('glib-2.80.5.tar.xz', 'pixman-0.42.2.tar.gz', 'zlib-1.3.2.tar.gz',
             'pcre2-10.48.tar.gz', 'libffi-3.8.0.tar.gz', 'libiconv-1.19.tar.gz',
             'proxy-libintl-0.4.tar', 'dtc-b6910bec.tar'):
    assert '/inputs/qemu-android-deps-20260924' in text and name in text, name
print('CI_CONSUMER_PATHS_OK deps=8 wheel_sites=3')
PY
printf 'CI_INPUT_NEGATIVES_OK missing_dep=1 tampered_dep=1 missing_qemu=1 wheel_pin=1\n'

awk -F= '$1 != "glib_source_sha256"' "$repo/tools/qemu-android/qemu-jni.lock" > "$fixture/missing-key.lock"
if CI_REPO="$repo" CI_INPUT_ROOT="$fixture/inputs" CI_LOCK="$fixture/missing-key.lock" bash "$verify" --verify-only > "$fixture/missing-key.txt" 2>&1; then
  printf 'FAIL missing lock key accepted\n' >&2; exit 1
fi
grep -q 'CI_INPUT_FAIL missing or duplicate lock key: glib_source_sha256' "$fixture/missing-key.txt"

prepare="$repo/tools/qemu-android/scripts/prepare-qemu-payload.sh"
mkdir -p "$fixture/out/qemu-android-build1-payload-one" "$fixture/out/qemu-android-build1-payload-two"
printf 'synthetic' > "$fixture/out/qemu-android-build1-payload-one/qemu-system-aarch64"
CI_OUTPUT_ROOT="$fixture/out" bash "$prepare" --select-product > "$fixture/product-one.txt"
grep -q 'qemu-android-build1-payload-one/qemu-system-aarch64' "$fixture/product-one.txt"
printf 'synthetic' > "$fixture/out/qemu-android-build1-payload-two/qemu-system-aarch64"
if CI_OUTPUT_ROOT="$fixture/out" bash "$prepare" --select-product > "$fixture/multiple-products.txt" 2>&1; then
  printf 'FAIL multiple QEMU products accepted\n' >&2; exit 1
fi
grep -q 'expected exactly one qemu-system-aarch64 product, found 2' "$fixture/multiple-products.txt"
printf 'CI_FAIL_CLOSED_OK missing_key=1 multiple_products=1 single_product=1\n'
