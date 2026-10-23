# Microdroid exec-gate policy fixtures

These are **minimal excerpts**, not a full policy tree. Each case keeps only
the type declarations and allow rules needed to decide whether
`microdroid_app` holds `execute_no_trans` on the main payload file type
(`system_file`). The fixtures are checked in so CI never fetches AOSP.

Source repository: `platform/system/sepolicy` (Apache License 2.0).

| Case | Branch | Commit | Fetched |
| --- | --- | --- | --- |
| `a16` | `android16-release` | `d4a7f392598cee96d9479a8ac0f84259c19b043a` | 2026-10-23 |
| `a17` | `android17-release` | `e066568e98d86db31a9346d30977f3632fa7073c` | 2026-10-23 |
| `aosp-main` | `main` | `4571ddd9440721fec583c906a337de949a77749e` | 2026-10-23 |

Canonical URLs (replace `<branch>` and `<path>`):

```text
https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/<branch>/<path>
```

Files excerpted:

- `microdroid/system/private/microdroid_app.te`: the `type` declaration
  showing `domain, coredomain`; A17 additionally adds
  `allow microdroid_app tenant_apk_file:file x_file_perms`.
- `microdroid/system/private/domain.te`: the `allow coredomain
  system_file:file { execute read open getattr map }` rule.
- `microdroid/system/public/file.te`: the `system_file` and
  `tenant_apk_file` type declarations.
- `microdroid/system/public/global_macros`: the `x_file_perms` /
  `rx_file_perms` definitions.

Result: all three cases are `GATE_CLOSED` for `system_file`. A16 and main
have no `execute_no_trans` anywhere for `microdroid_app`; A17 grants it only
to `tenant_apk_file`, which is not the main payload APK mount type.

A closed result is a property of these excerpts, not a permanent product
conclusion. Re-check with the runbook when a new ROM or AOSP release appears.
