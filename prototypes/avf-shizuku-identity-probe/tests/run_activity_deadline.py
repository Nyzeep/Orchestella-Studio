"""Run the real ProbeActivity against bounded JVM-only Android/Binder test doubles.

No APK, Gradle, device, signing, network, or production writes. Each label saves
exact compiled inputs and command evidence twice under the deadline-fix batch.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import subprocess

BASE = Path("D:/Development project(开发项目)/Orchestella开发目录")
CANONICAL = BASE / "Orchestella_dev/prototypes/avf-shizuku-identity-probe"
OUTPUT = BASE / "Orchestella/artifacts/avf-shizuku-device-20261016/batch-01/resume-01/qa-deadline-fix"
PRESERVATION = OUTPUT.parent / "preservation/qa-deadline-fix"
PACKAGE = "io/github/nyzeep/orchestella/researchauth/"
REAL = ["ProbeActivity.java", "ProbeConnectionEvents.java", "ProbeSession.java", "FixedPermissionPlan.java"]
SCENARIOS = ["same-session-grant", "same-session-readback", "same-session-restore",
             "replayed-finish", "current-deadline", "snapshot-deadline",
             "end-during-snapshot", "death-during-snapshot", "old-activity"]

# These only replace framework/remote dependencies. submit/bind/actions/lifecycle,
# connection guard, session gate and permission plan are compiled from real bytes.
STUBS = {
    "android/os/Bundle.java": """package android.os;
public class Bundle {
 private final java.util.Map<String,Object> data = new java.util.LinkedHashMap<>();
 public void putString(String k,String v){data.put(k,v);} public String getString(String k){return (String)data.get(k);}
 public void putInt(String k,int v){data.put(k,v);} public int getInt(String k){return ((Number)data.getOrDefault(k,0)).intValue();}
 public void putLong(String k,long v){data.put(k,v);} public long getLong(String k){return ((Number)data.getOrDefault(k,0L)).longValue();}
 public void putBoolean(String k,boolean v){data.put(k,v);} public boolean getBoolean(String k){return Boolean.TRUE.equals(data.get(k));}
 public java.util.Set<String> keySet(){return data.keySet();} public Object get(String k){return data.get(k);}
}
""",
    "android/os/IBinder.java": "package android.os; public interface IBinder {}\n",
    "android/os/Looper.java": "package android.os; public class Looper { public static Looper getMainLooper(){return new Looper();} }\n",
    "android/os/SystemClock.java": """package android.os;
public class SystemClock { public static volatile long now; private static long sequence;
 public static long elapsedRealtime(){return now;} public static synchronized long elapsedRealtimeNanos(){return 1000000L+now*1000000L+(++sequence);}
}
""",
    "android/os/Handler.java": """package android.os;
public class Handler {
 public static final class Task { public final Runnable run; public final long due; public final boolean delayed; public boolean removed;
  Task(Runnable r,long d,boolean delay){run=r;due=d;delayed=delay;} }
 private static final java.util.List<Task> tasks=new java.util.ArrayList<>();
 public Handler(Looper ignored){}
 public boolean post(Runnable r){synchronized(tasks){tasks.add(new Task(r,SystemClock.now,false));tasks.notifyAll();} return true;}
 public boolean postDelayed(Runnable r,long ms){synchronized(tasks){tasks.add(new Task(r,SystemClock.now+ms,true));} return true;}
 public void removeCallbacks(Runnable r){synchronized(tasks){for(Task t:tasks)if(t.run==r)t.removed=true;}}
 public static Task latestTimer(){synchronized(tasks){for(int i=tasks.size()-1;i>=0;i--)if(tasks.get(i).delayed)return tasks.get(i);}throw new AssertionError("no timer");}
 public static Task takeCompletion() throws Exception {long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
  synchronized(tasks){for(;;){for(int i=0;i<tasks.size();i++){Task t=tasks.get(i);if(!t.delayed&&!t.removed){tasks.remove(i);return t;}}
   long left=end-System.nanoTime();if(left<=0)throw new AssertionError("worker did not post completion");java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(tasks,left);}}}
 // A dispatched timer is no longer armed, just like a removed pending timer.
 public static void advanceTo(long now){SystemClock.now=now;for(;;){Task next=null;synchronized(tasks){for(Task t:tasks)if(t.delayed&&!t.removed&&t.due<=now&&(next==null||t.due<next.due))next=t;if(next!=null){tasks.remove(next);next.removed=true;}}if(next==null)return;next.run.run();}}
}
""",
    "android/content/Context.java": "package android.content; public class Context {}\n",
    "android/content/ComponentName.java": "package android.content; public class ComponentName { public ComponentName(Context c,Class<?> t){} }\n",
    "android/content/ServiceConnection.java": """package android.content; import android.os.IBinder;
