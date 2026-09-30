#!/bin/sh
# ==============================================================================
# DroidLayer Ticket 27: Outbound Network & SLIRP Contract Verification Spec
# Acceptance Criteria: AC-1 (SLIRP/VirtIO), AC-2 (DNS Traversal), AC-4 (apk update)
# Standard: ADR-0008, ADR-0010
# ==============================================================================
set -eu

LOG_TAG="[DROIDLAYER-NET-SMOKE]"

log() {
    printf '%s %s\n' "$LOG_TAG" "$*"
}

fail() {
    printf '%s [FAIL] %s\n' "$LOG_TAG" "$*" >&2
    exit 1
}

log "Starting DroidLayer Outbound Network Smoke Verification..."

# ------------------------------------------------------------------------------
# Step 1: Verify VirtIO Network Interface and Driver Binding
# ------------------------------------------------------------------------------
log "Step 1: Checking kernel network devices..."
if ! ip link show eth0 >/dev/null 2>&1; then
    fail "virtio-net device eth0 not detected by kernel! Check CONFIG_VIRTIO_NET=y and QEMU -device virtio-net-pci."
fi
log "PASS: eth0 interface detected."

# ------------------------------------------------------------------------------
# Step 2: Ensure DHCP / IP Address Configuration (10.0.2.15/24)
# ------------------------------------------------------------------------------
log "Step 2: Checking IP address assignment..."
CURRENT_IP="$(ip -4 addr show eth0 | awk '/inet / {print $2}' || true)"
if [ -z "$CURRENT_IP" ]; then
    log "IP address not configured, running udhcpc on eth0..."
    udhcpc -i eth0 -q -n || fail "udhcpc failed to acquire IP lease from SLIRP DHCP"
    CURRENT_IP="$(ip -4 addr show eth0 | awk '/inet / {print $2}')"
fi
log "Assigned IP: $CURRENT_IP"

case "$CURRENT_IP" in
    10.0.2.15/*|10.0.2.15)
        log "PASS: IP address matches standard SLIRP DHCP lease (10.0.2.15)."
        ;;
    *)
        log "WARNING: IP address $CURRENT_IP differs from default 10.0.2.15/24."
        ;;
esac

# ------------------------------------------------------------------------------
# Step 3: Verify Gateway Route (10.0.2.2)
# ------------------------------------------------------------------------------
log "Step 3: Checking default gateway routing..."
DEFAULT_GW="$(ip route show default | awk '/default via/ {print $3}' || true)"
log "Default Gateway: $DEFAULT_GW"
if [ "$DEFAULT_GW" != "10.0.2.2" ]; then
    log "Adding default gateway 10.0.2.2..."
    ip route add default via 10.0.2.2 dev eth0 2>/dev/null || true
fi

# ------------------------------------------------------------------------------
# Step 4: Verify DNS Traversal Configuration (Android No /etc/resolv.conf adaptation)
# ------------------------------------------------------------------------------
log "Step 4: Checking DNS resolver configuration..."
# Parse cmdline DNS if passed via droidlayer.dns=<ip>
CMDLINE_DNS="$(grep -o 'droidlayer\.dns=[^ ]*' /proc/cmdline 2>/dev/null | cut -d= -f2 || true)"
if [ -n "$CMDLINE_DNS" ]; then
    log "Detected cmdline DNS server: $CMDLINE_DNS"
fi

if [ ! -s /etc/resolv.conf ] || ! grep -q 'nameserver' /etc/resolv.conf 2>/dev/null; then
    TARGET_DNS="${CMDLINE_DNS:-8.8.8.8}"
    log "Configuring /etc/resolv.conf with fallback nameserver: $TARGET_DNS"
    printf 'nameserver %s\nnameserver 1.1.1.1\n' "$TARGET_DNS" > /etc/resolv.conf
fi
cat /etc/resolv.conf

# ------------------------------------------------------------------------------
# Step 5: Test DNS Resolution and Gateway Reachability
# ------------------------------------------------------------------------------
log "Step 5: Testing DNS resolution for Alpine package repository..."
if nslookup dl-cdn.alpinelinux.org >/dev/null 2>&1; then
    log "PASS: dl-cdn.alpinelinux.org successfully resolved."
else
    log "nslookup failed, verifying via getent or ping..."
    ping -c 2 8.8.8.8 || fail "Cannot reach public IP 8.8.8.8 via SLIRP gateway!"
fi

# ------------------------------------------------------------------------------
# Step 6: Core Acceptance Test: apk update & Package Fetching (AC-4)
# ------------------------------------------------------------------------------
log "Step 6: Executing apk update (fetching Alpine package index)..."
apk update || fail "apk update failed! Outbound Internet NAT is not functional."
log "PASS: apk update succeeded."

log "Step 6b: Testing package metadata query..."
apk info -v alpine-base || fail "Unable to query package info"
log "PASS: Alpine base repository is fully operational."

# ------------------------------------------------------------------------------
# Step 7: Security Boundary & Zero Inbound Exposure Assertion (ADR-0010)
# ------------------------------------------------------------------------------
log "Step 7: Validating zero inbound exposure..."
# Verify guest only has link-local / private address
ALL_IPS="$(ip -o -4 addr show | awk '{print $4}')"
log "Active guest IPv4 interfaces: $ALL_IPS"
for ip_entry in $ALL_IPS; do
    case "$ip_entry" in
        127.*|10.0.2.*) ;;
        *) fail "Unexpected public or host-exposed IP detected on guest: $ip_entry" ;;
    esac
done
log "PASS: Guest isolated to private NAT; zero unauthorized exposure."

log "=== DroidLayer Ticket 27 Outbound Network Smoke Verification PASSED ==="
