# C · UML 加速性能增强

Status: pending
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
