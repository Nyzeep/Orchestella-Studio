# 26: Protected 私有根盘、持久状态与实际FD入口可行性

Type: prototype
Status: blocked-for-device-vm-stage（低成本来源预检可先行）
Priority: P1（产品化否决门）
Owner: storage/AVF原型owner；主协调负责设备与ACR证据综合
Blocked by: 25（本票真实VM数据阶段）
Preliminary work: 无需启动VM的文件来源/标签/API路径分析可与24并行，仅中间产物，不能提前完成本票
Parent: 05/G4与ACR-01的protected数据证据；不复制其权限/合成32B FD测试

## 用户价值与来源

回答“能boot的protected Guest能否安全保存用户实例”，避免在桌面/性能投入后才发现只能用shell公共测试盘。来源：详细研究§5.2/5.3、ADR0005/0007/0008/0011、现05/ACR-01证据。固定A16拒典型App私有kernel/initrd/完整raw盘FD，转交FD不改变label；这属于目标ROM待实证预测，不直接声称本机行为已运行。

## 范围与交付

用全新合成QA磁盘/state，验证实际VM消费入口、文件label/mode/owner、跨正常重启身份/数据、FD接收/关闭/失败/取消与盘lease。先从参考实现和可读元数据给preflight，25真实自有Guest门通过后才实际create/读写盘。分别记录App私有根盘、kernel、基础initrd、含秘密的本轮initrd、VM配置/state/instance/rollback对象，不把后者藏成可删cache。

候选顺序先比较原App owner与最小API兼容，再考虑具体受限代理；无Root/无目录放权/无任意shell。任何实际读FD能力都是权限面，RO+禁日志不代表接收方看不到token/凭据。先用合成秘密sentinel做能力验证，不读取/归档真实embedded载荷。

禁止把用户盘复制公共目录、chcon/chmod、改partition名为vm-instance绕来源、自动delete/recreate旧VM、放宽共享/Root/修改系统信任、把memfd/pipe假定为所有raw接口接受。若必须改变私有存储/单盘/共同kernel/额外state/网络契约，列具体ACR影响后停相关生产扩张。

## 行为验收

- [ ] 实际caller/label/路径开FD与后续VM消费分清，App→US合成FD已过不当US→virtmgr/crosvm已过；错误类型/模式/closed/symlink/旧代/caller拒绝。
- [ ] 全新QA盘真实持久marker写入，正常stop/重开后同实例/盘引用与marker保留，所有相应state/身份对象归属和升级处理清楚。
- [ ] FD副本/迟到完成/取消/death收尾可观察，stop未知保持lease；App/server死亡原型不代VM停。
- [ ] 当前私有数据契约可保持，或给明确反证：最早拒绝层、只用shell盘的实验限度、无法满足的ADR条款与候选替代；不能只交“以后换存储”。
- [ ] 前期并行检查protected网络/API与现NAT/DNS/端口语义明显冲突；不实现整套新网络，结果交07/ACR作完整契约输入。
- [ ] assembled-initrd秘密可读对象范围、App/代理/platform谁可读、数据生命周期/迁移/回滚为ACR-01具体裁定包；prototype完成不自动批准。

## 测试、设备门与统计

现05 FD/caller/epoch和24收尾公共seam可复用；先来源检查负例，再真实QA VM入口。red命令依据实现后的prototype README，不伪造现不存在的测试已跑。数据I/O仅功能小sentinel，非吞吐/fsync耐久性基准；性能不适用。设备需25成功/无活事务/新QA实例和主协调串行窗口。

来源被拒且没有符合已授权契约的合法入口时，本票可交付证据化不可行研究结论并标resolved-negative；07protected产品化继续blocked，不为全绿变更用户数据位置。需要新选择时保持needs-architecture-decision，而非生产ready。

## 回滚、许可与证据

只本轮新QA盘/state、普通owner/FD收尾；不动用户实例/已有授权，QA文件保留按明确白名单由维护者处理，不自动清理ClassA。依赖沿现框架，自研原型，新增镜像/组件逐项许可与hash锁，公开分发另核GPL源码义务。

报告 `docs/reports/prototypes/avf-shizuku-26/prototype-report.zh-CN.md`，review同id，外部26专属根含input/operation/stop/数据引用脱敏receipt及preservation。当前只有规划；25、真实VM私有数据入口、ACR-01均未闭合。
