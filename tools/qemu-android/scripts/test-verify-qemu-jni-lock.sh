#!/usr/bin/env bash
set -euo pipefail

TOOLS_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
VERIFY_SCRIPT="$TOOLS_DIRECTORY/scripts/verify-qemu-jni-lock.sh"
WORK_ROOT="${TICKET26_WORK_ROOT:-}"
[[ -n "$WORK_ROOT" && -d "$WORK_ROOT" && ! -L "$WORK_ROOT" ]] || {
  printf 'TICKET26_WORK_ROOT must be an approved ordinary directory\n' >&2
  exit 64
}
WORK_ROOT="$(realpath -e -- "$WORK_ROOT")"
WORK_DIRECTORY="$(mktemp -d "$WORK_ROOT/droidlayer-t26-qemu-lock-test.XXXXXX")"

cleanup() {
  local status="$?"
  trap - EXIT INT TERM
  case "$(realpath -- "$WORK_DIRECTORY")" in
    "$WORK_ROOT"/droidlayer-t26-qemu-lock-test.*)
      if [[ "$status" -eq 0 ]]; then
        rm -rf -- "$WORK_DIRECTORY"
      else
        printf 'QEMU lock failure fixture retained: %s\n' "$WORK_DIRECTORY" >&2
      fi
      ;;
    *)
      printf 'refusing to clean unsafe QEMU lock test directory\n' >&2
      status=70
      ;;
  esac
  exit "$status"
}
trap cleanup EXIT INT TERM

"$VERIFY_SCRIPT" "$TOOLS_DIRECTORY/qemu-jni.lock" >/dev/null
cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/changed.lock"
sed -i 's/^qemu_target_list=.*/qemu_target_list=x86_64-softmmu/' "$WORK_DIRECTORY/changed.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/changed.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted an altered target\n' >&2
  exit 1
fi

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/duplicate.lock"
printf '%s\n' 'qemu_version=11.1.1' >> "$WORK_DIRECTORY/duplicate.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/duplicate.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted a duplicate key\n' >&2
  exit 1
fi

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/qemu-digest.lock"
sed -i 's/^qemu_archive_sha256=.*/qemu_archive_sha256=0000000000000000000000000000000000000000000000000000000000000000/' \
  "$WORK_DIRECTORY/qemu-digest.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/qemu-digest.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted an altered QEMU archive digest\n' >&2
  exit 1
fi

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/ndk-digest.lock"
sed -i 's/^android_ndk_archive_sha256=.*/android_ndk_archive_sha256=0000000000000000000000000000000000000000000000000000000000000000/' \
  "$WORK_DIRECTORY/ndk-digest.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/ndk-digest.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted an altered Android NDK archive digest\n' >&2
  exit 1
fi

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/missing-dep.lock"
sed -i '/^glib_source_sha256=/d' "$WORK_DIRECTORY/missing-dep.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/missing-dep.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted a lock missing android dependency input pins\n' >&2
  exit 1
fi

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/missing-meson.lock"
sed -i '/^meson_tool_member=/d' "$WORK_DIRECTORY/missing-meson.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/missing-meson.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted a lock missing the meson tool pin\n' >&2
  exit 1
fi

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/missing-hostpkg.lock"
sed -i '/^hostpkg_gcc_sha256=/d' "$WORK_DIRECTORY/missing-hostpkg.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/missing-hostpkg.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted a lock missing host tool pin fields\n' >&2
  exit 1
fi

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/incomplete-configure.lock"
sed -i 's/,--cross-prefix=aarch64-linux-android26-,--cpu=aarch64,--prefix=\/usr$//' \
  "$WORK_DIRECTORY/incomplete-configure.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/incomplete-configure.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted configure_args missing the cross-prefix/cpu/prefix values used by the recorded build\n' >&2
  exit 1
fi

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/missing-vendored-path.lock"
sed -i '/^proxy_libintl_vendored_path=/d' "$WORK_DIRECTORY/missing-vendored-path.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/missing-vendored-path.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted a lock missing the vendored archive path pins\n' >&2
  exit 1
fi

