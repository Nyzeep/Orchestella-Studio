#!/usr/bin/env bash
# DroidLayer One-Click Desktop Launch Script
set -euo pipefail

# 1. Initialize XDG Runtime Directory
if [[ -z "${XDG_RUNTIME_DIR:-}" ]]; then
    CURRENT_UID="$(id -u)"
    if [[ "$CURRENT_UID" -eq 0 && -d "/run/user/1000" ]]; then
        export XDG_RUNTIME_DIR="/run/user/1000"
    else
        export XDG_RUNTIME_DIR="/run/user/$CURRENT_UID"
    fi
fi

mkdir -p "$XDG_RUNTIME_DIR"
chmod 0700 "$XDG_RUNTIME_DIR"

# 2. Export Standard Wayland & Desktop Variables
export WAYLAND_DISPLAY="wayland-0"
export XDG_CURRENT_DESKTOP="labwc:wlroots"
export XDG_SESSION_TYPE="wayland"
export MOZ_ENABLE_WAYLAND=1
export QT_QPA_PLATFORM="wayland"
export GDK_BACKEND="wayland"
export SDL_VIDEODRIVER="wayland"
export _JAVA_AWT_WM_NONREPARENTING=1

# Source Mesa graphics acceleration configuration if available
if [[ -f "/etc/profile.d/10-droidlayer-graphics.sh" ]]; then
    # shellcheck disable=SC1091
    source "/etc/profile.d/10-droidlayer-graphics.sh"
fi

# 3. Launch droidlayer-display-agent in background if available
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
DISPLAY_AGENT_BIN="${SCRIPT_DIR}/../agent/display-agent"
if [[ -x "$DISPLAY_AGENT_BIN" ]] && ! pgrep -f "display-agent" >/dev/null 2>&1; then
    "$DISPLAY_AGENT_BIN" &
fi

# 4. Launch labwc Compositor
if command -v labwc >/dev/null 2>&1; then
    exec labwc "$@"
elif command -v weston >/dev/null 2>&1; then
    exec weston "$@"
else
    printf 'Error: neither labwc nor weston is installed. Run droidlayer-desktop-setup.sh first.\n' >&2
    exit 127
fi
