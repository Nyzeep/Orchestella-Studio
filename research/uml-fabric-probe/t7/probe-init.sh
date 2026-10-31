#!/bin/sh
# T7 probe init for Arch Linux ARM guest under QEMU (slirp network)
# Runs the product provision script (pacman probe copy) in --native mode.
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
mount -t proc proc /proc 2>/dev/null
mount -t sysfs sysfs /sys 2>/dev/null
mount -t devtmpfs devtmpfs /dev 2>/dev/null
mkdir -p /run
echo "UMARM_BOOT_OK nproc=$(nproc 2>/dev/null) page=$(getconf PAGESIZE 2>/dev/null)"
# gvproxy static net (product wiring: subnet 192.168.127.0/24, GUEST_IP=.15,
# gatewayIP=.1, hostIP=.254; DNS = product GUEST_DNS 223.5.5.5, NAT'd as plain UDP)
ip link set lo up 2>/dev/null
ip link set eth0 up 2>/dev/null
ip addr add 192.168.127.15/24 dev eth0 2>/dev/null
ip route add default via 192.168.127.1 2>/dev/null
printf 'nameserver 223.5.5.5\n' > /etc/resolv.conf
echo "T7_NET: $(ip -4 addr show eth0 2>/dev/null | grep inet) dns=$(cat /etc/resolv.conf)"
echo "T7_BEGIN $(date 2>/dev/null)"
echo "T7 pacman.conf DisableSandbox: $(grep -c DisableSandbox /etc/pacman.conf 2>/dev/null)"
sh /t7/orchestella-guest-provision.pacman-probe.sh --native
RC=$?
echo "T7_PROVISION_RC=$RC"
echo "T7_STATE: $(cat /var/lib/orchestella/provision/state 2>/dev/null | tr '\n' ' ')"
echo "T7_DONE"
sync
poweroff -f 2>/dev/null || halt -f
sleep 5
