"""Unit tests for the PVM-09 compiler-service manifest (no NDK, no device).

The generator's staging path calls verify_so(), which parses the ELF header of
every staged .so. That check needs only EM_AARCH64 plus a 16 KB-aligned
PT_LOAD, so the fixtures here are hand-built minimal aarch64 ELFs: generation
is exercised end to end through the CLI without an NDK toolchain.
"""

import hashlib
import json
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import assemble_payload_libs as compiler

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "assemble_payload_libs.py"

EM_AARCH64 = 183
PAGE_ALIGN = 16 * 1024
MANIFEST = Path("assets") / "PVM-COMPILER-MANIFEST"
COMPILER_VERSION = "22.1.8"
TARGET_TRIPLE = "aarch64-unknown-linux-android"
DEFAULT_PAYLOAD_BINARY = "libpvm_compiler_payload.so"


def aarch64_elf(payload: bytes = b"") -> bytes:
    """Minimal ELF64 LE aarch64 object with one 16 KB-aligned PT_LOAD segment."""
    ident = bytes([0x7F]) + b"ELF" + bytes([2, 1, 1]) + bytes(9)
    header = ident + struct.pack(
        "<HHIQQQIHHHHHH",
        3,            # e_type = ET_DYN
        EM_AARCH64,   # e_machine
        1,            # e_version
        0,            # e_entry
        64,           # e_phoff: program headers follow the 64-byte ELF header
        0,            # e_shoff
        0,            # e_flags
        64,           # e_ehsize
        56,           # e_phentsize
        1,            # e_phnum
        0,            # e_shentsize
        0,            # e_shnum
        0,            # e_shstrndx
    )
    total = 120 + len(payload)
    load = struct.pack("<IIQQQQQQ", 1, 5, 0, 0, 0, total, total, PAGE_ALIGN)
    return header + load + payload


def write_so(abi_dir: Path, name: str, payload: bytes) -> Path:
    abi_dir.mkdir(parents=True, exist_ok=True)
    path = abi_dir / name
    path.write_bytes(aarch64_elf(payload))
    return path


def run_cli(*arguments) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, "-B", str(SCRIPT), *[str(item) for item in arguments]],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )


def payload_lines(output: str, prefix: str) -> list[str]:
    return [line for line in output.splitlines() if line.startswith(prefix)]


class ManifestFixture(unittest.TestCase):
    """Shared temp-root staging helpers; every test builds its own tree."""

    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="pvm09-manifest-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def compiler_dir(self, with_libcxx: bool = False) -> Path:
        abi = self.root / "compiler" / "arm64-v8a"
        write_so(abi, "libLLVM.so", b"L" * 32)
        write_so(abi, "libclang-cpp.so", b"C" * 48)
        if with_libcxx:
            write_so(abi, "libc++_shared.so", b"X" * 16)
        return abi.parent

    def payload_dir(self, binary: str = DEFAULT_PAYLOAD_BINARY) -> Path:
        abi = self.root / "payload" / "arm64-v8a"
        write_so(abi, "libfastlane_payload.so", b"P" * 8)
        write_so(abi, binary, b"F" * 8)
        return abi.parent

    def generate(self, *extra) -> tuple[subprocess.CompletedProcess, Path]:
        out = self.root / "staging"
        result = run_cli(
            "--compiler-dir", self.compiler_dir(),
            "--payload-dir", self.payload_dir(),
            "--out", out,
            "--compiler-version", COMPILER_VERSION,
            "--target-triple", TARGET_TRIPLE,
            *extra,
        )
        return result, out

    def generated_manifest(self) -> tuple[Path, dict]:
        result, out = self.generate()
        self.assertEqual(0, result.returncode, result.stderr)
        return out, json.loads((out / MANIFEST).read_text(encoding="utf-8"))

    def rewrite_manifest(self, out: Path, mutate) -> None:
        path = out / MANIFEST
        manifest = json.loads(path.read_text(encoding="utf-8"))
        mutate(manifest)
        path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    def verify(self, out: Path) -> subprocess.CompletedProcess:
        return run_cli("--verify-manifest", out)


