# Issue tracker: Local Markdown

Issues and specs for this repo live as markdown files in `.scratch/`.

## Conventions

- One feature per directory: `.scratch/<feature-slug>/`
- The spec is `.scratch/<feature-slug>/spec.md`
- Implementation issues are one file per ticket at `.scratch/<feature-slug>/issues/<NN>-<slug>.md`, numbered from `01`, never a single combined tickets file
- Triage state is recorded as a `Status:` line near the top of each issue file (see `triage-labels.md` for the role strings)
- Comments and conversation history append to the bottom of the file under a `## Comments` heading

## When a skill says "publish to the issue tracker"

Create a new file under `.scratch/<feature-slug>/` (creating the directory if needed).

## When a skill says "fetch the relevant ticket"

Read the file at the referenced path. The user will normally pass the path or the issue number directly.

## Wayfinding operations

Used by `/wayfinder`. The **map** is a file with one **child** file per ticket.

- **Map**: `.scratch/<effort>/map.md` (the Notes / Decisions-so-far / Fog body).
- **Child ticket**: `.scratch/<effort>/issues/NN-<slug>.md`, numbered from `01`, with the question in the body. A `Type:` line records the ticket type (`research`/`prototype`/`grilling`/`task`); a `Status:` line records `claimed`/`resolved`.
- **Blocking**: a `Blocked by: NN, NN` line near the top. A ticket is unblocked when every file it lists is `resolved`.
- **Frontier**: scan `.scratch/<effort>/issues/` for files that are open, unblocked, and unclaimed; first by number wins.
- **Claim**: set `Status: claimed` and save before any work.
- **Resolve**: append the answer under an `## Answer` heading, set `Status: resolved`, then append a context pointer (gist + link) to the map's Decisions-so-far in `map.md`.

The active wayfinding efforts are **`.scratch/orchestella-fastlane/`** (current main line) and **`.scratch/orchestella-avf-shizuku/`** (time-boxed AVF research line; does not block release). `.scratch/orchestella-platform/` and `.scratch/orchestella-phase5/` are historical efforts retained for provenance, not current work.

## Reconciliation snapshot (2026-11-04)

Full audit: `docs/reports/repo-hygiene-20261104.zh-CN.md`. Every ticket file in `.scratch/*/issues/` was checked against git history and archived implementation reports (ticket-face `Status:` is not trusted on its own).

Backfilled this round (12 tickets, each with a dated `## Comments` entry citing the evidence):

- `quality-phase5-integration` 01/02/03 `claimed` → `resolved`, 04 `pending` → `resolved` — merged long ago via PR #2 (`ec0df2d`, 2026-10-14); final reports now under `docs/reports/archive/2026-10-phase5-quality/`.
- `orchestella-platform` 26/52/56 `ready-for-agent`/`ready-for-review` → `completed` — M1/M4 milestone merges on main (`3fc8143`, `dcaa5de`); the effort map already declared 25–58 fully accepted, the ticket files had not been updated.
- `orchestella-fastlane` P03/P09/P11 `ready-for-agent（zero-device）` → `implemented` — zero-device work landed 2026-10-21; implementation reports archived under `docs/reports/archive/2026-10-zero-device/tickets/fastlane-P0x/`; sibling P-tickets were backfilled earlier, these three were missed.
- `orchestella-pvm-compiler` PVM-10 and DV-PVM-03: status normalized to a `Status:` line per the convention above (they previously used a free-form `状态：` line).

Still-open tickets (after backfill; grouped, with what unblocks them):

