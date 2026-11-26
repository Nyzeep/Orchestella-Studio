"""Build a local W4-05 benchmark APK using the pinned Raw or ZRLE sources."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from zipfile import ZIP_STORED, ZipFile

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
APP_MAIN = REPO / "app/src/main"
JAVA_APP = APP_MAIN / "java/io/github/nyzeep/orchestella"
CONTRACT = Path("runtime/contract/src/main/java/io/github/nyzeep/orchestella/runtime/contract")
W4_02 = "5a04db43"
PKG = "io.github.nyzeep.orchestella.spike.w405bench"
MIN_SDK = "35"
sys.path.insert(0, str(HERE.parent / "track-a"))
from build_probe import QEMU, executable, run, unpack_payload


def manifest(profile):
    package = PKG if profile == "raw" else PKG + ".zrle"
    return f"""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}">
    <uses-sdk android:minSdkVersion="{MIN_SDK}" android:targetSdkVersion="36"/>
    <application android:label="Orchestella W4-05 benchmark"
        android:allowBackup="false" android:debuggable="true"
        android:extractNativeLibs="true"
        android:theme="@android:style/Theme.Material.NoActionBar.Fullscreen">
        <activity android:name="{PKG}.BenchActivity" android:screenOrientation="landscape"
            android:exported="true"/>
    </application>
</manifest>
"""


def rfb_factory(profile):
    supports = 'mode.equals("raw") || mode.equals("ivshmem")' if profile == "raw" else 'mode.equals("zrle")'
    options = "RfbDisplayChannel.Options.DEFAULT" if profile == "raw" else (
        "new RfbDisplayChannel.Options(64, 15_000, 30_000, true)"
    )
    return f"""package {PKG};

import io.github.nyzeep.orchestella.runtime.contract.display.RfbDisplayChannel;

final class RfbSource {{
    static boolean supports(String mode) {{ return {supports}; }}
    static RfbDisplayChannel open(RfbDisplayChannel.StreamConnector connector, String token) {{
        return new RfbDisplayChannel(connector, token, RfbDisplayChannel.InputCapabilities.NONE, {options});
    }}
}}
"""


def java_sources(work, profile):
    sources = [
        *(HERE / name for name in ("BenchActivity.java", "BenchFrames.java", "BenchRun.java")),
        *(JAVA_APP / name for name in (
            "DesktopPresentationGate.java", "DoubleBufferedRenderer.java",
            "NativeGlesSurfacePresenter.java", "ViewportAspectCalculator.java")),
        *(REPO / CONTRACT / name for name in (
            "InputEvent.java", "DisplayChannel.java", "display/DisplayViewportBudget.java")),
    ]
    names = ["RfbDisplayChannel.java", "RfbProtocol.java"]
    if profile == "raw":
        sources.extend(REPO / CONTRACT / "display" / name for name in names)
    else:
        for name in [*names, "RfbZrleDecoder.java"]:
            path = CONTRACT / "display" / name
            target = work / name
            target.write_bytes(subprocess.check_output(
                ["git", "-C", str(REPO), "show", f"{W4_02}:{path.as_posix()}"]))
            sources.append(target)
    factory = work / "RfbSource.java"
    factory.write_text(rfb_factory(profile), encoding="utf-8")
    return [*sources, factory]


def build(args):
    android_jar = args.sdk / "platforms/android-36/android.jar"
    build_tools = args.sdk / "build-tools" / args.build_tools
    host = "windows-x86_64" if os.name == "nt" else "linux-x86_64"
    clang_dir = args.sdk / "ndk" / args.ndk / "toolchains/llvm/prebuilt" / host / "bin"
    clang = executable(clang_dir, "aarch64-linux-android26-clang", ".cmd")
    clang_cpp = executable(clang_dir, "aarch64-linux-android26-clang++", ".cmd")
    aapt2 = executable(build_tools, "aapt2")
    zipalign = executable(build_tools, "zipalign")
    apksigner = executable(build_tools, "apksigner", ".bat")
    d8 = executable(build_tools, "d8", ".bat")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="w405bench-", dir=args.output.parent) as tmp:
        work = Path(tmp)
        assets = work / "assets"
        assets.mkdir()
        classes = work / "classes"
        classes.mkdir()
        qemu = work / "libdroidlayer_qemu.so"
        unpack_payload(args.source_apk, assets / "Image", qemu)
        shutil.copyfile(args.guest_initrd, assets / "desktop.cpio.gz")
        print(f"profile={args.profile}", flush=True)

        cpp = APP_MAIN / "cpp"
        gles = work / "liborchestella_gles.so"
        mapping = work / "libw405bench_map.so"
        align = "-Wl,-z,max-page-size=16384"
        run(clang_cpp, "-std=c++17", "-O2", "-shared", "-fPIC", align, "-static-libstdc++",
            cpp / "native_gles_presenter.cpp", cpp / "graphics/native_gles_presenter.cpp",
            "-landroid", "-lEGL", "-lGLESv3", "-llog", "-o", gles)
        run(clang, "-O2", "-Wall", "-Wextra", "-Werror", "-shared", "-fPIC", align,
            HERE / "mapped_frames.c", "-o", mapping)
        run("javac", "--release", "17", "-encoding", "UTF-8", "-classpath", android_jar,
            "-sourcepath", REPO / "runtime/contract/src/main/java", "-d", classes,
            *java_sources(work, args.profile))
        run(d8, "--min-api", MIN_SDK, "--lib", android_jar, "--output", work,
            *sorted(classes.rglob("*.class")))

        android_manifest = work / "AndroidManifest.xml"
        android_manifest.write_text(manifest(args.profile), encoding="utf-8")
        unsigned = work / "unsigned.apk"
        run(aapt2, "link", "--manifest", android_manifest, "-I", android_jar,
            "-A", assets, "-o", unsigned)
        with ZipFile(unsigned, "a", compression=ZIP_STORED) as apk:
            apk.write(work / "classes.dex", "classes.dex")
            apk.write(qemu, QEMU)
            for library in (gles, mapping):
                apk.write(library, f"lib/arm64-v8a/{library.name}")
        aligned = work / "aligned.apk"
        run(zipalign, "-P", "16", "-f", "4", unsigned, aligned)

        keystore = work / "probe.jks"
        run("keytool", "-genkeypair", "-keystore", keystore, "-storepass", "android",
            "-keypass", "android", "-alias", "probe", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=W4-05 local benchmark", "-validity", "30")
        run(apksigner, "sign", "--ks", keystore, "--ks-pass", "pass:android",
            "--out", args.output, aligned)
        run(apksigner, "verify", args.output)
        run(zipalign, "-c", "-P", "16", "4", args.output)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    parser.add_argument("--sdk", type=Path, default=sdk, required=sdk is None)
    parser.add_argument("--build-tools", default="36.1.0")
    parser.add_argument("--ndk", default="27.0.12077973")
    parser.add_argument("--profile", choices=("raw", "zrle"), required=True)
    parser.add_argument("--source-apk", type=Path, required=True)
    parser.add_argument("--guest-initrd", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output = args.output.resolve()
    build(args)
