# SPDX-License-Identifier: GPL-3.0-or-later
"""Bounded local AVF evidence I/O. Roots are always explicit; no network or default temp."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import uuid

MAX_INPUT = 2 * 1024 * 1024


class Rejected(ValueError):
    """Fixed reason codes only; never attach an input value or OS error text."""


class SafeParser(argparse.ArgumentParser):
    def error(self, message):
        self.exit(2, "ARGUMENTS_REJECTED\n")


def require_supported_runtime():
    if sys.version_info < (3, 12) or not callable(getattr(Path, "is_junction", None)):
        raise Rejected("RUNTIME_UNSUPPORTED")


def _root(value):
    require_supported_runtime()
    path = Path(value)
    if not path.is_absolute():
        raise Rejected("ROOT_REJECTED")
    for component in (path, *path.parents):
        if component.is_symlink() or component.is_junction():
            raise Rejected("LINK_REJECTED")
    return path.resolve()


def _path(root, relative, preservation=False):
    require_supported_runtime()
    if not isinstance(relative, str) or len(relative) > 200 or not re.fullmatch(r"[A-Za-z0-9_./-]+", relative):
        raise Rejected("PATH_REJECTED")
    parts = relative.split("/")
    if any(p in ("", ".", "..") or p.startswith(".") for p in parts):
        raise Rejected("PATH_REJECTED")
    if parts[0] == "preservation" and not preservation:
        raise Rejected("PATH_REJECTED")
    if not relative.endswith((".json", ".md", ".tsv", ".txt", ".py")):
        raise Rejected("EXTENSION_REJECTED")
    current = root
    for part in parts:
        current = current / part
        if current.is_symlink() or current.is_junction():
            raise Rejected("LINK_REJECTED")
    if not current.resolve().is_relative_to(root):
        raise Rejected("PATH_REJECTED")
    return current


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise Rejected("DUPLICATE_JSON_KEY")
        result[key] = value
    return result


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=True, allow_nan=False, indent=2) + "\n").encode("utf-8")


def parse_json(data):
    if type(data) is not bytes or len(data) > MAX_INPUT:
        raise Rejected("INPUT_REJECTED")
    try:
        return json.loads(data, object_pairs_hook=_unique_object,
                          parse_constant=lambda _: (_ for _ in ()).throw(Rejected("NONFINITE_JSON")))
    except (UnicodeError, json.JSONDecodeError, RecursionError):
        raise Rejected("JSON_REJECTED") from None


class EvidenceStore:
    def __init__(self, evidence_root, output_root):
        self.evidence_root = _root(evidence_root)
        self.output_root = _root(output_root)

    def evidence_path(self, relative):
        return _path(self.evidence_root, relative)

    def output_path(self, relative):
        return _path(self.output_root, relative)

    def read_bytes(self, relative):
        path = self.evidence_path(relative)
        if not path.is_file() or path.stat().st_size > MAX_INPUT:
            raise Rejected("INPUT_REJECTED")
        with path.open("rb") as stream:
            data = stream.read(MAX_INPUT + 1)
        if len(data) > MAX_INPUT:
            raise Rejected("INPUT_REJECTED")
        return data

    def load_json(self, relative):
        if not relative.endswith(".json"):
            raise Rejected("INPUT_REJECTED")
        return parse_json(self.read_bytes(relative))

    def assert_new_output(self, relative):
        target = self.output_path(relative)
        twin = _path(self.output_root, "preservation/" + relative, preservation=True)
        if target.exists() or twin.exists():
            raise Rejected("OUTPUT_EXISTS")
        return target, twin

    def save(self, relative, data):
        if type(data) is not bytes:
            raise Rejected("OUTPUT_REJECTED")
        target, twin = self.assert_new_output(relative)
        target.parent.mkdir(parents=True, exist_ok=True)
        twin.parent.mkdir(parents=True, exist_ok=True)
        # Independent copies, each staged in its explicit destination directory.
        # A hard-link publishes complete bytes atomically without replacing an old name;
        # the target and preservation copy never share an inode with each other.
        for destination in (twin, target):
            pending = destination.parent / (".avf-pending-" + uuid.uuid4().hex)
            try:
                with pending.open("xb") as stream:
                    stream.write(data)
                    stream.flush()
                    os.fsync(stream.fileno())
                os.link(pending, destination)
            except FileExistsError:
                raise Rejected("OUTPUT_EXISTS") from None
            finally:
                if pending.exists():
                    pending.unlink()  # only this call's private incomplete staging entry
        digest = hashlib.sha256(data).hexdigest()
        if twin.read_bytes() != data:
            raise Rejected("PRESERVATION_MISMATCH")
        return {"path": relative, "sha256": digest, "bytes": len(data), "preserved": True}

    def save_json(self, relative, value):
        return self.save(relative, json_bytes(value))