public interface ServiceConnection {void onServiceConnected(ComponentName n,IBinder b);void onServiceDisconnected(ComponentName n);}
""",
    "android/content/pm/PackageManager.java": "package android.content.pm; public class PackageManager {public static final int PERMISSION_GRANTED=0;}\n",
    "android/app/Activity.java": """package android.app;
public class Activity extends android.content.Context { public static final int MODE_PRIVATE=0;
 public void onCreate(android.os.Bundle b){} public void onStart(){} public void onStop(){}
 public void setContentView(android.widget.ScrollView v){}
 public java.io.FileOutputStream openFileOutput(String name,int mode)throws java.io.IOException{return new java.io.FileOutputStream(System.getProperty("qa.receipt.dir")+"/"+name);}
}
""",
    "android/view/View.java": """package android.view;
public class View {public interface OnClickListener{void onClick(View v);} private OnClickListener click;
 public void setOnClickListener(OnClickListener c){click=c;} public void performClick(){click.onClick(this);}}
""",
    "android/widget/Button.java": """package android.widget;
public class Button extends android.view.View {public static final java.util.List<Button> all=new java.util.ArrayList<>();public String title;
 public Button(android.content.Context c){all.add(this);} public void setText(String t){title=t;}}
""",
    "android/widget/TextView.java": """package android.widget;
public class TextView extends android.view.View {private String text=""; public TextView(android.content.Context c){} public void setTextIsSelectable(boolean b){}
 public void setText(String t){text=t;} public String getText(){return text;}}
""",
    "android/widget/LinearLayout.java": """package android.widget;
public class LinearLayout extends android.view.View {public static final int VERTICAL=1;public LinearLayout(android.content.Context c){}
 public void setOrientation(int i){} public void addView(android.view.View v){}}
""",
    "android/widget/ScrollView.java": "package android.widget; public class ScrollView extends android.view.View {public ScrollView(android.content.Context c){}public void addView(android.view.View v){}}\n",
    "org/json/JSONObject.java": """package org.json;
public class JSONObject {private final java.util.Map<String,Object> data=new java.util.LinkedHashMap<>();
 public void put(String k,Object v){data.put(k,v);}public String toString(int indent){return data.toString();}}
""",
    "rikka/shizuku/Shizuku.java": """package rikka.shizuku;
public class Shizuku {
 public interface OnBinderReceivedListener{void onBinderReceived();}public interface OnBinderDeadListener{void onBinderDead();}
 public interface OnRequestPermissionResultListener{void onRequestPermissionResult(int code,int result);}
 public static final class UserServiceArgs {public UserServiceArgs(android.content.ComponentName n){}public UserServiceArgs daemon(boolean b){return this;}
  public UserServiceArgs tag(String s){return this;}public UserServiceArgs version(int v){return this;}public UserServiceArgs processNameSuffix(String s){return this;}public UserServiceArgs debuggable(boolean b){return this;}}
 public static android.content.ServiceConnection connection;public static OnBinderDeadListener dead;public static int binds,removes;public static boolean alive=true;
 public static boolean pingBinder(){return alive;}public static int getVersion(){return 13;}public static int getUid(){return 2000;}
 public static int checkSelfPermission(){return 0;}public static int checkRemotePermission(String p){return 0;}public static boolean shouldShowRequestPermissionRationale(){return false;}
 public static void requestPermission(int c){}public static void addBinderDeadListener(OnBinderDeadListener d,android.os.Handler h){dead=d;}
 public static void addBinderReceivedListenerSticky(OnBinderReceivedListener r,android.os.Handler h){}
 public static void addRequestPermissionResultListener(OnRequestPermissionResultListener r,android.os.Handler h){}
 public static void removeBinderReceivedListener(OnBinderReceivedListener r){}public static void removeBinderDeadListener(OnBinderDeadListener d){if(dead==d)dead=null;}
 public static void removeRequestPermissionResultListener(OnRequestPermissionResultListener r){}
 public static void bindUserService(UserServiceArgs a,android.content.ServiceConnection c){connection=c;binds++;}
 public static void unbindUserService(UserServiceArgs a,android.content.ServiceConnection c,boolean remove){if(remove)removes++;if(connection==c)connection=null;}
 public static void die(){alive=false;dead.onBinderDead();}
}
""",
    PACKAGE + "IIdentityProbe.java": """package io.github.nyzeep.orchestella.researchauth;
public interface IIdentityProbe extends android.os.IBinder {
 void begin(int protocol,long generation);void end(long generation);android.os.Bundle inspect(long generation);
 android.os.Bundle permissionSnapshot(long generation);android.os.Bundle grantSelected(long generation,boolean manage,boolean custom);
 android.os.Bundle restoreAdded(long generation);android.os.Bundle managerCapability(long generation);
 final class Stub {public static IIdentityProbe asInterface(android.os.IBinder b){return (IIdentityProbe)b;}}
}
""",
    PACKAGE + "IdentityProbeService.java": "package io.github.nyzeep.orchestella.researchauth; public final class IdentityProbeService {}\n",
    PACKAGE + "ProbeSnapshot.java": """package io.github.nyzeep.orchestella.researchauth;
