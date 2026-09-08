package com.fongmi.android.tv.lab;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.R;

import java.io.File;
import java.io.IOException;

/**
 * 系统级 VPN 服务（内嵌 mihomo 内核版）。
 *
 * 架构（= ClashMetaForAndroid 正统实现）：
 *   VpnService 建立 TUN → detachFd() → nativeStart(fd, configPath)
 *   → libmihomo.so（c-shared 内嵌同进程）hub.ApplyConfig 加载完整 config
 *   → sing_tun.New(LC.Tun{FileDescriptor: fd, DNSHijack: ...}) 接管 fd
 *   → mihomo 自己劫持 DNS + fake-ip → 域名级分流全部生效
 *
 * 配置来源（本阶段）：读 {sdcard}/WebHTV/mihomo/config.yaml（与 lab CLI 版
 * 同一路径，用户在 NAS 生成后同步/订阅下载到此处）。三模式配置面板后续阶段加。
 *
 * 入口：增强功能 → 实验室 → mihomo → 启动代理 → 系统级VPN（lab_template.json
 * 中 mihomo run_config 的 clicks "系统级VPN" action=vpn）。
 *
 * 🔴 Android 8.0+ 前台服务时限（崩溃修复，2026-09-08）：
 *   startForegroundService() 之后必须在 5 秒内调用 startForeground()，
 *   否则系统抛 RemoteServiceException 杀进程。此前 startForeground 放在
 *   establish/nativeStart（耗时初始化）之后，5 秒必然超时 → 崩溃。
 *   修复：onStartCommand 最先 startForeground 占位（"正在启动"），
 *   耗时初始化放子线程，成功后再把通知更新为"运行中"。
 */
public class SystemVpnService extends VpnService {

    private static final String CHANNEL_ID = "system_vpn";
    private static final int NOTIFY_ID = 100;
    private static final String ACTION_STOP = "vpn_stop";
    private static volatile boolean runningState = false;

    /** 与 lab CLI 版共用同一份配置，用户在 NAS 维护 / 订阅下载到这里 */
    private static final String CONFIG_PATH =
            "/storage/emulated/0/WebHTV/mihomo/config.yaml";

    private ParcelFileDescriptor tunFd;

    static {
        try {
            System.loadLibrary("mihomo");
        } catch (UnsatisfiedLinkError e) {
            android.util.Log.e("SystemVpn", "libmihomo.so 加载失败", e);
        }
    }

    private static native int nativeLoadConfig(String path);

    private static native int nativeStart(int fd, String configPath);

    private static native int nativeStop();

    private static native int nativeIsRunning();

    public static void start(Context context) {
        Intent intent = new Intent(context, SystemVpnService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            ContextCompat.startForegroundService(context, intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.startService(new Intent(context, SystemVpnService.class).setAction(ACTION_STOP));
    }

    public static boolean isRunning() {
        return runningState;
    }

    public static String getConfigPath() {
        return CONFIG_PATH;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            return START_NOT_STICKY;
        }

        // 🔴 必须最先前台化：startForegroundService() 后 5 秒内不调
        // startForeground() 会被系统判死（RemoteServiceException）。
        // 先占位通知，再异步做耗时的 establish + nativeStart。
        startForeground(NOTIFY_ID, buildNotification("正在启动系统代理…"));

        if (tunFd == null && !runningState) {
            // 耗时初始化放子线程，避免阻塞主线程 & 拖垮前台化时限
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        startVpn();
                        updateNotification();
                    } catch (Exception e) {
                        android.util.Log.e("SystemVpn", "start failed", e);
                        runningState = false;
                        stopSelf();
                    }
                }
            }, "system-vpn-start");
            worker.start();
        } else {
            // 已在运行：只刷新通知即可
            updateNotification();
        }
        return START_STICKY;
    }

    private void startVpn() throws IOException {
        // 配置存在性检查（读不到时给明确提示，而不是静默空跑）
        File cfg = new File(CONFIG_PATH);
        if (!cfg.exists() || cfg.length() == 0) {
            throw new IOException("config not found: " + CONFIG_PATH);
        }

        Builder builder = new Builder();
        builder.setSession("WebHTV 系统代理");
        builder.setMtu(1500);
        // 虚拟地址 + 全流量进 TUN（真正的系统级）
        builder.addAddress("10.9.0.2", 32);
        builder.addRoute("0.0.0.0", 0);
        builder.addRoute("::", 0);

        // 🔴 关键：排除自身 app，防止出站环路！
        // 内嵌 mihomo 出站连机场节点时若也被 TUN 截获 → 无限循环。
        // 标准 VPN 实现（v2rayNG/sing-box/CMFA）都排除自己。
        try {
            builder.addDisallowedApplication(getPackageName());
        } catch (Exception e) {
            android.util.Log.e("SystemVpn", "addDisallowedApplication failed", e);
        }

        tunFd = builder.establish();
        if (tunFd == null) {
            throw new IOException("establish failed (user revoked?)");
        }

        // fd 所有权交给内嵌 mihomo 内核（detach 后由 native 负责 close）
        int fd = tunFd.detachFd();
        int rc = nativeStart(fd, CONFIG_PATH);
        if (rc != 0) {
            android.util.Log.e("SystemVpn", "nativeStart failed rc=" + rc);
            tunFd = null;
            throw new IOException("mihomo nativeStart failed rc=" + rc);
        }
        tunFd = null;

        runningState = true;
    }

    private void updateNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFY_ID, buildNotification(runningState ? "系统级VPN运行中" : "正在启动系统代理…"));
        }
    }

    private Notification buildNotification(String text) {
        Intent stop = new Intent(this, SystemVpnService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 0, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("WebHTV 系统代理")
                .setContentText(text)
                .setOngoing(true)
                .addAction(0, "关闭", stopPending)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "系统代理", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void shutdown() {
        runningState = false;
        nativeStop();
        if (tunFd != null) {
            try {
                tunFd.close();
            } catch (IOException ignored) {
            }
            tunFd = null;
        }
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
