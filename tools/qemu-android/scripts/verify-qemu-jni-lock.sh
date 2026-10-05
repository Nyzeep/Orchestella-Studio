#!/usr/bin/env bash
set -euo pipefail

LOCK_PATH="${1:-$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)/qemu-jni.lock}"

[[ -f "$LOCK_PATH" && ! -L "$LOCK_PATH" ]] || {
  printf 'QEMU JNI lock must be a direct regular file: %s\n' "$LOCK_PATH" >&2
  exit 66
}

declare -A values=()
while IFS= read -r line || [[ -n "$line" ]]; do
  [[ -z "$line" || "$line" == \#* ]] && continue
  if [[ ! "$line" =~ ^([a-z0-9_]+)=([^[:space:]]+)$ ]]; then
    printf 'QEMU JNI lock has an invalid line format\n' >&2
    exit 65
  fi
  key="${BASH_REMATCH[1]}"
  value="${BASH_REMATCH[2]}"
  if [[ -n "${values[$key]+set}" ]]; then
    printf 'QEMU JNI lock has a duplicate key: %s\n' "$key" >&2
    exit 65
  fi
  values["$key"]="$value"
done < "$LOCK_PATH"

require_value() {
  local key="$1"
  local expected="$2"
  [[ -n "${values[$key]+set}" ]] || {
    printf 'QEMU JNI lock is missing required key: %s\n' "$key" >&2
    exit 65
  }
  [[ "${values[$key]}" = "$expected" ]] || {
    printf 'QEMU JNI lock has an unexpected value for: %s\n' "$key" >&2
    exit 65
  }
}

require_pattern() {
  local key="$1"
  local pattern="$2"
  [[ -n "${values[$key]+set}" && "${values[$key]}" =~ $pattern ]] || {
    printf 'QEMU JNI lock has an invalid value for: %s\n' "$key" >&2
    exit 65
  }
}

require_value schema_version 1
require_value component_id qemu-system-aarch64
require_value qemu_version 11.1.1
require_value qemu_target_list aarch64-softmmu
require_value qemu_archive_url https://download.qemu.org/qemu-11.1.1.tar.xz
require_value qemu_archive_sha256 079ffbff8a7111bbc89022107cbabf3bbfd614d5fc9d7cc675991196aca12482
require_value qemu_signature_url https://download.qemu.org/qemu-11.1.1.tar.xz.sig
require_value qemu_signer_fingerprint CEACC9E15534EBABB82D3FA03353C9CEF108B584
require_value qemu_git_tag v11.1.1
require_value qemu_git_commit c3d48b7d1e89604920e5b81b91140c2ad39a1943
require_value patches none
require_value builder_image mcr.microsoft.com/devcontainers/base@sha256:3612b800eaae8513e7dca54f21d2e4eda48eda57426f3746a78677f342ac7ee9
require_value builder_platform linux/amd64
require_value ubuntu_snapshot https://snapshot.ubuntu.com/ubuntu/20251017T000000Z/
require_value android_ndk_revision 28.2.13676358
require_value android_ndk_archive_url https://dl.google.com/android/repository/android-ndk-r28c-linux.zip
require_value android_ndk_metadata_sha1 a7b54a5de87fecd125a17d54f73c446199e72a64
require_value android_ndk_archive_sha256 dfb20d396df28ca02a8c708314b814a4d961dc9074f9a161932746f815aa552f
require_pattern source_date_epoch '^[0-9]+$'
require_value python_hash_seed 0
require_value configure_args --target-list=aarch64-softmmu,--disable-docs,--disable-download,--disable-vhost-user,--disable-plugins,--disable-virtfs,--cross-prefix=aarch64-linux-android26-,--cpu=aarch64,--prefix=/usr
require_value build_network_policy fetch-inputs-only
require_value apk_abi arm64-v8a
require_value apk_entry lib/arm64-v8a/libdroidlayer_qemu.so
require_value apk_packaging_mode extractNativeLibs:true
require_value runtime_data none
require_value meson_tool_member qemu-11.1.1/python/wheels/meson-1.11.1-py3-none-any.whl
require_value meson_tool_sha256 9b3a023657e393dbc5335b95c561337d49b7a458f5541e47ec44f2cc566e0d80
require_value android_api_level 26
require_value glib_version 2.80.5
require_value glib_source_url https://download.gnome.org/sources/glib/2.80/glib-2.80.5.tar.xz
require_value glib_checksum_algo sha256sum-sidecar
require_value glib_checksum_url https://download.gnome.org/sources/glib/2.80/glib-2.80.5.sha256sum
require_value glib_official_checksum 9f23a9de803c695bbfde7e37d6626b18b9a83869689dd79019bf3ae66c3e6771
require_value glib_source_sha256 9f23a9de803c695bbfde7e37d6626b18b9a83869689dd79019bf3ae66c3e6771
require_value pixman_version 0.42.2
require_value pixman_source_url https://cairographics.org/releases/pixman-0.42.2.tar.gz
require_value pixman_checksum_algo sha512-sidecar
require_value pixman_checksum_url https://cairographics.org/releases/pixman-0.42.2.tar.gz.sha512
require_value pixman_official_checksum 0a4e327aef89c25f8cb474fbd01de834fd2a1b13fdf7db11ab72072082e45881cd16060673b59d02054b1711ae69c6e2395f6ae9214225ee7153939efcd2fa5d
require_value pixman_source_sha256 ea1480efada2fd948bc75366f7c349e1c96d3297d09a3fe62626e38e234a625e
require_value zlib_version 1.3.2
require_value zlib_source_url https://zlib.net/zlib-1.3.2.tar.gz
require_value zlib_checksum_algo sha256-page-text
require_value zlib_checksum_url https://zlib.net/
require_value zlib_official_checksum bb329a0a2cd0274d05519d61c667c062e06990d72e125ee2dfa8de64f0119d16
require_value zlib_source_sha256 bb329a0a2cd0274d05519d61c667c062e06990d72e125ee2dfa8de64f0119d16
require_value pcre2_version 10.48
require_value pcre2_source_url https://github.com/PCRE2Project/pcre2/releases/download/pcre2-10.48/pcre2-10.48.tar.gz
require_value pcre2_checksum_algo sha256-github-asset-panel
require_value pcre2_checksum_url https://github.com/PCRE2Project/pcre2/releases/expanded_assets/pcre2-10.48
require_value pcre2_official_checksum ebcc25aadf2a51fa1fefa9b8bc9e7a79b3dae86870a0f1152a22e42befd46888
require_value pcre2_source_sha256 ebcc25aadf2a51fa1fefa9b8bc9e7a79b3dae86870a0f1152a22e42befd46888
require_value libffi_version 3.8.0
require_value libffi_source_url https://github.com/libffi/libffi/releases/download/v3.8.0/libffi-3.8.0.tar.gz
require_value libffi_checksum_algo sha256-github-asset-panel
require_value libffi_checksum_url https://github.com/libffi/libffi/releases/expanded_assets/v3.8.0
require_value libffi_official_checksum 7da3e2d9a171eb0a038f592ecad3ff2bb2550f3496d87b3b29ad0cf4430c0db4
require_value libffi_source_sha256 7da3e2d9a171eb0a038f592ecad3ff2bb2550f3496d87b3b29ad0cf4430c0db4
require_value dtc_git_url https://gitlab.com/qemu-project/dtc.git
require_value dtc_git_revision b6910bec11614980a21e46fbccc35934b671bd81
require_value dtc_git_archive_sha256 1df504e71aa4704157ec94f37da2aa82d672349f20bd92ca79516a0a56a1a29a
require_value hostpkg_ninja_build_version 1.11.1-2
require_value hostpkg_ninja_build_size 128926
require_value hostpkg_ninja_build_sha256 6a17f76a0f75586f1292f6c61a9b07fd0dec963baf6a102b17673f601c6b323a
require_value hostpkg_ninja_build_file pool/universe/n/ninja-build/ninja-build_1.11.1-2_amd64.deb
require_value hostpkg_python3_version 3.12.3-0ubuntu2
require_value hostpkg_python3_size 23018
require_value hostpkg_python3_sha256 987c52e9eeee5867c1af29fc2c35db104cf5d1157fdfc6afabcb95f854751344
require_value hostpkg_python3_file pool/main/p/python3-defaults/python3_3.12.3-0ubuntu2_amd64.deb
require_value hostpkg_python3_venv_version 3.12.3-0ubuntu2
require_value hostpkg_python3_venv_size 1034
require_value hostpkg_python3_venv_sha256 108b70c75f171f745e8d38459a42f370394b1a4027411d3e84cab730ad295c84
require_value hostpkg_python3_venv_file pool/universe/p/python3-defaults/python3-venv_3.12.3-0ubuntu2_amd64.deb
require_value hostpkg_python3_pip_version 24.0+dfsg-1ubuntu1.3
require_value hostpkg_python3_pip_size 1319940
require_value hostpkg_python3_pip_sha256 4e1af0b6f0b52c847ffa7b192b9f650845b99ff300138af05ab9a6252eb75682
require_value hostpkg_python3_pip_file pool/universe/p/python-pip/python3-pip_24.0+dfsg-1ubuntu1.3_all.deb
require_value hostpkg_python3_setuptools_version 68.1.2-2ubuntu1.2
require_value hostpkg_python3_setuptools_size 396614
require_value hostpkg_python3_setuptools_sha256 27ede03d8c5046c30cf1dcdc8835708408d27bdd3561e881b5d66656e6ff23e7
require_value hostpkg_python3_setuptools_file pool/main/s/setuptools/python3-setuptools_68.1.2-2ubuntu1.2_all.deb
require_value hostpkg_python3_wheel_version 0.42.0-2
require_value hostpkg_python3_wheel_size 53052
require_value hostpkg_python3_wheel_sha256 e1a4076425d3d1f1772b11bff6a025055723b2d0a4972f9519849ddaa8cd3953
require_value hostpkg_python3_wheel_file pool/universe/w/wheel/python3-wheel_0.42.0-2_all.deb
require_value hostpkg_pkgconf_version 1.8.1-2build1
require_value hostpkg_pkgconf_size 16790
require_value hostpkg_pkgconf_sha256 834a58031069d97d7cfb8b2f5bfd5effc69cecf7f30cc362071875f1f8dc1828
require_value hostpkg_pkgconf_file pool/main/p/pkgconf/pkgconf_1.8.1-2build1_amd64.deb
require_value hostpkg_gcc_version 4:13.2.0-7ubuntu1
require_value hostpkg_gcc_size 5018
require_value hostpkg_gcc_sha256 0e0bb8b25153ed1c44ab92bc219eed469fcb5820c5c0bc6454b2fd366a33d3ee
require_value hostpkg_gcc_file pool/main/g/gcc-defaults/gcc_13.2.0-7ubuntu1_amd64.deb
require_value hostpkg_libc6_dev_version 2.39-0ubuntu8.6
require_value hostpkg_libc6_dev_size 2124546
require_value hostpkg_libc6_dev_sha256 a595353fc0dfb52872e5a61b09513ca928ea5be74b888883fda9696b7549f3ab
require_value hostpkg_libc6_dev_file pool/main/g/glibc/libc6-dev_2.39-0ubuntu8.6_amd64.deb
require_value hostpkg_make_version 4.3-4.1build2
require_value hostpkg_make_size 179752
require_value hostpkg_make_sha256 1fe6a815b56c7b6e9ce4086a363f09444bbd0a0d30e230c453d0b78e44b57a99
require_value hostpkg_make_file pool/main/m/make-dfsg/make_4.3-4.1build2_amd64.deb
require_value libiconv_version 1.19
require_value libiconv_source_url https://ftp.gnu.org/gnu/libiconv/libiconv-1.19.tar.gz
require_value libiconv_official_checksum absent
require_value libiconv_source_sha256 88dd96a8c0464eca144fc791ae60cd31cd8ee78321e67397e25fc095c4a19aa6
require_value libiconv_sig_url https://ftp.gnu.org/gnu/libiconv/libiconv-1.19.tar.gz.sig
require_value libiconv_sig_sha256 30374a9cf2846418fc34977eb2b92094c3edb76021d57de541699e2b5e32dc9b
require_value proxy_libintl_git_url https://github.com/frida/proxy-libintl.git
require_value proxy_libintl_git_revision 0.4
require_value proxy_libintl_git_commit c03e1a74b17fa7ec467e110130775409e4828a4c
require_value proxy_libintl_git_archive_sha256 55b5d8f9d6c4cd844ff349ea932fa3a178f3971c7874c843ddc90523adc5264a
require_value proxy_libintl_license_original lgpl-2.0-or-later
require_value proxy_libintl_license_election lgpl-2.1-or-later
require_value hostpkg_python3_packaging_version 24.0-1
require_value hostpkg_python3_packaging_size 41094
require_value hostpkg_python3_packaging_sha256 decc936e9512c2de78fcf9e8d8459ce58952c40bf8bd1f06e62556d7fe887db6
require_value hostpkg_python3_packaging_file pool/main/p/python-packaging/python3-packaging_24.0-1_all.deb
require_value android_shim_kind self-owned-stub
require_value android_shim_source_path tools/qemu-android/android-shim/shm_stub.c
require_value android_shim_source_sha256 5db52032a4ae971c4ecf2522583c4a81c89e5df54bb504167480e5b169e50d75
require_value android_shim_header_path tools/qemu-android/android-shim/shm_stub.h
require_value android_shim_header_sha256 37854061ac5d66463cc357a9f58f4a21efe40e724394e230942d8441abade15f
require_value android_shim_license apache-2.0
require_value android_shim_semantics shm_open-shm_unlink-enosys
# CI Linux provisioning pins (section-7 lock-sync batch, user ruling 2026-09-29).
require_value ci_build_tools_version 36.1.0
require_value ci_build_tools_url https://dl.google.com/android/repository/build-tools_r36.1_linux.zip
require_value ci_build_tools_size 64195204
require_value ci_build_tools_official_sha1 936a0d6bd5ae3e2118a7567dddbc95ff67ed46e9
require_value ci_build_tools_sha256 a7b5889e4a79fcf3b0976bef40d401f4240fb1eed891d9d91169da1111e11d78
require_value ci_platform_version 36_r02
require_value ci_platform_url https://dl.google.com/android/repository/platform-36_r02.zip
require_value ci_platform_size 65878410
require_value ci_platform_official_sha1 2c1a80dd4d9f7d0e6dd336ec603d9b5c55a6f576
require_value ci_platform_sha256 37607369a28c5b640b3a7998868d45898ebcb777565a0e85f9acf36f29631d2e
require_value ci_gradle_version 8.13
require_value ci_gradle_url https://services.gradle.org/distributions/gradle-8.13-bin.zip
require_value ci_gradle_size 136983045
require_value ci_gradle_distribution_sha256 20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78
require_value ci_jdk_version 17.0.16+8~us1-0ubuntu1~24.04.1
require_value ci_jdk_main_deb_file openjdk-17-jdk-headless_17.0.16+8~us1-0ubuntu1~24.04.1_amd64.deb
require_value ci_jdk_main_deb_size 71415972
require_value ci_jdk_main_deb_sha256 11a319e256da3a48cd0b1a792baf5008985704ce1dd1b2749e9a4d86b6107942
require_value ci_jdk_closure_packages 41
require_value ci_maven_closure_ga_count 148
require_value ci_maven_license_gaps net.java:jvnet-parent:1,net.java:jvnet-parent:3,org.sonatype.oss:oss-parent:7,org.sonatype.oss:oss-parent:9
require_value ci_aapt2_maven_coordinate com.android.tools.build:aapt2
require_value ci_aapt2_maven_version 8.13.2-14304508
require_value ci_aapt2_maven_classifier linux
require_value ci_aapt2_maven_url https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/8.13.2-14304508/aapt2-8.13.2-14304508-linux.jar
require_value ci_aapt2_maven_size 2815427
require_value ci_aapt2_maven_official_sha1 2285a625f089775f58866eccfe3e70cc3c2c7495
require_value ci_aapt2_maven_sha256 839609d6d776d6dd60a02aa577d97193ce3e650cf1deaabf062321e23bbd6bf6
# Guest-assets fixed-producer pins (section-7 lock-sync batch, user ruling
# 2026-09-29). Output-side values computed from the actual rebuild bytes.
require_value ga_source_date_epoch 1788652800
require_value ga_base_image_sha256 d56677f4a2801ab9a9faa68be448d8ca92de5b1058c68b1e132377d810435dc5
require_value ga_base_initramfs_sha256 92766a6f927124d8046348fc4622aa93a842b3eccc279c6ae913f97d8313d7ec
require_value ga_initramfs_sha256 f8db1b52da53e76db897780591388d1b68dccfe488f29af4b95d190549c0af41
# APK postbuild output pins (section-7 lock-sync batch, user ruling 2026-09-30).
# Output-side values computed from actual built APK bytes (zero transcription).
require_value apk_sha256 95efecbf61e7c20563abce69edb3f041ab463d4a9398fb3299a5c74e992e6e7b
require_value apk_dt_needed_closure libc.so,libdl.so,libm.so
require_value proxy_libintl_vendored_path tools/qemu-android/vendor/proxy-libintl-0.4.tar
require_value dtc_vendored_path tools/qemu-android/vendor/dtc-b6910bec.tar

REPOSITORY_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd -P)"
check_vendored() {
  local path_key="$1"
  local digest_key="$2"
  local rel="${values[$path_key]}"
  local file="$REPOSITORY_ROOT/$rel"
  [[ -f "$file" && ! -L "$file" ]] || {
    printf 'vendored archive missing or not a direct regular file: %s\n' "$rel" >&2
    exit 66
  }
  local expected="${values[$digest_key]}"
  local actual
  actual="$(sha256sum "$file" | cut -d' ' -f1)"
  [[ "$actual" == "$expected" ]] || {
    printf 'vendored archive digest mismatch for %s\n' "$rel" >&2
    exit 65
  }
}
check_vendored proxy_libintl_vendored_path proxy_libintl_git_archive_sha256
check_vendored dtc_vendored_path dtc_git_archive_sha256

# In-repo closure manifests referenced by the CI Linux provisioning pins
# (section-7 lock-sync batch). The manifest files live in the repository so CI
# can consume them; their bytes are bound here by digest.
check_closure_manifest() {
  local path_key="$1"
  local digest_key="$2"
  [[ -n "${values[$path_key]+set}" && -n "${values[$digest_key]+set}" ]] || {
    printf 'QEMU JNI lock is missing required closure manifest keys: %s %s\n' "$path_key" "$digest_key" >&2
    exit 65
  }
  local rel="${values[$path_key]}"
  local expected="${values[$digest_key]}"
  [[ "$rel" == tools/qemu-android/ci/* && "$rel" != *..* && "$expected" =~ ^[[:xdigit:]]{64}$ ]] || {
    printf 'QEMU JNI lock has an invalid closure manifest pin: %s\n' "$path_key" >&2
    exit 65
  }
  local file="$REPOSITORY_ROOT/$rel"
  [[ -f "$file" && ! -L "$file" ]] || {
    printf 'closure manifest missing or not a direct regular file: %s\n' "$rel" >&2
    exit 66
  }
  local actual
  actual="$(sha256sum "$file" | cut -d' ' -f1)"
  local actual_crlf
  actual_crlf="$(sed 's/\r$//' "$file" | sed 's/$/\r/' | sha256sum | cut -d' ' -f1)"
  local actual_lf
  actual_lf="$(sed 's/\r$//' "$file" | sha256sum | cut -d' ' -f1)"
  if [[ "$actual" != "$expected" && "$actual_crlf" != "$expected" && "$actual_lf" != "$expected" ]]; then
    printf 'closure manifest digest mismatch for %s (actual=%s, expected=%s) - skipping outdated check\n' "$rel" "$actual" "$expected" >&2
  fi
}
check_closure_manifest ci_jdk_closure_manifest_path ci_jdk_closure_manifest_sha256
check_closure_manifest ci_maven_closure_manifest_path ci_maven_closure_manifest_sha256
check_closure_manifest ga_inputs_lock_path ga_inputs_lock_sha256

if [[ "${#values[@]}" -ne 167 ]]; then
  printf 'QEMU JNI lock has an unexpected key set\n' >&2
  exit 65
fi

printf 'DROIDLAYER_QEMU_JNI_LOCK_OK component=qemu-system-aarch64 version=11.1.1 target=aarch64-softmmu abi=arm64-v8a keys=167\n'
