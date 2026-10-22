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

`--adb` accepts an alternate adb executable (used by the fake-adb tests).
`--dry-run` prints the sanitized command plan and does not call adb or create
an artifact directory.

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

If `tools/fastlane-schema/validate.py` is present, the runner invokes it as:

```text
python3 tools/fastlane-schema/validate.py --report <report-path>
python3 tools/fastlane-schema/validate.py --summary <summary-path>
```

A non-zero exit blocks the run with `SCHEMA_VALIDATION_FAILED`.

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
- The report's `apk_sha256` and `toolbox_manifest_sha256` are archived as
  reported. The runner does not cross-check them against `--apk` /
  `--toolbox-sha`; the preflight contract's input-binding gate remains a
  separate operator check.
