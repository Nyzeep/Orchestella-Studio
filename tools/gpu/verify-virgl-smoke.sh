#!/usr/bin/env bash
# DroidLayer VirGL 3D Hardware Acceleration Automated Verification Tool
# Probes DRM DRI nodes (/dev/dri/card0, /dev/dri/renderD128) and queries
# es2_info / glxinfo to verify that VirGL 3D hardware acceleration is active
# and identifying the underlying host GPU (e.g. Adreno / Mali).
set -euo pipefail

PROG_NAME="verify-virgl-smoke.sh"
CHECK_ONLY=0
DRY_RUN=0
OUTPUT_JSON=0
RUN_BENCHMARK=0

show_help() {
    cat <<EOF
Usage: $PROG_NAME [OPTIONS]

Options:
  --check        Quiet check mode (exit 0 if VirGL 3D hardware acceleration is active, 1 otherwise)
  --dry-run      Probe system without running interactive benchmark commands
  --json         Output structured JSON diagnostics
  --benchmark    Run glmark2-es2 benchmark and capture score & average FPS
  -h, --help     Show this help message
EOF
}

for arg in "$@"; do
    case "$arg" in
        --check)
            CHECK_ONLY=1
            ;;
        --dry-run)
            DRY_RUN=1
            ;;
        --json)
            OUTPUT_JSON=1
            ;;
        --benchmark)
            RUN_BENCHMARK=1
            ;;
        -h|--help)
            show_help
            exit 0
            ;;
        *)
            printf 'Unknown argument: %s\n' "$arg" >&2
            show_help >&2
            exit 2
            ;;
    esac
done

# 1. Probe DRI Device Nodes
CARD_NODE="/dev/dri/card0"
RENDER_NODE="/dev/dri/renderD128"
HAS_CARD=0
HAS_RENDER=0

if [[ -c "$CARD_NODE" || -e "$CARD_NODE" ]]; then
    HAS_CARD=1
fi
if [[ -c "$RENDER_NODE" || -e "$RENDER_NODE" ]]; then
    HAS_RENDER=1
fi

# 2. Inspect Mesa Gallium Environment Variables
GALLIUM_VAL="${GALLIUM_DRIVER:-}"
GL_OVERRIDE_VAL="${MESA_GL_VERSION_OVERRIDE:-}"
GLES_OVERRIDE_VAL="${MESA_GLES_VERSION_OVERRIDE:-}"

# 3. Query OpenGL ES / GL info via es2_info or glxinfo
GL_RENDERER="unknown"
GL_VERSION="unknown"
GL_VENDOR="unknown"
INFO_TOOL="none"

if command -v es2_info >/dev/null 2>&1; then
    INFO_TOOL="es2_info"
    INFO_OUT="$(es2_info 2>/dev/null || true)"
    GL_RENDERER="$(printf '%s\n' "$INFO_OUT" | grep -i 'GL_RENDERER' | head -n 1 | sed 's/.*:[[:space:]]*//' || echo 'unknown')"
    GL_VERSION="$(printf '%s\n' "$INFO_OUT" | grep -i 'GL_VERSION' | head -n 1 | sed 's/.*:[[:space:]]*//' || echo 'unknown')"
    GL_VENDOR="$(printf '%s\n' "$INFO_OUT" | grep -i 'GL_VENDOR' | head -n 1 | sed 's/.*:[[:space:]]*//' || echo 'unknown')"
elif command -v glxinfo >/dev/null 2>&1; then
    INFO_TOOL="glxinfo"
    INFO_OUT="$(glxinfo -B 2>/dev/null || true)"
    GL_RENDERER="$(printf '%s\n' "$INFO_OUT" | grep -i 'OpenGL renderer string' | head -n 1 | sed 's/.*:[[:space:]]*//' || echo 'unknown')"
    GL_VERSION="$(printf '%s\n' "$INFO_OUT" | grep -i 'OpenGL version string' | head -n 1 | sed 's/.*:[[:space:]]*//' || echo 'unknown')"
    GL_VENDOR="$(printf '%s\n' "$INFO_OUT" | grep -i 'OpenGL vendor string' | head -n 1 | sed 's/.*:[[:space:]]*//' || echo 'unknown')"
fi

# Fallback extraction from environment if tool unavailable but virgl configured
if [[ "$GL_RENDERER" == "unknown" && "$HAS_RENDER" -eq 1 && "$GALLIUM_VAL" == "virgl" ]]; then
    GL_RENDERER="virgl (Host Accelerated)"
    GL_VENDOR="Mesa / virglrenderer"
    GL_VERSION="OpenGL ES ${GL_OVERRIDE_VAL:-3.2}"
elif [[ "$GL_RENDERER" == "unknown" && "$GALLIUM_VAL" == "llvmpipe" ]]; then
    GL_RENDERER="llvmpipe (LLVM Software Rasterizer)"
    GL_VENDOR="Mesa / swrast"
    GL_VERSION="OpenGL ES 3.0"