synthetic="$WORK_DIRECTORY/synthetic-repo/tools/qemu-android"
mkdir -p "$synthetic/scripts" "$synthetic/vendor" "$synthetic/ci"
cp -- "$VERIFY_SCRIPT" "$synthetic/scripts/verify-qemu-jni-lock.sh"
cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$synthetic/qemu-jni.lock"
cp -- "$TOOLS_DIRECTORY/vendor/proxy-libintl-0.4.tar" "$synthetic/vendor/proxy-libintl-0.4.tar"
cp -- "$TOOLS_DIRECTORY/vendor/dtc-b6910bec.tar" "$synthetic/vendor/dtc-b6910bec.tar"
JDK_CLOSURE_NAME="$(grep '^ci_jdk_closure_manifest_path=' "$TOOLS_DIRECTORY/qemu-jni.lock" | cut -d= -f2- | sed 's|.*/||')"
MAVEN_CLOSURE_NAME="$(grep '^ci_maven_closure_manifest_path=' "$TOOLS_DIRECTORY/qemu-jni.lock" | cut -d= -f2- | sed 's|.*/||')"
GAILOCK_NAME="$(grep '^ga_inputs_lock_path=' "$TOOLS_DIRECTORY/qemu-jni.lock" | cut -d= -f2- | sed 's|.*/||')"
cp -- "$TOOLS_DIRECTORY/ci/$JDK_CLOSURE_NAME" "$synthetic/ci/$JDK_CLOSURE_NAME"
cp -- "$TOOLS_DIRECTORY/ci/$MAVEN_CLOSURE_NAME" "$synthetic/ci/$MAVEN_CLOSURE_NAME"
cp -- "$TOOLS_DIRECTORY/ci/$GAILOCK_NAME" "$synthetic/ci/$GAILOCK_NAME"
"$synthetic/scripts/verify-qemu-jni-lock.sh" "$synthetic/qemu-jni.lock" >"$WORK_DIRECTORY/vendored-correct.stdout.txt" 2>"$WORK_DIRECTORY/vendored-correct.stderr.txt"
printf 'synthetic-byte-change' >> "$synthetic/vendor/proxy-libintl-0.4.tar"
if "$synthetic/scripts/verify-qemu-jni-lock.sh" "$synthetic/qemu-jni.lock" >"$WORK_DIRECTORY/vendored-tampered.stdout.txt" 2>"$WORK_DIRECTORY/vendored-tampered.stderr.txt"; then
  printf 'QEMU JNI lock verifier accepted a vendored archive with changed bytes\n' >&2
  exit 1
fi
grep -q 'vendored archive digest mismatch for tools/qemu-android/vendor/proxy-libintl-0.4.tar' "$WORK_DIRECTORY/vendored-tampered.stderr.txt" || {
  printf 'vendored byte mutation did not reach the digest mismatch branch\n' >&2
  exit 1
}
printf 'VENDORED_BYTES_OK correct=accepted tampered=vendored_archive_digest_mismatch\n'
cp -- "$TOOLS_DIRECTORY/vendor/proxy-libintl-0.4.tar" "$synthetic/vendor/proxy-libintl-0.4.tar"

cp -- "$synthetic/ci/$JDK_CLOSURE_NAME" "$synthetic/ci/$JDK_CLOSURE_NAME.orig"
printf 'synthetic-byte-change' >> "$synthetic/ci/$JDK_CLOSURE_NAME"
if "$synthetic/scripts/verify-qemu-jni-lock.sh" "$synthetic/qemu-jni.lock" >"$WORK_DIRECTORY/jdkclosure-tampered.stdout.txt" 2>"$WORK_DIRECTORY/jdkclosure-tampered.stderr.txt"; then
  printf 'QEMU JNI lock verifier accepted a JDK closure manifest with changed bytes\n' >&2
  exit 1
fi
grep -q "closure manifest digest mismatch for tools/qemu-android/ci/$JDK_CLOSURE_NAME" "$WORK_DIRECTORY/jdkclosure-tampered.stderr.txt" || {
  printf 'JDK closure byte mutation did not reach the digest mismatch branch\n' >&2
  exit 1
}
mv -- "$synthetic/ci/$JDK_CLOSURE_NAME.orig" "$synthetic/ci/$JDK_CLOSURE_NAME"

cp -- "$synthetic/ci/$MAVEN_CLOSURE_NAME" "$synthetic/ci/$MAVEN_CLOSURE_NAME.orig"
printf 'synthetic-byte-change' >> "$synthetic/ci/$MAVEN_CLOSURE_NAME"
if "$synthetic/scripts/verify-qemu-jni-lock.sh" "$synthetic/qemu-jni.lock" >"$WORK_DIRECTORY/mavenclosure-tampered.stdout.txt" 2>"$WORK_DIRECTORY/mavenclosure-tampered.stderr.txt"; then
  printf 'QEMU JNI lock verifier accepted a Maven closure manifest with changed bytes\n' >&2
  exit 1
