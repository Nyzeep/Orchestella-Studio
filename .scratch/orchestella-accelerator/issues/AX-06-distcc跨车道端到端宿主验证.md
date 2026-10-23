# AX-06: distcc 跨车道端到端宿主验证

- Type: integration
- Status: ready-for-human
- Priority: P0（第一版功能闭环）
- Owner: distcc/cross-lane owner
- Blocked by: AX-03、AX-04、AX-05
- Parent: `../决策地图.md`
- Zero-device: 是

## 背景

P09 只有宿主字节桥的 17 个测试，没有真实 distcc 编译，也没有 payload 侧或加速 VM 侧的 stdio 泵。需要在不接设备的前提下，用宿主 x86_64 上的真实 distcc 客户端/服务器把协议、桥、回退路径全部跑通。

## 目标

- 在宿主上用真实 distcc 完成一次 C 编译：客户端 → AX-03 通道 → 加速侧 `distccd` → 返回 `.o`。
- `.o` 与本地编译产物可链接、可运行；哈希一致或记录可解释差异。
- 编译器不匹配、超时、鉴权失败、加速器崩溃时，客户端回退本地编译，不产出坏对象。

## 任务

- [x] 宿主测试夹具：安装/构建 distcc 3.4，启动 `distccd --inetd` 或 daemon；固定编译器（clang 或 gcc）与 flags。
- [x] 加速侧适配：`distccd --inetd` stdio 泵（若使用 daemon 则实现 TCP↔AXP1 转发），错误流不得污染协议。
- [x] 客户端配置：`CC=distcc clang`、`DISTCC_HOSTS`、`DISTCC_FALLBACK=1`、并发 `-j`。
- [x] 成功路径：编译 `hello.c` → `.o` → 链接 → 运行，输出固定字符串；记录字节数与耗时。
- [x] 失败路径：编译器版本不一致、通道超时、token 错误、加速器中途退出；每种都必须本地回退并标记原因。
- [x] 安全：日志不含源码、argv、token、对象正文；只记 job 数、字节数、耗时、code。
- [x] 测试：JVM + pytest 全套；真实 distcc 集成测试在 CI 上可跳过但必须本地可复现。
- [x] 文档：guest/加速 VM 配置、端口、故障排查、已知限制（C 优先，C++ `not-tested`）。

## 验收命令

```bash
./gradlew :runtime:fastlane-distcc:test
python -m pytest tools/fastlane-distcc -q
bash tools/fastlane-build/test-distcc-e2e.sh
```

## 边界

- 不接设备、不跑 QEMU guest、不把宿主测试当作真机证据。
- 不解析 distcc 协议；桥保持透明。
- 不承诺 C++、不承诺跨架构；只验证同架构同编译器。

## 证据

- 编译/链接/运行输出摘要、`.o` 哈希、失败样本写入 `docs/reports/tickets/ax-06/implementation-report.zh-CN.md`。
- 实现提交：`4622c6a`（批次三分支）。宿主回环模拟，不是 vsock/设备证据。
