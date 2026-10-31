#!/bin/sh
# T7 probe init for Arch Linux ARM guest under QEMU (gvproxy static net, product values)
# Runs the product provision script (pacman probe copy) in --native mode.
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
mount -t proc proc /proc 2>/dev/null
mount -t sysfs sysfs /sys 2>/dev/null
mount -t devtmpfs devtmpfs /dev 2>/dev/null
mkdir -p /run
echo "UMARM_BOOT_OK nproc=$(nproc 2>/dev/null) page=$(getconf PAGESIZE 2>/dev/null)"
# gvproxy static net (product wiring: subnet 192.168.127.0/24, GUEST_IP=.15,
# gatewayIP=.1, hostIP=.254; DNS = product GUEST_DNS 223.5.5.5, NAT'd as plain UDP;
# 数值出处 NetworkGatewayDaemon.java:96-100 + configuration() :341-344)
ip link set lo up 2>/dev/null
ip link set eth0 up 2>/dev/null
ip addr add 192.168.127.15/24 dev eth0 2>/dev/null
ip route add default via 192.168.127.1 2>/dev/null
printf 'nameserver 223.5.5.5\n' > /etc/resolv.conf
echo "T7_NET: $(ip -4 addr show eth0 2>/dev/null | grep inet) dns=$(cat /etc/resolv.conf)"
# ---- 网络面分层诊断（2026-11-08 配方新增；失败层定位用，全部限时防挂死）----
# L1 链路层：网关 ARP 是否学到（gvproxy qemu 监听活着且回 ARP 的直接证据）
echo "T7_ARP_BEGIN"
ping -c1 -W2 192.168.127.1 >/dev/null 2>&1
echo "T7_ARP: $(ip neigh show 2>/dev/null | grep 192.168.127.1)"
echo "T7_ETH_TX_RX: $(cat /sys/class/net/eth0/statistics/tx_packets 2>/dev/null) $(cat /sys/class/net/eth0/statistics/rx_packets 2>/dev/null)"
# L2 网关面：ping 网关（guest 以太帧 → QEMU → gvproxy gVisor 栈的往返证据）
echo "T7_PING_GW: $(ping -c1 -W2 192.168.127.1 2>&1 | tail -2 | tr '\n' ' ')"
# L3 上游/DNS 面：域名解析（gvproxy DNS 转发证据）
echo "T7_DNS: $(nslookup archlinuxarm.org 2>&1 | tail -3 | tr '\n' ' ')"
echo "T7_ETH_TX_RX_2: $(cat /sys/class/net/eth0/statistics/tx_packets 2>/dev/null) $(cat /sys/class/net/eth0/statistics/rx_packets 2>/dev/null)"
# ---- 主探测 ----
echo "T7_BEGIN $(date 2>/dev/null)"
echo "T7 pacman.conf DisableSandbox: $(grep -c DisableSandbox /etc/pacman.conf 2>/dev/null)"
sh /t7/orchestella-guest-provision.pacman-probe.sh --native
RC=$?
echo "T7_PROVISION_RC=$RC"
echo "T7_STATE: $(cat /var/lib/orchestella/provision/state 2>/dev/null | tr '\n' ' ')"
echo "T7_ETH_TX_RX_3: $(cat /sys/class/net/eth0/statistics/tx_packets 2>/dev/null) $(cat /sys/class/net/eth0/statistics/rx_packets 2>/dev/null)"
echo "T7_DONE"
sync
poweroff -f 2>/dev/null || halt -f
sleep 5
