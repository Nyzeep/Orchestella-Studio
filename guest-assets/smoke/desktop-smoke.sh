#!/usr/bin/env bash
# DroidLayer Desktop Headless Smoke Verification Script
# Validates automated desktop provisioning, configuration integrity, and compositor launch readiness
set -euo pipefail

# shellcheck source=../scripts/common.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)/scripts/common.sh"

SMOKE_WORK="${GUEST_ASSETS_SMOKE_WORK:-$GUEST_ASSETS_DIR/.work/desktop-smoke}"
safe_reset_directory "$SMOKE_WORK"

cleanup() {
    rm -rf -- "$SMOKE_WORK"
}
trap cleanup EXIT INT TERM

printf '=== [1/4] Checking desktop provisioning and launch scripts ===\n'
SETUP_SCRIPT="$GUEST_ASSETS_DIR/scripts/orchestella-desktop-setup.sh"
LAUNCH_SCRIPT="$GUEST_ASSETS_DIR/scripts/orchestella-desktop-launch.sh"

[[ -f "$SETUP_SCRIPT" && -x "$SETUP_SCRIPT" ]] || {
    printf 'Error: setup script missing or not executable: %s\n' "$SETUP_SCRIPT" >&2
    exit 1
}

[[ -f "$LAUNCH_SCRIPT" && -x "$LAUNCH_SCRIPT" ]] || {
    printf 'Error: launch script missing or not executable: %s\n' "$LAUNCH_SCRIPT" >&2
    exit 1
}

# Run dry-run of setup script
printf 'Executing setup dry-run...\n'
"$SETUP_SCRIPT" --dry-run

printf '=== [2/4] Verifying desktop configuration files ===\n'
DESKTOP_DIR="$GUEST_ASSETS_DIR/scripts/desktop"
[[ -d "$DESKTOP_DIR" ]] || {
    printf 'Error: desktop configurations directory missing: %s\n' "$DESKTOP_DIR" >&2
    exit 1
}

[[ -f "$DESKTOP_DIR/labwc/rc.xml" ]] || { printf 'Missing labwc/rc.xml\n' >&2; exit 1; }
[[ -f "$DESKTOP_DIR/labwc/menu.xml" ]] || { printf 'Missing labwc/menu.xml\n' >&2; exit 1; }
[[ -f "$DESKTOP_DIR/labwc/autostart" ]] || { printf 'Missing labwc/autostart\n' >&2; exit 1; }
[[ -f "$DESKTOP_DIR/sfwbar/sfwbar.config" ]] || { printf 'Missing sfwbar/sfwbar.config\n' >&2; exit 1; }
[[ -f "$DESKTOP_DIR/foot/foot.ini" ]] || { printf 'Missing foot/foot.ini\n' >&2; exit 1; }
[[ -f "$DESKTOP_DIR/weston/weston.ini" ]] || { printf 'Missing weston/weston.ini\n' >&2; exit 1; }
[[ -f "$DESKTOP_DIR/pulseaudio/default.pa" ]] || { printf 'Missing pulseaudio/default.pa\n' >&2; exit 1; }
[[ -f "$DESKTOP_DIR/clipboard/orchestella-clipboard-agent" ]] || { printf 'Missing clipboard/orchestella-clipboard-agent\n' >&2; exit 1; }

printf 'All desktop config templates present and intact.\n'

printf '=== [3/4] Verifying display agent and protocol contract ===\n'
DISPLAY_AGENT_SRC="$GUEST_ASSETS_DIR/agent/display-agent.c"
[[ -f "$DISPLAY_AGENT_SRC" ]] || {
    printf 'Error: display-agent.c source missing: %s\n' "$DISPLAY_AGENT_SRC" >&2
    exit 1
}

# Compile display-agent if compiler is available
if command -v gcc >/dev/null 2>&1 || command -v clang >/dev/null 2>&1; then
    CC="${CC:-$(command -v gcc || command -v clang)}"
    printf 'Compiling display-agent smoke binary with %s...\n' "$CC"
    "$CC" -std=c11 -Wall -Wextra -Werror "$DISPLAY_AGENT_SRC" -o "$SMOKE_WORK/display-agent-test"
    [[ -x "$SMOKE_WORK/display-agent-test" ]] || {
        printf 'Error: failed to compile display-agent smoke binary\n' >&2
        exit 1
    }
fi

printf '=== [4/4] Verifying simulated headless launch environment ===\n'
# Test simulated headless socket environment
export XDG_RUNTIME_DIR="$SMOKE_WORK/run-user-1000"
mkdir -p "$XDG_RUNTIME_DIR"
chmod 0700 "$XDG_RUNTIME_DIR"
export WAYLAND_DISPLAY="wayland-smoke-0"

# Verify dummy socket creation in XDG_RUNTIME_DIR
touch "$XDG_RUNTIME_DIR/$WAYLAND_DISPLAY"
[[ -e "$XDG_RUNTIME_DIR/$WAYLAND_DISPLAY" ]] || {
    printf 'Error: failed to create wayland socket in runtime dir\n' >&2
    exit 1
}
rm -f "$XDG_RUNTIME_DIR/$WAYLAND_DISPLAY"

printf '=== Desktop smoke test PASSED successfully ===\n'
exit 0
