package io.github.nyzeep.droidlayer;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.Toast;

/**
 * Provides explanation and tuning instructions for Android 12+ Phantom Process Killer
 * (ADR-0011 §6, AC-2).
 */
public final class PhantomProcessKillerGuide {
    public static final String ADB_COMMAND =
            "adb shell /system/bin/device_config put activity_manager max_phantom_processes 2147483647";

    private PhantomProcessKillerGuide() {}

    public static void showDialog(Context context) {
        String message = "【什么是幻影进程查杀（Phantom Process Killer）？】\n"
                + "Android 12 及更高版本默认限制后台应用派生的子进程总数最多为 32 个，且当子进程 CPU 突发占用较高时会被系统强行终止。\n\n"
                + "【Orchestella Studio 的防御架构】\n"
                + "Orchestella Studio 采用宿主进程收敛设计：所有的容器、编译和系统进程均运行于 Linux Guest 内核内部，而非 Android 应用沙箱层派生的子进程，天然规避了大多数查杀风险。\n\n"
                + "【彻底解除限制指引】\n"
                + "1. Android 14+ 官方开关：进入系统「设置」→「开发者选项」→ 开启「停用子进程限制（Disable child process restrictions）」即可。\n\n"
                + "2. Android 12/13/14 ADB 命令（一键解除 32 进程上限）：\n"
                + ADB_COMMAND;

        new AlertDialog.Builder(context)
                .setTitle("Android 12+ 幻影进程指引")
                .setMessage(message)
                .setPositiveButton("复制 ADB 命令", (dialog, which) -> {
                    ClipboardManager clipboard =
                            (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("ADB Command", ADB_COMMAND));
                        Toast.makeText(context, "已复制 ADB 解除命令到剪贴板", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("了解", null)
                .show();
    }
}
