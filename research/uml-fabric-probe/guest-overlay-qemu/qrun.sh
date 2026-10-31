#!/system/bin/sh
# device-side QEMU runner for T4 matrix (avoid adb quoting hell)
# usage: qrun.sh <smp> <logfile>
# usage(net): qrun.sh net   —— T7 网络面直驱（产品语法，配方说明见
#             docs/research/2026-11-08-t7-network-closure.md §5 与本目录 README）
if [ "$1" = "net" ]; then
    PORT=${T7_PORT:-24117}
    ROOTFS=${T7_ROOTFS:-/data/local/tmp/t7-arch.ext4}
    LOG=${T7_LOG:-/data/local/tmp/t7-console.log}
    GVPX=${T7_GVPROXY:-/data/local/tmp/qemu-bin/gvproxy}
    YAML=${T7_YAML:-/data/local/tmp/t7-net.yaml}
    PORT_HEX=$(printf '%04X' "$PORT")
    cd /data/local/tmp/qemu-bin || exit 3

    # 规则 1（硬性）：gvproxy 的 qemu 监听是单发 accept —— 每个 gvproxy 进程生命周期
    # 只服务一次 QEMU 连接（containers/gvisor-tap-vsock cmd/gvproxy/main.go 的
    # config.Interfaces.Qemu 分支：Accept 一次后 goroutine 返回，不再 accept）。
    # 上一次 QEMU 的残留连接或任何探活连接都会烧掉 accept 槽位并打
    # "cannot read size from socket: EOF"。因此每次重启 QEMU 前必须重启 gvproxy。
    pkill -f 't7-net.yaml' 2>/dev/null
    sleep 1

    # 规则 2（硬性）：就绪判定只允许 LISTEN 检查，绝不允许对 qemu 监听端口做连接式探测
    # （nc / curl / </dev/tcp）。连接式探测会被当作 QEMU 客户端 → EOF 掉线——产品提交
    # 109b43d5 的实测踩坑；产品就绪探测用端口绑定测试（NetworkGatewayDaemon.java:602-607）。
    # 本处等价实现：读 /proc/net/tcp 的 LISTEN(0A) 状态。
    "$GVPX" -config "$YAML" > "${LOG}-gvproxy.log" 2>&1 &
    GVPID=$!
    i=0
    LISTEN=
    while [ "$i" -lt 15 ]; do
        kill -0 "$GVPID" 2>/dev/null || { echo "gvproxy exited early"; exit 4; }
        while read -r _ local st _; do
            case "$local" in *:"$PORT_HEX") [ "$st" = "0A" ] && LISTEN=1 ;; esac
        done < /proc/net/tcp
        [ -n "$LISTEN" ] && break
        i=$((i + 1))
        sleep 1
    done
    [ -n "$LISTEN" ] || { echo "gvproxy port never LISTEN"; exit 5; }

    # QEMU 侧按产品 buildWithPasst(QemuTuning.DEFAULTS) 逐字拼装：
    #   runtime/engine-qemu/.../QemuLaunchSpec.java :520-523 + passtNetdevArg :569-575
    #     -netdev socket,id=orchestella-net,connect=127.0.0.1:<port>
    #     -device virtio-net-device,netdev=orchestella-net
    #   stream/addr.type=unix 变体仅当 QemuTuning.passtUnixSocketPath 非空（默认 null = TCP 路径）。
    #   打包版 QEMU 三条真机约束（产品提交 109b43d5）：socket netdev 不支持 addr.type 新语法、
    #   UNIX socket 无支持（sun_path 108 限制）、virtio-net-pci 需要 efi-virtio.rom（打包无 ROM）
    #   → 必须 legacy connect=host:port + virtio-net-device（virtio-mmio 免 ROM）。
    #   drive/device 行按产品 rootDriveArg/rootDeviceArg DEFAULTS :553-567/:542-551。
    #   内核网络参数按产品 QemuInstanceLauncher.java:651-655（orchestella.guest_* 通道），
    #   数值 = NetworkGatewayDaemon.java:96-100 常量。
    # 与产品的既有差异（探针形态，与网络面无关）：无 initramfs（Arch rootfs 直启 init=/init，
    #   2026-10-31 设备轮既定形态）；串口落文件替代 abstract chardev 终端面；
    #   省略 control/netproxy/display 三条 virtio-serial 平面（Arch 探针无 droidlayer guest agent）。
    exec ./qemu-system-aarch64 -machine virt,gic-version=3 \
        -accel tcg,thread=multi -cpu cortex-a57 -smp 2 -m 2048M \
        -nodefaults -display none -monitor none -no-reboot \
        -kernel assets/Image \
        -append "root=/dev/vda rw init=/init console=ttyAMA0 orchestella.guest_ip=192.168.127.15 orchestella.guest_gw=192.168.127.1 orchestella.guest_mask=255.255.255.0 orchestella.guest_dns=223.5.5.5 orchestella.proxy_enabled=0" \
        -drive if=none,id=root,file="$ROOTFS",format=raw,cache=writeback \
        -device virtio-blk-device,drive=root \
        -netdev socket,id=orchestella-net,connect=127.0.0.1:"$PORT" \
        -device virtio-net-device,netdev=orchestella-net \
        -serial "file:$LOG"
fi
SMP=$1
LOG=$2
cd /data/local/tmp/qemu-bin || exit 3
exec ./qemu-system-aarch64 -machine virt,gic-version=3 -accel tcg,thread=multi -cpu cortex-a57 -smp "$SMP" -m 6371M -nodefaults -display none -monitor none -no-reboot -kernel assets/Image -append "root=/dev/vda rw init=/init work=makebench j=$SMP console=ttyAMA0" -drive file=/data/local/tmp/c200-qemu.ext4,format=raw,if=virtio -serial "file:$LOG"
