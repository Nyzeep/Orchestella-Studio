# round-20261111-ui-perf 工单索引

| 工单 | lane | 状态 | head/合并 |
| --- | --- | --- | --- |
| P2-deepening-t2 | deepening/t2-control-channel | done | e3089c12 → merge 8b0f503e |
| P3-deepening-t3 | deepening/t3-keepalive-session | done | 694da281 → merge 86ff3c74 |
| U1-theme-tier | lane/u1-theme-tier | done | 761d09be → merge 5cc34d93 |
| U4-walkthrough-basket | lane/u4-walkthrough-basket | done | 2ad6c91d → merge 2af4c7c0 |
| U2-wizard-draft | lane/u2-wizard-draft | done | 9c754c2f → merge 0dfb5de8 |
| U3-material-symbols | lane/u3-material-symbols | done | 3649a687 → merge c5d7c179 |

边界扩展追认（编排层）：P2 的 AvfInstanceLauncher seed 构造器与 BridgeClient 包私有
token seam（均带正向断言证明 seam 读真实字段）；测试基线演进 1357→1371→1381→1408→1431。
