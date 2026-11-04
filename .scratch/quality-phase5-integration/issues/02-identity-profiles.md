# 02 Identity and profiles

Type: task
Status: resolved
Blocked by: 01 mechanical transplant

Carry validated username/hostname/hasSudo from creator through persistence, cold load and LaunchParameters to both strategy bootstrap inputs. Preserve old signatures and unknown/empty password semantics. Maintain a non-secret root-owned atomic instance-user marker for guest profiles; validate the actual ordinary user, home and sudo configuration, and show action-required if prerequisites are absent. Preserve native base provision/repository/recovery behavior. Optional features remain explicitly experimental and default terminal; no unverified distro capability promotion.

Owned paths follow map.md and worker dispatch. Acceptance: cold metadata, legacy defaults, invalid identity, no-sudo, unknown password and actual profile user regression coverage; complete applicable Linux profile suite passes. Final shared launcher/MainActivity/generator wiring belongs to coordinator. No network/download/device/Git operations by this worker. Evidence: external quality-phase5-integration/identity-profiles, .json/.md/.log.txt only.

## Comments

- 2026-11-04 / repo-hygiene lane（对账回填）：同批 01——合流经 PR #2（merge `ec0df2d`，2026-10-14）入 main；身份链验收与回归覆盖见归档报告 `docs/reports/archive/2026-10-phase5-quality/orchestration/quality-phase5-integration-verification.zh-CN.md` 与 `reviews/quality-phase5-integration/`。状态回填 `claimed` → `resolved`。
