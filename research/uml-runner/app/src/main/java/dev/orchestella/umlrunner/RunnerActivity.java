package dev.orchestella.umlrunner;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * UML（User Mode Linux, arm64）内核启动器 —— 研究用 APK。
 *
 * 行为：
 *  1. 读 filesDir/uml-config.json（不存在则用内置默认值）；
 *  2. 用 ProcessBuilder 执行 nativeLibraryDir/libuml_kernel.so，参数见 {@link Config}；
 *  3. 子进程 stdout+stderr 合并，逐行同时写入 filesDir/uml-console.log（覆盖写）与 logcat(tag=UmlRunner)；
 *  4. 退出码写入 filesDir/uml-exit.txt；
 *  5. 后台线程每 500ms 检查 filesDir/uml-stop，存在则 SIGTERM → SIGKILL。
 */
public class RunnerActivity extends Activity {

    private static final String TAG = "UmlRunner";

    /** logcat 单条上限约 4KB，超过需分片。 */
    private static final int LOGCAT_CHUNK = 3000;

    private File filesDir;
    private File consoleLog;
    private File exitFile;
    private File stopFile;

    private volatile Process process;
    private volatile boolean stopping;

    /** 屏显控制台：让真机截图本身成为证据（研究 APK 无其它 UI）。 */
    private TextView screen;
    private final StringBuilder screenBuf = new StringBuilder();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        filesDir = getFilesDir();
        consoleLog = new File(filesDir, "uml-console.log");
        exitFile = new File(filesDir, "uml-exit.txt");
        stopFile = new File(filesDir, "uml-stop");

        buildScreen();

