#!/usr/bin/env python
"""C200 fixture generator - F-MTTCG-C200 (doc 2026-11-04 section 5.1).

N_FILES C files x FUNCS_PER_FILE functions, ~20 lines each, file-local static
state (anti-DCE), files share only fixture.h (types + declarations, no inline
functions). Deterministic (fixed LCG seed) so identical bytes on regeneration.
Only .c compilation is measured: clang -c -O1 (external linkage => nothing is
dropped at -O1 without LTO).

usage: python gen_c200.py <outdir> [N_FILES] [FUNCS_PER_FILE]
writes: outdir/f000.c.. fNNN.c, fixture.h, Makefile, MANIFEST.sha256
"""
import hashlib
import os
import sys


def gen(outdir, n_files=200, funcs=10):
    os.makedirs(outdir, exist_ok=True)
    lines_total = 0
    manifest = []

    header = """#ifndef FIXTURE_H
#define FIXTURE_H
/* F-MTTCG-C200 shared header: declarations only, no inline functions. */
#define C200_ARR 16
#define C200_ITERS 8
typedef struct { int a[C200_ARR]; int base; } c200_state;
int c200_fn(int fi, int si, int x);
#endif
"""
    with open(os.path.join(outdir, "fixture.h"), "w", newline="\n") as f:
        f.write(header)
        lines_total += header.count("\n")
    manifest.append(("fixture.h", header))

    for fi in range(n_files):
        parts = []
        parts.append('/* C200 generated fixture file %03d (deterministic, do not edit) */\n' % fi)
        parts.append('#include "fixture.h"\n\n')
        # file-local static state (anti-DCE): one state table per function
        for si in range(funcs):
            seed = (fi * 1000003 + si * 97 + 11) & 0x7FFFFFFF
            vals = []
            s = seed
            for _ in range(16):
                s = (s * 1103515245 + 12345) & 0x7FFFFFFF
                vals.append(s % 1000)
            arr = ", ".join(str(v) for v in vals)
            body = (
                "static c200_state g_%d_%d = {{%s}, %d};\n\n"
                % (fi, si, arr, seed % 89 + 1)
                + "int c200_fn_%d_%d(int x)\n" % (fi, si)
                + "{\n"
                + "    c200_state *st = &g_%d_%d;\n" % (fi, si)
                + """    int acc = x ^ st->base;
    for (int k = 0; k < C200_ITERS; k++) {
        int i0 = (acc + k) % C200_ARR;
        int i1 = (acc * 3 + k * 7 + 5) % C200_ARR;
        int i2 = (st->base + k * 13) % C200_ARR;
        int v0 = st->a[i0];
        int v1 = st->a[i1];
        int v2 = st->a[i2];
        if (v0 > v1)
            acc += (v0 - v1) + (v2 >> (k % 7));
        else
            acc -= (v1 - v0) | (v2 << (k % 5));
        if (acc & 1)
            acc = (acc ^ (v0 << 1)) + (i2 - i0);
        else
            acc = (acc + v1) ^ (i1 + k);
        acc &= 0x3FFFFFFF;
    }
    return acc;
}
"""
            )
            parts.append(body)

        src = "".join(parts)
        name = "f%03d.c" % fi
        path = os.path.join(outdir, name)
        with open(path, "w", newline="\n") as f:
            f.write(src)
        lines_total += src.count("\n")
        manifest.append((name, src))

    makefile = """# F-MTTCG-C200 fixture makefile
CC      ?= clang
CFLAGS  ?= -c -O1
SRCS := $(wildcard f*.c)
OBJS := $(SRCS:.c=.o)
all: $(OBJS)
%.o: %.c fixture.h
\t$(CC) $(CFLAGS) -o $@ $<
clean:
\trm -f $(OBJS)
.PHONY: all clean
"""
    with open(os.path.join(outdir, "Makefile"), "w", newline="\n") as f:
        f.write(makefile)
        lines_total += makefile.count("\n")

    mpath = os.path.join(outdir, "MANIFEST.sha256")
    with open(mpath, "w", newline="\n") as f:
        for name, content in manifest:
            h = hashlib.sha256(content.encode()).hexdigest()
            f.write(f"{h}  {name}\n")

    total = n_files * funcs
    print(f"generated: {n_files} files x {funcs} funcs = {total} functions, "
          f"lines={lines_total}")
    print(f"manifest -> {mpath}")


if __name__ == "__main__":
    outdir = sys.argv[1] if len(sys.argv) > 1 else "c200"
    n = int(sys.argv[2]) if len(sys.argv) > 2 else 200
    fpf = int(sys.argv[3]) if len(sys.argv) > 3 else 10
    gen(outdir, n, fpf)
