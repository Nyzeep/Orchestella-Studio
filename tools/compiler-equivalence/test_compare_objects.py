"""PVM-05 judgement tests.

Standard library only (no pytest). Run from the repository root:

    python -m unittest discover -s tools/compiler-equivalence -p 'test_*.py'

Level-3/4 cases need `readelf` and ELF objects; on hosts where readelf cannot
parse the compiler's object format (e.g. PE/COFF), those cases skip with an
explicit message instead of failing.
"""

from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import compare_objects

HERE = Path(__file__).resolve().parent
FIXTURES = HERE / "fixtures"

CANDIDATE_C = "int fixture_entry(void) { return 42; }\n"
# Same observable behavior (exit 42) but different -O0 codegen: the second
# variant keeps the arithmetic on the stack instead of folding it.
CANDIDATE_C_ALT_CODE = "int fixture_entry(void) { int x = 40; return x + 2; }\n"
CANDIDATE_C_CONSTANT = "int fixture_entry(void) { return 6 * 8; }\n"
CANDIDATE_C_PRINT = (
    "int puts(const char*);\n"
    "int fixture_entry(void) { puts(\"verdict-alpha\"); return 0; }\n"
)
CANDIDATE_C_PRINT_ALT = (
    "int puts(const char*);\n"
    "int fixture_entry(void) { puts(\"verdict-beta\"); return 0; }\n"
)
CANDIDATE_C_PATH_TAG = (
    "const char* const kSourceTag = __FILE__;\n"
    "int fixture_entry(void) { return kSourceTag != 0 ? 42 : 1; }\n"
)
CANDIDATE_C_EXTRA_SYMBOL = (
    "int fixture_helper(void) { return 1; }\n"
    "int fixture_entry(void) { return fixture_helper() + 41; }\n"
)
CANDIDATE_CPP = (
    "template <int N> struct Adder { static int apply(int x) { return x + N; } };\n"
    "extern \"C\" int fixture_entry() { return Adder<6>::apply(36); }\n"
)

CXX_NAMES = ("c++", "g++", "clang++")


def compile_candidate(text: str, workdir: Path, compiler: str, name: str = "candidate.c") -> Path:
    source = workdir / name
    source.write_text(text, encoding="utf-8")
    obj = workdir / (Path(name).stem + ".o")
    result = subprocess.run(
        [compiler, "-c", str(source), "-o", str(obj)], capture_output=True, check=False
    )
    if result.returncode != 0:
        raise AssertionError(f"test setup compile failed: {result.stderr.decode()}")
    return obj


class CompareObjectsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cc = shutil.which("cc") or shutil.which("gcc") or shutil.which("clang")
        cls.cxx = shutil.which("c++") or shutil.which("g++") or shutil.which("clang++")
        cls.readelf = shutil.which("readelf")
        cls.nm = shutil.which("nm")
        if cls.cc is None:
            raise unittest.SkipTest("no C compiler on this host; verdicts cannot run")

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="pvm05-test-")
        self.workdir = Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)

    def _two_dirs(self) -> tuple[Path, Path]:
        dir_a = self.workdir / "build-a"
        dir_b = self.workdir / "build-b"
        dir_a.mkdir()
        dir_b.mkdir()
        return dir_a, dir_b

    def _elf_ok(self, obj: Path) -> bool:
        """readelf must parse the host object format, or level 3/4 cannot run."""
        if self.readelf is None:
            return False
        probe = subprocess.run(
            [self.readelf, "-S", str(obj)], capture_output=True, check=False
        )
        return probe.returncode == 0

    # -- EQUIVALENT ---------------------------------------------------------

    def test_equivalent_same_source_different_dirs(self):
        dir_a, dir_b = self._two_dirs()
        obj_a = compile_candidate(CANDIDATE_C, dir_a, self.cc)
        obj_b = compile_candidate(CANDIDATE_C, dir_b, self.cc)
        if not self._elf_ok(obj_a):
            self.skipTest("readelf cannot parse this host's object format")
        verdict, detail = compare_objects.compare_objects(obj_a, obj_b, FIXTURES / "c")
        self.assertEqual(verdict, compare_objects.EQUIVALENT, detail)

    def test_equivalent_path_only_difference_whitelisted(self):
        dir_a, dir_b = self._two_dirs()
        obj_a = compile_candidate(CANDIDATE_C_PATH_TAG, dir_a, self.cc)
        obj_b = compile_candidate(CANDIDATE_C_PATH_TAG, dir_b, self.cc)
        if not self._elf_ok(obj_a):
            self.skipTest("readelf cannot parse this host's object format")
        verdict, detail = compare_objects.compare_objects(obj_a, obj_b, FIXTURES / "c")
        self.assertEqual(verdict, compare_objects.EQUIVALENT, detail)

    def test_equivalent_cpp_template(self):
        if self.cxx is None:
            self.skipTest("no C++ compiler on this host")
        dir_a, dir_b = self._two_dirs()
        obj_a = compile_candidate(CANDIDATE_CPP, dir_a, self.cxx, "candidate.cpp")
        obj_b = compile_candidate(CANDIDATE_CPP, dir_b, self.cxx, "candidate.cpp")
        if not self._elf_ok(obj_a):
            self.skipTest("readelf cannot parse this host's object format")
        verdict, detail = compare_objects.compare_objects(obj_a, obj_b, FIXTURES / "cpp")
        self.assertEqual(verdict, compare_objects.EQUIVALENT, detail)

    # -- DIFFERENT ----------------------------------------------------------

    def test_different_exit_code(self):
        dir_a, dir_b = self._two_dirs()
        obj_a = compile_candidate(CANDIDATE_C, dir_a, self.cc)
        obj_b = compile_candidate(CANDIDATE_C_CONSTANT, dir_b, self.cc)
        verdict, detail = compare_objects.compare_objects(obj_a, obj_b, FIXTURES / "c")
        self.assertEqual(verdict, compare_objects.DIFFERENT, detail)
        self.assertIn("exit code", detail)

    def test_different_stdout(self):
        dir_a, dir_b = self._two_dirs()
        obj_a = compile_candidate(CANDIDATE_C_PRINT, dir_a, self.cc)
        obj_b = compile_candidate(CANDIDATE_C_PRINT_ALT, dir_b, self.cc)
        verdict, detail = compare_objects.compare_objects(obj_a, obj_b, FIXTURES / "c")
        self.assertEqual(verdict, compare_objects.DIFFERENT, detail)
        self.assertIn("stdout", detail)

    def test_different_code_same_behavior_caught_by_structure(self):
        dir_a, dir_b = self._two_dirs()
        obj_a = compile_candidate(CANDIDATE_C, dir_a, self.cc)
        obj_b = compile_candidate(CANDIDATE_C_ALT_CODE, dir_b, self.cc)
        if not self._elf_ok(obj_a):
            self.skipTest("readelf cannot parse this host's object format")
        verdict, detail = compare_objects.compare_objects(obj_a, obj_b, FIXTURES / "c")
        self.assertEqual(verdict, compare_objects.DIFFERENT, detail)

    def test_different_symbol_set(self):
        dir_a, dir_b = self._two_dirs()
        obj_a = compile_candidate(CANDIDATE_C, dir_a, self.cc)
        obj_b = compile_candidate(CANDIDATE_C_EXTRA_SYMBOL, dir_b, self.cc)
        if not self._elf_ok(obj_a):
            self.skipTest("readelf cannot parse this host's object format")
        verdict, detail = compare_objects.compare_objects(obj_a, obj_b, FIXTURES / "c")
        self.assertEqual(verdict, compare_objects.DIFFERENT, detail)

    def test_different_one_side_fails_to_link(self):
        dir_a, dir_b = self._two_dirs()
        obj_a = compile_candidate(CANDIDATE_C, dir_a, self.cc)
        obj_b = compile_candidate(
            Path(FIXTURES / "error" / "candidate.c").read_text(encoding="utf-8"),
            dir_b, self.cc,
        )
        verdict, detail = compare_objects.compare_objects(obj_a, obj_b, FIXTURES / "c")
        self.assertEqual(verdict, compare_objects.DIFFERENT, detail)
        self.assertIn("link", detail)

    # -- INCONCLUSIVE -------------------------------------------------------

    def test_inconclusive_missing_object(self):
        obj = compile_candidate(CANDIDATE_C, self.workdir, self.cc)
        verdict, detail = compare_objects.compare_objects(
            obj, self.workdir / "does-not-exist.o", FIXTURES / "c")
        self.assertEqual(verdict, compare_objects.INCONCLUSIVE, detail)

    def test_inconclusive_both_fail_to_link(self):
        error_source = Path(FIXTURES / "error" / "candidate.c").read_text(encoding="utf-8")
        dir_a, dir_b = self._two_dirs()
        obj_a = compile_candidate(error_source, dir_a, self.cc)
        obj_b = compile_candidate(error_source, dir_b, self.cc)
        verdict, detail = compare_objects.compare_objects(obj_a, obj_b, FIXTURES / "c")
        self.assertEqual(verdict, compare_objects.INCONCLUSIVE, detail)
        self.assertIn("links", detail)

    def test_inconclusive_tools_missing(self):
        dir_a, dir_b = self._two_dirs()
        obj_a = compile_candidate(CANDIDATE_C, dir_a, self.cc)
        obj_b = compile_candidate(CANDIDATE_C, dir_b, self.cc)
        original = compare_objects.find_tool
        compare_objects.find_tool = lambda *names: None
        try:
            verdict, detail = compare_objects.compare_objects(obj_a, obj_b, FIXTURES / "c")
        finally:
            compare_objects.find_tool = original
        self.assertEqual(verdict, compare_objects.INCONCLUSIVE, detail)
        self.assertIn("compiler", detail)

    # -- 噪声字节 ------------------------------------------------------------

    def test_equivalent_trailing_noise_byte(self):
        dir_a, dir_b = self._two_dirs()
        obj_a = compile_candidate(CANDIDATE_C, dir_a, self.cc)
        if not self._elf_ok(obj_a):
            self.skipTest("readelf cannot parse this host's object format")
        noisy = self.workdir / "noisy.o"
        noisy.write_bytes(obj_a.read_bytes() + b"\x7f")
        verdict, detail = compare_objects.compare_objects(obj_a, noisy, FIXTURES / "c")
        self.assertEqual(verdict, compare_objects.EQUIVALENT, detail)

    def test_different_in_section_noise_byte(self):
        dir_a, dir_b = self._two_dirs()
        obj_a = compile_candidate(CANDIDATE_C, dir_a, self.cc)
        obj_b = compile_candidate(CANDIDATE_C, dir_b, self.cc)
        if not self._elf_ok(obj_b):
            self.skipTest("readelf cannot parse this host's object format")
        sections = dict(compare_objects.read_elf_sections(obj_b))
        target = next((name for name in (".text", ".rodata") if sections.get(name)), None)
        if target is None:
            self.skipTest("no .text/.rodata section found")
        raw = bytearray(obj_b.read_bytes())
        content = sections[target]
        offset = raw.find(content)
        self.assertGreaterEqual(offset, 0)
        middle = offset + len(content) // 2
        raw[middle] ^= 0x01
        noisy = self.workdir / "bitflip.o"
        noisy.write_bytes(bytes(raw))
        verdict, detail = compare_objects.compare_objects(obj_a, noisy, FIXTURES / "c")
        self.assertEqual(verdict, compare_objects.DIFFERENT, detail)


if __name__ == "__main__":
    unittest.main()
