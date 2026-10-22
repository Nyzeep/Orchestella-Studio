#!/usr/bin/env python3
"""Validate DV-01B preflight reports and host run summaries.

This tool is deliberately stdlib-only: the zero-device lane must not fetch a
schema library to validate a sanitized artifact. It implements the JSON Schema
2020-12 subset used by ``preflight-report.schema.json`` and
``run-summary.schema.json`` and adds the contract's cross-field checks.

Diagnostics are stable reason codes. Values from the input are never printed:
a report or summary is already supposed to be sanitized, and a validator error
must not become the place where a serial, model, token or path leaks.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Iterable

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent.parent

PREFLIGHT_SCHEMA_FILE = "preflight-report.schema.json"
RUN_SCHEMA_FILE = "run-summary.schema.json"
PREFLIGHT_SCHEMA_ID = "orchestella.fastlane.dv01.preflight.v1"
PREFLIGHT_SCHEMA_URN = "urn:orchestella:fastlane:dv01:preflight:v1"
RUN_SCHEMA_ID = "orchestella.fastlane.dv01.run.v1"
RUN_SCHEMA_URN = "urn:orchestella:fastlane:dv01:run:v1"

TOKEN_RE = re.compile(r"^[0-9a-f]{16,64}$")
TOKEN_SEARCH_RE = re.compile(r"[0-9a-f]{16,64}")
ABSOLUTE_PATH_RE = re.compile(r"^(?:/|\\|[A-Za-z]:)")
REASON_CODE_RE = re.compile(r"^[A-Z][A-Z0-9_]{1,63}$")

EXIT_OK = 0
EXIT_INVALID = 1
EXIT_CONFIG = 2

# Fixed diagnostic codes. Keep these stable; tests and operators key off them.
E_USAGE = "E_USAGE"
E_INTERNAL = "E_INTERNAL"
E_INPUT_NOT_FOUND = "E_INPUT_NOT_FOUND"
E_INPUT_NOT_READABLE = "E_INPUT_NOT_READABLE"
E_INPUT_NOT_UTF8 = "E_INPUT_NOT_UTF8"
E_INPUT_NOT_JSON = "E_INPUT_NOT_JSON"
E_INPUT_NOT_OBJECT = "E_INPUT_NOT_OBJECT"
E_SCHEMA_DIR_INVALID = "E_SCHEMA_DIR_INVALID"
E_SCHEMA_FILE_MISSING = "E_SCHEMA_FILE_MISSING"
E_SCHEMA_FILE_INVALID = "E_SCHEMA_FILE_INVALID"
E_SCHEMA_ID_MISMATCH = "E_SCHEMA_ID_MISMATCH"
E_SCHEMA_SUBSCHEMA = "E_SCHEMA_SUBSCHEMA"
E_SCHEMA_REF = "E_SCHEMA_REF"
E_SCHEMA_TYPE = "E_SCHEMA_TYPE"
E_SCHEMA_CONST = "E_SCHEMA_CONST"
E_SCHEMA_ENUM = "E_SCHEMA_ENUM"
E_SCHEMA_REQUIRED = "E_SCHEMA_REQUIRED"
E_SCHEMA_ADDITIONAL_PROPERTY = "E_SCHEMA_ADDITIONAL_PROPERTY"
E_SCHEMA_PROPERTY_NAME = "E_SCHEMA_PROPERTY_NAME"
E_SCHEMA_PATTERN = "E_SCHEMA_PATTERN"
E_SCHEMA_MIN_ITEMS = "E_SCHEMA_MIN_ITEMS"
E_SCHEMA_UNIQUE_ITEMS = "E_SCHEMA_UNIQUE_ITEMS"
E_SCHEMA_CONTAINS = "E_SCHEMA_CONTAINS"
E_SCHEMA_MINIMUM = "E_SCHEMA_MINIMUM"
E_SCHEMA_MAXIMUM = "E_SCHEMA_MAXIMUM"
E_SCHEMA_MAX_LENGTH = "E_SCHEMA_MAX_LENGTH"
E_SCHEMA_FORMAT = "E_SCHEMA_FORMAT"
E_SERIAL_RECORDED = "E_SERIAL_RECORDED"
E_REPORT_PATH_NOT_RELATIVE = "E_REPORT_PATH_NOT_RELATIVE"
E_REPORT_PATH_INVALID = "E_REPORT_PATH_INVALID"
E_REPORT_PATH_NOT_CANONICAL = "E_REPORT_PATH_NOT_CANONICAL"
E_TOKEN_PRESENT = "E_TOKEN_PRESENT"
E_TIME_ORDER = "E_TIME_ORDER"
E_REASON_CODE_OUTCOME = "E_REASON_CODE_OUTCOME"
E_PREFLIGHT_OK_GATE = "E_PREFLIGHT_OK_GATE"
E_SUMMARY_REPORT_MISSING = "E_SUMMARY_REPORT_MISSING"
E_SUMMARY_REPORT_HASH_MISMATCH = "E_SUMMARY_REPORT_HASH_MISMATCH"
E_SUMMARY_REPORT_PATH_MISMATCH = "E_SUMMARY_REPORT_PATH_MISMATCH"
E_SUMMARY_ALIAS_MISMATCH = "E_SUMMARY_ALIAS_MISMATCH"
E_SUMMARY_OUTCOME_MISMATCH = "E_SUMMARY_OUTCOME_MISMATCH"
E_SUMMARY_APK_MISMATCH = "E_SUMMARY_APK_MISMATCH"


@dataclass(frozen=True)
class Diagnostic:
    target: str
    code: str
    path: str = ""

    def render(self) -> str:
        suffix = f" path={self.path}" if self.path else ""
        return f"FAIL {self.target} reason={self.code}{suffix}"


class ConfigError(Exception):
    def __init__(self, code: str, target: str, context: str = "") -> None:
        super().__init__(code)
        self.code = code
        self.target = target
        self.context = context

    def render(self) -> str:
        suffix = f" file={self.context}" if self.context else ""
        return f"FAIL {self.target} reason={self.code}{suffix}"


class SafeArgumentParser(argparse.ArgumentParser):
    """Never echo an argument value in a usage error."""

    def error(self, message: str) -> None:
        print(f"FAIL usage reason={E_USAGE}", file=sys.stderr)
        raise SystemExit(EXIT_CONFIG)


# A path segment is (token, schema_declared). Unknown object keys are rendered
# as <key> so an unexpected property name cannot leak through a diagnostic.
Location = tuple[tuple[Any, bool], ...]


def render_pointer(location: Location) -> str:
    if not location:
        return "/"
    parts: list[str] = []
    for token, declared in location:
        if isinstance(token, int):
            parts.append(str(token))
        elif declared:
            parts.append(str(token).replace("~", "~0").replace("/", "~1"))
        else:
            parts.append("<key>")
    return "/" + "/".join(parts)


def json_equal(left: Any, right: Any) -> bool:
    """JSON equality, keeping booleans distinct from 0/1."""
    if isinstance(left, bool) or isinstance(right, bool):
        return isinstance(left, bool) and isinstance(right, bool) and left is right
    if isinstance(left, (int, float)) and isinstance(right, (int, float)):
        return left == right
    if type(left) is not type(right):
        return False
    if isinstance(left, dict):
        return left.keys() == right.keys() and all(
            json_equal(left[key], right[key]) for key in left
        )
    if isinstance(left, list):
        return len(left) == len(right) and all(
            json_equal(a, b) for a, b in zip(left, right)
        )
    return left == right


def is_type(value: Any, expected: str) -> bool:
    if expected == "object":
        return isinstance(value, dict)
    if expected == "array":
        return isinstance(value, list)
    if expected == "string":
        return isinstance(value, str)
    if expected == "integer":
        return (
            isinstance(value, int) and not isinstance(value, bool)
        ) or (isinstance(value, float) and value.is_integer())
    if expected == "number":
        return isinstance(value, (int, float)) and not isinstance(value, bool)
    if expected == "boolean":
        return isinstance(value, bool)
    if expected == "null":
        return value is None
    return False


def parse_utc(value: Any) -> datetime | None:
    if not isinstance(value, str):
        return None
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00" if value.endswith("Z") else value)
    except ValueError:
        return None
    return parsed if parsed.tzinfo is not None else None


class SchemaValidator:
    """The JSON Schema subset needed by the two DV-01B schemas."""

    def __init__(self, root: dict, strict: bool) -> None:
        self.root = root
        self.strict = strict
        self.errors: list[tuple[str, str]] = []

    def fail(self, code: str, location: Location) -> None:
        self.errors.append((code, render_pointer(location)))

    def probe(self, instance: Any, schema: Any) -> list[tuple[str, str]]:
        probe = SchemaValidator(self.root, self.strict)
        probe.validate(instance, schema, ())
        return probe.errors

    def resolve_ref(self, ref: Any) -> Any:
        if not isinstance(ref, str) or not ref.startswith("#/"):
            return None
        node: Any = self.root
        for raw in ref[2:].split("/"):
            token = raw.replace("~1", "/").replace("~0", "~")
            if isinstance(node, dict) and token in node:
                node = node[token]
            elif isinstance(node, list) and token.isdigit() and int(token) < len(node):
                node = node[int(token)]
            else:
                return None
        return node

    def validate(self, instance: Any, schema: Any, location: Location) -> None:
        if schema is True:
            return
        if schema is False:
            self.fail(E_SCHEMA_SUBSCHEMA, location)
            return
        if not isinstance(schema, dict):
            self.fail(E_SCHEMA_SUBSCHEMA, location)
            return

        if "$ref" in schema:
            target = self.resolve_ref(schema["$ref"])
            if target is None:
                self.fail(E_SCHEMA_REF, location)
            else:
                self.validate(instance, target, location)

        if "type" in schema:
            expected = schema["type"]
            allowed = [expected] if isinstance(expected, str) else expected
            if not isinstance(allowed, list) or not any(
                is_type(instance, str(name)) for name in allowed
            ):
                self.fail(E_SCHEMA_TYPE, location)
                return

        if "const" in schema and not json_equal(instance, schema["const"]):
            self.fail(E_SCHEMA_CONST, location)
        if "enum" in schema and not any(
            json_equal(instance, candidate) for candidate in schema["enum"]
        ):
            self.fail(E_SCHEMA_ENUM, location)

        if isinstance(instance, dict):
            self._validate_object(instance, schema, location)
        elif isinstance(instance, list):
            self._validate_array(instance, schema, location)
        elif isinstance(instance, str):
            self._validate_string(instance, schema, location)
        elif isinstance(instance, (int, float)) and not isinstance(instance, bool):
            self._validate_number(instance, schema, location)

        for sub in schema.get("allOf", []) or []:
            self.validate(instance, sub, location)

        if "if" in schema:
            condition = self.probe(instance, schema["if"])
            if not condition and "then" in schema:
                self.validate(instance, schema["then"], location)
            elif condition and "else" in schema:
                self.validate(instance, schema["else"], location)

    def _validate_object(
        self, instance: dict, schema: dict, location: Location
    ) -> None:
        for name in schema.get("required", []) or []:
            if name not in instance:
                self.fail(E_SCHEMA_REQUIRED, location + ((name, True),))

        property_names = schema.get("propertyNames")
        if isinstance(property_names, dict):
            for key in instance:
                if self.probe(key, property_names):
                    self.fail(E_SCHEMA_PROPERTY_NAME, location)

        properties = schema.get("properties") or {}
        for key, subschema in properties.items():
            if key in instance:
                self.validate(instance[key], subschema, location + ((key, True),))

        if "additionalProperties" in schema:
            additional = schema["additionalProperties"]
            for key in instance:
                if key in properties:
                    continue
                if additional is False:
                    # Do not name the unexpected key: it may be sensitive.
                    self.fail(E_SCHEMA_ADDITIONAL_PROPERTY, location)
                elif additional is not True:
                    self.validate(
                        instance[key], additional, location + ((key, False),)
                    )

    def _validate_array(self, instance: list, schema: dict, location: Location) -> None:
        minimum = schema.get("minItems")
        if isinstance(minimum, int) and len(instance) < minimum:
            self.fail(E_SCHEMA_MIN_ITEMS, location)

        if schema.get("uniqueItems") is True:
            seen: set[str] = set()
            for item in instance:
                try:
                    marker = json.dumps(item, sort_keys=True, ensure_ascii=False)
                except (TypeError, ValueError):
                    marker = repr(type(item))
                if marker in seen:
                    self.fail(E_SCHEMA_UNIQUE_ITEMS, location)
                    break
                seen.add(marker)

        items = schema.get("items")
        if isinstance(items, dict):
            for index, item in enumerate(instance):
                self.validate(item, items, location + ((index, True),))

        contains = schema.get("contains")
        if isinstance(contains, dict) and not any(
            not self.probe(item, contains) for item in instance
        ):
            self.fail(E_SCHEMA_CONTAINS, location)

    def _validate_string(self, instance: str, schema: dict, location: Location) -> None:
        maximum = schema.get("maxLength")
        if isinstance(maximum, int) and len(instance) > maximum:
            self.fail(E_SCHEMA_MAX_LENGTH, location)

        pattern = schema.get("pattern")
        if isinstance(pattern, str):
            try:
                match = re.search(pattern, instance)
            except re.error:
                self.fail(E_SCHEMA_SUBSCHEMA, location)
            else:
                matched = match is not None
                # Python's $ also matches before a trailing newline; JSON
                # Schema's ECMA pattern does not. Reject that difference.
                if (
                    matched
                    and pattern.startswith("^")
                    and pattern.endswith("$")
                ):
                    matched = match.group(0) == instance
                if not matched:
                    self.fail(E_SCHEMA_PATTERN, location)

        if self.strict and schema.get("format") == "date-time":
            if parse_utc(instance) is None:
                self.fail(E_SCHEMA_FORMAT, location)

    def _validate_number(self, instance: float, schema: dict, location: Location) -> None:
        minimum = schema.get("minimum")
        if isinstance(minimum, (int, float)) and instance < minimum:
            self.fail(E_SCHEMA_MINIMUM, location)
        maximum = schema.get("maximum")
        if isinstance(maximum, (int, float)) and instance > maximum:
            self.fail(E_SCHEMA_MAXIMUM, location)


def load_json(path: Path, target: str) -> tuple[dict, bytes]:
    try:
        raw = path.read_bytes()
    except FileNotFoundError as failure:
        raise ConfigError(E_INPUT_NOT_FOUND, target) from failure
    except OSError as failure:
        raise ConfigError(E_INPUT_NOT_READABLE, target) from failure
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as failure:
        raise ConfigError(E_INPUT_NOT_UTF8, target) from failure
    try:
        document = json.loads(text)
    except json.JSONDecodeError as failure:
        raise ConfigError(E_INPUT_NOT_JSON, target) from failure
    if not isinstance(document, dict):
        raise ConfigError(E_INPUT_NOT_OBJECT, target)
    return document, raw


def load_schema(
    path: Path, expected_urn: str, expected_id: str
) -> dict:
    if not path.is_file():
        raise ConfigError(E_SCHEMA_FILE_MISSING, "schema", path.name)
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as failure:
        raise ConfigError(E_SCHEMA_FILE_INVALID, "schema", path.name) from failure
    if not isinstance(document, dict):
        raise ConfigError(E_SCHEMA_FILE_INVALID, "schema", path.name)
    if document.get("$id") != expected_urn:
        raise ConfigError(E_SCHEMA_ID_MISMATCH, "schema", path.name)
    embedded = (document.get("properties") or {}).get("schema") or {}
    if embedded.get("const") != expected_id:
        raise ConfigError(E_SCHEMA_ID_MISMATCH, "schema", path.name)
    return document


def schema_paths(schema_dir: str | None) -> tuple[Path, Path]:
    if schema_dir is None:
        return (
            REPO_ROOT / "tools" / "fastlane-build" / PREFLIGHT_SCHEMA_FILE,
            SCRIPT_DIR / RUN_SCHEMA_FILE,
        )
    directory = Path(schema_dir)
    if not directory.is_dir():
        raise ConfigError(E_SCHEMA_DIR_INVALID, "schema")
    return directory / PREFLIGHT_SCHEMA_FILE, directory / RUN_SCHEMA_FILE


def _dict(value: Any) -> dict:
    return value if isinstance(value, dict) else {}


def _token_values(document: dict) -> Iterable[tuple[str, Any]]:
    """String fields that must never carry an opaque run token."""
    yield "/device_alias", document.get("device_alias")
    for index, value in enumerate(document.get("not_tested") or []):
        yield f"/not_tested/{index}", value
    for name, value in _dict(document.get("stage_results")).items():
        if isinstance(value, dict):
            yield f"/stage_results/{name}/detail", value.get("detail")
    for name, value in _dict(document.get("failure_codes")).items():
        if isinstance(value, dict):
            yield f"/failure_codes/{name}/layer", value.get("layer")
    yield "/vsock_control", document.get("vsock_control")
    yield "/vsock_terminal", document.get("vsock_terminal")


def check_preflight_semantics(document: dict) -> list[Diagnostic]:
    diagnostics: list[Diagnostic] = []

    started = parse_utc(document.get("started_at_utc"))
    finished = parse_utc(document.get("finished_at_utc"))
    if started and finished and finished < started:
        diagnostics.append(Diagnostic("preflight", E_TIME_ORDER, "/finished_at_utc"))

    if document.get("outcome") == "PREFLIGHT_TERMINAL_OK":
        terminal = _dict(document.get("terminal"))
        stages = _dict(document.get("stage_results"))
        delete_status = _dict(stages.get("delete")).get("status")
        if (
            terminal.get("status") != "pass"
            or terminal.get("reason_code") != "PREFLIGHT_TERMINAL_OK"
            or document.get("stop") != "CONFIRMED"
            or delete_status != "pass"
        ):
            diagnostics.append(
                Diagnostic("preflight", E_PREFLIGHT_OK_GATE, "/outcome")
            )

    for path, value in _token_values(document):
        if isinstance(value, str) and TOKEN_RE.match(value):
            diagnostics.append(Diagnostic("preflight", E_TOKEN_PRESENT, path))

    return diagnostics


def check_summary_semantics(
    document: dict, strict: bool
) -> list[Diagnostic]:
    diagnostics: list[Diagnostic] = []

    if document.get("serial_recorded") is not False:
        diagnostics.append(
            Diagnostic("summary", E_SERIAL_RECORDED, "/serial_recorded")
        )

    report_path = document.get("report_path")
    if isinstance(report_path, str):
        if ABSOLUTE_PATH_RE.match(report_path):
            diagnostics.append(
                Diagnostic("summary", E_REPORT_PATH_NOT_RELATIVE, "/report_path")
            )
        elif (
            "\\" in report_path
            or ":" in report_path
            or any(segment in ("", ".", "..") for segment in report_path.split("/"))
        ):
            diagnostics.append(
                Diagnostic("summary", E_REPORT_PATH_INVALID, "/report_path")
            )
        if TOKEN_RE.match(report_path) or TOKEN_SEARCH_RE.search(
            Path(report_path).stem
        ):
            diagnostics.append(Diagnostic("summary", E_TOKEN_PRESENT, "/report_path"))
        if strict and report_path != "report.json":
            diagnostics.append(
                Diagnostic("summary", E_REPORT_PATH_NOT_CANONICAL, "/report_path")
            )

    alias = document.get("alias")
    if isinstance(alias, str) and TOKEN_RE.match(alias):
        diagnostics.append(Diagnostic("summary", E_TOKEN_PRESENT, "/alias"))

    outcome = document.get("outcome")
    reason = document.get("reason_code")
    if outcome == "PREFLIGHT_TERMINAL_OK" and reason is not None:
        diagnostics.append(
            Diagnostic("summary", E_REASON_CODE_OUTCOME, "/reason_code")
        )
    if outcome in (
        "PREFLIGHT_TERMINAL_BLOCKED",
        "PREFLIGHT_BLOCKED_EXTERNAL",
    ) and not (isinstance(reason, str) and REASON_CODE_RE.match(reason)):
        diagnostics.append(
            Diagnostic("summary", E_REASON_CODE_OUTCOME, "/reason_code")
        )

    started = parse_utc(document.get("started_at_utc"))
    finished = parse_utc(document.get("finished_at_utc"))
    if started and finished and finished < started:
        diagnostics.append(Diagnostic("summary", E_TIME_ORDER, "/finished_at_utc"))

    return diagnostics


def check_binding(
    report: dict,
    report_bytes: bytes,
    report_name: str,
    summary: dict,
) -> list[Diagnostic]:
    diagnostics: list[Diagnostic] = []

    if Path(str(summary.get("report_path"))).name != report_name:
        diagnostics.append(
            Diagnostic("binding", E_SUMMARY_REPORT_PATH_MISMATCH, "/report_path")
        )

    digest = hashlib.sha256(report_bytes).hexdigest()
    if summary.get("report_sha256") != digest:
        diagnostics.append(
            Diagnostic("binding", E_SUMMARY_REPORT_HASH_MISMATCH, "/report_sha256")
        )
    if summary.get("alias") != report.get("device_alias"):
        diagnostics.append(
            Diagnostic("binding", E_SUMMARY_ALIAS_MISMATCH, "/alias")
        )
    if summary.get("outcome") != report.get("outcome"):
        diagnostics.append(
            Diagnostic("binding", E_SUMMARY_OUTCOME_MISMATCH, "/outcome")
        )
    if summary.get("apk_sha256") != report.get("apk_sha256"):
        diagnostics.append(
            Diagnostic("binding", E_SUMMARY_APK_MISMATCH, "/apk_sha256")
        )

    return diagnostics


def dedupe(diagnostics: Iterable[Diagnostic]) -> list[Diagnostic]:
    seen: set[tuple[str, str, str]] = set()
    result: list[Diagnostic] = []
    for diagnostic in diagnostics:
        key = (diagnostic.target, diagnostic.code, diagnostic.path)
        if key not in seen:
            seen.add(key)
            result.append(diagnostic)
    return result


def validate_document(
    document: dict, schema: dict, target: str, strict: bool
) -> list[Diagnostic]:
    validator = SchemaValidator(schema, strict)
    validator.validate(document, schema, ())
    diagnostics = [Diagnostic(target, code, path) for code, path in validator.errors]
    # Semantic checks are guarded and still useful when the schema failed:
    # they name the contract invariant (serial, path, token) rather than only
    # the generic JSON Schema keyword.
    if target == "preflight":
        diagnostics.extend(check_preflight_semantics(document))
    else:
        diagnostics.extend(check_summary_semantics(document, strict))
    return dedupe(diagnostics)


def run(argv: list[str] | None = None) -> int:
    parser = SafeArgumentParser(
        description=(
            "Validate a sanitized DV-01B preflight report and/or run summary. "
            "Diagnostics use fixed reason codes and never echo field values."
        )
    )
    parser.add_argument("--report", help="path to a preflight report JSON file")
    parser.add_argument("--summary", help="path to a run-summary JSON file")
    parser.add_argument(
        "--schema-dir",
        help=(
            "directory holding preflight-report.schema.json and/or "
            "run-summary.schema.json; each requested target loads its own "
            "schema (default: the repository locations)"
        ),
    )
    parser.add_argument(
        "--strict",
        action="store_true",
        help=(
            "enforce date-time formats, canonical report.json, and, when both "
            "inputs are supplied, report/summary binding"
        ),
    )
    args = parser.parse_args(argv)

    if not args.report and not args.summary:
        print(f"FAIL usage reason={E_USAGE}", file=sys.stderr)
        return EXIT_CONFIG

    try:
        preflight_schema_path, run_schema_path = schema_paths(args.schema_dir)
        need_preflight = args.report is not None or (
            args.strict and args.summary is not None
        )
        preflight_schema = (
            load_schema(
                preflight_schema_path, PREFLIGHT_SCHEMA_URN, PREFLIGHT_SCHEMA_ID
            )
            if need_preflight
            else None
        )
        run_schema = (
            load_schema(run_schema_path, RUN_SCHEMA_URN, RUN_SCHEMA_ID)
            if args.summary is not None
            else None
        )

        report: dict | None = None
        report_bytes: bytes | None = None
        report_name = ""
        if args.report:
            report, report_bytes = load_json(Path(args.report), "preflight")
            report_name = Path(args.report).name
        summary: dict | None = None
        summary_path: Path | None = None
        if args.summary:
            summary_path = Path(args.summary)
            summary, _ = load_json(summary_path, "summary")
    except ConfigError as failure:
        print(failure.render(), file=sys.stderr)
        return EXIT_CONFIG

    diagnostics: list[Diagnostic] = []
    report_valid = True
    summary_valid = True

    if report is not None:
        assert preflight_schema is not None
        found = validate_document(report, preflight_schema, "preflight", args.strict)
        diagnostics.extend(found)
        report_valid = not found
    if summary is not None:
        assert run_schema is not None
        found = validate_document(summary, run_schema, "summary", args.strict)
        diagnostics.extend(found)
        summary_valid = not found

    if args.strict and summary is not None and summary_valid:
        if report is None:
            assert summary_path is not None
            declared = summary.get("report_path")
            if isinstance(declared, str) and not ABSOLUTE_PATH_RE.match(declared):
                candidate = summary_path.parent / declared
                try:
                    report, report_bytes = load_json(candidate, "preflight")
                    report_name = candidate.name
                except ConfigError:
                    diagnostics.append(
                        Diagnostic("binding", E_SUMMARY_REPORT_MISSING, "/report_path")
                    )
            else:
                diagnostics.append(
                    Diagnostic("binding", E_SUMMARY_REPORT_MISSING, "/report_path")
                )
            if report is not None:
                assert preflight_schema is not None
                found = validate_document(
                    report, preflight_schema, "preflight", args.strict
                )
                diagnostics.extend(found)
                report_valid = not found
        elif report is not None and report_valid:
            if summary.get("report_path") != "report.json":
                diagnostics.append(
                    Diagnostic(
                        "summary", E_REPORT_PATH_NOT_CANONICAL, "/report_path"
                    )
                )
        if (
            report is not None
            and report_bytes is not None
            and report_valid
            and summary_valid
        ):
            diagnostics.extend(
                check_binding(report, report_bytes, report_name, summary)
            )

    diagnostics = dedupe(diagnostics)
    if diagnostics:
        for diagnostic in diagnostics:
            print(diagnostic.render(), file=sys.stderr)
        return EXIT_INVALID

    if report is not None:
        print("OK preflight")
    if summary is not None:
        print("OK summary")
    if args.strict and report is not None and summary is not None:
        print("OK binding")
    return EXIT_OK


def main() -> int:
    try:
        return run()
    except SystemExit:
        raise
    except Exception:
        # Never let a traceback echo an input path or value.
        print(f"FAIL internal reason={E_INTERNAL}", file=sys.stderr)
        return EXIT_CONFIG


if __name__ == "__main__":
    sys.exit(main())
