#!/usr/bin/env python3
"""Sensitive developer data guard.

Implements the gate required by
docs/agent-handoff/DEFENSIVE_CONSTRAINTS/SENSITIVE_DEVELOPER_DATA_POLICY.md
section 3.2. Standard library only; Python 3.12.

Modes
-----
--self-test
    Builds synthetic fixtures in a temporary directory, asserts that every
    detector fires and that the documented negative controls do not, and
    prints ``SENSITIVE_DATA_OK`` only when all checks pass.

--range A..B
    Scans only the added lines of the two-dot tree diff plus every commit
    message reachable from B but not from A. Tracked path categories are
    evaluated on the tree at B.

--range A...B
    Same, but the diff and commit-message range start at the merge base
    (pull-request semantics).

--tag TAG
    Scans the tracked tree at TAG plus the annotated tag message.

no arguments
    Scans the tracked tree at HEAD plus the HEAD commit message. This is the
    release gate form and works in a depth-1 checkout.

An all-zero or unresolvable range base (for example the first push of a new
branch) degrades to the no-argument form: tracked tree plus head commit
message. The tool never crashes on that input.

Output vocabulary
-----------------
Success prints exactly one line::

    SENSITIVE_DATA_OK

Findings print ``SENSITIVE_DATA_FOUND`` followed by one line per non-zero
category::

    category=<name> count=<n>

Matched lines, values, paths and commit message bodies are never printed.
Unexpected failures fail closed as ``category=scanner_error count=1``.

Counting
--------
Text categories count one per matching line per category (a line that
matches several rules in the same category still counts once). Path
categories count one per tracked path. A line can increment more than one
category.

Calibration decisions
---------------------
* 40-character and 64-character hex tokens are allowed: commit ids and
  SHA-256 provenance hashes are explicitly permitted by policy. Other bare
  hex runs of 16-63 characters are findings unless the same line carries an
  explicit hash context (sha1/sha256/md5/checksum/digest/hash) or an ADR
  reference next to the token.
* Stable device aliases (``device-a`` style and short numeric aliases) and
  the documented ``redacted-serial-hash`` placeholder are not findings.
  Standard ``emulator-<port>`` serials are generic emulator identifiers and
  are not findings either.
* ``/tmp``, ``/var/tmp``, ``/dev/shm`` and SDK install prefixes are generic
  documented locations, not host-user paths; only Windows drive paths and
  home-directory paths are findings.
* A tracked ``gradle.properties`` is a finding only when it carries signing
  credential keys (store/key password, store file, key alias, keystore).
  The repository intentionally tracks a benign build-config
  ``gradle.properties``; ``.env``, ``*.env`` and ``local.properties`` are
  forbidden regardless of content.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import tempfile
from pathlib import Path
from typing import Callable, Iterable

OK_LINE = "SENSITIVE_DATA_OK"
FOUND_LINE = "SENSITIVE_DATA_FOUND"

CATEGORY_ORDER = (
    "device_identifier",
    "absolute_path",
    "personal_email",
    "private_key",
    "token_shape",
    "tracked_media",
    "key_material",
    "env_file",
    "self_test",
    "scanner_error",
)

MEDIA_SUFFIXES = (
    ".png",
    ".jpg",
    ".jpeg",
    ".webp",
    ".gif",
    ".bmp",
    ".mp4",
    ".mov",
    ".webm",
    ".mkv",
    ".avi",
)

KEY_SUFFIXES = (".jks", ".keystore", ".p12", ".pfx", ".pem", ".key")

# Device identifier detectors. The label casing follows the policy command
# literally: Serial, IMEI and MEID are matched case-sensitively so ordinary
# code identifiers such as serialVersionUID are not treated as labels.
_SERIAL_LABEL_RE = re.compile(
    r"(?:(?<![A-Za-z0-9_])(?:Serial(?:[ \t_-]+(?:Number|No\.?))?|IMEI|MEID)"
    r"(?![A-Za-z0-9_])|序列号|设备[ _-]?(?:ID|编号))"
    r"[ \t]*[:：=]?[ \t]*[\"'`]?([A-Za-z0-9][A-Za-z0-9_.:-]{7,})"
)

_ADB_SERIAL_RE = re.compile(
    r"(?<![A-Za-z0-9_])adb[ \t]+-s[ \t]+[\"'`]?"
    r"([A-Za-z0-9][A-Za-z0-9_.:-]{5,})"
)

_MAC_RE = re.compile(
    r"(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}(?![0-9A-Fa-f:])"
)

_FP_PART = r"[A-Za-z0-9._-]+"
_FINGERPRINT_RE = re.compile(
    rf"(?<![A-Za-z0-9._-]){_FP_PART}/{_FP_PART}/{_FP_PART}:"
    rf"{_FP_PART}/{_FP_PART}/{_FP_PART}:{_FP_PART}/{_FP_PART}"
    rf"(?![A-Za-z0-9._-])"
)

_BUILD_FP_LABEL_RE = re.compile(
    r"(?<![A-Za-z0-9_.])(?:ro\.)?build\.fingerprint[ \t]*[:=][ \t]*"
    r"[\"'`]?([^\s\"'`]{6,})",
    re.IGNORECASE,
)

_ANDROID_ID_RE = re.compile(
    r"(?<![A-Za-z0-9_])(?:android[_-]?id|ssaid)(?![A-Za-z0-9_])"
    r"[^0-9A-Fa-f\n]{0,24}([0-9A-Fa-f]{16})(?![0-9A-Fa-f])",
    re.IGNORECASE,
)

_STABLE_ALIAS_RE = re.compile(r"device(?:-[a-z]|-[0-9]{1,3})")
_EMULATOR_SERIAL_RE = re.compile(r"emulator-[0-9]{4,5}")
_ALLOWED_DEVICE_VALUES = frozenset({"redacted-serial-hash"})

# Absolute host paths. The lookbehind and lookahead keep URL scheme
# separators and longer hex-like runs from being mistaken for drive paths.
_WINDOWS_DRIVE_RE = re.compile(
    r"(?<![A-Za-z0-9])[A-Za-z]:[\\/](?![\\/])"
)
_HOME_PATH_RE = re.compile(
    r"/(?:home|Users)/[A-Za-z0-9._-]+(?:/|[^A-Za-z0-9._-]|$)"
)

_EMAIL_RE = re.compile(
    r"(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@"
    r"[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}"
    r"(?![A-Za-z0-9-])"
)

_PRIVATE_KEY_RE = re.compile(
    r"-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----"
)

_HEX_RUN_RE = re.compile(r"(?<![0-9A-Fa-f])[0-9A-Fa-f]{16,64}(?![0-9A-Fa-f])")
_HASH_CONTEXT_RE = re.compile(
    r"(?i)\b(?:sha-?256(?:sums?)?|sha-?1(?:sum)?|md5(?:sum)?|"
    r"checksum|digest|hash)\b"
)
_ADR_CONTEXT_RE = re.compile(r"(?i)\badr\b")

_GRADLE_SECRET_RE = re.compile(
    r"(?im)^[ \t]*[A-Za-z0-9_.-]*(?:"
    r"store[_-]?password|key[_-]?password|store[_-]?file|key[_-]?alias|"
    r"keystore|signing[._-]?config"
    r")[A-Za-z0-9_.-]*[ \t]*[=:]"
)


class ScannerError(RuntimeError):
    """Internal fail-closed error; details are never printed."""


def _is_allowed_device_value(value: str) -> bool:
    normalized = value.rstrip(".:-")
    if normalized in _ALLOWED_DEVICE_VALUES:
        return True
    return _STABLE_ALIAS_RE.fullmatch(normalized) is not None


def _is_placeholder_value(value: str) -> bool:
    stripped = value.strip("\"'`")
    if not stripped:
        return True
    if stripped[0] in "<${%(":
        return True
    return stripped.lower() in {"null", "unknown", "redacted", "placeholder"}


def _line_has_device_identifier(line: str) -> bool:
    for match in _SERIAL_LABEL_RE.finditer(line):
        if not _is_allowed_device_value(match.group(1)):
            return True
    for match in _ADB_SERIAL_RE.finditer(line):
        value = match.group(1).rstrip(".:-")
        if _is_allowed_device_value(value):
            continue
        if _EMULATOR_SERIAL_RE.fullmatch(value):
            continue
        return True
    if _MAC_RE.search(line):
        return True
    if _FINGERPRINT_RE.search(line):
        return True
    for match in _BUILD_FP_LABEL_RE.finditer(line):
        if not _is_placeholder_value(match.group(1)):
            return True
    if _ANDROID_ID_RE.search(line):
        return True
    return False


def _line_has_absolute_path(line: str) -> bool:
    return bool(_WINDOWS_DRIVE_RE.search(line) or _HOME_PATH_RE.search(line))


def _line_has_personal_email(line: str) -> bool:
    for match in _EMAIL_RE.finditer(line):
        # Generic git remote pseudo-address, not a personal mailbox.
        if match.group(0).lower() == "git@github.com":
            continue
        # Kotlin qualified-this expressions such as this@Activity.member
        # are shaped like an address but are code, not an email.
        if match.group(0).lower().startswith("this@"):
            continue
        return True
    return False


def _line_has_private_key(line: str) -> bool:
    return _PRIVATE_KEY_RE.search(line) is not None


def _line_has_token_shape(line: str) -> bool:
    for match in _HEX_RUN_RE.finditer(line):
        token = match.group(0)
        if len(token) in (40, 64):
            continue
        window_start = max(0, match.start() - 48)
        window_end = min(len(line), match.end() + 48)
        if _HASH_CONTEXT_RE.search(line[window_start:window_end]):
            continue
        if _ADR_CONTEXT_RE.search(line, max(0, match.start() - 24), match.start()):
            continue
        return True
    return False


_TEXT_DETECTORS: tuple[tuple[str, Callable[[str], bool]], ...] = (
    ("device_identifier", _line_has_device_identifier),
    ("absolute_path", _line_has_absolute_path),
    ("personal_email", _line_has_personal_email),
    ("private_key", _line_has_private_key),
    ("token_shape", _line_has_token_shape),
)


def scan_text_lines(lines: Iterable[str]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for line in lines:
        for category, detector in _TEXT_DETECTORS:
            if detector(line):
                counts[category] = counts.get(category, 0) + 1
    return counts


def _path_categories(
    path: str, read_text: Callable[[str], str]
) -> tuple[str, ...]:
    lower = path.lower()
    basename = lower.rsplit("/", 1)[-1]
    categories: list[str] = []
    if lower.endswith(MEDIA_SUFFIXES):
        categories.append("tracked_media")
    if lower.endswith(KEY_SUFFIXES):
        categories.append("key_material")
    if basename == ".env" or basename.endswith(".env"):
        categories.append("env_file")
    elif basename == "local.properties":
        categories.append("env_file")
    elif basename == "gradle.properties":
        if _GRADLE_SECRET_RE.search(read_text(path)):
            categories.append("env_file")
    return tuple(categories)


def scan_paths(
    paths: Iterable[str], read_text: Callable[[str], str]
) -> dict[str, int]:
    counts: dict[str, int] = {}
    for path in paths:
        for category in _path_categories(path, read_text):
            counts[category] = counts.get(category, 0) + 1
    return counts


def _add_counts(total: dict[str, int], extra: dict[str, int]) -> None:
    for category, count in extra.items():
        if count:
            total[category] = total.get(category, 0) + count


def _emit(counts: dict[str, int]) -> int:
    non_zero = {name: count for name, count in counts.items() if count > 0}
    if not non_zero:
        print(OK_LINE)
        return 0
    print(FOUND_LINE)
    for name in CATEGORY_ORDER:
        if non_zero.get(name):
            print(f"category={name} count={non_zero[name]}")
    for name in sorted(set(non_zero) - set(CATEGORY_ORDER)):
        print(f"category={name} count={non_zero[name]}")
    return 1


def _hex_run(blocks: int) -> str:
    """Synthetic hex token built at runtime, never a literal hit."""
    return "9f3a" * blocks


def _serial_value() -> str:
    """Synthetic device value with non-hex letters, built at runtime."""
    return "R58M" + "9f3a" * 2


def _mac_value() -> str:
    return ":".join(["9f"] * 6)


def _fingerprint_value() -> str:
    head = "/".join(["synthetic", "product", "device"])
    middle = "/".join(["14", "UE1A.230829.036", "11228849"])
    return head + ":" + middle + ":userdebug/test-keys"


def run_self_test() -> bool:
    checks: list[bool] = []
    with tempfile.TemporaryDirectory(prefix="sensitive-data-self-test-") as td_raw:
        root = Path(td_raw)

        positive_lines = [
            "Serial" + ": " + _serial_value(),
            "序列号" + "=" + _serial_value(),
            "adb" + " -s " + _serial_value(),
            _mac_value(),
            _fingerprint_value(),
            "ro." + "build." + "fingerprint=" + "synthetic-build-value",
            "android" + "_id=" + _hex_run(4),
            "C" + ":" + "\\Users" + "\\" + "fixture",
            "/" + "home/" + "fixture" + "/docs",
            "fixture" + "@" + "example" + "." + "com",
            "-----BEGIN " + "RSA " + "PRIVATE KEY-----",
            _hex_run(5),
            _hex_run(8),
        ]

        negative_lines = [
            "adb -s <serial>",
            "Serial" + ": " + "device-a",
            "Serial" + ": " + "device-b",
            "Serial" + ": " + "device-01",
            "Serial" + ": " + "redacted-serial-hash",
            "adb" + " -s " + "device-a",
            "adb" + " -s " + "redacted-serial-hash",
            "SerializableThing",
            "serializeSomething",
            "adb" + " -s " + "emulator" + "-5554",
            "build." + "fingerprint=" + "<value>",
            "android" + "_id=" + "redacted",
            "@Composable",
            "this" + "@" + "GuestFeaturesActivity" + "." + "id",
            "/tmp/fixture",
            "/var/tmp/fixture",
            "/dev/shm/fixture",
            "/opt/android-sdk/platform-tools/adb",
            "https" + "://" + "example.com/path",
            "app/src/main/App.kt",
            _hex_run(10),
            _hex_run(16),
            "sha256" + ": " + _hex_run(5),
            "ADR-0001 " + _hex_run(5),
        ]

        positive_path = root / "positive.txt"
        positive_path.write_text(
            "\n".join(positive_lines) + "\n", encoding="utf-8"
        )
        negative_path = root / "negative.txt"
        negative_path.write_text(
            "\n".join(negative_lines) + "\n", encoding="utf-8"
        )

        positive_counts = scan_text_lines(
            positive_path.read_text(encoding="utf-8").splitlines()
        )
        for category in (
            "device_identifier",
            "absolute_path",
            "personal_email",
            "private_key",
            "token_shape",
        ):
            checks.append(positive_counts.get(category, 0) >= 1)

        negative_counts = scan_text_lines(
            negative_path.read_text(encoding="utf-8").splitlines()
        )
        checks.append(all(count == 0 for count in negative_counts.values()))

        fixtures = {
            "docs/media/frame.png": "fixture",
            "artifacts/clip.mp4": "fixture",
            "signing/release.jks": "fixture",
            "keys/app.pem": "fixture",
            ".env": "fixture",
            "config/prod.env": "fixture",
            "local.properties": "fixture",
            "gradle.properties": "org.gradle.jvmargs=-Xmx1g\n",
            "signing/gradle.properties": (
                "orchestella." + "signing." + "store" + "File=release.keystore\n"
            ),
            "docs/notes.txt": "fixture",
        }
        for relative, content in fixtures.items():
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")

        relative_paths = sorted(
            item.relative_to(root).as_posix()
            for item in root.rglob("*")
            if item.is_file()
        )

        def read_text(relative: str) -> str:
            return (root / relative).read_text(encoding="utf-8")

        path_counts = scan_paths(relative_paths, read_text)
        checks.append(path_counts.get("tracked_media", 0) == 2)
        checks.append(path_counts.get("key_material", 0) == 2)
        checks.append(path_counts.get("env_file", 0) == 4)
        checks.append(
            not any(
                name in path_counts
                for name in (
                    "device_identifier",
                    "absolute_path",
                    "personal_email",
                    "private_key",
                    "token_shape",
                )
            )
        )

    return bool(checks) and all(checks)


class GitRepo:
    def __init__(self, root: Path) -> None:
        self.root = root

    @classmethod
    def discover(cls) -> "GitRepo":
        try:
            proc = subprocess.run(
                ["git", "rev-parse", "--show-toplevel"],
                cwd=Path.cwd(),
                capture_output=True,
                timeout=60,
            )
        except (OSError, subprocess.SubprocessError) as exc:
            raise ScannerError("git is unavailable") from exc
        if proc.returncode != 0:
            raise ScannerError("not a git work tree")
        root_text = proc.stdout.decode("utf-8", "replace").strip()
        if not root_text:
            raise ScannerError("empty git root")
        return cls(Path(root_text))

    def _run(self, args: list[str], check: bool = True) -> subprocess.CompletedProcess:
        try:
            proc = subprocess.run(
                ["git", *args],
                cwd=self.root,
                capture_output=True,
                timeout=180,
            )
        except (OSError, subprocess.SubprocessError) as exc:
            raise ScannerError("git invocation failed") from exc
        if check and proc.returncode != 0:
            raise ScannerError("git command failed")
        return proc

    def _decode(self, data: bytes) -> str:
        return data.decode("utf-8", "replace")

    def _resolve(self, revision: str) -> str | None:
        proc = self._run(
            [
                "rev-parse",
                "--verify",
                "--quiet",
                "--end-of-options",
                f"{revision}^{{commit}}",
            ],
            check=False,
        )
        if proc.returncode != 0:
            return None
        resolved = self._decode(proc.stdout).strip()
        return resolved or None

    def _merge_base(self, base: str, head: str) -> str | None:
        proc = self._run(["merge-base", base, head], check=False)
        if proc.returncode != 0:
            return None
        resolved = self._decode(proc.stdout).strip()
        return resolved or None

    def _ls_tree(self, revision: str) -> list[str]:
        raw = self._run(
            ["ls-tree", "-r", "-z", "--name-only", revision]
        ).stdout
        return [
            item.decode("utf-8", "replace")
            for item in raw.split(b"\0")
            if item
        ]

    def _read_blob(self, revision: str, path: str) -> str:
        return self._decode(
            self._run(["show", f"{revision}:{path}"]).stdout
        )

    def _log_messages(self, start: str, end: str) -> list[str]:
        raw = self._run(["log", "--format=%B", f"{start}..{end}"]).stdout
        return self._decode(raw).splitlines()

    def _head_message(self, revision: str) -> list[str]:
        raw = self._run(
            ["log", "-1", "--format=%B", revision]
        ).stdout
        return self._decode(raw).splitlines()

    def _added_lines(self, start: str, end: str) -> list[str]:
        # A custom added-line indicator removes the "a leaked line that starts
        # with ++ looks like a +++ header" bypass. File headers and hunks are
        # tracked explicitly so only true added content is scanned.
        raw = self._run(
            [
                "diff",
                "--unified=0",
                "--no-color",
                "--no-ext-diff",
                "--no-textconv",
                "--output-indicator-new=>",
                start,
                end,
            ]
        ).stdout
        added: list[str] = []
        in_header = False
        for line in self._decode(raw).splitlines():
            if line.startswith("diff --git "):
                in_header = True
                continue
            if line.startswith("@@"):
                in_header = False
                continue
            if in_header:
                if line.startswith(">"):
                    in_header = False
                continue
            if line.startswith(">"):
                added.append(line[1:])
        return added

    def _tree_and_commit_message(self, revision: str) -> dict[str, int]:
        paths = self._ls_tree(revision)
        counts = scan_paths(
            paths, lambda path: self._read_blob(revision, path)
        )
        _add_counts(counts, scan_text_lines(self._head_message(revision)))
        return counts

    def scan_head(self) -> dict[str, int]:
        head = self._resolve("HEAD")
        if head is None:
            raise ScannerError("HEAD does not resolve")
        return self._tree_and_commit_message(head)

    def scan_tag(self, tag: str) -> dict[str, int]:
        if tag.startswith("-"):
            raise ScannerError("invalid tag")
        tag_commit = self._resolve(f"refs/tags/{tag}")
        if tag_commit is None:
            raise ScannerError("tag does not resolve")
        counts = scan_paths(
            self._ls_tree(tag_commit),
            lambda path: self._read_blob(tag_commit, path),
        )
        message = self._decode(
            self._run(
                ["tag", "-l", "--format=%(contents)", "--", tag]
            ).stdout
        )
        _add_counts(counts, scan_text_lines(message.splitlines()))
        return counts

    def scan_range(self, spec: str) -> dict[str, int]:
        if "..." in spec:
            three_dot = True
            base_text, separator, head_text = spec.partition("...")
        else:
            three_dot = False
            base_text, separator, head_text = spec.partition("..")
        if (
            not separator
            or not base_text
            or not head_text
            or ".." in head_text
            or "..." in head_text
        ):
            raise ScannerError("invalid range")

        head = self._resolve(head_text)
        if head is None:
            raise ScannerError("range head does not resolve")
        base = self._resolve(base_text)
        if base is None or set(base) == {"0"}:
            # New branch push or force-push base that is no longer present.
            return self._tree_and_commit_message(head)

        if three_dot:
            compare_base = self._merge_base(base, head)
            if compare_base is None:
                return self._tree_and_commit_message(head)
        else:
            compare_base = base

        counts = scan_paths(
            self._ls_tree(head),
            lambda path: self._read_blob(head, path),
        )
        _add_counts(
            counts, scan_text_lines(self._added_lines(compare_base, head))
        )
        _add_counts(
            counts,
            scan_text_lines(self._log_messages(compare_base, head)),
        )
        return counts


class _ArgumentParser(argparse.ArgumentParser):
    def error(self, message: str) -> None:
        # Keep usage errors inside the scanner's output vocabulary.
        raise ScannerError(message)


def _build_parser() -> argparse.ArgumentParser:
    parser = _ArgumentParser(
        description="Scan tracked trees, added diff lines and messages for "
        "forbidden sensitive developer data."
    )
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument(
        "--self-test",
        action="store_true",
        help="run synthetic positive and negative detector checks",
    )
    mode.add_argument(
        "--range",
        dest="range_spec",
        metavar="A..B|A...B",
        help="scan added lines and commit messages for a revision range",
    )
    mode.add_argument(
        "--tag",
        metavar="TAG",
        help="scan the tree and annotated message of TAG",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = _build_parser()
    try:
        args = parser.parse_args(argv)
    except SystemExit as exc:
        # argparse exits 0 for --help and 2 for usage errors.
        if exc.code == 0:
            return 0
        return _emit({"scanner_error": 1})
    except Exception:
        return _emit({"scanner_error": 1})

    try:
        if args.self_test:
            if run_self_test():
                return _emit({})
            return _emit({"self_test": 1})

        repo = GitRepo.discover()
        if args.tag is not None:
            counts = repo.scan_tag(args.tag)
        elif args.range_spec is not None:
            counts = repo.scan_range(args.range_spec)
        else:
            counts = repo.scan_head()
        return _emit(counts)
    except Exception:
        # Fail closed without a traceback or any matched content.
        return _emit({"scanner_error": 1})


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception:
        print(FOUND_LINE)
        print("category=scanner_error count=1")
        raise SystemExit(1)
