"""Phase 5 external JVM verification. No Gradle/cache mutation or device access."""
import argparse
import datetime
import pathlib
import shutil
import subprocess
import sys

WORKTREE = pathlib.Path(__file__).resolve().parents[2]
BATCH = pathlib.Path("D:/Development project(开发项目)/Orchestella开发目录/Orchestella/artifacts/phase5-parallel-20261013")
JUNIT = pathlib.Path("V:/gradle-home/caches/modules-2/files-2.1/junit/junit/4.13.2/8ac9e16d933b6fb43bc7f576336b8f4d7eb5ba12/junit-4.13.2.jar")
HAMCREST = pathlib.Path("V:/gradle-home/caches/modules-2/files-2.1/org.hamcrest/hamcrest-core/1.3/42a25dc3219429f0e5d060061f71acb49bf010a0/hamcrest-core-1.3.jar")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--label", required=True)
    parser.add_argument("--test", action="append")
    parser.add_argument("--full", action="store_true")
    args = parser.parse_args()
    if not args.label.replace("-", "").replace("_", "").isalnum():
        parser.error("label must be an alphanumeric evidence alias")
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    output = BATCH / "C/implementation"
    build = output / "temp" / (stamp + "-" + args.label)
    classes = build / "classes"
    classes.mkdir(parents=True)
    temp = build / "jvm-temp"
    temp.mkdir()
    evidence = output / "evidence" / (stamp + "-" + args.label + ".log.txt")
    evidence.parent.mkdir(parents=True, exist_ok=True)
    preserved = BATCH / "B/implementation/evidence/preserved" / evidence.name
    preserved.parent.mkdir(parents=True, exist_ok=True)
    source_roots = [WORKTREE / "runtime/contract/src/main/java"]
    test_roots = [WORKTREE / "prototypes/phase5-readonly-data/src/test/java", WORKTREE / "prototypes/phase5-usb-fd/src/test/java"]
    source_roots += [WORKTREE / "prototypes/phase5-readonly-data/src/main/java", WORKTREE / "prototypes/phase5-usb-fd/src/main/java"]
    if args.full:
        source_roots.append(WORKTREE / "runtime/contract/src/test/java")
    sources = sorted({p for root in source_roots + test_roots if root.exists() for p in root.rglob("*.java")})
    argfile = build / "javac.args"
    argfile.write_text("\n".join('"' + p.as_posix() + '"' for p in sources), encoding="utf-8")
    classpath = ";".join(str(p) for p in [classes, JUNIT, HAMCREST])
    compile_command = ["D:/JDK17/bin/javac.exe", "-J-Dfile.encoding=UTF-8", "-encoding", "UTF-8", "--release", "17", "-Xlint:all", "-Werror", "-cp", classpath, "-d", str(classes), "@" + str(argfile)]
    tests = args.test or sorted(".".join(p.relative_to(root).with_suffix("").parts) for root in test_roots if root.exists() for p in root.rglob("*Test.java"))
    status = 0
    with evidence.open("w", encoding="utf-8") as record:
        def run(command):
            record.write("COMMAND " + repr(command) + "\n")
            record.flush()
            result = subprocess.run(command, cwd=WORKTREE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, encoding="utf-8", errors="replace", timeout=120)
            record.write(result.stdout + "\nEXIT " + str(result.returncode) + "\n")
            record.flush()
            shutil.copy2(evidence, preserved)
            print(result.stdout, end="")
            return result.returncode
        try:
            status = run(compile_command)
            if status == 0 and tests:
                status = run(["D:/JDK17/bin/java.exe", "-Dfile.encoding=UTF-8", "-Djava.io.tmpdir=" + str(temp), "-cp", classpath, "org.junit.runner.JUnitCore"] + tests)
            if status == 0 and args.full:
                for p in sorted((WORKTREE / "runtime/contract/src/test/java").rglob("*Test.java")):
                    text = p.read_text(encoding="utf-8")
                    if "static void main(" not in text:
                        continue
                    cls = ".".join(p.relative_to(WORKTREE / "runtime/contract/src/test/java").with_suffix("").parts)
                    status = run(["D:/JDK17/bin/java.exe", "-Dfile.encoding=UTF-8", "-Djava.io.tmpdir=" + str(temp), "-cp", classpath, cls])
                    if status:
                        break
        except (subprocess.TimeoutExpired, OSError) as exc:
            record.write("EXECUTION_FAILED " + type(exc).__name__ + "\n")
            status = 1
        finally:
            record.write("FINAL_EXIT " + str(status) + "\n")
            record.flush()
            shutil.copy2(evidence, preserved)
    print("EVIDENCE " + str(evidence))
    print("CLASSES " + str(classes))
    return status


if __name__ == "__main__":
    sys.exit(main())