| Effort | Tickets | Blocking reason | Needs |
| --- | --- | --- | --- |
| orchestella-fastlane | P13 (accel reservation leak, P1) | unclaimed, zero-device fix | nothing — ready work |
| orchestella-fastlane | DV-01A → DV-01/02/03/04 | external: OEM SELinux `execute_no_trans` on protected payload | device window + OEM evidence |
| orchestella-fastlane | P04 | FLN1 adapter landed; rest waits DV-02 | device window |
| orchestella-avf-shizuku | 24, 27, 28 ready; 29/30 blocked by 28 | protected-lane chain, waits 28 prototype | agent + device window |
| orchestella-avf-shizuku | 05/06/11/12/14/15/19/20 partial | dev done, external OEM/physical gates open | device window |
| orchestella-avf-shizuku | 07/08/09/10, 25/26 blocked; ACR-01/02/03 | platform evidence / architecture decisions | maintainer + device |
| orchestella-pvm-compiler | PVM-06 | Gradle gate not yet run by a main session | nothing — ready work |
| orchestella-pvm-compiler | PVM-10, PVM-11, DV-PVM-02b | on-device A/B/C acceptance `not-tested` | device window |
| orchestella-accelerator | AX-01..08, PL-01/02 | `ready-for-human`: zero-device done, awaiting human/device review | maintainer + device window |
| orchestella-phase5 | 05–10 + ACR-01 | `implemented-local; device-gates-open`; ACR-01 is an open decision | device + maintainer |
| orchestella-platform | 17 (AVF e2e prototype), 18 (QEMU TCG baseline) | prototype gates for tickets 34/35 device acceptance; 18 substantively answered by later UML-fabric device rounds but never formally closed | device + maintainer decision |
| runtime-performance | 01–08 | `ready-for-human` on a superseded line; reports archived to `2026-10-zero-device` | maintainer: close as superseded or re-anchor |

Zero open tickets remain in: `quality-round-20261103` (Q1–Q4 resolved, commit `46ce4e5`), `orchestella-uml-fabric` (13/13 resolved), `orchestella-hw-virt` (10/10), `p0-2-runtime-supervisor` (6/6), `quality-phase5-integration` (4/4 after this backfill). `release-readiness` holds no tickets (single handoff prompt, absorbed by the 0.6.0/0.6.2 release line). `round-20261104-device-window` is spec-only (T9 device lane landed on main as `1f4e0f4`).

### release/0.6.0-stabilization disposition (recommendation only, no branch moved)

The branch is fully merged: PR #57 (`b0b0b3a`) brought it into main, and its tip `c9717c3` is the merge-base — it is 0 commits ahead of main and ~31–32 behind. A fast-forward would be a no-op that only renames where the label sits, and keeping it invites accidental stabilization work against a two-weeks-stale base. Recommendation: delete the branch (local and `origin/release/0.6.0-stabilization`) once the current release line (0.6.2) needs no cherry-picks; if a future 0.6.x patch release is required, cut a fresh `release/0.6.x` from main at that time. Nothing is deleted by this reconciliation — maintainer call, tracked here so it does not get lost.

## Comments（2026-11-04 · 零设备收尾轮）

PVM-06（`orchestella-pvm-compiler`）状态与门禁证据对账。本节为新增记录，上方 2026-11-04 的 reconciliation snapshot 表格是带日期的历史快照，不回改。

- **分派器与生产接线已入库**：`app/src/main/java/io/github/nyzeep/orchestella/WorkerPoolDistccDispatcher.java`（提交 `95bcf97`）；配套测试 `app/src/test/java/io/github/nyzeep/orchestella/WorkerPoolDistccDispatcherTest.java`（现 10 条 `@Test`；工单报告 §5 的落档口径为 9 条，另 `AcceleratorLaneAssemblyTest` 新增 2 条）。
- **门禁证据（来源：2026-11-04 并行优化轮 Lane C，全 Gradle 实跑、零设备）**：`:app:testDebugUnitTest` 1128 tests / 0 skipped / 0 failed / 0 errors（138 类）；`:runtime:fastlane-distcc` 77 tests / 8 skipped / 0 failed（8 个 skip 均为既有 POSIX 前置用例，含 PVM-11 的 E2E 本体）。
- **剩余缺口**：产品 TCP 监听起点（guest distcc → `bridge.start()` → 本分派器）按 PVM-09 报告 §5.4 划归 **DV-PVM-02b** 的设备边界，**不属本票**。
- **结论**：票面 `Status: implemented（零设备；Gradle 门禁待主会话统一跑）` 中「Gradle 门禁待跑」这一半已由上述证据闭合；状态字串本身留给维护者裁定，本轮不擅自改。
