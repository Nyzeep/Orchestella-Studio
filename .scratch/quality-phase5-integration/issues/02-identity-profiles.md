# 02 Identity and profiles

Type: task
Status: claimed
Blocked by: 01 mechanical transplant

Carry validated username/hostname/hasSudo from creator through persistence, cold load and LaunchParameters to both strategy bootstrap inputs. Preserve old signatures and unknown/empty password semantics. Maintain a non-secret root-owned atomic instance-user marker for guest profiles; validate the actual ordinary user, home and sudo configuration, and show action-required if prerequisites are absent. Preserve native base provision/repository/recovery behavior. Optional features remain explicitly experimental and default terminal; no unverified distro capability promotion.

Owned paths follow map.md and worker dispatch. Acceptance: cold metadata, legacy defaults, invalid identity, no-sudo, unknown password and actual profile user regression coverage; complete applicable Linux profile suite passes. Final shared launcher/MainActivity/generator wiring belongs to coordinator. No network/download/device/Git operations by this worker. Evidence: external quality-phase5-integration/identity-profiles, .json/.md/.log.txt only.