class GenerationTest(ManifestFixture):
    def test_generation_writes_contract_fields_in_order(self):
        result, out = self.generate()

        self.assertEqual(0, result.returncode, result.stderr)
        manifest = json.loads((out / MANIFEST).read_text(encoding="utf-8"))
        self.assertEqual(
            ["schema", "compiler_version", "target_triple", "vcpu_topology",
             "memory_mib", "payload_binary", "libs"],
            list(manifest),
        )
        self.assertEqual(1, manifest["schema"])
        self.assertEqual(COMPILER_VERSION, manifest["compiler_version"])
        self.assertEqual(TARGET_TRIPLE, manifest["target_triple"])
        self.assertEqual("one_cpu", manifest["vcpu_topology"])
        self.assertEqual(2048, manifest["memory_mib"])
        self.assertEqual(DEFAULT_PAYLOAD_BINARY, manifest["payload_binary"])

    def test_libs_are_sorted_by_name_and_match_the_staged_bytes(self):
        out, manifest = self.generated_manifest()

        self.assertEqual(["libLLVM.so", "libclang-cpp.so"], [lib["name"] for lib in manifest["libs"]])
        for lib in manifest["libs"]:
            staged = out / "arm64-v8a" / lib["name"]
            self.assertEqual(staged.stat().st_size, lib["size"])
            self.assertEqual(hashlib.sha256(staged.read_bytes()).hexdigest(), lib["sha256"])
            self.assertRegex(lib["sha256"], r"\A[0-9a-f]{64}\Z")

    def test_optional_libcxx_is_described_when_present(self):
        out = self.root / "staging"
        result = run_cli(
            "--compiler-dir", self.compiler_dir(with_libcxx=True),
            "--out", out,
            "--compiler-version", COMPILER_VERSION,
            "--target-triple", TARGET_TRIPLE,
        )

        self.assertEqual(0, result.returncode, result.stderr)
        manifest = json.loads((out / MANIFEST).read_text(encoding="utf-8"))
        self.assertEqual(
            ["libLLVM.so", "libc++_shared.so", "libclang-cpp.so"],
            [lib["name"] for lib in manifest["libs"]],
        )

    def test_stage_ok_line_carries_the_manifest_summary(self):
        result, out = self.generate()

        lines = payload_lines(result.stdout, "PVM_COMPILER_STAGE_OK ")
        self.assertEqual(1, len(lines))
        summary = json.loads(lines[0][len("PVM_COMPILER_STAGE_OK "):])
        self.assertEqual({"path": "assets/PVM-COMPILER-MANIFEST", "libs": 2}, summary["manifest"])
        self.assertEqual(2, len(summary["files"]))
        self.assertTrue((out / MANIFEST).is_file())

    def test_manifest_out_overrides_the_default_location(self):
        custom = self.root / "elsewhere" / "MANIFEST.json"
        result, out = self.generate("--manifest-out", custom)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(custom.is_file())
        self.assertFalse((out / MANIFEST).exists())
        summary = json.loads(payload_lines(result.stdout, "PVM_COMPILER_STAGE_OK ")[0].split(" ", 1)[1])
        self.assertEqual("MANIFEST.json", summary["manifest"]["path"])

    def test_memory_and_payload_binary_overrides_are_recorded(self):
        out = self.root / "staging"
        binary = "libcustom_payload.so"
        result = run_cli(
            "--compiler-dir", self.compiler_dir(),
            "--payload-dir", self.payload_dir(binary=binary),
            "--out", out,
            "--compiler-version", COMPILER_VERSION,
            "--target-triple", TARGET_TRIPLE,
            "--payload-binary", binary,
            "--memory-mib", "512",
        )

        self.assertEqual(0, result.returncode, result.stderr)
        manifest = json.loads((out / MANIFEST).read_text(encoding="utf-8"))
        self.assertEqual(binary, manifest["payload_binary"])
        self.assertEqual(512, manifest["memory_mib"])

    def test_generation_without_payload_dir_still_writes_a_manifest(self):
        out = self.root / "staging"
        result = run_cli(
            "--compiler-dir", self.compiler_dir(),
            "--out", out,
            "--compiler-version", COMPILER_VERSION,
            "--target-triple", TARGET_TRIPLE,
        )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue((out / MANIFEST).is_file())


