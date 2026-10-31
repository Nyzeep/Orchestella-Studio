package dev.orchestella.umlrunner;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * 前台服务：UML 内核存活期把 App 抬到 foreground 状态，规避 HyperOS cgroup
 * freezer 对后台 App（连带其 UML 子进程）的整组冻结。
 *
 * 设备级实锤（2026-11-07 设备轮 §7.1.6，T5 门2 attempt1）：屏幕 Dozing 后数分钟内
 * runner App 与 UML 子进程同入冻结 cgroup（cgroup.freeze=1），guest uptime 冻结、
 * console 停 tick，冻结 33 分钟零内存漂移——后台形态在 HyperOS 上不可长驻。
 * 因此前台服务是长驻场景（W-064 门2）的产品化前提，不是可选优化。
 *
 * 设计：本服务只负责「前台状态 + 最低配通知」（IMPORTANCE_LOW，无声音无横幅）；
 * PARTIAL_WAKE_LOCK 由 RunnerActivity 按 UML 存活期管理（acquire 带超时上限、
 * finally 释放），两者解耦——服务被系统回收时 wake lock 语义不受牵连。
 *
 * foregroundServiceType 用 specialUse（targetSdk 34+ 必须显式声明 type）：
 * 研究启动器不适合 existingTypes 的任何语义（dataSync/mediaPlayback 等都会引入
 * 不当的系统预期），subtype 写入 manifest property 说明用途。
 */
public class KeepAliveService extends Service {

    private static final String CHANNEL_ID = "uml-runner-keepalive";
    private static final int NOTIFICATION_ID = 1;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                "UML runner", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("UML Runner")
                .setContentText("UML kernel running")
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        // START_NOT_STICKY：通知与前台态的语义必须绑死「UML 存活期」——若用 STICKY，
        // 系统会在 runner 进程死后重建服务，通知仍称 "UML kernel running" 而 UML 已不在，
        // 误导设备窗口判读。保活断档由 RunnerActivity 的 startForegroundService 再次拉起。
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
