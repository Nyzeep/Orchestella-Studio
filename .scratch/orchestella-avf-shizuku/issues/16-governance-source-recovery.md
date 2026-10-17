# 16: 缺失治理文档的来源保全与恢复裁定

Type: research
Status: resolved-source-preserved
Owner: 主协调
Blocked by: None（本票来源调查已完成；现行政策恢复须维护者核对）

用户价值：后续Agent不再根据引用标题编造条文或把历史状态当当前。来源研究§9与E/governance-history/manifest.json。任务限三份定点文档的Git历史，不归档整仓或敏感内容。

已完成：triage-labels、ADR0015、GPL_RELEASE_DELIVERY_POLICY均于66cf781d85435b651f53aeec9584766b273c1623移出版本库；父提交5c118560879fd44e3a05496777924f2fd462fb4e的原文已按bytes导出、SHA256和即时preservation。发现历史GPL“当前状态”早于公开发布；保持历史reference-only，不静默恢复active或改变维护者责任。

验收：三文件完整来源/bytes/hash可复查，当前不存在/旧引用明确记录，正文不编造，历史保护条款不因缺文件被默认放宽。命令为定点git show/log与导出hash；全部实际运行。设备/性能/IPC/FD/VMowner不适用，无新依赖。失败不自动从未知来源补政策；回滚只新历史导出副本，原Git/现政策不变。研究报告§9为canonical结论，原件E/governance-history/，0新.log。

后续恢复现行版本需维护者核对历史发布恢复与责任状态，另开小票；本票不自动把旧文件force-add或初始化tracker。用户未授权提交/推送/发布。