class GenerationArgumentTest(ManifestFixture):
    def test_missing_compiler_version_fails(self):
        result = run_cli(
            "--compiler-dir", self.compiler_dir(),
            "--out", self.root / "staging",
            "--target-triple", TARGET_TRIPLE,
        )

        self.assertEqual(1, result.returncode)
        self.assertIn("--compiler-version", result.stderr)

    def test_missing_target_triple_fails(self):
        result = run_cli(
            "--compiler-dir", self.compiler_dir(),
            "--out", self.root / "staging",
            "--compiler-version", COMPILER_VERSION,
        )

        self.assertEqual(1, result.returncode)
        self.assertIn("--target-triple", result.stderr)

    def test_payload_binary_must_be_a_bare_so_name(self):
        result, _ = self.generate("--payload-binary", "arm64-v8a/libpayload.so")

        self.assertEqual(1, result.returncode)
        self.assertIn("--payload-binary", result.stderr)

    def test_payload_binary_missing_from_payload_dir_fails(self):
        out = self.root / "staging"
        result = run_cli(
            "--compiler-dir", self.compiler_dir(),
            "--payload-dir", self.payload_dir(),
            "--out", out,
            "--compiler-version", COMPILER_VERSION,
            "--target-triple", TARGET_TRIPLE,
            "--payload-binary", "libabsent_payload.so",
        )

        self.assertEqual(1, result.returncode)
        self.assertIn("libabsent_payload.so", result.stderr)
        self.assertFalse((out / MANIFEST).exists())

    def test_memory_mib_bounds_are_enforced(self):
        for value in ("0", "16385"):
            with self.subTest(value=value):
                result, _ = self.generate("--memory-mib", value)

                self.assertEqual(1, result.returncode)
                self.assertIn("--memory-mib", result.stderr)

    def test_placeholder_without_ndk_fails_instead_of_silently_staging_nothing(self):
        result = run_cli(
            "--placeholder",
            "--out", self.root / "staging",
            "--compiler-version", COMPILER_VERSION,
            "--target-triple", TARGET_TRIPLE,
        )

        self.assertEqual(1, result.returncode)
        self.assertIn("--ndk", result.stderr)


