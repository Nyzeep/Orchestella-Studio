#!/bin/sh
# Run inside the explicitly selected guest; host/device handoff is managed separately.
set -eu
controller=/usr/local/lib/orchestella/profilectl
if [ "${ORCHESTELLA_PHASE5_RUN:-}" != 1 ]; then
    printf '%s\n' 'Set ORCHESTELLA_PHASE5_RUN=1 inside the selected guest to run read-only status checks.'
    exit 64
fi
printf '%s\n' '=== Native guest baseline ==='
uname -sr
printf 'PID1: '; cat /proc/1/comm
printf 'cgroup2: '; stat -fc %T /sys/fs/cgroup
for profile in desktop-labwc podman code-server docker-cli; do
    "$controller" probe "$profile" || true
    "$controller" status "$profile" || true
done
printf '%s\n' '=== Ordinary user ==='
id orchestella 2>/dev/null || true
printf '%s\n' '=== Optional listeners (state only; no credential output) ==='
if command -v ss >/dev/null 2>&1; then
    ss -lnt | grep -E '(:8080|:18080|State)' || true
fi
printf '%s\n' 'These checks do not prove Android presentation, GPU acceleration, or USB passthrough.'
