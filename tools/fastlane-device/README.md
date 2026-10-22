# Fast Lane DV-01B device runner

`run_dv01.py` is the device-independent host half of a Fast Lane DV-01B QA run.
It only drives `adb`; it does not build APKs, start a real device, use root, or
change FLN1/payload/SELinux/pvmfw behaviour.

Standard library only. Python 3.12 is the CI baseline; no extra packages are
required.

## Usage

All values below are placeholders. Use an approved external artifact root, not
the repository, a system temp directory (`/tmp`, `/var/tmp`, `/dev/shm`), or a
path inside the source tree. The runner rejects those roots before invoking
adb.

```bash
python3 tools/fastlane-device/run_dv01.py \
  --mode preflight \
  --alias qa-device \
  --apk <path-to-qa-apk> \
  --test-apk <path-to-test-apk> \
  --toolbox-sha <64-lowercase-hex> \
  --serial <serial> \
  --artifact-root <external-artifact-root>
```

`--serial` is optional. When omitted, `ANDROID_SERIAL` is used if set;
otherwise adb chooses its default device. The serial is only passed to adb in
process memory.

`--adb` accepts an alternate adb executable (used by the fake-adb tests). When
it is omitted, the runner resolves adb from `PATH`, then
`$ANDROID_HOME/platform-tools/adb`, then `$ANDROID_SDK_ROOT/platform-tools/adb`.
If none exists, the run stops with `ADB_UNAVAILABLE` before an artifact
directory is created. An explicit `--adb` that is not a file is an error; it
never silently falls back to another path. `--dry-run` prints the sanitized
command plan and does not call adb or create an artifact directory.

### Modes

| CLI `--mode` | Device `qaMode` | Device report |
| --- | --- | --- |
| `qualification` | `qualification` | `files/avf-qa/probe-<token>.json` |
| `preflight` | `fastlane-dv01-preflight` | `files/avf-qa/fastlane-dv01-preflight-<token>.json` |
| `full` | `fastlane-dv01` | `files/avf-qa/fastlane-dv01-<token>.json` |

The `preflight` device mode is the P1 harness contract. This runner does not
modify `app/src/androidTest/`; it targets the contract, not a private copy of
the probe.

`run-summary.json.outcome` is `QUALIFICATION_OBSERVED` or
`QUALIFICATION_BLOCKED` for qualification, the contract outcome for preflight,
and the full report outcome (or `FULL_BLOCKED` on a host failure) for full.
A full report outcome other than `PASS` always carries a fixed `reason_code`:
the report's code is kept when it has a valid one, otherwise the summary
records `FULL_BLOCKED`. Only `PASS` may leave `reason_code` null.

A full report's `auth_token` may only be `empty` or `not-run`. Any other value
is rejected as `REPORT_AUTH_TOKEN_REJECTED` before `report.json` is written;
the value is never archived, and it is not redacted in place.

## Artifact layout

Every real run writes exactly this shape:

```text
<artifact-root>/protected-fastlane/<UTC YYYYMMDD>/<mode>/<token>/
  report.json
  run-summary.json
  commands.txt
  SHA256SUMS
  README.txt
```

`report.json` is the canonical device report. It is omitted when the runner
blocks before a valid report is available (for example an install or permission
failure); `run-summary.json` then has `report_present: false` and
`report_path: null`. The local `README.txt` records that state.

`run-summary.json` uses `orchestella.fastlane.dv01.run.v1` and always includes
`serial_recorded: false`. The run token is never written into a summary or a
command file; it only appears in the run directory name.

`commands.txt` contains one sanitized command per executed line, in execution
order. Device serials become `<serial>`; APK paths become `<apk>` and
`<test-apk>`; the token becomes `<token>`; the device report path becomes
`<report-path>`; the alias becomes `<alias>`. Raw adb stdout/stderr and logcat
are never archived.

The report is pulled with `adb shell run-as <qa-package> cat <path>` rather
than root or a shell pipeline. The test APK's instrumentation component is
`<qa-package>.test/io.github.nyzeep.orchestella.AvfQaProbeInstrumentation`,
which is the default Android Gradle test application id. No root fallback is
available.

`SHA256SUMS` is generated last and covers `report.json` (when present),
`run-summary.json`, `commands.txt`, and files under `artifacts/`. It does not
cover itself or `README.txt`.

