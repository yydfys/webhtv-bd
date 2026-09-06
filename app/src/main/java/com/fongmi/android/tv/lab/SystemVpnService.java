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

import java.io.IOException;

/**
 * 系统级 VPN 服务（完整版）。
 *
 * 架构：VpnService 建立 TUN → 将 fd 交给内置 tun2socks 内核(libtun2socks.so)
 * → 内核(gVisor netstack)消费 TUN 包 → 以 SOCKS5 连 mihomo 127.0.0.1:7890
 * → 全机流量(0.0.0.0/0)进入隧道，实现真正的系统级代理。
 *
 * 入口：增强功能 → 实验室 → mihomo → 启动代理 → 系统级VPN（lab_template.json
 * 中 mihomo run_config 的 clicks "系统级VPN" action=vpn value=127.0.0.1:7890）。
 */
public class SystemVpnService extends VpnService {

    private static final String CHANNEL_ID = "system_vpn";
    private static final int NOTIFY_ID = 100;
    private static final String ACTION_STOP = "vpn_stop";
    private static volatile boolean runningState = false;

    private ParcelFileDescriptor tunFd;

    static {
        try {
            System.loadLibrary("tun2socks");
        } catch (UnsatisfiedLinkError e) {
            android.util.Log.e("SystemVpn", "libtun2socks.so 加载失败", e);
        }
    }

    private static native int nativeStart(int fd);

    private static native int nativeStop();

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
        if (tunFd != null) {
            updateNotification();
            return START_STICKY;
        }
        try {
            startVpn();
        } catch (Exception e) {
            android.util.Log.e("SystemVpn", "start failed", e);
            stopSelf();
        }
        return START_STICKY;
    }

    private void startVpn() throws IOException {
        Builder builder = new Builder();
        builder.setSession("WebHTV 系统代理");
        builder.setMtu(1500);
        // 虚拟地址（TUN 接口自身地址，/30 网络内 1 个地址即可，用 /32 亦常见）
        builder.addAddress("10.9.0.2", 32);
        // 全流量进入 TUN —— 真正的系统级
        builder.addRoute("0.0.0.0", 0);
        builder.addRoute("::", 0);
        // DNS 走隧道
        builder.addDnsServer("8.8.8.8");
        builder.addDnsServer("1.1.1.1");

        // 🔴 关键：把自身 app 排除出 VPN 隧道，防止环路！
        // mihomo（跑在 lab/proot，与 app 同 uid）出站连接机场节点时，
        // 若自身没被排除，出站流量也会被 TUN 截获 → 转回 tun2socks → 又转给
        // mihomo → 再出站 → 又进 TUN → 无限循环 = 代理不通 + CPU 空转发热。
        // 标准 VPN 实现（v2rayNG/sing-box）都会 addDisallowedApplication 排除自己，
        // 让 mihomo 能直连出站；telegram 等其他 app 流量照常进隧道。
        try {
            builder.addDisallowedApplication(getPackageName());
        } catch (Exception e) {
            android.util.Log.e("SystemVpn", "addDisallowedApplication failed", e);
        }

        tunFd = builder.establish();
        if (tunFd == null) {
            throw new IOException("establish failed (user revoked?)");
        }

        // 把底层 fd 所有权交给 tun2socks 内核（detach 后由 native 负责 close）
        int fd = tunFd.detachFd();
        int rc = nativeStart(fd);
        if (rc != 0) {
            android.util.Log.e("SystemVpn", "nativeStart failed rc=" + rc);
            tunFd = null;
            throw new IOException("tun2socks nativeStart failed rc=" + rc);
        }
        tunFd = null;

        runningState = true;
        startForeground(NOTIFY_ID, buildNotification("系统级VPN运行中"));
    }

    private void updateNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFY_ID, buildNotification("系统级VPN运行中"));
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
