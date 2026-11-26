# W4-05 实验桌面 Guest 夹具

在原型分支的工作树根目录运行以下命令。使用 x86_64 Linux 或 WSL、Python 3.11 以上、GNU cpio、tar、gzip、curl、dpkg-deb、aarch64-linux-gnu-gcc 和 readelf。构建底包的宿主命令需要为归档设置所有者并创建设备节点，使用已有 WSL 的 root 用户即可。Guest 中的 labwc、wayvnc、foot、采集端和拖动端由控制器以 UID/GID 1000 启动。

```bash
fixture_out=/path/to/w405-fixture-output
bench=tools/display-spike/benchmark

bash "$bench/build_guest_fixture.sh" "$fixture_out"
bash "$bench/build_guest_clients.sh" "$fixture_out"
python3 "$bench/pack_guest.py" \
  --base "$fixture_out/desktop-base.cpio.gz" \
  --capture "$fixture_out/w405-capture" \
  --drag "$fixture_out/w405-drag" \
  --output "$fixture_out/desktop-benchmark.cpio.gz"
sha256sum "$fixture_out/desktop-benchmark.cpio.gz"
```

底包从 Alpine 3.23.8 aarch64 minirootfs 构建。两个架构的 minirootfs 启动归档使用脚本内的 SHA-256 核对。APK 的版本、仓库和 SHA-256 固定在 `desktop-packages.lock`，apk-tools 使用 minirootfs 的 Alpine 公钥逐包验签，在临时 Guest 根目录离线安装并停用包脚本。缓存、压缩包和实际安装版本清单保存在 `fixture_out`。发行仓库淘汰旧版 APK 时可以继续复用这一份经哈希校验的缓存。[Alpine 3.23 aarch64 发布目录](https://dl-cdn.alpinelinux.org/alpine/v3.23/releases/aarch64/)

夹具使用 labwc 0.9.2、wlroots 0.19.2、wayvnc 0.9.1、foot 1.25.0、Python 3.12.16 和 DejaVu 字体。完整的官方依赖安装约占 377 MiB。Guest 启动时还需要解压空间和运行余量，测试配置使用 1536 MiB Guest RAM。

采集端与拖动端使用锁定的 Alpine musl 和 Wayland 头文件，配合 Ubuntu noble 的 Wayland scanner 生成协议绑定。脚本从固定提交获取 screencopy 和 virtual pointer XML，核对 SHA-256 后生成绑定与 AArch64 ELF。脚本用 readelf 输出程序解释器和链接依赖，供核对 `/lib/ld-musl-aarch64.so.1` 与 `libwayland-client.so.0`。[协议 XML 固定提交](https://github.com/swaywm/wlr-protocols/tree/b010a03648b88d143236de193bddbfea0c08bc84/unstable)

Guest 控制器创建 UID 1000 私有 runtime 目录并独立启动 labwc 与 RFB 通道。labwc 使用 headless、pixman 和单输出。控制器等待 `/run/user/1000/wayland-0` 后，运行 `w405-desktop geometry` 设置并确认 960×540、60 Hz、1 倍缩放。Raw 和 ZRLE 共用已入库的 Guest display relay，wayvnc 在 `/run/user/1000/orchestella/wayvnc.sock` 提供服务，默认每秒最多 15 帧。ivshmem 使用同一 compositor 和负载，通过 screencopy 采集。
