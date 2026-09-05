# 安全边界：guest root、共享面与供应链

Labels: wayfinder:grilling
Type: grilling
Status: open
Blocked by: 11

## Question

安全边界的产品立场：guest root 与 host 权限的隔离承诺如何表述、共享目录/vsock 通道的鉴权要求、guest 镜像与内核分发的校验标准、默认暴露面（哪些默认只绑 loopback）。

## Why it matters

无 Root 产品把"VM 是安全边界"作为核心卖点之一；任何"guest 可指挥 host 做事"的通道都是攻击面。Podroid 的公开 root 密码与零校验分发是现成反面教材（01 票）。

## Known facts

- AVF 提供硬件隔离边界（pKVM）；QEMU TCG 是进程级隔离（弱一档）——两档差异需如实呈现。
- AVF 平台保证 vsock 仅 owner App 可连；Podroid 的失败点在默认凭据与零校验，而非平台通道。

## Working recommendation

承诺分级：guest root ≠ Android 用户数据（结构性成立，如实宣传，不夸大）；guest-agent 命令需 per-VM token 鉴权；全部端口转发默认 127.0.0.1、LAN 暴露需显式开启；镜像/kernel 分发强制 SHA256 manifest（构建期生成）；共享目录白名单制。

## Reversibility

鉴权/校验后补极难（涉及存量用户迁移）；必须从第一天做对。
