package dev.orchestella.umlprobe;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * UML (User-Mode Linux) 可行性 D1a/D1b 域探针。
 * 四个探针顺序执行，异常不崩溃，结果同时输出到 UI / Logcat(tag=UmlProbe) / filesDir/probe_results.txt。
 */
public class ProbeActivity extends Activity {

    private static final String TAG = "UmlProbe";
    private static final int PROBE_SIZE = 65536; // 64KB
    private static final String[] CIRCLED = {"①", "②", "③", "④"};

    private final List<String> results = new ArrayList<>();
    private LinearLayout listLayout;
    private File resultFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        resultFile = new File(getFilesDir(), "probe_results.txt");
        buildUi();
        runAllProbes();
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        listLayout = new LinearLayout(this);
        listLayout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 12);
        listLayout.setPadding(pad, pad, pad, pad);
        scroll.addView(listLayout, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    private void addRow(String line, boolean pass) {
        TextView tv = new TextView(this);
        tv.setText(line);
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tv.setTextSize(13);
        tv.setTextColor(pass ? Color.rgb(0, 128, 0) : Color.rgb(200, 0, 0));
        tv.setPadding(0, 8, 0, 8);
        listLayout.addView(tv, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ------------------------------------------------------------ 探针执行

    private void runAllProbes() {
        String[] lines = {
                probe1FilesDirRwx(),
                probe2AnonymousExec(),
                probe3MemfdExec(),
                probe4StubInfo(),
        };

        for (String line : lines) {
            boolean pass = line.contains("| PASS |");
            addRow(line, pass);
            Log.i(TAG, line);
        }

        // 汇总写入 filesDir/probe_results.txt
        StringBuilder sb = new StringBuilder();
        sb.append("=== UML D1a/D1b 域探针 ===\n");
        sb.append("time=").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append('\n');
        sb.append("device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
          .append(" api=").append(Build.VERSION.SDK_INT)
          .append(" release=").append(Build.VERSION.RELEASE).append('\n');
        sb.append("targetSdk=").append(getApplicationInfo().targetSdkVersion).append('\n');
        for (String r : results) sb.append(r).append('\n');
        try (FileOutputStream fos = new FileOutputStream(resultFile)) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            Log.i(TAG, "结果已写入 " + resultFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "写 probe_results.txt 失败", e);
        }
    }

    // ------------------------------------------------- 探针① filesDir 文件 RWX

    /**
     * 在 filesDir 创建 uml_probe_mem.bin（64KB），Os.open 拿 fd 后对文件做 RWX mmap。
     * 这是 UML check_tmpexec() 所需的核心能力：对普通文件建立可执行映射。
     */
    private String probe1FilesDirRwx() {
        String name = "filesDir 文件 RWX";
        try {
            File f = new File(getFilesDir(), "uml_probe_mem.bin");
            byte[] data = new byte[PROBE_SIZE];
            for (int i = 0; i < data.length; i++) data[i] = (byte) (i & 0xFF);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(data);
            }
            FileDescriptor fd = Os.open(f.getAbsolutePath(), OsConstants.O_RDWR, 0);
            try {
                long addr = Os.mmap(0, PROBE_SIZE,
                        OsConstants.PROT_READ | OsConstants.PROT_WRITE | OsConstants.PROT_EXEC,
                        OsConstants.MAP_PRIVATE, fd, 0);
                Os.munmap(addr, PROBE_SIZE);
            } finally {
                Os.close(fd);
            }
            return fmt(1, name, true, null,
                    "文件 mmap RWX 成功 — UML check_tmpexec() 所需能力可用");
        } catch (Exception e) {
            return fmt(1, name, false, e, "文件 mmap RWX 被拒/异常 — W^X 或 SELinux 强制");
        }
    }

    // ------------------------------------------------- 探针② 匿名 PROT_EXEC

    /**
     * 匿名 RWX mmap：MAP_PRIVATE|MAP_ANONYMOUS + PROT_READ|WRITE|EXEC。
     * libcore 对匿名映射传 fd 可能抛 IllegalArgumentException / EINVAL；
     * 此时改用 memfd_create 拿 fd、仍带 MAP_ANONYMOUS 重试一次（内核忽略匿名映射的 fd，语义不变）。
     */
    private String probe2AnonymousExec() {
        String name = "匿名 PROT_EXEC";
        try {
            long addr = Os.mmap(0, PROBE_SIZE,
                    OsConstants.PROT_READ | OsConstants.PROT_WRITE | OsConstants.PROT_EXEC,
                    OsConstants.MAP_PRIVATE | OsConstants.MAP_ANONYMOUS,
                    new FileDescriptor(), 0);
            Os.munmap(addr, PROBE_SIZE);
            return fmt(2, name, true, null, "匿名 RWX mmap 成功（方式: 空 FileDescriptor）");
        } catch (IllegalArgumentException e) {
            return retryProbe2WithMemfd(name, e);
        } catch (ErrnoException e) {
            if (e.errno == OsConstants.EINVAL) {
                return retryProbe2WithMemfd(name, e);
            }
            return fmt(2, name, false, e, "匿名 RWX mmap 被拒");
        } catch (Exception e) {
            return fmt(2, name, false, e, "匿名 RWX mmap 异常");
        }
    }

    private String retryProbe2WithMemfd(String name, Exception firstError) {
        Log.w(TAG, "探针②首次尝试失败(" + firstError + ")，改用 memfd fd + MAP_ANONYMOUS 重试");
        try {
            FileDescriptor mfd = Os.memfd_create("uml_probe_anon", 0);
            try {
                long addr = Os.mmap(0, PROBE_SIZE,
                        OsConstants.PROT_READ | OsConstants.PROT_WRITE | OsConstants.PROT_EXEC,
                        OsConstants.MAP_PRIVATE | OsConstants.MAP_ANONYMOUS, mfd, 0);
                Os.munmap(addr, PROBE_SIZE);
            } finally {
                Os.close(mfd);
            }
            return fmt(2, name, true, null,
                    "匿名 RWX mmap 成功（方式: memfd fd + MAP_ANONYMOUS，首次失败: "
                            + firstError.getClass().getSimpleName() + "）");
        } catch (Exception e) {
            return fmt(2, name, false, e,
                    "匿名 RWX mmap 被拒（空 fd 方式: " + firstError.getClass().getSimpleName()
                            + "; memfd 重试亦失败）");
        }
    }

    // ------------------------------------------------- 探针③ memfd + PROT_EXEC

    /**
     * memfd_create("uml_probe") → 写入数据 → ftruncate 64KB → MAP_SHARED RWX mmap。
     * 「memfd 缝隙」测试：即使文件系统 W^X 被封，memfd 匿名内存文件可能仍可执行。
     * 注：规范建议 adoptFd(int) 包装；因 SDK 无公开的 FileDescriptor→int 通道
     * （getInt$() 为 hidden API，targetSdk 36 下有被封锁风险），改用公开 API
     * ParcelFileDescriptor.dup(FileDescriptor) 拿包装 fd，mmap 语义等价。
     */
    private String probe3MemfdExec() {
        String name = "memfd + PROT_EXEC";
        if (Build.VERSION.SDK_INT < 29) {
            return fmt(3, name, false, null,
                    "memfd_create 需 API 29+，当前 API=" + Build.VERSION.SDK_INT);
        }
        ParcelFileDescriptor pfd = null;
        FileDescriptor mfd = null;
        try {
            mfd = Os.memfd_create("uml_probe", 0);
            byte[] data = new byte[PROBE_SIZE];
            for (int i = 0; i < data.length; i++) data[i] = (byte) (0x90); // NOP sled 填充
            ByteBuffer buf = ByteBuffer.wrap(data);
            while (buf.hasRemaining()) {
                Os.write(mfd, buf);
            }
            Os.ftruncate(mfd, PROBE_SIZE);
            pfd = ParcelFileDescriptor.dup(mfd);
            FileDescriptor wrapped = pfd.getFileDescriptor();
            long addr = Os.mmap(0, PROBE_SIZE,
                    OsConstants.PROT_READ | OsConstants.PROT_WRITE | OsConstants.PROT_EXEC,
                    OsConstants.MAP_SHARED, wrapped, 0);
            Os.munmap(addr, PROBE_SIZE);
            return fmt(3, name, true, null,
                    "memfd MAP_SHARED RWX mmap 成功 — memfd 缝隙存在");
        } catch (Exception e) {
            return fmt(3, name, false, e, "memfd RWX mmap 被拒/异常");
        } finally {
            if (pfd != null) try { pfd.close(); } catch (Exception ignored) { }
            if (mfd != null) try { Os.close(mfd); } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------- 探针④ stub 前置信息（必过）

    /**
     * 只读信息采集：页大小、SELinux 上下文、ABI、API 级别、targetSdk。
     * D1b 的域确认证据 + D8 的宿主页大小证据。
     */
    private String probe4StubInfo() {
        String name = "stub 前置信息";
        StringBuilder note = new StringBuilder();
        try {
            note.append("pagesize=").append(Os.sysconf(OsConstants._SC_PAGESIZE));
        } catch (Exception e) {
            note.append("pagesize=?(").append(e.getClass().getSimpleName()).append(')');
        }
        note.append(", selinux=").append(readSelinuxContext());
        note.append(", abi=").append(Build.SUPPORTED_ABIS[0]);
        note.append(", api=").append(Build.VERSION.SDK_INT)
            .append('(').append(Build.VERSION.RELEASE).append(')');
        note.append(", targetSdk=").append(getApplicationInfo().targetSdkVersion);
        return fmt(4, name, true, null, note.toString());
    }

    private String readSelinuxContext() {
        try (FileInputStream fis = new FileInputStream("/proc/self/attr/current")) {
            byte[] b = new byte[256];
            int n = fis.read(b);
            if (n <= 0) return "?";
            return new String(b, 0, n, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "unreadable(" + e.getClass().getSimpleName() + ")";
        }
    }

    // ------------------------------------------------------------ 结果格式化

    /** 统一行格式：[①] 名称 | PASS/FAIL | errno=N(msg) | 语义备注 */
    private String fmt(int idx, String name, boolean pass, Exception e, String note) {
        String errnoPart;
        if (e instanceof ErrnoException) {
            ErrnoException ee = (ErrnoException) e;
            errnoPart = "errno=" + ee.errno + "(" + errnoName(ee.errno) + ") " + ee.getMessage();
        } else if (e != null) {
            errnoPart = "errno=- " + e.getClass().getSimpleName() + ": " + e.getMessage();
        } else {
            errnoPart = "errno=-";
        }
        String line = "[" + CIRCLED[idx - 1] + "] " + name + " | "
                + (pass ? "PASS" : "FAIL") + " | " + errnoPart + " | " + note;
        results.add(line);
        return line;
    }

    /** 常见 errno 的符号名（D1a 语义解读用）。 */
    private static String errnoName(int errno) {
        switch (errno) {
            case 1:  return "EPERM";
            case 2:  return "ENOENT";
            case 13: return "EACCES";
            case 22: return "EINVAL";
            case 24: return "EMFILE";
            case 38: return "ENOSYS";
            case 95: return "EOPNOTSUPP";
            default: return "errno_" + errno;
        }
    }
}