        Thread t = new Thread(this::run, "uml-runner");
        t.setDaemon(false);
        t.start();
    }

    // ------------------------------------------------------------------ main

    private void run() {
        long startMs = System.currentTimeMillis();
        Config cfg;
        try {
            cfg = Config.load(new File(filesDir, "uml-config.json"), this);
        } catch (Exception e) {
            logLine("E", "配置解析失败，回退默认值: " + e);
            cfg = Config.defaults(this);
        }

        for (String problem : cfg.validate()) logLine("W", "precheck: " + problem);

        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(consoleLog, false), StandardCharsets.UTF_8))) {

            writeHeader(w, cfg);

            ProcessBuilder pb = new ProcessBuilder(cfg.command());
            pb.directory(filesDir);
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            env.put("TMPDIR", cfg.tmpDir);
            env.put("HOME", filesDir.getAbsolutePath());
            env.put("PATH", "/system/bin:/system/xbin:/vendor/bin");
            if (!cfg.stdinPipe) {
                pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
            }

            logLine("I", "exec: " + cfg.kernel + " " + String.join(" ", cfg.args));
            logLine("I", "console log -> " + consoleLog.getAbsolutePath());

            Process p;
            try {
                p = pb.start();
            } catch (Exception e) {
                logLine("E", "ProcessBuilder.start 失败: " + e);
                writeLine(w, "start-failed: " + e);
                writeExit(exitFile, startMs, -1, "spawn-error: " + e, cfg);
                return;
            }
            process = p;
            logLine("I", "pid=" + pidOf(p));

            // 保活 stdin 管道（fd0 可 poll）；不写入内容，也不关闭。
            OutputStream stdin = cfg.stdinPipe ? p.getOutputStream() : null;

            Thread stopWatch = new Thread(() -> watchStop(p), "uml-stop-watch");
            stopWatch.setDaemon(true);
            stopWatch.start();

            // 泵 stdout/stderr：写日志 + logcat
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    p.getInputStream(), StandardCharsets.UTF_8), 8192)) {
                String line;
                while ((line = r.readLine()) != null) {
                    writeLine(w, line);
                    logLine("I", line);
                    if (stopping) break;
                }
            } catch (Exception e) {
                logLine("E", "读子进程输出异常: " + e);
            }

            int code;
            try {
                code = p.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                code = -2;
            }
            long duration = System.currentTimeMillis() - startMs;
            String reason = stopping ? "stop-file" : "exited";
            logLine("I", "kernel exited: code=" + code + " reason=" + reason
                    + " duration_ms=" + duration);
            writeLine(w, "");
            writeLine(w, "=== kernel exited code=" + code + " reason=" + reason
                    + " duration_ms=" + duration + " ===");
            writeExit(exitFile, startMs, code, reason, cfg);

            if (stdin != null) {
                try { stdin.close(); } catch (Exception ignored) { }
            }
        } catch (Exception e) {
            logLine("E", "runner 顶层异常: " + e);
            writeExit(exitFile, startMs, -3, "runner-error: " + e, cfg);
        }
    }

    private void writeHeader(BufferedWriter w, Config cfg) throws Exception {
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        writeLine(w, "=== UmlRunner ===");
        writeLine(w, "time=" + ts);
        writeLine(w, "device=" + Build.MANUFACTURER + " " + Build.MODEL
                + " api=" + Build.VERSION.SDK_INT + "(" + Build.VERSION.RELEASE + ")"
                + " abi=" + Build.SUPPORTED_ABIS[0]
                + " targetSdk=" + getApplicationInfo().targetSdkVersion);
        writeLine(w, "filesDir=" + filesDir.getAbsolutePath());
        writeLine(w, "nativeLibraryDir=" + getApplicationInfo().nativeLibraryDir);
        writeLine(w, "kernel=" + cfg.kernel);
        writeLine(w, "kernel.len=" + new File(cfg.kernel).length()
                + " kernel.canExecute=" + new File(cfg.kernel).canExecute());
        writeLine(w, "stub=" + cfg.stubExe
                + " stub.len=" + new File(cfg.stubExe).length()
                + " stub.canExecute=" + new File(cfg.stubExe).canExecute());
        writeLine(w, "rootfs=" + cfg.rootfs + " exists=" + new File(cfg.rootfs).isDirectory());
        writeLine(w, "tmpdir=" + cfg.tmpDir);
        writeLine(w, "exec=" + cfg.kernel + " " + String.join(" ", cfg.args));
        writeLine(w, "--- kernel output ---");
        w.flush();
    }

    private void writeLine(BufferedWriter w, String line) {
        try {
            w.write(line);
            w.write('\n');
            w.flush();
        } catch (Exception ignored) {
        }
    }

    private void logLine(String level, String line) {
        if (line == null) line = "";
        appendToScreen(line);
        if (line.length() <= LOGCAT_CHUNK) {
            logcat(level, line);
            return;
        }
        for (int i = 0; i < line.length(); i += LOGCAT_CHUNK) {
            logcat(level, line.substring(i, Math.min(line.length(), i + LOGCAT_CHUNK)));
        }
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        screen = new TextView(this);
        screen.setTypeface(Typeface.MONOSPACE);
        screen.setTextSize(9);
        screen.setTextColor(Color.rgb(120, 255, 140));
        int pad = (int) (getResources().getDisplayMetrics().density * 8);
        screen.setPadding(pad, pad, pad, pad);
        screen.setText("uml-runner\n等待内核输出…\n");
        scroll.addView(screen, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    /** 只保留末 400 行，避免长跑时 TextView 撑爆。 */
    private void appendToScreen(final String line) {
        if (screen == null) return;
        synchronized (screenBuf) {
            screenBuf.append(line).append('\n');
            if (screenBuf.length() > 24000) {
                int cut = screenBuf.indexOf("\n", screenBuf.length() - 16000);
                if (cut > 0) screenBuf.delete(0, cut + 1);
            }
        }
        uiHandler.post(new Runnable() {
            @Override public void run() {
                if (screen == null) return;
                synchronized (screenBuf) { screen.setText(screenBuf); }
            }
        });
    }

    private void logcat(String level, String msg) {
        if ("E".equals(level)) {
            Log.e(TAG, msg);
        } else if ("W".equals(level)) {
            Log.w(TAG, msg);
        } else {
            Log.i(TAG, msg);
        }
    }

    private void writeExit(File f, long startMs, int code, String reason, Config cfg) {
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date());
        StringBuilder sb = new StringBuilder();
        sb.append("exit_code=").append(code).append('\n');
        sb.append("exit_code_hex=0x").append(Integer.toHexString(code)).append('\n');
        sb.append("signal_guess=").append(code > 128 ? (code - 128) : 0).append('\n');
        sb.append("reason=").append(reason).append('\n');
        sb.append("ended_at=").append(ts).append('\n');
        sb.append("duration_ms=").append(System.currentTimeMillis() - startMs).append('\n');
        sb.append("kernel=").append(cfg.kernel).append('\n');
        sb.append("stub_exe=").append(cfg.stubExe).append('\n');
        sb.append("rootfs=").append(cfg.rootfs).append('\n');
        sb.append("args=").append(String.join(" ", cfg.args)).append('\n');
        try (FileOutputStream fos = new FileOutputStream(f, false)) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            logLine("E", "写 " + f.getName() + " 失败: " + e);
        }
        logLine("I", "exit code written: " + f.getAbsolutePath());
    }

    // -------------------------------------------------------------- stop file

    private void watchStop(Process p) {
        while (p.isAlive()) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
            if (stopFile.exists() && !stopping) {
                stopping = true;
                logLine("W", "检测到 " + stopFile.getName() + "，发送 SIGTERM");
                p.destroy();
                try {
                    if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                        logLine("W", "3s 未退出，SIGKILL");
                        p.destroyForcibly();
                    }
                } catch (InterruptedException ignored) {
                }
                if (stopFile.delete()) {
                    logLine("I", "已删除 " + stopFile.getName() + "（保持可重跑）");
                }
            }
        }
    }

    /** Process.pid() 在 android.jar(API 28) 里不可见，反射取，失败返回 "?"。 */
    private static String pidOf(Process p) {
        try {
            Object v = Process.class.getMethod("pid").invoke(p);
            return String.valueOf(v);
        } catch (Throwable t) {
            try {
                java.lang.reflect.Field f = p.getClass().getDeclaredField("pid");
                f.setAccessible(true);
                return String.valueOf(f.get(p));
            } catch (Throwable t2) {
                return "?";
            }
        }
    }

    // ------------------------------------------------------------------ config

    static final class Config {
        String kernel;
        String stubExe;
        String rootfs;
        String tmpDir;
        boolean stdinPipe;
        List<String> args;

        static Config defaults(Activity a) {
            Config c = new Config();
            String libDir = a.getApplicationInfo().nativeLibraryDir;
            File files = a.getFilesDir();
            c.kernel = new File(libDir, "libuml_kernel.so").getAbsolutePath();
            c.stubExe = new File(libDir, "libuml_stub.so").getAbsolutePath();
            c.rootfs = new File(files, "rootfs").getAbsolutePath();
            c.tmpDir = files.getAbsolutePath();
            c.stdinPipe = true;
            c.args = defaultArgs(512, c.stubExe, c.rootfs);
            return c;
        }

        static List<String> defaultArgs(int memMb, String stubExe, String rootfs) {
            return new ArrayList<>(Arrays.asList(
                    "mem=" + memMb + "M",
                    "rw",
                    "init=/um-init",
                    "con=null",
                    "con0=fd:0,fd:1",
                    "stub_exe=" + stubExe,
                    "rootfstype=hostfs",
                    "rootflags=" + rootfs,
                    "seccomp=on",
                    "panic=1"));
        }

        /**
         * 读 JSON 配置；文件不存在 → 默认值。支持字段：
         * memMb(int) / rootfs / kernel / stubExe / tmpDir / stdin("pipe"|"null") / args([...])。
         * args 一旦给出即整体替换默认参数数组；其中可用 ${stubExe} ${rootfs} ${nativeLibDir} 占位。
         */
        static Config load(File cfgFile, Activity a) throws Exception {
            Config c = defaults(a);
            if (!cfgFile.isFile()) {
                Log.i(TAG, "未找到 " + cfgFile.getAbsolutePath() + "，用默认配置");
                return c;
            }
            String text = readAll(cfgFile);
            JSONObject o = new JSONObject(text);
            String libDir = a.getApplicationInfo().nativeLibraryDir;

            if (o.has("rootfs")) c.rootfs = o.getString("rootfs");
            if (o.has("tmpDir")) c.tmpDir = o.getString("tmpDir");
            if (o.has("stubExe")) c.stubExe = o.getString("stubExe");
            if (o.has("kernel")) c.kernel = o.getString("kernel");
            if (o.has("stdin")) c.stdinPipe = !"null".equalsIgnoreCase(o.getString("stdin"));

            int memMb = o.optInt("memMb", 512);
            if (o.has("args")) {
                JSONArray arr = o.getJSONArray("args");
                List<String> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    list.add(expand(arr.getString(i), c, libDir));
                }
                c.args = list;
            } else {
                c.args = defaultArgs(memMb, c.stubExe, c.rootfs);
            }
            return c;
        }

        private static String expand(String s, Config c, String libDir) {
            return s.replace("${stubExe}", c.stubExe)
                    .replace("${rootfs}", c.rootfs)
                    .replace("${nativeLibDir}", libDir);
        }

        List<String> command() {
            List<String> cmd = new ArrayList<>();
            cmd.add(kernel);
            cmd.addAll(args);
            return cmd;
        }

        /** 只做可观测性检查，不阻断启动（研究 APK 要留下失败现场）。 */
        List<String> validate() {
            List<String> out = new ArrayList<>();
            File k = new File(kernel);
            if (!k.isFile()) out.add("kernel 不存在: " + kernel);
            else if (!k.canExecute()) out.add("kernel 无执行位(access X_OK 失败): " + kernel);
            File s = new File(stubExe);
            if (!s.isFile()) out.add("stub 不存在: " + stubExe);
            else if (!s.canExecute()) out.add("stub 无执行位: " + stubExe);
            if (!new File(rootfs).isDirectory()) out.add("rootfs 目录不存在: " + rootfs);
            if (!new File(tmpDir).canWrite()) out.add("tmpDir 不可写: " + tmpDir);
            return out;
        }
    }

    private static String readAll(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            return new String(buf, 0, off, StandardCharsets.UTF_8);
        }
    }
}
