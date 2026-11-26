#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later

"""Build the disposable W4-05 Android graphics probe outside the app build."""

import argparse
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import zipfile


SOURCE = Path(__file__).resolve().parent
NDK_VERSION = "27.0.12077973"
BUILD_TOOLS_VERSION = "36.1.0"
HOST_TAG = {
    "win32": "windows-x86_64",
    "linux": "linux-x86_64",
    "darwin": "darwin-x86_64",
}


def run(*arguments, env=None):
    subprocess.run([os.fspath(argument) for argument in arguments], env=env, check=True)


def build(sdk, output, temporary):
    tools = sdk / "build-tools" / BUILD_TOOLS_VERSION
    compiler = sdk / "ndk" / NDK_VERSION / "toolchains" / "llvm" / "prebuilt"
    compiler = compiler / HOST_TAG[sys.platform] / "bin"
    executable = ".exe" if sys.platform == "win32" else ""
    script = ".bat" if sys.platform == "win32" else ""
    android_jar = sdk / "platforms" / "android-36" / "android.jar"

    library = temporary / "libdisplayprobe.so"
    run(compiler / ("clang" + executable), "--target=aarch64-linux-android26",
        "-shared", "-fPIC", "-O2", "-Wall", "-Wextra", "-Werror",
        "-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384",
        SOURCE / "display_probe.c", "-landroid", "-llog", "-lEGL", "-lGLESv2",
        "-o", library)

    classes = temporary / "classes"
    classes.mkdir()
    run("javac", "--release", "8", "-classpath", android_jar, "-d", classes,
        *sorted((SOURCE / "java").rglob("*.java")))
    run(tools / ("d8" + script), "--min-api", "26", "--lib", android_jar,
        "--output", temporary, *sorted(classes.rglob("*.class")))

    unsigned = temporary / "unsigned.apk"
    aligned = temporary / "aligned.apk"
    run(tools / ("aapt2" + executable), "link", "-I", android_jar,
        "--manifest", SOURCE / "AndroidManifest.xml", "-o", unsigned)
    with zipfile.ZipFile(unsigned, "a") as apk:
        apk.write(temporary / "classes.dex", "classes.dex", zipfile.ZIP_DEFLATED)
        apk.write(library, "lib/arm64-v8a/libdisplayprobe.so", zipfile.ZIP_STORED)
    zipalign = tools / ("zipalign" + executable)
    run(zipalign, "-P", "16", "-f", "4", unsigned, aligned)

    keystore = temporary / "probe.p12"
    password_name = "W405_PROBE_KEY_PASSWORD"
    env = dict(os.environ)
    env[password_name] = secrets.token_hex(16)
    run("keytool", "-genkeypair", "-keystore", keystore, "-storetype", "PKCS12",
        "-storepass:env", password_name, "-dname", "CN=W4-05 disposable probe",
        "-alias", "probe", "-keyalg", "RSA", "-keysize", "2048", "-validity", "30",
        env=env)
    apksigner = tools / ("apksigner" + script)
    run(apksigner, "sign", "--ks", keystore, "--ks-pass", "env:" + password_name,
        "--v4-signing-enabled", "false", "--out", output, aligned, env=env)
    run(apksigner, "verify", output)
    run(zipalign, "-c", "-P", "16", "4", output)
    run(compiler / ("llvm-readelf" + executable), "--segments", "--wide", library)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    default_sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    parser.add_argument("--sdk", type=Path, default=default_sdk, required=not default_sdk)
    parser.add_argument("--out", type=Path, required=True)
    arguments = parser.parse_args()
    output = arguments.out.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="w405-display-probe-") as directory:
        build(arguments.sdk.resolve(), output, Path(directory))
    print(output)


if __name__ == "__main__":
    main()
