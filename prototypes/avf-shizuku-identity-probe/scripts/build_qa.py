"""Offline QA-only assembly. No Gradle, network, signing, installation, or device calls.

Default is AIDL/javac compile-only. --apk and explicit version values are reserved
for the coordinator. Inputs are fixed 13.1.5 cache entries; all writes are in A's
current approved external root. Existing output labels are never overwritten.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import zipfile

BASE = Path("D:/Development project(开发项目)/Orchestella开发目录")
SOURCE = BASE / "Orchestella_dev/prototypes/avf-shizuku-identity-probe"
OUT = BASE / "Orchestella/artifacts/avf-shizuku-device-20261016/batch-01/research-auth-device"
DEADLINE_OUT = BASE / "Orchestella/artifacts/avf-shizuku-device-20261016/batch-01/resume-01/qa-deadline-fix"
SDK = Path("D:/AndroidSDK")
JDK = Path("D:/JDK17")
CACHE_ENV = "ORCHESTELLA_SHIZUKU_CACHE"
PINS = {
    "api": "1f760c56493bdbc01462930e75bb325033b36fe2",
    "provider": "4135b99cae9362fe2f63380230d04a9bcba9be3f",
    "aidl": "457504c277d796adea698b80b4e765f98dd0799a",
    "shared": "8c4e73ce466cd27aff10565d2ec49809c258f974",
}


def digest(path):
    return {"bytes": path.stat().st_size, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def resolve_cache(value):
    if not value:
        raise ValueError("supply --gradle-cache or " + CACHE_ENV)
    cache = Path(value)
    if not cache.is_absolute() or not cache.is_dir():
        raise ValueError("Shizuku cache must be an existing absolute directory")
    for name, pin in PINS.items():
        if not (cache / name / "13.1.5" / pin / (name + "-13.1.5.aar")).is_file():
            raise ValueError("Shizuku cache must contain all four pinned 13.1.5 AARs")
    return cache


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--label", required=True)
    parser.add_argument("--apk", action="store_true")
    parser.add_argument("--version-code", type=int)
    parser.add_argument("--version-name")
    parser.add_argument("--output-root", type=Path, default=OUT)
    parser.add_argument("--gradle-cache", type=Path, default=os.environ.get(CACHE_ENV),
                        help="existing dev.rikka.shizuku artifact-cache directory; no downloads")
    options = parser.parse_args()
    try:
        cache = resolve_cache(options.gradle_cache)
    except ValueError as error:
        parser.error(str(error))
    if not re.fullmatch(r"[a-z0-9-]+", options.label):
        parser.error("a unique simple evidence label is required")
    if options.apk and (not options.version_code or not options.version_name):
        parser.error("coordinator must supply both QA version values")
    if options.output_root.resolve() not in (OUT.resolve(), DEADLINE_OUT.resolve()):
        parser.error("output root must be an approved QA batch root")
    work = options.output_root / options.label
    work.mkdir(parents=True, exist_ok=False)
    preserved = (DEADLINE_OUT.parent / "preservation/qa-deadline-fix" if options.output_root.resolve()
                 == DEADLINE_OUT.resolve() else OUT / "preservation") / options.label
    preserved.mkdir(parents=True, exist_ok=False)
    evidence = {"scope": "isolated QA only", "level": "compile-only", "commands": [],
                "source": {}, "libraries": {}, "signed": False, "installed": False}
    env = dict(os.environ)
    if not Path("W:/").samefile(BASE / "Orchestella_dev") or not Path("V:/").samefile(BASE / "Orchestella"):
        raise RuntimeError("coordinator-established W:/ and V:/ aliases must match approved roots")

    def command_path(value):
        value = str(value).replace("\\", "/")
        return value.replace((BASE / "Orchestella_dev").as_posix(), "W:").replace(
            (BASE / "Orchestella").as_posix(), "V:").replace("/", "\\")

    env["TEMP"] = env["TMP"] = command_path(work / "temp")
    (work / "temp").mkdir()
    jars = []

    def run(argv, name):
        # Lists go directly to subprocess; no shell expansion or path conversion.
        argv = [command_path(x) for x in argv]
        result = subprocess.run(argv, cwd=command_path(work), env=env,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                timeout=90, text=True, encoding="utf-8", errors="replace")
        output = work / (name + ".log.txt")
        output.write_text(result.stdout, encoding="utf-8")
        shutil.copyfile(output, preserved / output.name)
        evidence["commands"].append({"argv": [str(x) for x in argv], "exit": result.returncode,
                                     "output": output.name})
        print(name, "exit", result.returncode)
        if result.returncode:
            print(result.stdout[-6000:])
            raise RuntimeError(name + " failed")

    try:
        # Preserve exact bounded inputs before invoking any compiler. Compile the preserved
        # local snapshot, so later editor changes cannot alter this run's bytes.
        snapshot = work / "source"
        for path in [*sorted((SOURCE / "src").rglob("*")), SOURCE / "scripts/build_qa.py"]:
            if not path.is_file(): continue
            relative = path.relative_to(SOURCE)
            data = path.read_bytes()
            evidence["source"][relative.as_posix()] = {
                "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
            for root in (snapshot, preserved / "source"):
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
        notice_input = BASE / "Orchestella_dev/app/src/main/assets/third-party-notices.txt"
        notice_data = notice_input.read_bytes()
        for root in (snapshot, preserved / "source"):
            target = root / "inputs/third-party-notices.txt"
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(notice_data)
        evidence["notice"] = {"bytes": len(notice_data), "sha256": hashlib.sha256(notice_data).hexdigest()}
        for name, pin in PINS.items():
            aar = cache / name / "13.1.5" / pin / (name + "-13.1.5.aar")
            evidence["libraries"][name + ":13.1.5"] = digest(aar)
            jar = work / (name + "-13.1.5.jar")
            with zipfile.ZipFile(aar) as archive:
                jar.write_bytes(archive.read("classes.jar"))
            jars.append(jar)
        generated = work / "generated"
        generated.mkdir()
        classes = work / "classes"
        classes.mkdir()
        # AIDL embeds argv in its generated Java comment using the local Windows codepage.
        # The verified coordinator aliases keep this comment ASCII without changing generated code.
        aidl = snapshot / "src/main/aidl"
        run([SDK / "build-tools/36.1.0/aidl.exe", "-p" + (SDK / "platforms/android-36/framework.aidl").as_posix(),
             "-I" + aidl.as_posix(), "-o" + generated.as_posix(),
             (aidl / "io/github/nyzeep/orchestella/researchauth/IIdentityProbe.aidl").as_posix()], "aidl")
        java = sorted((snapshot / "src/main/java").rglob("*.java")) + sorted(generated.rglob("*.java"))
        android = SDK / "platforms/android-36/android.jar"
        run([JDK / "bin/javac.exe", "-encoding", "UTF-8", "-source", "8", "-target", "8",
             "-classpath", os.pathsep.join(str(x) for x in [android, *jars]),
             "-d", classes, *java], "javac")
        evidence["classCount"] = len(list(classes.rglob("*.class")))
        if options.apk:
            program = work / "probe.jar"
            with zipfile.ZipFile(program, "x", zipfile.ZIP_DEFLATED) as archive:
                for path in sorted(classes.rglob("*.class")):
                    archive.write(path, path.relative_to(classes).as_posix())
            dex = work / "dex"
            dex.mkdir()
            run([JDK / "bin/java.exe", "-Djava.io.tmpdir=" + str(work / "temp"), "-cp",
                 SDK / "build-tools/36.1.0/lib/d8.jar", "com.android.tools.r8.D8",
                 "--min-api", "26", "--lib", android, "--output", dex, program, *jars], "d8")
            assets = work / "assets"
            assets.mkdir()
            notice = snapshot / "inputs/third-party-notices.txt"
            shutil.copyfile(notice, assets / notice.name)
            resource_apk = work / "resources.apk"
            run([SDK / "build-tools/36.1.0/aapt2.exe", "link", "-I", android,
                 "--manifest", snapshot / "src/main/AndroidManifest.xml", "--min-sdk-version", "26",
                 "--target-sdk-version", "36", "--version-code", str(options.version_code),
                 "--version-name", options.version_name, "-A", assets, "-o", resource_apk], "aapt2")
            unsigned = work / "identity-qa-unsigned.apk"
            shutil.copyfile(resource_apk, unsigned)
            with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as archive:
                for path in sorted(dex.glob("*.dex")): archive.write(path, path.name)
            evidence.update(level="unsigned-qa-apk", versionCode=options.version_code,
                            versionName=options.version_name, apk=digest(unsigned))
            shutil.copyfile(unsigned, preserved / unsigned.name)
        evidence["result"] = "PASS"
    except Exception as failure:
        evidence["result"] = "FAIL"
        evidence["failureType"] = type(failure).__name__
        raise
    finally:
        receipt = work / "result.json"
        receipt.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
        shutil.copyfile(receipt, preserved / receipt.name)
        print(str(receipt))


if __name__ == "__main__":
    main()
