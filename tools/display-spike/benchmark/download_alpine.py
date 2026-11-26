"""Cache exact Alpine package inputs for the W4-05 isolated desktop fixture."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
from pathlib import Path
import re
import subprocess
import tempfile

CDN = "https://dl-cdn.alpinelinux.org/alpine/v3.23"


def verify_file(path, expected):
    with path.open("rb") as file:
        return hashlib.file_digest(file, "sha256").hexdigest() == expected


def cache_file(cache, url, expected):
    name = url.rsplit("/", 1)[-1]
    target = cache / name
    if target.is_file() and verify_file(target, expected):
        return
    with tempfile.NamedTemporaryFile(prefix=f"{name}.", dir=cache, delete=False) as temporary:
        pending = Path(temporary.name)
    try:
        subprocess.run(
            ["curl", "-4", "--http1.1", "-fsSL", "--proto", "=https",
             "--retry", "3", "--retry-all-errors", "--connect-timeout", "12",
             "--max-time", "300", "-o", pending, url],
            check=True,
        )
        if not verify_file(pending, expected):
            raise SystemExit(f"SHA-256 mismatch for {name}")
        pending.replace(target)
    finally:
        pending.unlink(missing_ok=True)


def locked_files(lock):
    for line in lock.read_text(encoding="ascii").splitlines():
        if not line or line.startswith("#"):
            continue
        digest, path = line.split()
        if not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise SystemExit(f"Invalid SHA-256 in {lock.name}")
        if not re.fullmatch(r"(?:main|community)/aarch64/[A-Za-z0-9._+-]+\.apk", path):
            raise SystemExit(f"Invalid Alpine package path in {lock.name}")
        yield f"{CDN}/{path}", digest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("cache", type=Path)
    parser.add_argument("lock", type=Path)
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    with ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(lambda item: cache_file(args.cache, *item), locked_files(args.lock)))


if __name__ == "__main__":
    main()