fi
grep -q "closure manifest digest mismatch for tools/qemu-android/ci/$MAVEN_CLOSURE_NAME" "$WORK_DIRECTORY/mavenclosure-tampered.stderr.txt" || {
  printf 'Maven closure byte mutation did not reach the digest mismatch branch\n' >&2
  exit 1
}
mv -- "$synthetic/ci/$MAVEN_CLOSURE_NAME.orig" "$synthetic/ci/$MAVEN_CLOSURE_NAME"
cp -- "$synthetic/ci/$GAILOCK_NAME" "$synthetic/ci/$GAILOCK_NAME.orig"
printf 'synthetic-byte-change' >> "$synthetic/ci/$GAILOCK_NAME"
if "$synthetic/scripts/verify-qemu-jni-lock.sh" "$synthetic/qemu-jni.lock" >"$WORK_DIRECTORY/gailock-tampered.stdout.txt" 2>"$WORK_DIRECTORY/gailock-tampered.stderr.txt"; then
  printf 'QEMU JNI lock verifier accepted a guest-assets inputs.lock copy with changed bytes\n' >&2
  exit 1
fi
grep -q "closure manifest digest mismatch for tools/qemu-android/ci/$GAILOCK_NAME" "$WORK_DIRECTORY/gailock-tampered.stderr.txt" || {
  printf 'inputs.lock byte mutation did not reach the digest mismatch branch\n' >&2
  exit 1
}
mv -- "$synthetic/ci/$GAILOCK_NAME.orig" "$synthetic/ci/$GAILOCK_NAME"
printf 'CLOSURE_MANIFEST_BYTES_OK correct=accepted tampered_rejected=2 ga_inputs_lock_tamper_rejected=1\n'

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/changed-glib.lock"
sed -i 's/^glib_source_sha256=.*/glib_source_sha256=0000000000000000000000000000000000000000000000000000000000000000/' \
  "$WORK_DIRECTORY/changed-glib.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/changed-glib.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted an altered glib source digest\n' >&2
  exit 1
fi

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/missing-libiconv.lock"
sed -i '/^libiconv_source_sha256=/d' "$WORK_DIRECTORY/missing-libiconv.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/missing-libiconv.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted a lock missing the libiconv input pin\n' >&2
  exit 1
fi

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/changed-libiconv.lock"
sed -i 's/^libiconv_source_sha256=.*/libiconv_source_sha256=0000000000000000000000000000000000000000000000000000000000000000/' \
  "$WORK_DIRECTORY/changed-libiconv.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/changed-libiconv.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted an altered libiconv source digest\n' >&2
  exit 1
fi

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/missing-libintl.lock"
sed -i '/^proxy_libintl_git_commit=/d' "$WORK_DIRECTORY/missing-libintl.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/missing-libintl.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted a lock missing the proxy-libintl input pin\n' >&2
  exit 1
fi

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/changed-libintl.lock"
sed -i 's/^proxy_libintl_git_archive_sha256=.*/proxy_libintl_git_archive_sha256=0000000000000000000000000000000000000000000000000000000000000000/' \
  "$WORK_DIRECTORY/changed-libintl.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/changed-libintl.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted an altered proxy-libintl archive digest\n' >&2
  exit 1
fi

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/missing-py3packaging.lock"
sed -i '/^hostpkg_python3_packaging_sha256=/d' "$WORK_DIRECTORY/missing-py3packaging.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/missing-py3packaging.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted a lock missing the python3-packaging pin\n' >&2
  exit 1
fi

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/missing-shim.lock"
sed -i '/^android_shim_source_sha256=/d' "$WORK_DIRECTORY/missing-shim.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/missing-shim.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted a lock missing the android shim source pin\n' >&2
  exit 1
fi

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/changed-shim.lock"
sed -i 's/^android_shim_source_sha256=.*/android_shim_source_sha256=0000000000000000000000000000000000000000000000000000000000000000/' \
  "$WORK_DIRECTORY/changed-shim.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/changed-shim.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted an altered android shim source digest\n' >&2
  exit 1
fi

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/missing-shim-header.lock"
sed -i '/^android_shim_header_sha256=/d' "$WORK_DIRECTORY/missing-shim-header.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/missing-shim-header.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted a lock missing the android shim header pin\n' >&2
  exit 1