public class ProbeSnapshot {public static android.os.Bundle inspect(android.content.Context c,long epoch,String actor){android.os.Bundle b=new android.os.Bundle();b.putLong("generation",epoch);b.putString("actor",actor);return b;}}
""",
    PACKAGE + "ProbeErrors.java": "package io.github.nyzeep.orchestella.researchauth; public class ProbeErrors {public static String kind(Throwable e){return e.getClass().getSimpleName();}}\n",
    PACKAGE + "QaFdClient.java": """package io.github.nyzeep.orchestella.researchauth;
public class QaFdClient {public static android.os.Bundle run(android.content.Context c,IIdentityProbe p,long e){return new android.os.Bundle();}}
""",
    PACKAGE + "QaManagerCapability.java": """package io.github.nyzeep.orchestella.researchauth;
public class QaManagerCapability {public static android.os.Bundle inspect(android.content.Context c,long e){return new android.os.Bundle();}}
""",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--label", required=True)
    parser.add_argument("--source-root", type=Path, default=CANONICAL)
    options = parser.parse_args()
    if not re.fullmatch(r"[a-z0-9-]+", options.label):
        parser.error("unique evidence label required")
    allowed = [CANONICAL.resolve(), (OUTPUT / "before-qa3/source").resolve(), (OUTPUT / "source").resolve()]
    if options.source_root.resolve() not in allowed:
        parser.error("source must be canonical, preserved QA3, or frozen QA4")
    work = OUTPUT / options.label
    twin = PRESERVATION / options.label
    work.mkdir(parents=True, exist_ok=False)
    twin.mkdir(parents=True, exist_ok=False)

    def save(relative, data):
        for root in (work, twin):
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)

    inputs = {}
    for name in REAL:
        relative = "src/main/java/" + PACKAGE + name
        data = (options.source_root / relative).read_bytes()
        save("source/" + relative, data)
        inputs[relative] = {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
    for name in ("run_activity_deadline.py", "ProbeActivityDeadlineHarness.java"):
        data = (Path(__file__).resolve().parent / name).read_bytes()
        save("source/tests/" + name, data)
        inputs["tests/" + name] = {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
    for relative, text in STUBS.items():
        save("stubs/" + relative, text.encode("utf-8"))
    (work / "classes").mkdir()
    (work / "temp").mkdir()
    for scenario in SCENARIOS:
        (work / "receipts" / scenario).mkdir(parents=True)
    env = dict(os.environ, TEMP=str(work / "temp"), TMP=str(work / "temp"))
    evidence = {"level": "JVM actual Activity source with synthetic framework/Binder dependencies",
                "inputs": inputs, "commands": [], "scenario_count": len(SCENARIOS),
                "android_system_behavior_proven": False, "device_calls": 0, "apk_built": False}

    def run(argv, label):
        result = subprocess.run([str(x) for x in argv], cwd=work, env=env, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=30, text=True, encoding="utf-8", errors="replace")
        save(label + ".log.txt", result.stdout.encode("utf-8"))
        evidence["commands"].append({"argv": [str(x) for x in argv], "exit": result.returncode,
                                     "output": label + ".log.txt"})
        print(label, "exit", result.returncode)
        print(result.stdout.strip())
        return result.returncode

    try:
        sources = sorted((work / "stubs").rglob("*.java")) + sorted((work / "source/src").rglob("*.java"))
        sources += [work / "source/tests/ProbeActivityDeadlineHarness.java"]
        compiled = run(["D:/JDK17/bin/javac.exe", "-encoding", "UTF-8", "-source", "8", "-target", "8",
                        "-d", work / "classes", *sources], "javac")
        failures = []
        if compiled == 0:
            for scenario in SCENARIOS:
                code = run(["D:/JDK17/bin/java.exe", "-Djava.io.tmpdir=" + str(work / "temp"),
                            "-Dqa.receipt.dir=" + str(work / "receipts" / scenario), "-cp", work / "classes",
                            "io.github.nyzeep.orchestella.researchauth.ProbeActivityDeadlineHarness", scenario], scenario)
                if code:
                    failures.append(scenario)
                for receipt in (work / "receipts" / scenario).iterdir():
                    save(receipt.relative_to(work), receipt.read_bytes())
        evidence["failed_scenarios"] = failures
        evidence["result"] = "PASS" if compiled == 0 and not failures else "FAIL"
    finally:
        save("result.json", (json.dumps(evidence, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
    return 0 if evidence.get("result") == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