class VerifyManifestTest(ManifestFixture):
    def test_verify_accepts_a_freshly_generated_staging_directory(self):
        out, _ = self.generated_manifest()

        result = self.verify(out)

        self.assertEqual(0, result.returncode, result.stderr)
        lines = payload_lines(result.stdout, "PVM_COMPILER_MANIFEST_OK ")
        self.assertEqual(1, len(lines))
        summary = json.loads(lines[0][len("PVM_COMPILER_MANIFEST_OK "):])
        self.assertEqual(1, summary["schema"])
        self.assertEqual(2, summary["libs"])
        self.assertEqual(TARGET_TRIPLE, summary["target_triple"])

    def test_missing_manifest_fails(self):
        out, _ = self.generated_manifest()
        (out / MANIFEST).unlink()

        result = self.verify(out)

        self.assertEqual(1, result.returncode)
        self.assertTrue(result.stderr.startswith("FAIL manifest:"))

    def test_invalid_json_fails(self):
        out, _ = self.generated_manifest()
        (out / MANIFEST).write_text("{not json", encoding="utf-8")

        result = self.verify(out)

        self.assertEqual(1, result.returncode)
        self.assertIn("not valid UTF-8 JSON", result.stderr)

    def test_missing_library_fails(self):
        out, _ = self.generated_manifest()
        (out / "arm64-v8a" / "libclang-cpp.so").unlink()

        result = self.verify(out)

        self.assertEqual(1, result.returncode)
        self.assertIn("libclang-cpp.so", result.stderr)
        self.assertIn("missing", result.stderr)

    def test_undeclared_library_fails(self):
        out, _ = self.generated_manifest()
        write_so(out / "arm64-v8a", "libextra.so", b"E" * 8)

        result = self.verify(out)

        self.assertEqual(1, result.returncode)
        self.assertIn("libextra.so", result.stderr)

    def test_size_tampering_fails(self):
        out, manifest = self.generated_manifest()
        name = manifest["libs"][0]["name"]
        self.rewrite_manifest(out, lambda data: data["libs"][0].update(size=data["libs"][0]["size"] + 1))

        result = self.verify(out)

        self.assertEqual(1, result.returncode)
        self.assertIn(name, result.stderr)
        self.assertIn("size mismatch", result.stderr)

    def test_sha256_tampering_fails(self):
        out, manifest = self.generated_manifest()
        name = manifest["libs"][0]["name"]
        # Same length, different bytes: only the digest can catch this.
        payload = b"Z" * 32 if name == "libLLVM.so" else b"Z" * 48
        write_so(out / "arm64-v8a", name, payload)

        result = self.verify(out)

        self.assertEqual(1, result.returncode)
        self.assertIn(name, result.stderr)
        self.assertIn("sha256 mismatch", result.stderr)

    def test_uppercase_sha256_is_rejected(self):
        out, _ = self.generated_manifest()
        self.rewrite_manifest(out, lambda data: data["libs"][0].update(sha256="A" * 64))

        result = self.verify(out)

        self.assertEqual(1, result.returncode)
        self.assertIn("lowercase hex", result.stderr)

    def test_non_hex_sha256_is_rejected(self):
        out, _ = self.generated_manifest()
        self.rewrite_manifest(out, lambda data: data["libs"][0].update(sha256="z" * 64))

        result = self.verify(out)

        self.assertEqual(1, result.returncode)
        self.assertIn("lowercase hex", result.stderr)

    def test_duplicate_library_entry_fails(self):
        out, _ = self.generated_manifest()

        def duplicate(data):
            data["libs"].append(dict(data["libs"][0]))

        self.rewrite_manifest(out, duplicate)

        result = self.verify(out)

        self.assertEqual(1, result.returncode)
        self.assertIn("duplicate", result.stderr)

    def test_empty_libs_array_fails(self):
        out, _ = self.generated_manifest()
        self.rewrite_manifest(out, lambda data: data.update(libs=[]))

        result = self.verify(out)

        self.assertEqual(1, result.returncode)
        self.assertIn("non-empty array", result.stderr)

    def test_wrong_schema_number_fails(self):
        out, _ = self.generated_manifest()
        self.rewrite_manifest(out, lambda data: data.update(schema=2))

        result = self.verify(out)

        self.assertEqual(1, result.returncode)
        self.assertIn("schema", result.stderr)

    def test_string_schema_fails(self):
        out, _ = self.generated_manifest()
        self.rewrite_manifest(out, lambda data: data.update(schema="1"))

        result = self.verify(out)

        self.assertEqual(1, result.returncode)
        self.assertIn("schema", result.stderr)

    def test_missing_field_fails(self):
        for field in ("compiler_version", "target_triple", "payload_binary", "vcpu_topology",
                      "memory_mib", "libs"):
            with self.subTest(field=field):
                out = self.root / ("staging-" + field)
                result = run_cli(
                    "--compiler-dir", self.compiler_dir(),
                    "--out", out,
                    "--compiler-version", COMPILER_VERSION,
                    "--target-triple", TARGET_TRIPLE,
                )
                self.assertEqual(0, result.returncode, result.stderr)
                self.rewrite_manifest(out, lambda data, key=field: data.pop(key))

                failure = self.verify(out)

                self.assertEqual(1, failure.returncode)
                self.assertIn(field, failure.stderr)

    def test_blank_string_field_fails(self):
        out, _ = self.generated_manifest()
        self.rewrite_manifest(out, lambda data: data.update(compiler_version="   "))

        result = self.verify(out)

        self.assertEqual(1, result.returncode)
        self.assertIn("compiler_version", result.stderr)

    def test_topology_other_than_one_cpu_fails(self):
        out, _ = self.generated_manifest()
        self.rewrite_manifest(out, lambda data: data.update(vcpu_topology="two_cpu"))

        result = self.verify(out)

        self.assertEqual(1, result.returncode)
        self.assertIn("one_cpu", result.stderr)

    def test_memory_mib_out_of_range_fails(self):
        for value in (0, 16385):
            with self.subTest(value=value):
                out = self.root / f"staging-mem-{value}"
                result = run_cli(
                    "--compiler-dir", self.compiler_dir(),
                    "--out", out,
                    "--compiler-version", COMPILER_VERSION,
                    "--target-triple", TARGET_TRIPLE,
                )
                self.assertEqual(0, result.returncode, result.stderr)
                self.rewrite_manifest(out, lambda data, mem=value: data.update(memory_mib=mem))

                failure = self.verify(out)

                self.assertEqual(1, failure.returncode)
                self.assertIn("memory_mib", failure.stderr)

    def test_payload_binary_with_a_path_fails(self):
        out, _ = self.generated_manifest()
        self.rewrite_manifest(out, lambda data: data.update(payload_binary="libs/libpayload.so"))

        result = self.verify(out)

        self.assertEqual(1, result.returncode)
        self.assertIn("payload_binary", result.stderr)

    def test_missing_arm64_v8a_directory_fails(self):
        out, _ = self.generated_manifest()
        for staged in (out / "arm64-v8a").iterdir():
            staged.unlink()
        (out / "arm64-v8a").rmdir()

        result = self.verify(out)

        self.assertEqual(1, result.returncode)
        self.assertTrue(result.stderr.startswith("FAIL manifest:"))

    def test_verify_rejects_generation_arguments(self):
        out, _ = self.generated_manifest()

        result = run_cli("--verify-manifest", out, "--compiler-dir", self.compiler_dir())

        self.assertEqual(1, result.returncode)
        self.assertIn("mutually exclusive", result.stderr)


