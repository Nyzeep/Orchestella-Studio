"""PVM-05 judgement tests.

Standard library only (no pytest). Run from the repository root:

    python -m unittest discover -s tools/compiler-equivalence -p 'test_*.py'

Level-3/4 cases need `readelf` and ELF objects; on hosts where readelf cannot
parse the compiler's object format (e.g. PE/COFF), those cases skip with an
explicit message instead of failing.
"""

from __future__ import annotations

import shutil
import struct
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


def synthetic_object(
    machine: int = 183,
    text: bytes = b"\xc0\x03\x5f\xd6",           # aarch64 `ret`
    symbols: tuple[str, ...] = ("fixture_entry",),
    extra_sections: tuple[tuple[str, bytes], ...] = (),
) -> bytes:
    """A minimal ELF64 relocatable object .text + a global symbol per name.

    The static path must judge aarch64 objects, and a Windows/mingw host has
    no ELF compiler and no cross toolchain, so the fixtures are written byte
    by byte: header, section contents, section header table. `nm` reads the
    result like any other relocatable object, which is all the criterion needs.
    `extra_sections` exists to model the real cross.o/ref.o shape, where one
    side carries .eh_frame and the other does not.
    """
    names = [".text", *(name for name, _ in extra_sections), ".symtab", ".strtab", ".shstrtab"]
    shstrtab = b"\0"
    name_offset: dict[str, int] = {}
    for name in names:
        name_offset[name] = len(shstrtab)
        shstrtab += name.encode("ascii") + b"\0"

    strtab = b"\0"
    symbol_name_offset: dict[str, int] = {}
    for name in symbols:
        symbol_name_offset[name] = len(strtab)
        strtab += name.encode("ascii") + b"\0"

    body = bytearray()
    cursor = 64                                   # ELF header size
    text_offset = cursor
    body += text
    cursor += len(text)
    extra_offsets: list[tuple[str, int, int]] = []
    for name, content in extra_sections:
        while cursor % 8:
            body += b"\0"
            cursor += 1
        extra_offsets.append((name, cursor, len(content)))
        body += content
        cursor += len(content)
    while cursor % 8:
        body += b"\0"
        cursor += 1
    symtab_offset = cursor
    symtab = b"\0" * 24                           # STN_UNDEF
    for name in symbols:
        # st_name, st_info=(GLOBAL<<4)|FUNC, st_other, st_shndx=.text, value, size
        symtab += struct.pack("<IBBHQQ", symbol_name_offset[name], 0x12, 0, 1, 0, len(text))
    body += symtab
    cursor += len(symtab)
    strtab_offset = cursor
    body += strtab
    cursor += len(strtab)
    shstrtab_offset = cursor
    body += shstrtab
    cursor += len(shstrtab)
    while cursor % 8:
        body += b"\0"
        cursor += 1
    section_header_offset = cursor

    first_extra_index = 2
    symtab_index = first_extra_index + len(extra_sections)
    strtab_index = symtab_index + 1
    shstrtab_index = strtab_index + 1
    section_count = shstrtab_index + 1

    def section_header(name_index: int, sh_type: int, flags: int, offset: int, size: int,
                       link: int = 0, info: int = 0, align: int = 1, entsize: int = 0) -> bytes:
        return struct.pack("<IIQQQQIIQQ", name_index, sh_type, flags, 0, offset, size,
                           link, info, align, entsize)

    headers = [b"\0" * 64]                        # SHT_NULL
    headers.append(section_header(name_offset[".text"], 1, 6, text_offset, len(text), align=4))
    for name, offset, size in extra_offsets:
        headers.append(section_header(name_offset[name], 1, 2, offset, size, align=8))
    headers.append(section_header(name_offset[".symtab"], 2, 0, symtab_offset, len(symtab),
                                  link=strtab_index, info=1, align=8, entsize=24))
    headers.append(section_header(name_offset[".strtab"], 3, 0, strtab_offset, len(strtab)))
    headers.append(section_header(name_offset[".shstrtab"], 3, 0, shstrtab_offset, len(shstrtab)))

    ident = bytes([0x7F]) + b"ELF" + bytes([2, 1, 1]) + bytes(9)
    header = ident + struct.pack(
        "<HHIQQQIHHHHHH",
        1,                 # e_type = ET_REL
        machine,           # e_machine
        1,                 # e_version
        0,                 # e_entry
        0,                 # e_phoff
        section_header_offset,
        0,                 # e_flags
        64,                # e_ehsize
        0,                 # e_phentsize
        0,                 # e_phnum
        64,                # e_shentsize
        section_count,
        shstrtab_index,
    )
    return header + bytes(body) + b"".join(headers)


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


