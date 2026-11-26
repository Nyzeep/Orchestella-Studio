"""Overlay benchmark programs onto the disposable Alpine initramfs."""
import argparse
import gzip
import io
from pathlib import Path
import shutil
import stat

HERE = Path(__file__).resolve().parent


def archive(files):
    data = io.BytesIO()
    for index, (dest, payload, mode) in enumerate([*files, ("TRAILER!!!", b"", 0)], 1):
        name = dest.encode("ascii") + b"\x00"
        fields = (index, mode, 0, 0, 1, 0, len(payload), 0, 0, 0, 0, len(name), 0)
        data.write(b"070701" + "".join(f"{value:08x}" for value in fields).encode("ascii"))
        data.write(name)
        data.write(b"\x00" * ((-data.tell()) % 4))
        data.write(payload)
        data.write(b"\x00" * ((-data.tell()) % 4))
    return gzip.compress(data.getvalue(), mtime=0)


def script(path):
    return path.read_bytes().replace(b"\r\n", b"\n")


def elf_aarch64(path):
    binary = path.read_bytes()
    if binary[:6] != b"\x7fELF\x02\x01" or binary[18:20] != b"\xb7\x00":
        raise ValueError("expected a little-endian AArch64 executable")
    return binary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", type=Path, required=True)
    parser.add_argument("--capture", type=Path, required=True)
    parser.add_argument("--drag", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.base.resolve() == args.output.resolve():
        parser.error("output must differ from base")
    executable = stat.S_IFREG | 0o755
    overlay = [
        ("init", script(HERE / "init"), executable),
        ("usr/local/bin/w405d", script(HERE / "guest.py"), executable),
        ("usr/local/bin/workload", script(HERE / "workload.sh"), executable),
        ("usr/local/bin/capture", elf_aarch64(args.capture), executable),
        ("usr/local/bin/w405-drag", elf_aarch64(args.drag), executable),
    ]
    with args.base.open("rb") as base, args.output.open("wb") as output:
        shutil.copyfileobj(base, output)
        output.write(archive(overlay))


if __name__ == "__main__":
    main()