The runner never overwrites an existing run directory. An explicit `--token`
that is already in use fails with `ARTIFACT_CONFLICT`; a generated token is
retried with a new value.

## Permissions

The runner installs both APKs with `adb install -r -g`, then runs both:

```text
adb shell pm grant <qa-package> android.permission.MANAGE_VIRTUAL_MACHINE
adb shell pm grant <qa-package> android.permission.USE_CUSTOM_VIRTUAL_MACHINE
```

There is no root fallback and no `--force` path. A failed grant is recorded as
`PERMISSION_GRANT_FAILED` and the run is blocked before instrumentation.

## Report validation

The runner archives `report.json` first, then `run-summary.json`, then
validates the archived pair at its canonical paths. For preflight it invokes:

```text
python3 tools/fastlane-schema/validate.py \
  --report <run-dir>/report.json \
  --summary <run-dir>/run-summary.json --strict
```

`--strict` binds the summary to the archived bytes: canonical `report_path`,
`report_sha256`, alias, outcome, and `apk_sha256` must all agree. A second
validation copy is never used, because binding requires the canonical
`report.json` name.

Before any report is archived, the runner binds the report to its inputs:

- `report.apk_sha256` must equal the SHA-256 of `--apk`;
- `report.toolbox_manifest_sha256` must equal `--toolbox-sha`.

A mismatch is rejected before `report.json` is written. The summary then
records the mode's blocked outcome with `reason_code=SCHEMA_VALIDATION_FAILED`,
`schema_validation.status=FAIL`, and exits non-zero. `status=FAIL` also covers
this local input-binding rejection, before the validator could run.

A validator failure after archiving (including a report/summary binding
mismatch) rewrites the summary to the mode's blocked outcome with
`reason_code=SCHEMA_VALIDATION_FAILED` and `schema_validation.status=FAIL`,
keeps `report.json` for audit, and exits non-zero. The rewritten summary is
not re-bound; its outcome intentionally no longer matches the report.

`mode=full` has no v1 report schema in this repository, so a full report
cannot be bound with `--strict`. Full runs still enforce the APK/toolbox input
binding locally and schema-validate `run-summary.json`; only preflight reports
get canonical `--strict` report/summary binding. `mode=qualification` reports
are never sent to the preflight validator; only the qualification summary is
validated.

When the validator is absent, the runner performs local required-key and
fixed-value checks only. `run-summary.json.schema_validation` then records
`full_schema_validation: false` with the note
`tools/fastlane-schema/validate.py not present`; `README.txt` says the full
schema validation was not executed. This is never silently skipped.

## Exit codes

| Code | Meaning |
| --- | --- |
| `0` | Run archived; no device-level block |
| `1` | Host/runner failure (blocked summary when a run directory exists) |
| `2` | Argument or input path rejected before a run directory was created |
| `3` | Preflight report archived, but the device outcome is not `PREFLIGHT_TERMINAL_OK` |

## Tests

The tests use an executable fake adb and never contact a device:

```bash
python3 -m pytest tools/fastlane-device -q
# or, without pytest:
python3 -m unittest discover -s tools/fastlane-device -p 'test_*.py' -v
```

Coverage includes all three mode command sequences, alias/token/serial handling,
artifact paths and checksums, blocked summaries for install and permission
failures, report pull/parse/validation failures, redaction, dry-run, and
non-overwrite of existing runs.

## Known boundary

- This session does not execute a real device or real adb.
- `mode=full` is implemented against the fake-adb contract only; it is not
  evidence that a 20-cycle run passed.
- The runner assumes the test APK application id is `<qa-package>.test` and
  the instrumentation class is
  `io.github.nyzeep.orchestella.AvfQaProbeInstrumentation`. The QA package must
  contain `.qa.`; `preflight` and `full` additionally require `.qa.dv01`.
  Override the app package with `--qa-package` if needed.
- The runner does not inspect the APK manifest; it cannot prove that the APK
  applicationId matches `--qa-package` before install.
- `mode=full` reports have no v1 JSON schema here, so the runner cannot bind a
  full report to its summary with `--strict`. It enforces the APK/toolbox
  input binding locally and validates the summary; preflight is the mode with
  canonical `--strict` report/summary binding.
- The runner does not scan a full report for arbitrary secrets beyond the
  `auth_token` allowlist and the token/serial substring checks.