class StaticComparisonTest(unittest.TestCase):
    """The linker-free path: .text bytes + external defined symbol names.

    It exists because the four-level judgement cannot reach a verdict for a
    cross-target object on a Windows/mingw host: both candidates fail to link,
    so the old code answered INCONCLUSIVE and the equivalence claim had to be
    patched up by hand. These two criteria need no linker and no readelf, so
    the fixtures here are hand-built aarch64 objects.
    """

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="pvm12-static-")
        self.workdir = Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)
        self.nm = shutil.which("nm")
        if self.nm is None:
            self.skipTest("nm is missing; the symbol criterion cannot run")

    def write(self, name: str, content: bytes) -> Path:
        path = self.workdir / name
        path.write_bytes(content)
        return path

    def test_equivalent_aarch64_pair_ignores_a_missing_eh_frame(self):
        # The real cross.o/ref.o shape: identical .text, one side lacks the
        # unwinding tables. That gap is a section-set difference, not a code
        # difference, and the linker-free path must not turn it into DIFFERENT.
        a = self.write("cross.o", synthetic_object())
        b = self.write("ref.o", synthetic_object(
            extra_sections=((".eh_frame", b"\0" * 24),)))

        verdict, detail = compare_objects.compare_objects_static(a, b)

        self.assertEqual(verdict, compare_objects.EQUIVALENT, detail)
        self.assertIn("sha256=", detail)
        self.assertIn("external symbols 1/1", detail)
        self.assertIn("aarch64", detail)

    def test_different_text_is_different(self):
        a = self.write("a.o", synthetic_object())
        b = self.write("b.o", synthetic_object(text=b"\xc0\x03\x5f\xd6\x1f\x20\x03\xd5"))

        verdict, detail = compare_objects.compare_objects_static(a, b)

        self.assertEqual(verdict, compare_objects.DIFFERENT, detail)
        self.assertIn(".text differs", detail)
        self.assertIn("sha256=", detail)
        self.assertIn("4 B", detail)
        self.assertIn("8 B", detail)

    def test_different_symbol_set_is_different(self):
        a = self.write("a.o", synthetic_object())
        b = self.write("b.o", synthetic_object(symbols=("fixture_entry", "fixture_helper")))

        verdict, detail = compare_objects.compare_objects_static(a, b)

        self.assertEqual(verdict, compare_objects.DIFFERENT, detail)
        self.assertIn("symbol sets differ", detail)
        self.assertIn("1", detail)
        self.assertIn("2", detail)

    def test_architecture_mismatch_is_inconclusive_not_different(self):
        a = self.write("aarch64.o", synthetic_object(machine=183))
        b = self.write("x86_64.o", synthetic_object(machine=62))

        verdict, detail = compare_objects.compare_objects_static(a, b)

        self.assertEqual(verdict, compare_objects.INCONCLUSIVE, detail)
        self.assertIn("not comparable", detail)
        self.assertIn("aarch64", detail)
        self.assertIn("x86-64", detail)

    def test_expected_arch_turns_a_foreign_object_into_different(self):
        # The 608-byte x86-64 fake-engine stub, caught as a wrong-machine
        # object rather than as a code difference.
        a = self.write("stub.o", synthetic_object(machine=62, symbols=("pvm_fake_engine_answer",)))
        b = self.write("ref.o", synthetic_object(machine=183))

        verdict, detail = compare_objects.compare_objects_static(
            a, b, expect_arch="aarch64")

        self.assertEqual(verdict, compare_objects.DIFFERENT, detail)
        self.assertIn("x86-64", detail)
        self.assertIn("expected aarch64", detail)

    def test_expected_arch_keeps_a_matching_pair_equivalent(self):
        a = self.write("a.o", synthetic_object(machine=183))
        b = self.write("b.o", synthetic_object(machine=183))

        verdict, detail = compare_objects.compare_objects_static(
            a, b, expect_arch="aarch64")

        self.assertEqual(verdict, compare_objects.EQUIVALENT, detail)

    def test_cli_expect_arch_exits_one_for_a_foreign_object(self):
        a = self.write("stub.o", synthetic_object(machine=62))
        b = self.write("ref.o", synthetic_object(machine=183))

        code = compare_objects.main(
            ["--a", str(a), "--b", str(b), "--no-link", "--expect-arch", "aarch64"])

        self.assertEqual(1, code)

    def test_cli_rejects_an_unknown_expect_arch(self):
        a = self.write("a.o", synthetic_object())
        b = self.write("b.o", synthetic_object())

        code = compare_objects.main(
            ["--a", str(a), "--b", str(b), "--no-link", "--expect-arch", "vax"])

        self.assertEqual(3, code)

    # --- producer fingerprint (PVM-13) -------------------------------------
    #
    # The static criteria (.text + symbols) cannot tell WHO produced an
    # object: the cxx-3 round judged two locally re-compiled objects
    # EQUIVALENT while the real remote job had failed. The .comment section
    # names the producing compiler, so an expected-producer substring turns
    # "equivalent bytes" into "equivalent bytes from the expected compiler".

    def test_expect_producer_accepts_the_matching_comment(self):
        a = self.write("cross.o", synthetic_object(extra_sections=(
            (".comment", b"Android (7495987, based on r56) clang version 22.1.8\x00"),)))
        b = self.write("ref.o", synthetic_object(extra_sections=(
            (".comment", b"Alpine clang version 22.1.3\x00"),
            (".eh_frame", b"\0" * 24))))

        verdict, detail = compare_objects.compare_objects_static(
            a, b, expect_producer="22.1.8")

        self.assertEqual(verdict, compare_objects.EQUIVALENT, detail)

    def test_expect_producer_rejects_a_foreign_producer(self):
        # The exact shape that fooled the cxx round: both objects carry the
        # chroot toolchain's fingerprint because distcc recompiled locally.
        a = self.write("cross.o", synthetic_object(extra_sections=(
            (".comment", b"Alpine clang version 22.1.3\x00"),)))
        b = self.write("ref.o", synthetic_object(extra_sections=(
            (".comment", b"Alpine clang version 22.1.3\x00"),)))

        verdict, detail = compare_objects.compare_objects_static(
            a, b, expect_producer="22.1.8")

        self.assertEqual(verdict, compare_objects.DIFFERENT)
        self.assertIn("not produced by the expected compiler", detail)
        self.assertIn("22.1.8", detail)

    def test_expect_producer_fails_closed_without_a_comment(self):
        # A missing .comment must not pass silently: "cannot verify" is
        # a rejection, never a pass.
        a = self.write("cross.o", synthetic_object())
        b = self.write("ref.o", synthetic_object(extra_sections=(
            (".comment", b"clang version 22.1.8\x00"),)))

        verdict, detail = compare_objects.compare_objects_static(
            a, b, expect_producer="22.1.8")

        self.assertEqual(verdict, compare_objects.DIFFERENT)
        self.assertIn(".comment", detail)

    def test_expect_producer_checks_only_the_object_under_test(self):
        # --a is the object the payload produced; the local reference may
        # carry another toolchain's .comment and must not be held to the
        # payload's fingerprint (that is the real cross.o/ref.o shape).
        a = self.write("cross.o", synthetic_object(extra_sections=(
            (".comment", b"clang version 22.1.8\x00"),)))
        b = self.write("ref.o", synthetic_object(extra_sections=(
            (".comment", b"Alpine clang version 22.1.3\x00"),)))

        verdict, detail = compare_objects.compare_objects_static(
            a, b, expect_producer="22.1.8")

        self.assertEqual(verdict, compare_objects.EQUIVALENT, detail)

    def test_cli_rejects_expect_producer_without_no_link(self):
        a = self.write("a.o", synthetic_object())
        b = self.write("b.o", synthetic_object())

        code = compare_objects.main(
            ["--a", str(a), "--b", str(b), "--expect-producer", "22.1.8"])

        self.assertEqual(3, code)

    def test_cli_expect_producer_rejects_a_foreign_object(self):
        a = self.write("cross.o", synthetic_object(extra_sections=(
            (".comment", b"Alpine clang version 22.1.3\x00"),)))
        b = self.write("ref.o", synthetic_object(extra_sections=(
            (".comment", b"Alpine clang version 22.1.3\x00"),)))

        code = compare_objects.main(
            ["--a", str(a), "--b", str(b), "--no-link",
             "--expect-producer", "22.1.8"])

        self.assertEqual(1, code)

    def test_non_elf_is_inconclusive(self):
        a = self.write("a.o", synthetic_object())
        notes = self.write("notes.txt", b"not an object")

        verdict, detail = compare_objects.compare_objects_static(a, notes)

        self.assertEqual(verdict, compare_objects.INCONCLUSIVE, detail)
        self.assertIn("not an ELF", detail)

    def test_missing_object_is_inconclusive(self):
        a = self.write("a.o", synthetic_object())

        verdict, detail = compare_objects.compare_objects_static(a, self.workdir / "gone.o")

        self.assertEqual(verdict, compare_objects.INCONCLUSIVE, detail)
        self.assertIn("does not exist", detail)

    def test_missing_nm_is_inconclusive(self):
        a = self.write("a.o", synthetic_object())
        b = self.write("b.o", synthetic_object())
        original = compare_objects.find_tool
        compare_objects.find_tool = lambda *names: None
        try:
            verdict, detail = compare_objects.compare_objects_static(a, b)
        finally:
            compare_objects.find_tool = original

        self.assertEqual(verdict, compare_objects.INCONCLUSIVE, detail)
        self.assertIn("nm", detail)

    def test_cli_no_link_prints_equivalent(self):
        a = self.write("a.o", synthetic_object())
        b = self.write("b.o", synthetic_object())

        code = compare_objects.main(["--a", str(a), "--b", str(b), "--no-link"])

        self.assertEqual(0, code)

    def test_cli_no_link_does_not_need_fixtures(self):
        a = self.write("a.o", synthetic_object())
        b = self.write("b.o", synthetic_object(text=b"\x1f\x20\x03\xd5"))

        code = compare_objects.main(["--a", str(a), "--b", str(b), "--no-link"])

        self.assertEqual(1, code)

    def test_cli_still_requires_fixtures_when_linking(self):
        a = self.write("a.o", synthetic_object())
        b = self.write("b.o", synthetic_object())

        code = compare_objects.main(["--a", str(a), "--b", str(b)])

        self.assertEqual(3, code)


if __name__ == "__main__":
    unittest.main()
