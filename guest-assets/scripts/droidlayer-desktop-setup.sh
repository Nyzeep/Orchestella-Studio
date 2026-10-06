#!/usr/bin/env bash
# DroidLayer Unattended Desktop Setup Script
# Supports Alpine Linux (3.24+) and Debian 13 (Trixie)
set -euo pipefail

DRY_RUN=0
CHECK_ONLY=0

for arg in "$@"; do
    case "$arg" in
        --dry-run)
            DRY_RUN=1
            ;;
        --check)
            CHECK_ONLY=1
            ;;
        --help|-h)
            printf 'Usage: %s [--dry-run|--check]\n' "$0"
            exit 0
            ;;
        *)
            ;;
    esac
done

# Resolve scripts directory
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
DESKTOP_SRC="$SCRIPT_DIR/desktop"

# Detect distribution via /etc/os-release
OS_ID=""
if [[ -f "/etc/os-release" ]]; then
    # shellcheck disable=SC1091
    source "/etc/os-release"
    OS_ID="${ID:-}"
fi

execute_cmd() {
    if [[ "$DRY_RUN" -eq 1 ]]; then
        printf '[DRY-RUN] %s\n' "$*"
    else
        "$@"
    fi
}

check_installed() {
    local pkg="$1"
    case "$pkg" in
        font-noto-cjk|fonts-noto-cjk)
            [[ -d "/usr/share/fonts" ]] || command -v fc-list >/dev/null 2>&1
            ;;
        pulseaudio-utils)
            command -v pactl >/dev/null 2>&1 || command -v pacmd >/dev/null 2>&1 || command -v pulseaudio >/dev/null 2>&1
            ;;
        wl-clipboard)
            command -v wl-copy >/dev/null 2>&1 || command -v wl-paste >/dev/null 2>&1
            ;;
        *)
            command -v "$pkg" >/dev/null 2>&1
            ;;
    esac
}

# 1. Package installation per distribution
if [[ "$OS_ID" == "alpine" ]]; then
    ALPINE_PACKAGES=("labwc" "sfwbar" "foot" "xwayland" "font-noto-cjk" "pulseaudio" "pulseaudio-utils" "wl-clipboard")
    if [[ "$CHECK_ONLY" -eq 1 ]]; then
        for pkg in "${ALPINE_PACKAGES[@]}"; do
            if ! check_installed "$pkg"; then
                printf 'Alpine package missing: %s\n' "$pkg"
                exit 1
            fi
        done
        printf 'All Alpine desktop packages verified.\n'
        exit 0
    fi
    printf 'Installing Alpine desktop suite...\n'
    execute_cmd apk add --no-cache "${ALPINE_PACKAGES[@]}"
elif [[ "$OS_ID" == "debian" || "$OS_ID" == "ubuntu" || "${ID_LIKE:-}" =~ debian ]]; then
    DEBIAN_PACKAGES=("labwc" "sfwbar" "foot" "xwayland" "fonts-noto-cjk" "pulseaudio" "pulseaudio-utils" "wl-clipboard")
    if [[ "$CHECK_ONLY" -eq 1 ]]; then
        for pkg in "${DEBIAN_PACKAGES[@]}"; do
            if ! check_installed "$pkg"; then
                printf 'Debian package missing: %s\n' "$pkg"
                exit 1
            fi
        done
        printf 'All Debian desktop packages verified.\n'
        exit 0
    fi
    printf 'Installing Debian desktop suite...\n'
    execute_cmd env DEBIAN_FRONTEND=noninteractive apt-get update -y
    execute_cmd env DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends "${DEBIAN_PACKAGES[@]}"
else
    if [[ "$CHECK_ONLY" -eq 1 ]]; then
        printf 'Warning: Unrecognized distribution OS_ID=%s\n' "$OS_ID"
        exit 0
    fi
    printf 'Warning: Distribution %s not explicitly Alpine or Debian. Skipping package manager.\n' "$OS_ID"
fi

# 2. Runtime directory initialization (/run/user/1000)
RUNTIME_DIR="/run/user/1000"
if [[ ! -d "$RUNTIME_DIR" ]]; then
    execute_cmd mkdir -p "$RUNTIME_DIR"
fi
execute_cmd chmod 700 "$RUNTIME_DIR"
if command -v chown >/dev/null 2>&1 && [[ "$(id -u)" -eq 0 ]]; then
    execute_cmd chown 1000:1000 "$RUNTIME_DIR" 2>/dev/null || true
fi

# 3. User configuration directories setup
TARGET_HOME="${HOME:-/root}"
if [[ -d "/home/droidlayer" ]]; then
    TARGET_HOME="/home/droidlayer"
