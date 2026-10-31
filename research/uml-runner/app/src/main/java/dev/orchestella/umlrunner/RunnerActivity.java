package dev.orchestella.umlrunner;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
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
 *  5. 后台线程每 500ms 检查 filesDir/uml-stop，存在则 SIGTERM → SIGKILL；
 *  6. UML 存活期持前台服务（KeepAliveService）+ PARTIAL_WAKE_LOCK，规避 HyperOS
 *     cgroup freezer 对后台 App 的整组冻结（2026-11-07 设备轮 §7.1.6 设备级实锤）。
 */
public class RunnerActivity extends Activity {

    private static final String TAG = "UmlRunner";

    /** logcat 单条上限约 4KB，超过需分片。 */
    private static final int LOGCAT_CHUNK = 3000;

    /**
     * WAKE_LOCK 超时上限：防泄漏持有。研究窗口（T5 门2 60min 驻留）远小于该值；
     * 超时后系统自动回收，UML 可能被冻结——视为研究会话已超时。
     */
    private static final long WAKE_LOCK_TIMEOUT_MS = 2L * 60 * 60 * 1000;

    private File filesDir;
    private File consoleLog;
    private File exitFile;
    private File stopFile;

    private volatile Process process;
    private volatile boolean stopping;

    /** UML 存活期的保活锁（acquire 带超时上限，退出 finally 释放）。 */
    private PowerManager.WakeLock wakeLock;

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

        // targetSdk 33+ 前台服务通知需要运行时通知权限；旧版本上未知权限会被静默拒绝，无害。
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }

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

        // 启动前清理上一实例遗留的 stop 文件。
        // T5 门1 cycle1 根因（2026-11-07 设备轮 §5.1）：上一轮进程死亡时 watcher 的
        // while (p.isAlive()) 循环先退出，其后生成/遗留的 uml-stop 不再被消费；
        // 下一实例启动 549ms 就把它当成停止信号，产生非正常退出。启动期清理是
        // 「陈旧文件」的权威修法（App 被杀时 watcher 线程随之消亡，死后兜底够不到）。
        if (stopFile.delete()) {
            logLine("W", "清理上一实例遗留的 " + stopFile.getName() + "（非本轮停止信号）");
        }

        // 长驻保活：前台服务抬 foreground 状态 + PARTIAL_WAKE_LOCK（带超时上限），
        // 规避 HyperOS cgroup freezer 对后台 App 连带 UML 子进程的整组冻结
        // （2026-11-07 设备轮 §7.1.6 实锤：Dozing 后冻结 33 分钟零漂移）。
        startKeepAlive();
        try {
            runKernel(startMs, cfg);
        } finally {
            // try/finally：spawn-error 提前 return 与顶层异常路径都保证释放。
            stopKeepAlive();
        }
    }

    private void runKernel(long startMs, Config cfg) {
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

            // 进程死后兜底清理（与启动期清理互补）：若 stop 文件在进程死亡前后生成、
            // watcher 的 while (p.isAlive()) 已退出（消费窗口关闭），文件会遗留到下一
            // 实例。此处删掉，保证「任何一轮结束时 filesDir 里不留 uml-stop」。
            // 注：正常 stop-file 路径下 watchStop 已删除过一次，此处 delete 返回 false，无副作用。
            if (stopFile.delete()) {
                logLine("W", "进程已退出，清理本轮遗留的 " + stopFile.getName()
                        + "（watcher 消费窗口已关闭）");
            }

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

    // ------------------------------------------------------------- keep alive

    /** UML 启动前调用：前台服务 + PARTIAL_WAKE_LOCK（获取失败不阻断研究运行）。 */
    private void startKeepAlive() {
        try {
            // 前台服务把 App 抬到 foreground 状态（HyperOS freezer 的豁免面）；
            // 通知为最低配（IMPORTANCE_LOW），无声音无横幅。
            startForegroundService(new Intent(this, KeepAliveService.class));
        } catch (Exception e) {
            logLine("W", "startForegroundService 失败（继续运行，长驻抗冻结不保证）: " + e);
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UmlRunner:uml-kernel");
            wakeLock.setReferenceCounted(false);
            // acquire(timeout)：CPU 计时器持有，超时自动释放（防泄漏上限），
            // 不依赖退出路径必达——退出路径由 stopKeepAlive() 兜底提前释放。
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
            logLine("I", "wake lock acquired (timeout_ms=" + WAKE_LOCK_TIMEOUT_MS + ")");
        } catch (Exception e) {
            wakeLock = null;
            logLine("W", "wake lock 获取失败（继续运行）: " + e);
        }
    }

    /** UML 退出（run 的 finally）调用：立即释放 wake lock 并停掉前台服务。 */
    private void stopKeepAlive() {
        if (wakeLock != null) {
            try {
                wakeLock.release();
                logLine("I", "wake lock released");
            } catch (Exception ignored) {
            }
            wakeLock = null;
        }
        try {
            stopService(new Intent(this, KeepAliveService.class));
        } catch (Exception ignored) {
        }
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
            c.args = defaultArgs(512, c.stubExe, c.rootfs, 0);
            return c;
        }

        /**
         * ncpus：SMP 内核多核旋钮，写作 {@code ncpus=N}（不带 {@code --} 前缀），
         * 语义为「默认 host-1」：省略即单核（host CPU 数 − 1）；仅当显式 ≥2 时才拼进
         * argv（UP 内核对未知参数会透传 userspace，多余旋钮无意义且污染 argv）。
         * {@code --cpus=N} 不被 SMP 内核识别，同样透传 userspace——禁止使用。
         * （2026-11-07 设备轮 §7.1.2）
         */
        static List<String> defaultArgs(int memMb, String stubExe, String rootfs, int ncpus) {
            List<String> args = new ArrayList<>(Arrays.asList(
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
            if (ncpus >= 2) {
                args.add("ncpus=" + ncpus);
            }
            return args;
        }

        /**
         * 读 JSON 配置；文件不存在 → 默认值。支持字段：
         * memMb(int) / ncpus(int, ≥2 时追加 ncpus=N，SMP 内核旋钮) / rootfs / kernel /
         * stubExe / tmpDir / stdin("pipe"|"null") / args([...])。
         * args 一旦给出即整体替换默认参数数组；其中可用 ${stubExe} ${rootfs} ${nativeLibDir} 占位。
         * 注意：args 覆盖时 ncpus 字段不生效，需在 args 里自行写入 ncpus=N。
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
            int ncpus = o.optInt("ncpus", 0);
            if (o.has("args")) {
                JSONArray arr = o.getJSONArray("args");
                List<String> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    list.add(expand(arr.getString(i), c, libDir));
                }
                c.args = list;
            } else {
                c.args = defaultArgs(memMb, c.stubExe, c.rootfs, ncpus);
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
