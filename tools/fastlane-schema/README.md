# Fast Lane DV-01B schema tools

Host-only validation for the sanitized DV-01B artifacts. No adb, no device,
no network, no third-party runtime dependency.

## Files

| File | Purpose |
| --- | --- |
| `validate.py` | Stdlib-only validator for the preflight report and run summary |
| `run-summary.schema.json` | `orchestella.fastlane.dv01.run.v1` host summary schema |
| `fixtures/preflight/` | Legal and illegal preflight reports |
| `fixtures/run-summary/` | Legal and illegal run summaries |
| `tests/test_validate.py` | Schema, negative, binding and sanitization tests |
| `requirements-test.txt` | Pinned pytest for the test lane |

The preflight report schema is the existing
`tools/fastlane-build/preflight-report.schema.json`
(`orchestella.fastlane.dv01.preflight.v1`); this directory does not duplicate
or modify it.

## Usage

```bash
python3 tools/fastlane-schema/validate.py --report report.json
python3 tools/fastlane-schema/validate.py --summary run-summary.json
python3 tools/fastlane-schema/validate.py --report report.json --summary run-summary.json --strict
```

- `--report <path>` validates a device preflight report.
- `--summary <path>` validates a host run summary.
- `--schema-dir <dir>` reads the schema file(s) for the requested inputs from
  that directory. The default locations are
  `tools/fastlane-build/preflight-report.schema.json` and this directory's
  `run-summary.schema.json`.
- `--strict` enforces ISO-8601 date-times and, when both inputs are present,
  the report/summary binding: canonical `report.json`, matching SHA-256,
  alias, outcome and APK digest. With only `--summary --strict`, the validator
  loads `report_path` relative to the summary directory.

Exit codes: `0` valid, `1` validation failure, `2` usage/input/schema problem.

Diagnostics are stable reason codes and JSON Pointer paths. Field values are
never printed, and an unexpected property name is rendered as `<key>` so a
diagnostic cannot echo a serial, model, token or path.

## Run summary invariants

`run-summary.json` must be `orchestella.fastlane.dv01.run.v1`, `mode` must be
`preflight` (other modes need a schema revision), `serial_recorded` is always
`false`, `report_path` is a relative path with no `.`/`..` segment, and there
is no `token` property. A token value in `alias`, `report_path` or another
checked field is rejected with `E_TOKEN_PRESENT`.

## Tests

```bash
python3 -m pip install -r tools/fastlane-schema/requirements-test.txt
python3 -m pytest tools/fastlane-schema -q
```

The suite uses only local fixtures. It does not contact a device or a network
service.
