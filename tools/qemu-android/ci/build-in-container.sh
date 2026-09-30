#!/usr/bin/env bash
# Ticket 26 CI build entrypoint (in-container). Canonical copy of the
# evidence build script c-android-incontainer-20260925.sh (same stages and
# pins; see QEMU-BUILD-INPUTS build_entrypoint records for the executed
# evidence copy and its hash). Called by .github/workflows/qemu-android.yml
# inside the digest-pinned builder container.

#    LDFLAGS so meson cc.has_function('shm_open') links and the librt fallback
#    is never taken (user ruling 2026-09-25 option 1);
#  - recorded config fix: QEMU 11.1.1 forces fdt to 'disabled' under
#    --wrap-mode=nodownload (meson.build:2043-2064 skips the internal dtc
#    subproject route), so aarch64-softmmu dies at meson.build:3445. Fix: build
#    libfdt from the SAME lock-pinned frozen dtc archive into $PREFIX so the
#    system-route cc.find_library('fdt') succeeds. Same source bytes/revision/
#    hash, zero new sources/endpoints, no QEMU patch (policy §7 ordinary fix).
# Endpoint discipline: apt ONLY snapshot.ubuntu.com/ubuntu/20251017T000000Z/ (rule 3);
# no deb-src; no wrap downloads (configure --disable-download -> meson --wrap-mode=nodownload).
set -euo pipefail

STAGE="${1:-all}"
SNAP='https://snapshot.ubuntu.com/ubuntu/20251017T000000Z/'
NDK='/ndk/android-ndk-r28c'
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
SYSROOT="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
QEMU_ARCHIVE='/inputs/qemu-11.1.1.tar.xz'
QEMU_ARCHIVE_SHA='079ffbff8a7111bbc89022107cbabf3bbfd614d5fc9d7cc675991196aca12482'
DEPS='/inputs/qemu-android-deps-20260924'
DTC_ARCHIVE_SHA='1df504e71aa4704157ec94f37da2aa82d672349f20bd92ca79516a0a56a1a29a'
STUB_SRC='/workspace/tools/qemu-android/android-shim/shm_stub.c'
STUB_SHA='5db52032a4ae971c4ecf2522583c4a81c89e5df54bb504167480e5b169e50d75'
SHIM_HDR_SRC='/workspace/tools/qemu-android/android-shim/shm_stub.h'
SHIM_HDR_SHA='37854061ac5d66463cc357a9f58f4a21efe40e724394e230942d8441abade15f'
WORK='/io/.work/c-android-20260925'
PREFIX='/io/prefix'
OUT='/io/out'
export SOURCE_DATE_EPOCH=0
# Determinism: decodetree.py-generated decode-*.c.inc pattern order varies with
# Python hash randomization (two clean builds produced same-size, byte-different
# .inc and different translate*.c.o); pin the seed like SOURCE_DATE_EPOCH.
export PYTHONHASHSEED=0
export CC="$TC/aarch64-linux-android26-clang"
export CXX="$TC/aarch64-linux-android26-clang++"
export AR="$TC/llvm-ar" RANLIB="$TC/llvm-ranlib" STRIP="$TC/llvm-strip"
export NM="$TC/llvm-nm" OBJCOPY="$TC/llvm-objcopy" READELF="$TC/llvm-readelf"
export PATH="$TC:/usr/sbin:/usr/bin:/sbin:/bin"
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig:$PREFIX/share/pkgconfig"
export PKG_CONFIG_SYSROOT_DIR='/'
export CFLAGS="-I$PREFIX/include" LDFLAGS="-L$PREFIX/lib"
export CPPFLAGS="-I$PREFIX/include"

log() { printf '[c-android %s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }
die() { printf 'C_ANDROID_DIE: %s\n' "$*"; exit 1; }

clean_owned() {
  case "$1" in
    "$WORK"/deps-src|"$WORK"/qemu-build|"$WORK"/qemu-src|"$WORK"/venv|"$WORK"/scratch|"$WORK"/shim|"$WORK"/fdt-src|"$WORK"/qemu-src-build1|"$WORK"/qemu-src-build2|"$WORK"/qemu-build-build1|"$WORK"/qemu-build-build2)
      rm -rf -- "$1" ;;
    *) die "refusing to clean unsafe path: $1" ;;
  esac
}

