#!/usr/bin/env bash
# Check desktop provisioning and capture a frame from a headless labwc session.
set -euo pipefail

if (( $# > 1 )); then
    printf 'Usage: %s [--mode=live|--mode=static]\n' "$0" >&2
    exit 64
fi
case "${1---mode=live}" in
    --mode=live) MODE=live ;;
    --mode=static) MODE=static ;;
    --help|-h)
        printf 'Usage: %s [--mode=live|--mode=static]\n' "$0"
        exit 0 ;;
    *)
        printf 'Usage: %s [--mode=live|--mode=static]\n' "$0" >&2
        exit 64 ;;
esac

# shellcheck source=../scripts/common.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)/scripts/common.sh"

SMOKE_PARENT="${GUEST_ASSETS_SMOKE_WORK:-$GUEST_ASSETS_DIR/.work}"
mkdir -p -- "$SMOKE_PARENT"
SMOKE_WORK="$(mktemp -d -- "$SMOKE_PARENT/desktop-smoke.XXXXXXXX")"

RUNTIME_DIR=""
LABWC_PID=""
SWAYBG_PID=""
WAYVNC_PID=""

stop_process() {
    local pid="$1" attempt
    [[ -n "$pid" ]] || return 0
    kill "$pid" 2>/dev/null || true
    for ((attempt = 0; attempt < 20; attempt++)); do
        kill -0 "$pid" 2>/dev/null || break
        sleep 0.1
    done
    if (( attempt == 20 )); then
        kill -KILL "$pid" 2>/dev/null || true
    fi
    wait "$pid" 2>/dev/null || true
}