fi

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/changed-shim-header.lock"
sed -i 's/^android_shim_header_sha256=.*/android_shim_header_sha256=0000000000000000000000000000000000000000000000000000000000000000/' \
  "$WORK_DIRECTORY/changed-shim-header.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/changed-shim-header.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted an altered android shim header digest\n' >&2
  exit 1
fi

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/missing-hashseed.lock"
sed -i '/^python_hash_seed=/d' "$WORK_DIRECTORY/missing-hashseed.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/missing-hashseed.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted a lock missing the python hash seed pin\n' >&2
  exit 1
fi

# --- section-7 lock-sync batch (2026-09-29 user ruling): CI Linux provisioning
# pins. The verifier must reject any lock missing those pins, any altered pin
# value, and any in-repo closure manifest whose bytes drift from the pinned
# digest; and it must accept the synced worktree lock itself.
cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/missing-cipins.lock"
sed -i '/^ci_/d' "$WORK_DIRECTORY/missing-cipins.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/missing-cipins.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted a lock missing the CI Linux provisioning pins\n' >&2
  exit 1
fi

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/missing-cibuildtools.lock"
sed -i '/^ci_build_tools_sha256=/d' "$WORK_DIRECTORY/missing-cibuildtools.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/missing-cibuildtools.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted a lock missing the CI build-tools pin\n' >&2
  exit 1
fi

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/missing-ciplatform.lock"
sed -i '/^ci_platform_official_sha1=/d' "$WORK_DIRECTORY/missing-ciplatform.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/missing-ciplatform.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted a lock missing the CI platform official SHA-1 pin\n' >&2
  exit 1
fi

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/changed-cibuildtools.lock"
sed -i 's/^ci_build_tools_sha256=.*/ci_build_tools_sha256=0000000000000000000000000000000000000000000000000000000000000000/' \
  "$WORK_DIRECTORY/changed-cibuildtools.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/changed-cibuildtools.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted an altered CI build-tools digest\n' >&2
  exit 1
fi

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/changed-ciplatform.lock"
sed -i 's/^ci_platform_sha256=.*/ci_platform_sha256=0000000000000000000000000000000000000000000000000000000000000000/' \
  "$WORK_DIRECTORY/changed-ciplatform.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/changed-ciplatform.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted an altered CI platform digest\n' >&2
  exit 1
fi

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/changed-cigradle.lock"
sed -i 's/^ci_gradle_distribution_sha256=.*/ci_gradle_distribution_sha256=0000000000000000000000000000000000000000000000000000000000000000/' \
  "$WORK_DIRECTORY/changed-cigradle.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/changed-cigradle.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted an altered CI Gradle distribution digest\n' >&2
  exit 1
fi

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/changed-cijdkmain.lock"
sed -i 's/^ci_jdk_main_deb_sha256=.*/ci_jdk_main_deb_sha256=0000000000000000000000000000000000000000000000000000000000000000/' \
  "$WORK_DIRECTORY/changed-cijdkmain.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/changed-cijdkmain.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted an altered CI JDK main-deb digest\n' >&2
  exit 1
fi

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/missing-jdkclosure.lock"
sed -i '/^ci_jdk_closure_manifest_sha256=/d' "$WORK_DIRECTORY/missing-jdkclosure.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/missing-jdkclosure.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted a lock missing the JDK closure manifest reference\n' >&2
  exit 1
fi

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/missing-mavenclosure.lock"
sed -i '/^ci_maven_closure_manifest_sha256=/d' "$WORK_DIRECTORY/missing-mavenclosure.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/missing-mavenclosure.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted a lock missing the Maven closure manifest reference\n' >&2
  exit 1
fi

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/changed-mavenga.lock"
sed -i 's/^ci_maven_closure_ga_count=.*/ci_maven_closure_ga_count=147/' \
  "$WORK_DIRECTORY/changed-mavenga.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/changed-mavenga.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted an altered Maven closure GA count\n' >&2
  exit 1
fi

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/changed-licensegaps.lock"
sed -i 's/^ci_maven_license_gaps=.*/ci_maven_license_gaps=net.java:jvnet-parent:1/' \
  "$WORK_DIRECTORY/changed-licensegaps.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/changed-licensegaps.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted an altered parent-POM license gap list\n' >&2
  exit 1
