#!/bin/sh
# Run one repeatable load in the private 960x540 labwc benchmark session.
set -eu

render_seed() {
    printf '\033[?25l\033[36mW4-05 display benchmark\033[0m\n\n'
    for label in compositor capture transfer present; do
        printf '  %-12s  |  0123456789 ABC abc\n' "$label"
    done
}

read_uptime_ticks() {
    IFS='. ' read -r seconds centiseconds unused </proc/uptime
    clock_ticks=$((seconds * 100 + 1$centiseconds - 100))
}

run_terminal_bursts() {
    remaining=$((($1 - 2) * 10))
    read_uptime_ticks
    next_tick=$((clock_ticks + 100))
    burst=0

    while [ "$burst" -lt "$remaining" ]; do
        read_uptime_ticks
        pause=$((next_tick - clock_ticks))
        if [ "$pause" -gt 0 ]; then
            sleep "$(printf '%d.%02d' "$((pause / 100))" "$((pause % 100))")"
        fi

        line=0
        while [ "$line" -lt 64 ]; do
            printf 'W4-05 %04d/%02d | ABCDEFGHIJKLMNOPQRSTUVWXYZ | 0123456789\n' "$burst" "$line"
            line=$((line + 1))
        done
        next_tick=$((next_tick + 10))
        burst=$((burst + 1))
    done
}

if [ "${1:-}" = "--scene" ]; then
    # Internal foot child. The window starts with the same pixels in all modes.
    render_seed
    : > "$4"
    if [ "$2" = "terminal" ]; then
        run_terminal_bursts "$3"
        exec sleep 1
    fi
    exec sleep "$3"
fi

usage() {
    printf 'Usage: %s {static|terminal|drag} [seconds, default 36]\n' "$0" >&2
    exit 2
}

mode=${1:-}
duration=${2:-36}
[ "$#" -le 2 ] || usage
case "$mode" in static|terminal|drag) ;; *) usage ;; esac
case "$duration" in [3-9]|[1-9][0-9]|1[01][0-9]|120) ;; *) usage ;; esac

: "${XDG_RUNTIME_DIR:?Start the isolated labwc session first}"
: "${WAYLAND_DISPLAY:?Start the isolated labwc session first}"
script_path=$(readlink -f -- "$0")
script_dir=${script_path%/*}
case "$mode" in
    drag)
        drag_tool=${W405_DRAG_TOOL:-"$script_dir/w405-drag"}
        if [ ! -x "$drag_tool" ]; then
            printf 'Missing virtual pointer helper: %s\n' "$drag_tool" >&2
            exit 2
        fi
        ;;
esac

# The caller can stop the workload as soon as the host sample ends.
run_dir=$(mktemp -d "${XDG_RUNTIME_DIR%/}/w405-load.XXXXXXXX")
scene_ready="$run_dir/scene-ready"
foot_pid=
drag_pid=
clean_up() {
    for pid in "$drag_pid" "$foot_pid"; do
        if [ -n "$pid" ]; then
            kill "$pid" 2>/dev/null || :
            wait "$pid" 2>/dev/null || :
        fi
    done
    rm -f -- "$scene_ready"
    rmdir -- "$run_dir"
}
trap clean_up EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP

foot --config=/dev/null --app-id=orchestella.w405.bench --title='W4-05 benchmark' \
    --window-size-pixels=480x288 -o 'font=DejaVu Sans Mono:size=11' \
    -o 'cursor.blink=no' -- /bin/sh "$script_path" \
    --scene "$mode" "$duration" "$scene_ready" &
foot_pid=$!

if [ "$mode" = "drag" ]; then
    attempt=0
    until [ -f "$scene_ready" ]; do
        if [ "$attempt" -ge 100 ] || ! kill -0 "$foot_pid" 2>/dev/null; then
            printf 'The benchmark window did not start\n' >&2
            exit 1
        fi
        sleep 0.1
        attempt=$((attempt + 1))
    done
    sleep 1
    "$drag_tool" "$((duration - 2))" &
    drag_pid=$!
    wait "$drag_pid"
    drag_pid=
fi

wait "$foot_pid"
foot_pid=
