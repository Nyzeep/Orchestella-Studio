"""Build the isolated W4-05 BAR2 probe from a payload-bearing APK and the Android SDK."""
import argparse
import gzip
import hashlib
import io
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
from zipfile import ZIP_STORED, ZipFile

HERE = Path(__file__).resolve().parent
QEMU = "lib/arm64-v8a/libdroidlayer_qemu.so"
HOST_MAP = "lib/arm64-v8a/libw405a_host_map.so"
IMAGE = "Image"
MIN_SDK = "26"


def run(*args):
    subprocess.run([os.fspath(arg) for arg in args], check=True)


def build_guest(compiler, output):
    source = HERE / "guest_bar_probe.c"
    command = [compiler]
    if os.name == "nt":
        command = ["wsl", "--exec", compiler]
        source = subprocess.check_output(
            ["wsl", "--exec", "wslpath", "-a", os.fspath(source)], text=True).strip()
        output = subprocess.check_output(
            ["wsl", "--exec", "wslpath", "-a", os.fspath(output)], text=True).strip()
    run(*command, "-static", "-O2", "-Wall", "-Wextra", "-Werror", source, "-o", output)


def executable(directory, name, windows_suffix=".exe"):
    return directory / (name + windows_suffix if os.name == "nt" else name)


def extract_verified(source, entry, destination, expected):
    digest = hashlib.sha256()
    with source.open(entry) as input_file, destination.open("wb") as output_file:
        while block := input_file.read(1024 * 1024):
            digest.update(block)
            output_file.write(block)
    if digest.hexdigest() != expected:
        raise ValueError(f"runtime payload SHA-256 mismatch for {entry}")
    print(f"{entry} sha256={expected}", flush=True)


def unpack_payload(source_apk, image, qemu):
    with ZipFile(source_apk) as source:
        raw_checksums = source.read("assets/runtime-artifacts-v1.sha256").decode("ascii")
        checksums = {entry: digest for digest, entry in map(str.split, raw_checksums.splitlines())}
        extract_verified(source, f"assets/{IMAGE}", image, checksums[IMAGE])
        extract_verified(source, QEMU, qemu, checksums[QEMU])


def initramfs(guest_probe):
    archive = io.BytesIO()

    def add(ino, name, mode=0, data=b"", major=0, minor=0):
        encoded = name.encode("ascii") + b"\0"
        values = (ino, mode, 0, 0, 1, 0, len(data), 0, 0, major, minor, len(encoded), 0)
        archive.write(b"070701" + "".join(f"{value:08x}" for value in values).encode("ascii"))
        archive.write(encoded)
        archive.write(b"\0" * ((-archive.tell()) % 4))
        archive.write(data)
        archive.write(b"\0" * ((-archive.tell()) % 4))

    add(1, "dev", stat.S_IFDIR | 0o755)
    add(2, "dev/console", stat.S_IFCHR | 0o600, major=5, minor=1)
    add(3, "sys", stat.S_IFDIR | 0o755)
    add(4, "init", stat.S_IFREG | 0o755, guest_probe)
    add(5, "TRAILER!!!")
    return gzip.compress(archive.getvalue(), mtime=0)


def build(args):
    build_tools = args.sdk / "build-tools" / args.build_tools
    android_jar = args.sdk / "platforms/android-36/android.jar"
    host = "windows-x86_64" if os.name == "nt" else "linux-x86_64"
    ndk_bin = args.sdk / "ndk" / args.ndk / "toolchains/llvm/prebuilt" / host / "bin"
    clang = executable(ndk_bin, "aarch64-linux-android26-clang", ".cmd")
    aapt = executable(build_tools, "aapt2")
    zipalign = executable(build_tools, "zipalign")
    d8 = executable(build_tools, "d8", ".bat")
    apksigner = executable(build_tools, "apksigner", ".bat")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="w405a-", dir=args.output.parent) as folder:
        work = Path(folder)
        assets = work / "assets"
        classes = work / "classes"
        assets.mkdir()
        classes.mkdir()
        qemu = work / "libdroidlayer_qemu.so"
        unpack_payload(args.source_apk, assets / IMAGE, qemu)
        init = work / "init"
        host_map = work / "libw405a_host_map.so"
        build_guest(args.guest_cc, init)
        (assets / "probe-init.cpio.gz").write_bytes(initramfs(init.read_bytes()))
        run(clang, "-shared", "-fPIC", "-O2", "-Wall", "-Wextra", "-Werror",
            "-Wl,-z,max-page-size=16384", HERE / "host_map.c", "-o", host_map)
        run("javac", "--release", "11", "-classpath", android_jar, "-d", classes,
            HERE / "TrackAProbe.java")
        run(d8, "--min-api", MIN_SDK, "--lib", android_jar, "--output", work,
            next(classes.rglob("TrackAProbe.class")))
        apk = work / "unsigned.apk"
        run(aapt, "link", "--manifest", HERE / "AndroidManifest.xml", "-I", android_jar,
            "-A", assets, "-o", apk)
        with ZipFile(apk, "a", compression=ZIP_STORED) as package:
            package.write(work / "classes.dex", "classes.dex")
            package.write(qemu, QEMU)
            package.write(host_map, HOST_MAP)
        aligned = work / "aligned.apk"
        run(zipalign, "-P", "16", "-f", "4", apk, aligned)
        keystore = work / "probe.jks"
        run("keytool", "-genkeypair", "-keystore", keystore, "-storepass", "android",
            "-keypass", "android", "-alias", "probe", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=W4-05 Track A local probe", "-validity", "30")
        run(apksigner, "sign", "--ks", keystore, "--ks-pass", "pass:android",
            "--out", args.output, aligned)
        run(apksigner, "verify", args.output)
        run(zipalign, "-c", "-P", "16", "4", args.output)
        if args.keep_guest:
            shutil.copyfile(assets / "probe-init.cpio.gz", args.output.with_suffix(".cpio.gz"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    default_sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    parser.add_argument("--sdk", type=Path, default=default_sdk, required=default_sdk is None)
    parser.add_argument("--build-tools", default="36.1.0")
    parser.add_argument("--ndk", default="27.0.12077973")
    parser.add_argument("--guest-cc", default="aarch64-linux-gnu-gcc",
                        help="Linux arm64 cross compiler (run under default WSL distro on Windows)")
    parser.add_argument("--source-apk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--keep-guest", action="store_true", help="also export the probe initramfs")
    args = parser.parse_args()
    args.output = args.output.resolve()
    build(args)


if __name__ == "__main__":
    main()
