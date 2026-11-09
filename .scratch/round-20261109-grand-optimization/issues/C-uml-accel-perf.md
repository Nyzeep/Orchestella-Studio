# C · UML 加速性能增强

Status: done
Owner: lane/c-uml-perf
Files: UmlFaceLauncher.java、UmlFaceKeepAliveService.java、AcceleratorLaneAssembly.java（接线格）、新增通道/内核资产装配件

## What

承接 UML 主路径遗留（device-window-20261107 §9.4 + fabric 二轮判词）：

1. **内核资产落位**：libuml_kernel.so 装配路径与校验（payload 对账体系对齐）
2. **Channel seam（D-1 ACR-bound）**：UML 面与宿主通信通道抽象（接口先行，
   实现可 stub；不动 ACR 管辖的既有标记通道语义）
3. **wake lock 续期**：UmlFaceKeepAliveService 2h 上限的续期机制 + FGS 驻留门
   对照 E18 判词
4. **宽度控温接线**：S(2)=1.60 跨设备成立 → UML 面宽度供给接
   FabricOrchestrator 账本（ncpus≥2 取 N 判词落地为代码）

## Acceptance

- JVM 契约测试锁定新增 seam 与宽度判定（≥15 用例）
- :app 全量 ≥1266/0/0；lint 0 新增
- 产品 dispatch 接线缺口票（FabricOrchestrator.dispatch 零调用方）不动（另行立票跟踪）

## 边界

- 不碰 res/、不碰 pVM 线（设备平台级崩溃区）、不动 QEMU 面已收口件

## Comments

### 2026-11-10（编排层回填）

lane/c-uml-perf 78ca3f7a 合并 main；:app 1320/0/0（+47：资产13/通道12/宽度12/续期10）。四项全落（资产对账七态/UmlChannel seam+ACR 边界写清/wake lock 60min 阈值 24h 上限/forDeviceCapability 宽度入账本）。真机联调遗留 4 条在 lane 回报（资产实机落位、D-1 真通道注入、续期实机观测、宽度实机观测）。dispatch 零调用方立票不动。
