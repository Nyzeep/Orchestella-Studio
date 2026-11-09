# T3 · 设备窗：构建上机

Status: done
Owner: 主会话（设备 lane 串行）
前置：T1/T2 合并 + 敏感门后执行（设备跑本轮合并后 main，一次构建覆盖全部改动）

## What

1. ASCII worktree（/d/t/orch-closeout-20261108）基于合并后 main；**构建前清
   app/build 与 outputs 旧残留**。
2. 复制主检出 gitignored payload（与 runtime-artifacts-v1.sha256 对账）进 worktree。
3. 复制 local.properties。
4. `:app:assembleDebug`，带
   `-Porchestella.assets.allowMissingPayload=true -Porchestella.acceleratorLane=true`。
5. adb install -r 上机（亮屏解锁；am start 前 force-stop）。

## Acceptance

- 设备运行合并后 main（0.6.2/code602 + 本轮 T1/T2 改动），冷启动零崩溃。
- APK sha256 记入仓外 evidence/optimization-closeout-20261108/。

## Comments

### 2026-11-10（编排层回填）

APK#1 f299efaa（b52ba76f）与 APK#2 15287897（400151dc）先后上机，冷启动均零崩溃；accelerator lane flag 生效（仪表盘 pVM 编译加速·就绪）。payload 4 件复制对账吻合；manifest 两文档类 BAD 为 manifest 过期存量（git 干净检出同状），非缺件。坑：MSYS_NO_PATHCONV=1 时 adb install 本地路径也须盘符形。