cleanup() {
    local status=$? log
    stop_process "$WAYVNC_PID"
    stop_process "$SWAYBG_PID"
    stop_process "$LABWC_PID"
    if (( status != 0 )); then
        for log in labwc swaybg wayvnc; do
            if [[ -f "$SMOKE_WORK/$log.log" ]]; then
                printf '%s log:\n' "$log" >&2
                cat -- "$SMOKE_WORK/$log.log" >&2
            fi
        done
    fi
    if [[ -n "$RUNTIME_DIR" ]]; then
        rm -rf -- "$RUNTIME_DIR"
    fi
    rm -rf -- "$SMOKE_WORK"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

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

if [[ "$MODE" == static ]]; then
    printf '=== Desktop static smoke passed ===\n'
    exit 0
fi

printf '=== [4/4] Capturing a frame from headless labwc and wayvnc ===\n'
for dependency in labwc swaybg wayvnc python3 timeout; do
    if ! command -v "$dependency" >/dev/null; then
        printf 'Error: live desktop smoke requires %s\n' "$dependency" >&2
        exit 69
    fi
done

wait_for_socket() {
    local socket_path="$1" pid="$2" process_name="$3" attempt
    for ((attempt = 0; attempt < 100; attempt++)); do
        if ! kill -0 "$pid" 2>/dev/null; then
            printf 'Error: %s exited before creating its socket\n' "$process_name" >&2
            return 1
        fi
        [[ -S "$socket_path" ]] && return 0
        sleep 0.1
    done
    printf 'Error: timed out waiting for %s socket\n' "$process_name" >&2
    return 1
}

# Unix socket paths must be short, including on WSL with a long checkout path.
RUNTIME_DIR="$(mktemp -d /tmp/orchestella-desktop-smoke.XXXXXXXX)"
export XDG_RUNTIME_DIR="$RUNTIME_DIR"
unset WAYLAND_DISPLAY WAYLAND_SOCKET DISPLAY DBUS_SESSION_BUS_ADDRESS
mkdir -- "$RUNTIME_DIR/labwc"
cp -- "$DESKTOP_DIR/labwc/rc.xml" "$DESKTOP_DIR/labwc/menu.xml" "$RUNTIME_DIR/labwc/"

WLR_BACKENDS=headless WLR_RENDERER=pixman WLR_HEADLESS_OUTPUTS=1 \
    labwc --config-dir "$RUNTIME_DIR/labwc" >"$SMOKE_WORK/labwc.log" 2>&1 &
LABWC_PID=$!
export WAYLAND_DISPLAY=wayland-0
wait_for_socket "$XDG_RUNTIME_DIR/$WAYLAND_DISPLAY" "$LABWC_PID" labwc

# An actual Wayland surface with a known color distinguishes capture from the
# legal placeholder pixels wayvnc can send while waiting for the first frame.
SMOKE_COLOR=316ca4
swaybg --color "#$SMOKE_COLOR" >"$SMOKE_WORK/swaybg.log" 2>&1 &
SWAYBG_PID=$!

printf 'enable_auth=false\n' >"$RUNTIME_DIR/wayvnc.conf"
RFB_SOCKET="$RUNTIME_DIR/wayvnc.sock"
wayvnc --config "$RUNTIME_DIR/wayvnc.conf" --unix-socket "$RFB_SOCKET" \
    >"$SMOKE_WORK/wayvnc.log" 2>&1 &
WAYVNC_PID=$!
wait_for_socket "$RFB_SOCKET" "$WAYVNC_PID" wayvnc

timeout --kill-after=2s 20s python3 - "$RFB_SOCKET" "$SMOKE_COLOR" <<'PY'
import socket
import struct
import sys
import time


def require(condition, message):
    if not condition:
        raise ValueError(message)


def verify_frame(socket_path, rgb):
    expected_pixel = bytes.fromhex(rgb)[::-1]
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
        client.settimeout(5)
        client.connect(socket_path)
        with client.makefile("rb") as stream:
            def read(size):
                data = stream.read(size)
                require(len(data) == size, "truncated RFB message")
                return data

            version = b"RFB 003.008\n"
            require(read(12) == version, "expected RFB 3.8 server")
            client.sendall(version)

            security_none = b"\x01"
            require(security_none in read(read(1)[0]), "server did not offer Security None")
            client.sendall(security_none)
            require(read(4) == bytes(4), "Security None negotiation failed")
            client.sendall(b"\x01")  # ClientInit: shared connection

            width, height, name_size = struct.unpack("!HH16xI", read(24))
            require(0 < width <= 4096 and 0 < height <= 4096, "invalid framebuffer dimensions")
            require(name_size <= 4096, "oversized desktop name")
            read(name_size)

            # SetPixelFormat: 32-bit little-endian XRGB. SetEncodings: Raw.
            client.sendall(struct.pack("!B3xBBBBHHHBBB3x", 0, 32, 24, 0, 1, 255, 255, 255, 16, 8, 0))
            client.sendall(struct.pack("!BxHi", 2, 1, 0))
            request = struct.pack("!BBHHHH", 3, 0, 0, 0, width, height)
            center_x, center_y = width // 2, height // 2

            center_pixel = None
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                client.sendall(request)
                message, count = struct.unpack("!BxH", read(4))
                require(message == 0 and count > 0, "expected a nonempty FramebufferUpdate")
                center_pixel = None
                for _ in range(count):
                    x, y, rect_width, rect_height, encoding = struct.unpack("!HHHHi", read(12))
                    require(encoding == 0, "expected Raw framebuffer pixels")
                    require(
                        rect_width > 0 and rect_height > 0
                        and x + rect_width <= width and y + rect_height <= height,
                        "invalid framebuffer rectangle",
                    )
                    pixels = read(rect_width * rect_height * 4)
                    if x <= center_x < x + rect_width and y <= center_y < y + rect_height:
                        offset = ((center_y - y) * rect_width + center_x - x) * 4
                        center_pixel = pixels[offset:offset + 3]
                # Allow rounding during conversion to the requested pixel format.
                if center_pixel is not None and all(
                    abs(actual - expected) <= 3 for actual, expected in zip(center_pixel, expected_pixel)
                ):
                    print(f"Verified RFB 3.8 Security None and {width}x{height} Raw compositor pixels.")
                    return
                time.sleep(0.1)
            sample = center_pixel.hex() if center_pixel is not None else "missing"
            raise ValueError(f"framebuffer never contained the Wayland test surface (center pixel: {sample})")


try:
    verify_frame(*sys.argv[1:])
except (OSError, ValueError) as error:
    sys.exit(f"Error: desktop RFB smoke failed: {error}")
PY

printf '=== Desktop live smoke passed ===\n'
