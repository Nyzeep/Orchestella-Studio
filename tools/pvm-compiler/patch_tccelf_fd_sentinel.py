#!/usr/bin/env python3
"""QA-only TinyCC sink patch for the DV-PVM-01 device gate.

The Microdroid guest denies every path-based write the compiler could use:
open() with O_CREAT through a /proc/self/fd magic link fails with EACCES,
and all standard writable directories are denied for the payload domain.
tcc_write_elf_file therefore cannot open its output file by path.

This patch teaches tcc_write_elf_file an fd sentinel: when the caller passes
"::fd::<n>" as the filename, tcc writes the object to a dup() of that
descriptor (fdopen) instead of opening any path. The caller owns a memfd, so
the object never touches visible storage. Applied by tools/pvm-compiler/
build_probe.sh to its sanitized TinyCC copy only; the upstream tarball and
every other build path stay untouched.
"""

from __future__ import annotations

import sys

SENTINEL = '''    if (filename != NULL && strncmp(filename, "::fd::", 6) == 0) {
        int dup_fd = dup(atoi(filename + 6));
        if (dup_fd < 0 || (f = fdopen(dup_fd, "wb")) == NULL)
            return tcc_error_noabort("could not fdopen '%s: %s'", filename, strerror(errno));
        if (s1->verbose)
            printf("<- %s\\n", filename);
#ifdef TCC_TARGET_COFF
        if (s1->output_format == TCC_OUTPUT_FORMAT_COFF)
            ret = tcc_output_coff(s1, f);
        else
#endif
        if (s1->output_format == TCC_OUTPUT_FORMAT_ELF)
            ret = tcc_output_elf(s1, f, phnum, phdr);
        else
            ret = tcc_output_binary(s1, f);
        if (ret == 0 && fwrite("PVMMARK", 1, 7, f) != 7)
            ret = tcc_error_noabort("marker write failed: %s", strerror(errno));
        if (ret == 0 && fflush(f) != 0)
            ret = tcc_error_noabort("marker flush failed: %s", strerror(errno));
        fclose(f);
        return ret;
    }
'''

ANCHOR = "    unlink(filename);"


def main(path: str) -> int:
    with open(path, encoding="utf-8") as handle:
        source = handle.read()
    if 'strncmp(filename, "::fd::", 6)' in source:
        print("tccelf fd-sentinel patch already applied")
        return 0
    if ANCHOR not in source:
        print("FAIL tcc_write_elf_file anchor missing", file=sys.stderr)
        return 1
    source = source.replace(ANCHOR, SENTINEL + ANCHOR, 1)
    with open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write(source)
    print("tccelf fd-sentinel patch applied")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
