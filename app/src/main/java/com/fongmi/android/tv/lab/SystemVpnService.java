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
 * 系统级 VPN 服务（最小闭环第一版）。
 *
 * 授权链路：LabVpnActivity 弹系统授权窗 → 成功后 start 本服务。
 * 本版行为：建立 TUN 接口 + 前台通知（VPN 图标点亮），但不添加 0.0.0.0/0
 * 全流量路由，避免无内核消费时整机断网。
 *
 * 完整版（下一版）：引入 tun2socks/gvisor 内核消费 TUN 包，桥接到 mihomo
 * SOCKS5 (127.0.0.1:7890)，再 addRoute 全流量，实现真正的系统级代理。
 */
public class SystemVpnService extends VpnService {

    private static final String CHANNEL_ID = "system_vpn";
    private static final int NOTIFY_ID = 100;
    private static final String ACTION_STOP = "vpn_stop";
    private static volatile boolean runningState = false;

    private ParcelFileDescriptor tunFd;

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

    private static volatile boolean stateCache = false;

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
        // 仅加虚拟地址，不加全流量路由 —— 第一版防断网
        builder.addAddress("10.9.0.2", 32);
        tunFd = builder.establish();
        if (tunFd == null) {
            throw new IOException("establish failed (user revoked?)");
        }
        runningState = true;
        startForeground(NOTIFY_ID, buildNotification("系统级VPN已开启（等待流量内核）"));
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