fi

LABWC_CONF_DIR="$TARGET_HOME/.config/labwc"
SFWBAR_CONF_DIR="$TARGET_HOME/.config/sfwbar"
FOOT_CONF_DIR="$TARGET_HOME/.config/foot"
PULSE_CONF_DIR="$TARGET_HOME/.config/pulse"
CLIPBOARD_CONF_DIR="$TARGET_HOME/.config/droidlayer/clipboard"
BIN_DIR="$TARGET_HOME/.local/bin"

execute_cmd mkdir -p "$LABWC_CONF_DIR" "$SFWBAR_CONF_DIR" "$FOOT_CONF_DIR" "$PULSE_CONF_DIR" "$CLIPBOARD_CONF_DIR" "$BIN_DIR"

if [[ -d "$DESKTOP_SRC/labwc" ]]; then
    execute_cmd cp -f "$DESKTOP_SRC/labwc/rc.xml" "$LABWC_CONF_DIR/rc.xml"
    execute_cmd cp -f "$DESKTOP_SRC/labwc/menu.xml" "$LABWC_CONF_DIR/menu.xml"
    execute_cmd cp -f "$DESKTOP_SRC/labwc/autostart" "$LABWC_CONF_DIR/autostart"
    execute_cmd chmod 755 "$LABWC_CONF_DIR/autostart"
fi

if [[ -f "$DESKTOP_SRC/sfwbar/sfwbar.config" ]]; then
    execute_cmd cp -f "$DESKTOP_SRC/sfwbar/sfwbar.config" "$SFWBAR_CONF_DIR/sfwbar.config"
fi

if [[ -f "$DESKTOP_SRC/foot/foot.ini" ]]; then
    execute_cmd cp -f "$DESKTOP_SRC/foot/foot.ini" "$FOOT_CONF_DIR/foot.ini"
fi

if [[ -f "$DESKTOP_SRC/pulseaudio/default.pa" ]]; then
    execute_cmd cp -f "$DESKTOP_SRC/pulseaudio/default.pa" "$PULSE_CONF_DIR/default.pa"
fi

if [[ -f "$DESKTOP_SRC/clipboard/droidlayer-clipboard-agent" ]]; then
    execute_cmd cp -f "$DESKTOP_SRC/clipboard/droidlayer-clipboard-agent" "$CLIPBOARD_CONF_DIR/droidlayer-clipboard-agent"
    execute_cmd chmod 755 "$CLIPBOARD_CONF_DIR/droidlayer-clipboard-agent"
    execute_cmd cp -f "$DESKTOP_SRC/clipboard/droidlayer-clipboard-agent" "$BIN_DIR/droidlayer-clipboard-agent"
    execute_cmd chmod 755 "$BIN_DIR/droidlayer-clipboard-agent"
fi

# 4. Modern Shell and environment profile setup (/etc/profile.d/00-droidlayer-env.sh)
PROFILE_D="/etc/profile.d"
DROIDLAYER_SHELL_DIR="/etc/droidlayer/shell"
SHELL_SRC="$SCRIPT_DIR/../shell"
if [[ ! -d "$SHELL_SRC" && -d "$SCRIPT_DIR/shell" ]]; then
    SHELL_SRC="$SCRIPT_DIR/shell"
fi

if [[ -d "$SHELL_SRC" ]]; then
    execute_cmd mkdir -p "$PROFILE_D" "$DROIDLAYER_SHELL_DIR"
    if [[ -f "$SHELL_SRC/droidlayer-prompt.sh" ]]; then
        execute_cmd cp -f "$SHELL_SRC/droidlayer-prompt.sh" "$DROIDLAYER_SHELL_DIR/droidlayer-prompt.sh"
        execute_cmd chmod 755 "$DROIDLAYER_SHELL_DIR/droidlayer-prompt.sh"
    fi
    if [[ -f "$SHELL_SRC/aliases.sh" ]]; then
        execute_cmd cp -f "$SHELL_SRC/aliases.sh" "$DROIDLAYER_SHELL_DIR/aliases.sh"
        execute_cmd chmod 644 "$DROIDLAYER_SHELL_DIR/aliases.sh"
    fi
    if [[ -f "$SHELL_SRC/00-droidlayer-env.sh" ]]; then
        execute_cmd cp -f "$SHELL_SRC/00-droidlayer-env.sh" "$PROFILE_D/00-droidlayer-env.sh"
        execute_cmd chmod 755 "$PROFILE_D/00-droidlayer-env.sh"
    fi
fi

printf 'DroidLayer desktop provisioning completed successfully.\n'
exit 0