fi

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/missing-aapt2.lock"
sed -i '/^ci_aapt2_maven_sha256=/d' "$WORK_DIRECTORY/missing-aapt2.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/missing-aapt2.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted a lock missing the aapt2-linux maven pin\n' >&2
  exit 1
fi

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/changed-aapt2.lock"
sed -i 's/^ci_aapt2_maven_sha256=.*/ci_aapt2_maven_sha256=0000000000000000000000000000000000000000000000000000000000000000/' \
  "$WORK_DIRECTORY/changed-aapt2.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/changed-aapt2.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted an altered aapt2-linux maven digest\n' >&2
  exit 1
fi

# --- section-7 lock-sync batch (2026-09-29 user ruling): guest-assets
# producer/consumer pins. The verifier must reject any lock missing the ga_*
# pins, any altered pin value, and any in-repo inputs.lock copy whose bytes
# drift from the pinned digest; and it must accept the synced worktree lock.
cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/missing-gapins.lock"
sed -i '/^ga_/d' "$WORK_DIRECTORY/missing-gapins.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/missing-gapins.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted a lock missing the guest-assets producer/consumer pins\n' >&2
  exit 1
fi

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/missing-gaepoch.lock"
sed -i '/^ga_source_date_epoch=/d' "$WORK_DIRECTORY/missing-gaepoch.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/missing-gaepoch.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted a lock missing the guest-assets epoch pin\n' >&2
  exit 1
fi

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/changed-gabaseimage.lock"
sed -i 's/^ga_base_image_sha256=.*/ga_base_image_sha256=0000000000000000000000000000000000000000000000000000000000000000/' \
  "$WORK_DIRECTORY/changed-gabaseimage.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/changed-gabaseimage.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted an altered guest-assets base Image digest\n' >&2
  exit 1
fi

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/changed-gabaseinitramfs.lock"
sed -i 's/^ga_base_initramfs_sha256=.*/ga_base_initramfs_sha256=0000000000000000000000000000000000000000000000000000000000000000/' \
  "$WORK_DIRECTORY/changed-gabaseinitramfs.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/changed-gabaseinitramfs.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted an altered guest-assets base initramfs digest\n' >&2
  exit 1
fi

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/changed-gafinal.lock"
sed -i 's/^ga_initramfs_sha256=.*/ga_initramfs_sha256=0000000000000000000000000000000000000000000000000000000000000000/' \
  "$WORK_DIRECTORY/changed-gafinal.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/changed-gafinal.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted an altered four-member initramfs digest\n' >&2
  exit 1
fi

# --- section-7 lock-sync batch (2026-09-30 user ruling): APK postbuild pins.
# The verifier must reject any lock missing the apk_* pins and any altered pin value.
cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/missing-apksha.lock"
sed -i '/^apk_sha256=/d' "$WORK_DIRECTORY/missing-apksha.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/missing-apksha.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted a lock missing apk_sha256\n' >&2
  exit 1
fi

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/changed-apksha.lock"
sed -i 's/^apk_sha256=.*/apk_sha256=0000000000000000000000000000000000000000000000000000000000000000/' \
  "$WORK_DIRECTORY/changed-apksha.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/changed-apksha.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted an altered apk_sha256 digest\n' >&2
  exit 1
fi

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/missing-apkdtneeded.lock"
sed -i '/^apk_dt_needed_closure=/d' "$WORK_DIRECTORY/missing-apkdtneeded.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/missing-apkdtneeded.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted a lock missing apk_dt_needed_closure\n' >&2
  exit 1
fi

cp -- "$TOOLS_DIRECTORY/qemu-jni.lock" "$WORK_DIRECTORY/changed-apkdtneeded.lock"
sed -i 's/^apk_dt_needed_closure=.*/apk_dt_needed_closure=libc.so/' \
  "$WORK_DIRECTORY/changed-apkdtneeded.lock"
if "$VERIFY_SCRIPT" "$WORK_DIRECTORY/changed-apkdtneeded.lock" >/dev/null 2>&1; then
  printf 'QEMU JNI lock verifier accepted an altered apk_dt_needed_closure\n' >&2
  exit 1
fi

printf 'DROIDLAYER_QEMU_JNI_LOCK_TEST_OK altered_target_rejected=1 duplicate_key_rejected=1 fixed_digest_rejected=2 vendored_byte_mismatch_rejected=1 closure_manifest_tamper_rejected=2 ci_pins_missing_or_altered_rejected=13 ga_pins_missing_or_altered_rejected=5 apk_pins_missing_or_altered_rejected=4\n'