class BuildManifestTest(ManifestFixture):
    """Direct tests of the pure builder the CLI wraps."""

    def test_builder_matches_the_files_on_disk(self):
        abi = self.root / "abi" / "arm64-v8a"
        llvm = write_so(abi, "libLLVM.so", b"L" * 16)
        clang = write_so(abi, "libclang-cpp.so", b"C" * 8)
        (abi / "notes.txt").write_text("not a library", encoding="ascii")

        manifest = compiler.build_manifest(
            abi,
            compiler_version=COMPILER_VERSION,
            target_triple=TARGET_TRIPLE,
        )

        self.assertEqual(["libLLVM.so", "libclang-cpp.so"], [lib["name"] for lib in manifest["libs"]])
        self.assertEqual(
            {"name": "libLLVM.so", "size": llvm.stat().st_size,
             "sha256": hashlib.sha256(llvm.read_bytes()).hexdigest()},
            manifest["libs"][0],
        )
        self.assertEqual(clang.stat().st_size, manifest["libs"][1]["size"])
        self.assertEqual(DEFAULT_PAYLOAD_BINARY, manifest["payload_binary"])
        self.assertEqual("one_cpu", manifest["vcpu_topology"])

    def test_builder_output_is_stable_across_file_creation_order(self):
        abi = self.root / "abi" / "arm64-v8a"
        write_so(abi, "libclang-cpp.so", b"C" * 8)
        write_so(abi, "libLLVM.so", b"L" * 16)

        first = compiler.build_manifest(abi, COMPILER_VERSION, TARGET_TRIPLE)
        second = compiler.build_manifest(abi, COMPILER_VERSION, TARGET_TRIPLE)

        self.assertEqual(first, second)
        self.assertEqual(["libLLVM.so", "libclang-cpp.so"], [lib["name"] for lib in first["libs"]])


if __name__ == "__main__":
    unittest.main()
