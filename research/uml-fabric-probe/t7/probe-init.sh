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
# 探针侧环境修复（provision 保持产品逐字节同形）：上一次操作超时被 SIGKILL 的 pacman 会
# 遗留 /var/lib/pacman/db.lck，下一次 boot 的 package-install 立即 "unable to lock database"
# 失败（2026-11-01 attempt5 实证，产品 provision 无锁恢复路径——产品化建议回填：fail_stage
# 或重试入口应含锁清理/检测）。
rm -f /var/lib/pacman/db.lck 2>/dev/null
# machine-id 修复（探针侧环境修复）：ArchLinuxARM rootfs 的 /etc/machine-id 为 0 字节空文件
# （正常由首启 systemd-machine-id-setup 填充）；provision machine-identity 阶段的
# `dbus-uuidgen --ensure=/etc/machine-id` 对已存在的空文件直接拒绝（2026-11-01 attempt6
# 实证 "should contain a hex string of length 32, not length 0"）。删除空文件让
# dbus-uuidgen --ensure 重建（2026-11-01 attempt7 实证过墙）。
rm -f /etc/machine-id 2>/dev/null
# sshd privsep 目录权限修复（探针侧最小修复）：镜像目录层遗留 0777（10-31 构建时 staging 在
# WSL /mnt/d DrvFs 上，目录位全 777；pacman hook 仅告警，但 openssh 10 的 sshd -t 对
# /usr/share/empty.sshd 强校验 → "must be owned by root and not group or world-writable"
# exit 255（2026-11-01 attempt9 实证）。产品构建建议：staging 须在 WSL 原生 ext4 内组装。
chmod 755 /usr/share/empty.sshd 2>/dev/null
chown root:root /usr/share/empty.sshd 2>/dev/null
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
# 操作日志回吐（run_step 把 pacman/系统输出重定向到该文件，:132；不回吐则错误只在 guest 内不可见）
echo "T7_PROVISION_LOG_TAIL_BEGIN"
tail -n 400 /var/log/orchestella-provision.log.txt 2>/dev/null | grep -av "warning: directory permissions differ"
echo "T7_PROVISION_LOG_SSHD_BEGIN"
grep -a -B3 -A8 -E "sshd|/etc/ssh" /var/log/orchestella-provision.log.txt 2>/dev/null | tail -40
echo "T7_PROVISION_LOG_SSHD_END"
echo "T7_PROVISION_LOG_TAIL_END"
echo "T7_ETH_TX_RX_3: $(cat /sys/class/net/eth0/statistics/tx_packets 2>/dev/null) $(cat /sys/class/net/eth0/statistics/rx_packets 2>/dev/null)"
echo "T7_DONE"
sync
poweroff -f 2>/dev/null || halt -f
sleep 5
