# Quality / Phase 5 integration map

Status: claimed
Base: 8d0e8c75d769ecc645ea102b90a304c8657344a1
Branch: integration/quality-phase5
Foundation: 3b637e48f51fb4b78282d468d9c78b37d582de87

Scope and acceptance: [complete execution prompt](../../docs/agent-handoff/QUALITY_PHASE5_MERGE_PROMPT.zh-CN.md). Existing product decisions remain authoritative. Seven-distribution long acceptance stays cancelled; reference upstreams are read-only.

| Ticket | Status | Dependency | Owner |
| --- | --- | --- | --- |
| [01 Shared semantic integration](issues/01-shared-integration.md) | claimed | verified quality foundation, Phase5 frozen gate | coordinator |
| [02 Identity and profiles](issues/02-identity-profiles.md) | claimed | 01 mechanical file transplant | identity/profiles worker |
| [03 Display, services, AVF and 9p](issues/03-module-validation.md) | claimed | 01 mechanical file transplant | independent module worker |
| [04 Producer, validation, review and delivery](issues/04-delivery.md) | pending | 01, 02, 03 | coordinator and two independent reviewers |

Source worktrees are preserved. The canonical 141-entry Phase5 manifest is the only feature-delta index. Captured before and fixed HEAD blobs are selected per entry; generated init is rebuilt from final canonical modules. External outputs and evidence belong to Orchestella/artifacts/quality-phase5-integration. Original logs use .log.txt/.json/.md; credentials/disks are excluded from Git.

Coordinator owns BridgeClient, ExecutionSession, QEMU/AVF application launchers, C agent, generator/entry, shared existing activities, Gradle/manifest and runtime production. Identity worker owns InstanceStrategyLauncher/LaunchParameters, creators, QemuRuntimeBootstrapper, InstanceLifecycle/UserConfig, features/profile scripts, identity portions of runtime-functions/provision and their tests. Module worker owns independent display/native/services/sharing/prototypes and runtime engine-avf. Device access is coordinator-only.