build_shim() {
  clean_owned "$WORK/shim"
  mkdir -p "$WORK/shim"
  log "shim: verify self-owned source hash (lock android_shim_source_sha256)"
  printf '%s  %s\n' "$STUB_SHA" "$STUB_SRC" | sha256sum --check --status || die "shim source hash mismatch"
  printf '%s  %s\n' "$SHIM_HDR_SHA" "$SHIM_HDR_SRC" | sha256sum --check --status || die "shim header hash mismatch"
  log "shim: compile -fPIC static archive"
  "$CC" -fPIC -std=c11 -Wall -Wextra -Werror -c "$STUB_SRC" -o "$WORK/shim/shm_stub.o" \
    || die "shim compile failed"
  "$AR" rcs "$WORK/shim/libdroidlayer_shm_stub.a" "$WORK/shim/shm_stub.o" \
    || die "shim archive failed"
  log "SHIM_OK archive=$WORK/shim/libdroidlayer_shm_stub.a"
}

stage_apt() {
  log "apt: snapshot-only source + pinned installs"
  mkdir -p /etc/apt/sources.list.d
  for f in /etc/apt/sources.list /etc/apt/sources.list.d/*.list /etc/apt/sources.list.d/*.sources; do
    [ -e "$f" ] || continue
    mv -- "$f" /root/"$(basename "$f")".disabled-orig
  done
  printf 'Types: deb\nURIs: %s\nSuites: noble noble-updates noble-security\nComponents: main universe\nSigned-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n' "$SNAP" \
    > /etc/apt/sources.list.d/droidlayer-snapshot.sources
  apt-get update -qq
  local pins=(
    'make=4.3-4.1build2' 'gcc=4:13.2.0-7ubuntu1' 'libc6-dev=2.39-0ubuntu8.6'
    'ninja-build=1.11.1-2' 'python3=3.12.3-0ubuntu2' 'python3-venv=3.12.3-0ubuntu2'
    'python3-pip=24.0+dfsg-1ubuntu1.3' 'python3-setuptools=68.1.2-2ubuntu1.2'
    'python3-wheel=0.42.0-2' 'pkgconf=1.8.1-2build1' 'python3-packaging=24.0-1'
  )
  local want install=()
  for spec in "${pins[@]}"; do
    want="${spec#*=}"
    local cur
    cur=$(dpkg-query -W -f='${Version}' "${spec%%=*}" 2>/dev/null) || cur=""
    if [ -z "$cur" ]; then
      install+=("$spec")
      log "PIN_INSTALL $spec"
    elif [ "$cur" = "$want" ]; then
      log "SATISFIED $spec"
    else
      log "KEEP_NEWER_NO_DOWNGRADE ${spec%%=*} cur=$cur want=$want"
    fi
  done
  if [ "${#install[@]}" -gt 0 ]; then
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends "${install[@]}" \
      || die "apt install failed"
  fi
  if [ -n "$(dpkg --audit)" ]; then die "dpkg audit not empty"; fi
  log "APT_DONE"
}

wrap_guard_proof() {
  log "wrapguard: synthetic negative proof (nodownload must forbid wrap fetch)"
  clean_owned "$WORK/scratch"
  mkdir -p "$WORK/scratch/wrapguard/subprojects" "$WORK/scratch/wrapguard/build"
  cat > "$WORK/scratch/wrapguard/meson.build" <<'EOF'
project('wrapguard', 'c')
dep = dependency('wrapguarddep', allow_fallback: true)
EOF
  cat > "$WORK/scratch/wrapguard/subprojects/wrapguarddep.wrap" <<'EOF'
[wrap-git]
url = https://example.invalid/wrapguarddep.git
revision = 0000000000000000000000000000000000000000
[provide]
wrapguarddep = wrapguarddep_dep
EOF
  local out rc=0
  out=$(meson setup "$WORK/scratch/wrapguard/build" "$WORK/scratch/wrapguard" --wrap-mode=nodownload 2>&1) || rc=$?
  printf '%s\n' "$out" | tail -4
  if printf '%s\n' "$out" | grep -qiE 'cloning|git clone|downloading'; then
    die "WRAPGUARD FAILED: fetch attempted under --wrap-mode=nodownload"
  fi
  if [ "$rc" -eq 0 ]; then
    die "WRAPGUARD FAILED: fallback unexpectedly succeeded"
  fi
  log "WRAPGUARD_OK no-fetch-under-nodownload rc=$rc"
}

stage_wrapguard() {
  clean_owned "$WORK/venv"
  python3 -m venv --system-site-packages "$WORK/venv"
  "$WORK/venv/bin/pip" install --no-index /qemu-src-ro/qemu-11.1.1/python/wheels/meson-1.11.1-py3-none-any.whl >/dev/null \
    || die "meson wheel install failed"
  export PATH="$WORK/venv/bin:$PATH"
  wrap_guard_proof
  log "WRAPGUARD_STAGE_DONE"
}

write_cross_ini() {
  mkdir -p "$WORK"
  cat > "$WORK/deps-cross.ini" <<EOF
[binaries]
c = ['$CC']
cpp = ['$CXX']
ar = '$AR'
strip = '$STRIP'
nm = '$NM'
objcopy = '$OBJCOPY'
pkg-config = '/usr/bin/pkgconf'
[built-in options]
c_args = ['-I/io/prefix/include']
c_link_args = ['-L/io/prefix/lib']
cpp_args = ['-I/io/prefix/include']
cpp_link_args = ['-L/io/prefix/lib']
[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
[properties]
needs_exe_wrapper = true
EOF
}

stage_deps() {
  clean_owned "$WORK/deps-src"
  mkdir -p "$WORK/deps-src" "$PREFIX"
  log "extract deps tarballs"
  tar -xf "$DEPS/zlib-1.3.2.tar.gz" -C "$WORK/deps-src"
  tar -xf "$DEPS/libffi-3.8.0.tar.gz" -C "$WORK/deps-src"
  tar -xf "$DEPS/pcre2-10.48.tar.gz" -C "$WORK/deps-src"
  tar -xf "$DEPS/pixman-0.42.2.tar.gz" -C "$WORK/deps-src"
  tar -xf "$DEPS/glib-2.80.5.tar.xz" -C "$WORK/deps-src"
  tar -xf "$DEPS/libiconv-1.19.tar.gz" -C "$WORK/deps-src"
  printf '%s  %s\n' '55b5d8f9d6c4cd844ff349ea932fa3a178f3971c7874c843ddc90523adc5264a' "$DEPS/proxy-libintl-0.4.tar" \
    | sha256sum --check --status || die "proxy-libintl archive hash mismatch"
  mkdir -p "$WORK/deps-src/glib-2.80.5/subprojects/proxy-libintl"
  tar -xf "$DEPS/proxy-libintl-0.4.tar" -C "$WORK/deps-src/glib-2.80.5/subprojects/proxy-libintl"

  log "zlib cross build"
  ( cd "$WORK/deps-src/zlib-1.3.2" && CHOST=aarch64-linux-android ./configure --static --prefix="$PREFIX" && make -j"$(nproc)" && make install ) \
    || die "zlib build failed"

  log "libffi cross build"
  ( cd "$WORK/deps-src/libffi-3.8.0" && ./configure --host=aarch64-linux-android --prefix="$PREFIX" \
      --disable-shared --enable-static --disable-docs --disable-multi-os-directory --disable-builddir \
      && make -j"$(nproc)" && make install ) \
    || die "libffi build failed"

  log "pcre2 cross build"
  ( cd "$WORK/deps-src/pcre2-10.48" && ./configure --host=aarch64-linux-android --prefix="$PREFIX" \
      --disable-shared --enable-static --disable-docs && make -j"$(nproc)" && make install ) \
    || die "pcre2 build failed"

  log "meson venv from vendored wheel"
  clean_owned "$WORK/venv"
  python3 -m venv --system-site-packages "$WORK/venv"
  "$WORK/venv/bin/pip" install --no-index /qemu-src-ro/qemu-11.1.1/python/wheels/meson-1.11.1-py3-none-any.whl >/dev/null \
    || die "meson wheel install failed"
  export PATH="$WORK/venv/bin:$PATH"

  write_cross_ini

  log "pixman cross build"
  ( cd "$WORK/deps-src/pixman-0.42.2" && meson setup build --wrap-mode=nodownload --cross-file "$WORK/deps-cross.ini" \
      --default-library=static --prefix="$PREFIX" -Dtests=disabled -Dgtk=disabled -Dlibpng=disabled \
      -Da64-neon=disabled -Dneon=disabled -Darm-simd=disabled \
      && ninja -C build -j"$(nproc)" && ninja -C build install ) \
    || die "pixman build failed"

  log "libiconv cross build"
  ( cd "$WORK/deps-src/libiconv-1.19" && ./configure --host=aarch64-linux-android --prefix="$PREFIX" \
      --disable-shared --enable-static --disable-nls \
      && make -j"$(nproc)" && make install ) \
    || die "libiconv build failed"

  log "glib cross build"
  ( cd "$WORK/deps-src/glib-2.80.5" && meson setup build --wrap-mode=nodownload --cross-file "$WORK/deps-cross.ini" \
      --default-library=static --prefix="$PREFIX" -Dtests=false -Dnls=disabled -Dman-pages=disabled \
      -Dgtk_doc=false -Dintrospection=disabled \
      && ninja -C build -j"$(nproc)" && ninja -C build install ) \
    || die "glib build failed"

  ( cd "$PREFIX" && find . -type f -print0 | sort -z | xargs -0 sha256sum ) > "$OUT/deps-prefix-manifest-20260924.txt" \
    || die "prefix manifest failed"
  log "DEPS_DONE"
}

# 2026-09-25 recorded config fix (see header): libfdt from the frozen dtc
# archive into $PREFIX; system-route find_library('fdt') then succeeds.
stage_fdt() {
  write_cross_ini
  clean_owned "$WORK/fdt-src"
  mkdir -p "$WORK/fdt-src"
  log "fdt: verify frozen dtc archive (lock dtc_git_archive_sha256)"
  printf '%s  %s\n' "$DTC_ARCHIVE_SHA" "$DEPS/dtc-b6910bec.tar" | sha256sum --check --status \
    || die "dtc frozen archive hash mismatch"
  tar -xf "$DEPS/dtc-b6910bec.tar" -C "$WORK/fdt-src"
  log "fdt: meson venv from vendored wheel (fresh container has no meson)"
  clean_owned "$WORK/venv"
  python3 -m venv --system-site-packages "$WORK/venv"
  "$WORK/venv/bin/pip" install --no-index /qemu-src-ro/qemu-11.1.1/python/wheels/meson-1.11.1-py3-none-any.whl >/dev/null \
    || die "meson wheel install failed"
  export PATH="$WORK/venv/bin:$PATH"
  log "fdt: meson cross build (static, tools/yaml/python disabled)"
  ( cd "$WORK/fdt-src" && meson setup build --wrap-mode=nodownload --cross-file "$WORK/deps-cross.ini" \
      --default-library=static --prefix="$PREFIX" -Dtools=false -Dyaml=disabled -Dpython=disabled \
      && ninja -C build -j"$(nproc)" && ninja -C build install ) \
    || die "libfdt build failed"
  ( cd "$PREFIX" && find . -type f -print0 | sort -z | xargs -0 sha256sum ) > "$OUT/deps-prefix-manifest-20260925.txt" \
    || die "prefix manifest (post-fdt) failed"
  log "FDT_DONE"
}

build_qemu_once() {
  local tag="$1" run="$2"
  local src="$WORK/qemu-src" build="$WORK/qemu-build"
  clean_owned "$src"
  clean_owned "$build"
  mkdir -p "$build"
  log "qemu $tag: verify + extract archive"
  printf '%s  %s\n' "$QEMU_ARCHIVE_SHA" "$QEMU_ARCHIVE" | sha256sum --check --status || die "qemu archive hash mismatch"
  mkdir -p "$src"
  tar -xf "$QEMU_ARCHIVE" -C "$src"
  build_shim
  log "qemu $tag: configure (stub archive appended to LDFLAGS; libfdt via prefix)"
  # --disable-vhost-user: 2026-09-25 recorded config fix — libvhost-user bundled
  # standard-headers collide with bionic linux headers and memfd_create is not
  # declared at API 26; vhost-user is not used by the DroidLayer QEMU slice.
  # --disable-plugins: contrib/plugins/uftrace.c uses timespec_get which bionic
  # does not declare at API 26; TCG instrumentation plugins are not used by the
  # DroidLayer QEMU slice (TCG itself unaffected).
  # --disable-virtfs: bionic sys/stat.h st_atime_nsec/st_mtime_nsec/st_ctime_nsec
  # macros collide with QEMU fsdev/9p-marshal.h field names; virtfs shared-fs is
  # not in the DroidLayer QEMU slice scope.
  # Lock configure_args updated atomically (QEMU-JNI policy §3/§7).
  ( cd "$build" && \
    env CC="$CC" CXX="$CXX" AR="$AR" NM="$NM" RANLIB="$RANLIB" STRIP="$STRIP" READELF="$READELF" \
        PKG_CONFIG=/usr/bin/pkgconf \
        CFLAGS="$CFLAGS -include $SHIM_HDR_SRC" \
        LDFLAGS="$LDFLAGS $WORK/shim/libdroidlayer_shm_stub.a" \
    "$src/qemu-11.1.1/configure" \
      --target-list=aarch64-softmmu --disable-docs --disable-download \
      --disable-vhost-user --disable-plugins --disable-virtfs \
      --cross-prefix=aarch64-linux-android26- --cpu=aarch64 --prefix=/usr ) || die "qemu configure failed"
  log "qemu $tag: ninja"
  ninja -C "$build" -j"$(nproc)" || die "qemu build failed"
  mkdir -p "$OUT/qemu-android-$tag-$run-20260925"
  local f products=()
  for f in qemu-system-aarch64 qemu-img qemu-io; do
    if [ -f "$build/$f" ]; then
      cp -- "$build/$f" "$OUT/qemu-android-$tag-$run-20260925/"
      products+=("$f")
    fi
  done
  ( cd "$OUT/qemu-android-$tag-$run-20260925" && sha256sum "${products[@]}" | sort ) > "$OUT/qemu-android-$tag-$run-20260925/PRODUCTS.sha256"
  log "qemu $tag: build-tree manifest (taken while tree is intact)"
  ( cd "$build" && find . -type f -print0 | sort -z | xargs -0 sha256sum ) \
    > "$OUT/qemu-build-tree-$tag-$run-20260925.txt" || die "tree manifest $tag failed"
  log "qemu $tag: DONE"
}

stage_qos() {
  local run
  run="${QOS_RUN:-$(date +%H%M%S)}"
  build_qemu_once build1 "$run"
  build_qemu_once build2 "$run"
  log "compare products"
  diff "$OUT/qemu-android-build1-$run-20260925/PRODUCTS.sha256" "$OUT/qemu-android-build2-$run-20260925/PRODUCTS.sha256" >"$OUT/two-clean-build-diff-$run-20260925.txt" 2>&1 \
    && log "TWO_CLEAN_BUILD_IDENTICAL" || { log "TWO_CLEAN_BUILD_DIFFERENT (see two-clean-build-diff-$run-20260925.txt)"; }
  diff "$OUT/qemu-build-tree-build1-$run-20260925.txt" "$OUT/qemu-build-tree-build2-$run-20260925.txt" \
    > "$OUT/qemu-build-tree-diff-$run-20260925.txt" 2>&1 \
    && log "BUILD_TREES_IDENTICAL" || log "BUILD_TREES_DIFFER (see qemu-build-tree-diff-$run-20260925.txt)"
}

mkdir -p "$WORK" "$OUT"
case "$STAGE" in
  apt)  stage_apt ;;
  wrapguard) stage_apt; stage_wrapguard ;;
  deps) stage_apt; stage_deps ;;
  shim) stage_apt; build_shim ;;
  fdt)  stage_apt; stage_fdt ;;
  qos)  stage_apt; stage_qos ;;
  all)  stage_apt; stage_deps; stage_fdt; stage_qos ;;
  *) die "unknown stage: $STAGE" ;;
esac
log "STAGE_${STAGE}_COMPLETE"
