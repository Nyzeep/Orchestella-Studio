# DV-02: Sealed toolbox / 终端 / 代理 / 导出真机门（串行 2）

Type: device-verification
Status: blocked-by-DV-01
Priority: P0（Fast Lane 产品形态）
Owner: 设备唯一操作者/主协调
Blocked by: DV-01, P03, P04, P05, P06
External gates: 同 DV-01；APK 体积/许可证已审

## 目标

验证 Fast Lane 是否真的能提供用户可感知的 Linux 环境：

- 从 `/mnt/apk/bin` 执行 shell/工具/编译器；
- PTY 终端；
- encrypted home 持久化；
- 宿主 SOCKS5/HTTP CONNECT 代理；
- 导出/导入；
- 边界测试有真实结论。

## 步骤

1. 启动 Fast Lane；从 `bin/` 执行 shell：`echo FASTLANE_OK`。
2. 执行 git/python/clang（按 P03 选型）；编译最小 C 程序到 encrypted storage（不要求执行产物）。
3. PTY：交互、resize、EOF、Ctrl-C、关闭；多次 open/close。
4. 网络：`curl https://example.com`、`git clone`、pure-Python pip；DNS/TLS。
5. 导出：tar → vsock → SAF；导入反向；hash 一致；host 无明文。
6. 边界：从 encrypted storage 执行、chroot、mount、proot、memfd、JIT；记录允许/拒绝。
7. 记录 APK 体积、启动时间、终端延迟、内存。

## 验收

- [ ] shell/工具/编译器可执行；数据持久。
- [ ] 终端稳定；无泄漏/串流；关闭收敛。
- [ ] 代理 curl/git/pip 成功；只绑 127.0.0.1；失败 typed。
- [ ] 导出/导入 hash 一致；host 不落明文。
- [ ] 边界测试：encrypted exec/chroot/mount/proot 按预期拒绝；memfd/JIT 有真实结论。
- [ ] APK 体积在预算内；启动时间可接受。
- [ ] 不把 Termux/PRoot 的宿主性能当 Fast Lane 性能。

## 证据

`protected-fastlane/20261021/dv02/`；含工具版本/SHA、执行输出、终端记录（脱敏）、代理日志（无 URL 正文）、导出 manifest/hash、边界 errno/SELinux denial、体积/启动数据。

## 停止条件

- 无法从 `bin/` 执行任何工具：降级 native payload 或停止。
- chroot/proot/mount 被拒是预期边界；不绕过。
- 代理无法 DNS/TLS：只做离线/终端。
- 导出无法保证明文不落宿主：停止产品化。