fi

# 4. Determine Acceleration Status
IS_ACCELERATED=0
IS_LLVMPIPE=0

case "$GL_RENDERER" in
    *virgl*|*VirGL*|*Adreno*|*Mali*|*Qualcomm*|*ARM*)
        IS_ACCELERATED=1
        ;;
    *llvmpipe*|*softpipe*|*swrast*)
        IS_LLVMPIPE=1
        ;;
    *)
        if [[ "$HAS_RENDER" -eq 1 && "$GALLIUM_VAL" == "virgl" ]]; then
            IS_ACCELERATED=1
        fi
        ;;
esac

# 5. Optional Benchmark Execution
BENCH_FPS="N/A"
BENCH_SCORE="N/A"
if [[ "$RUN_BENCHMARK" -eq 1 && "$DRY_RUN" -eq 0 ]]; then
    if command -v glmark2-es2 >/dev/null 2>&1; then
        BENCH_OUT="$(glmark2-es2 --fullscreen --run-forever=false 2>&1 || true)"
        BENCH_SCORE="$(printf '%s\n' "$BENCH_OUT" | grep -i 'glmark2 Score:' | awk '{print $NF}' || echo 'N/A')"
        BENCH_FPS="$(printf '%s\n' "$BENCH_OUT" | grep -i 'fps:' | tail -n 1 | awk '{print $(NF-1)}' || echo 'N/A')"
    fi
fi

# 6. Check mode exit
if [[ "$CHECK_ONLY" -eq 1 ]]; then
    if [[ "$IS_ACCELERATED" -eq 1 ]]; then
        exit 0
    else
        exit 1
    fi
fi

# 7. Output Result (JSON or Text)
if [[ "$OUTPUT_JSON" -eq 1 ]]; then
    cat <<EOF
{
  "accelerated": $( [[ "$IS_ACCELERATED" -eq 1 ]] && echo "true" || echo "false" ),
  "has_card0": $( [[ "$HAS_CARD" -eq 1 ]] && echo "true" || echo "false" ),
  "has_renderD128": $( [[ "$HAS_RENDER" -eq 1 ]] && echo "true" || echo "false" ),
  "gallium_driver": "${GALLIUM_VAL}",
  "mesa_gl_override": "${GL_OVERRIDE_VAL}",
  "info_tool": "${INFO_TOOL}",
  "gl_renderer": "${GL_RENDERER}",
  "gl_vendor": "${GL_VENDOR}",
  "gl_version": "${GL_VERSION}",
  "benchmark_score": "${BENCH_SCORE}",
  "benchmark_fps": "${BENCH_FPS}"
}
EOF
    exit 0
fi

printf '=======================================================\n'
printf '  DroidLayer VirGL 3D Acceleration Smoke Verification  \n'
printf '=======================================================\n'
printf '[DRI Device Nodes]\n'
printf '  /dev/dri/card0:       %s\n' "$( [[ "$HAS_CARD" -eq 1 ]] && echo "PRESENT" || echo "NOT FOUND" )"
printf '  /dev/dri/renderD128:  %s\n' "$( [[ "$HAS_RENDER" -eq 1 ]] && echo "PRESENT (VirGL 3D Ready)" || echo "NOT FOUND" )"
printf '\n[Gallium Environment]\n'
printf '  GALLIUM_DRIVER:       %s\n' "${GALLIUM_VAL:-<unset>}"
printf '  GL_VERSION_OVERRIDE:  %s\n' "${GL_OVERRIDE_VAL:-<unset>}"
printf '\n[OpenGL ES / DRI Info]\n'
printf '  Detection Tool:       %s\n' "$INFO_TOOL"
printf '  GL_RENDERER:          %s\n' "$GL_RENDERER"
printf '  GL_VENDOR:            %s\n' "$GL_VENDOR"
printf '  GL_VERSION:           %s\n' "$GL_VERSION"

if [[ "$RUN_BENCHMARK" -eq 1 ]]; then
    printf '\n[glmark2-es2 Benchmark]\n'
    printf '  Average FPS:          %s\n' "$BENCH_FPS"
    printf '  Score:                %s\n' "$BENCH_SCORE"
fi

printf '=======================================================\n'
if [[ "$IS_ACCELERATED" -eq 1 ]]; then
    printf 'STATUS: PASS - VirGL 3D Hardware Acceleration ACTIVE\n'
    printf '=======================================================\n'
    exit 0
elif [[ "$IS_LLVMPIPE" -eq 1 ]]; then
    printf 'STATUS: WARN - Fallback to llvmpipe Software Rasterizer\n'
    printf '=======================================================\n'
    exit 0
else
    printf 'STATUS: UNKNOWN - No DRI / 3D driver detected\n'
    printf '=======================================================\n'
    exit 1
fi
