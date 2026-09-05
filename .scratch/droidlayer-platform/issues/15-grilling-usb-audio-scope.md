# USB/音频 MVP 范围

Labels: wayfinder:grilling
Type: grilling
Status: open
Blocked by: 04

## Question

USB 直通与音频在 MVP 的立场：不做、还是只做 QEMU 档最小直通？音频（guest 出声到手机）是否 MVP？

## Why it matters

USB 是权限/安全/兼容性三重深坑（Podroid 真实适配器仍有 open bug）；音频是"桌面体验"的前置。两者都不阻塞核心价值（终端+网络）。

## Known facts

- Podroid：USB 仅 QEMU（fd 流方案 + 运行时授权弹窗），AVF 无；音频单向 guest→host（01 票）。
- AVF 对第三方 app 的 USB 直通能力未知（无公开证据）。

## Working recommendation

MVP：两者都不做，写入非目标；USB 列为 v2（先 QEMU 档复用 fd 方案），音频随后（PulseAudio simple-protocol 模式已验证可行）。

## Reversibility

延后实现不产生不可逆成本；反而避免在无用户反馈时过度设计。
