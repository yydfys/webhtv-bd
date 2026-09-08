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
import android.text.TextUtils;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 系统级 VPN + mihomo 代理服务（内嵌 mihomo 内核版，两步启动 2026-09-08）。
 *
 * 两级开关状态机（设置弹窗控制，mihomo 代理为总开关，系统级 VPN 依赖它）：
 *
 *   【mihomo代理】开 → startProxy：SetHomeDir + nativeStartProxy(config)
 *                       仅内核 + mixed-port 7890 就绪（app/爬虫可用 127.0.0.1:7890）
 *                       通知：「mihomo 代理运行中 · 127.0.0.1:7890」
 *   【系统级VPN】开（依赖 mihomo 开，授权后）→ startVpn：establish TUN →
 *                       nativeStartTun(fd) 挂 TUN，整机流量经 mihomo
 *                       通知：「系统级 VPN 运行中 · 全流量已代理」
 *   【系统级VPN】关 → stopVpn：只 nativeStopTun + 撤 TUN，mihomo 7890 继续
 *   【mihomo代理】关 → stopAll：nativeStopAll（TUN + 7890 全停）撤通知退出
 *
 * 🔴 两步启动（2026-09-08 修复"卡正在启动"根因）：
 *   内核启动顺序铁律 = 先内核+7890 就绪 → 再 establish TUN → 再挂 sing_tun。
 *   若先建 TUN 全流量进洞、内核还没读 TUN → geo/订阅联网变黑洞永久超时。
 *   内核启动在子线程，成功后再更新通知文案；失败则显示具体原因并停服。
 *
 * 🔴 Android 8.0+ 前台服务时限：
 *   startForegroundService() 后 5 秒内必须 startForeground()，否则系统判死。
 *   故 onStartCommand 最先 startForeground 占位，耗时初始化全部放子线程。
 */
public class SystemVpnService extends VpnService {

    private static final String CHANNEL_ID = "system_vpn";
    private static final int NOTIFY_ID = 100;
    private static final String ACTION_STOP = "vpn_stop";
    private static final String ACTION_START_PROXY = "start_proxy";
    private static final String ACTION_START_VPN = "start_vpn";
    private static final String ACTION_STOP_VPN = "stop_vpn";
    private static final String ACTION_STOP_ALL = "stop_all";

    /** 与 lab CLI 版共用同一份配置：用户在 NAS 维护 / 订阅生成后放这里 */
    private static final String HOME_DIR = "/storage/emulated/0/WebHTV/mihomo";
    private static final String CONFIG_PATH = HOME_DIR + "/config.yaml";
    private static final String GEOIP_PATH = HOME_DIR + "/GeoIP.dat";
    private static final String GEOSITE_PATH = HOME_DIR + "/GeoSite.dat";

    private static volatile boolean proxyState = false;
    private static volatile boolean vpnState = false;

    private ParcelFileDescriptor tunFd;

    static {
        try {
            System.loadLibrary("mihomo");
        } catch (UnsatisfiedLinkError e) {
            android.util.Log.e("SystemVpn", "libmihomo.so 加载失败", e);
        }
    }

    private static native int nativeStartProxy(String configPath);

    private static native int nativeStartTun(int fd);

    private static native int nativeStopTun();

    private static native int nativeStopAll();

    private static native int nativeIsProxyRunning();

    private static native int nativeIsTunRunning();

    /** mihomo 代理开关（仅内核 + 7890，无需系统授权） */
    public static void startProxy(Context context) {
        Intent intent = new Intent(context, SystemVpnService.class).setAction(ACTION_START_PROXY);
        if (Build.VERSION.SDK_INT >= 26) {
            ContextCompat.startForegroundService(context, intent);
        } else {
            context.startService(intent);
        }
    }

    /** 系统级 VPN 开关（必须先经 LabVpnActivity 系统授权） */
    public static void startVpn(Context context) {
        Intent intent = new Intent(context, SystemVpnService.class).setAction(ACTION_START_VPN);
        if (Build.VERSION.SDK_INT >= 26) {
            ContextCompat.startForegroundService(context, intent);
        } else {
            context.startService(intent);
        }
    }

    /** 只停 TUN，mihomo 7890 保留（系统级 VPN 关闭） */
    public static void stopVpn(Context context) {
        context.startService(new Intent(context, SystemVpnService.class).setAction(ACTION_STOP_VPN));
    }

    /** 全停：TUN + mihomo 内核 */
    public static void stopAll(Context context) {
        context.startService(new Intent(context, SystemVpnService.class).setAction(ACTION_STOP_ALL));
    }

    /** 兼容旧调用（原 start/stop 语义 = 全开/全停） */
    public static void start(Context context) {
        startVpn(context);
    }

    public static void stop(Context context) {
        stopAll(context);
    }

    public static boolean isProxyRunning() {
        return proxyState;
    }

    public static boolean isVpnRunning() {
        return vpnState;
    }

    /** 兼容旧调用：设置弹窗旧逻辑读 isRunning 判断 VPN 是否开 */
    public static boolean isRunning() {
        return vpnState;
    }

    public static String getHomeDir() {
        return HOME_DIR;
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
        if (intent == null) return START_STICKY;
        String action = intent.getAction();
        if (action == null) return START_STICKY;

        switch (action) {
            case ACTION_START_PROXY:
                startForeground(NOTIFY_ID, buildNotification("正在启动 mihomo 代理…"));
                startProxyInBackground();
                break;
            case ACTION_START_VPN:
                startForeground(NOTIFY_ID, buildNotification("正在启动系统代理…"));
                startVpnInBackground();
                break;
            case ACTION_STOP_VPN:
                stopVpnInternal();
                break;
            case ACTION_STOP_ALL:
                stopAllInternal();
                break;
            case ACTION_STOP:
                stopAllInternal();
                break;
            default:
                break;
        }
        return START_STICKY;
    }

    // ---------------- 后台启动 ----------------

    private void startProxyInBackground() {
        Thread worker = new Thread(() -> {
            try {
                ensureConfigAssets();
                ensureGeoAssets();
                if (!proxyState) {
                    int rc = nativeStartProxy(CONFIG_PATH);
                    if (rc != 0) throw new IOException("mihomo 内核启动失败 rc=" + rc);
                    proxyState = true;
                }
                updateNotification("mihomo 代理运行中 · 127.0.0.1:7890");
            } catch (Exception e) {
                android.util.Log.e("SystemVpn", "proxy start failed", e);
                proxyState = false;
                failStop(e.getMessage());
            }
        }, "mihomo-proxy-start");
        worker.start();
    }

    private void startVpnInBackground() {
        Thread worker = new Thread(() -> {
            try {
                ensureConfigAssets();
                ensureGeoAssets();
                if (!proxyState) {
                    int rc = nativeStartProxy(CONFIG_PATH);
                    if (rc != 0) throw new IOException("mihomo 内核启动失败 rc=" + rc);
                    proxyState = true;
                }
                startVpnInternal();
            } catch (Exception e) {
                android.util.Log.e("SystemVpn", "vpn start failed", e);
                proxyState = false;
                vpnState = false;
                failStop(e.getMessage());
            }
        }, "system-vpn-start");
        worker.start();
    }

    private void startVpnInternal() throws IOException {
        if (vpnState) {
            updateNotification("系统级 VPN 运行中 · 全流量已代理");
            return;
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
        int rc = nativeStartTun(fd);
        if (rc != 0) {
            android.util.Log.e("SystemVpn", "nativeStartTun failed rc=" + rc);
            tunFd = null;
            throw new IOException("mihomo TUN 挂载失败 rc=" + rc);
        }
        tunFd = null;

        vpnState = true;
        updateNotification("系统级 VPN 运行中 · 全流量已代理");
    }

    private void stopVpnInternal() {
        vpnState = false;
        nativeStopTun();
        if (tunFd != null) {
            try {
                tunFd.close();
            } catch (IOException ignored) {
            }
            tunFd = null;
        }
        if (proxyState) {
            // 只撤 VPN，mihomo 7890 继续服务
            updateNotification("mihomo 代理运行中 · 127.0.0.1:7890");
        } else {
            stopForeground(true);
            stopSelf();
        }
    }

    private void stopAllInternal() {
        vpnState = false;
        proxyState = false;
        nativeStopAll();
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

    private void failStop(String message) {
        try {
            vpnState = false;
            proxyState = false;
            nativeStopAll();
        } catch (Throwable ignored) {
        }
        if (tunFd != null) {
            try {
                tunFd.close();
            } catch (IOException ignored) {
            }
            tunFd = null;
        }
        // 失败也先前台化再停，避免二次崩溃
        startForeground(NOTIFY_ID, buildNotification("系统代理启动失败"));
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFY_ID, buildNotification("启动失败：" + message));
        android.util.Log.e("SystemVpn", "fail: " + message);
        stopForeground(true);
        stopSelf();
    }

    // ---------------- assets 释放 ----------------

    /**
     * 首次运行时把内置 GeoIP.dat / GeoSite.dat 释放到 homeDir。
     * 只拷贝不覆盖（已存在 = 用户/订阅更新过，保留）。
     */
    private void ensureGeoAssets() {
        File dir = new File(HOME_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            android.util.Log.w("SystemVpn", "mkdirs failed: " + HOME_DIR);
        }
        copyAssetIfMissing("mihomo/GeoIP.dat", GEOIP_PATH);
        copyAssetIfMissing("mihomo/GeoSite.dat", GEOSITE_PATH);
    }

    /**
     * config.yaml 缺失时生成内置模板。生成策略：
     *   1) 用户填了订阅 URL → 复制订阅版模板并替换 __SUB_URL__ 占位符
     *   2) 没填订阅 → 复制直连兜底模板（7890 能启动，规则全直连，提示补配置）
     * 已存在（用户手动放置 / NAS 同步）→ 不覆盖，保留用户配置优先。
     */
    private void ensureConfigAssets() {
        File dir = new File(HOME_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            android.util.Log.w("SystemVpn", "mkdirs failed: " + HOME_DIR);
        }
        File cfg = new File(CONFIG_PATH);
        if (cfg.exists() && cfg.length() > 0) return;
        String sub = LabConfig.get().getSubUrl();
        try {
            if (!TextUtils.isEmpty(sub)) {
                String template = readAsset("mihomo/config_sub_template.yaml");
                if (template != null) {
                    template = template.replace("__SUB_URL__", sub.trim());
                    writeText(new File(CONFIG_PATH), template);
                    android.util.Log.i("SystemVpn", "config generated from subscription template");
                    return;
                }
            }
            copyAssetIfMissing("mihomo/config_direct_template.yaml", CONFIG_PATH);
        } catch (Exception e) {
            android.util.Log.w("SystemVpn", "config generate failed, fallback direct template", e);
            copyAssetIfMissing("mihomo/config_direct_template.yaml", CONFIG_PATH);
        }
    }

    private void copyAssetIfMissing(String asset, String target) {
        File file = new File(target);
        if (file.exists() && file.length() > 0) return;
        try (InputStream in = App.get().getAssets().open(asset);
             OutputStream out = new FileOutputStream(file)) {
            byte[] buf = new byte[65536];
            int len;
            while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
            android.util.Log.i("SystemVpn", "asset released: " + asset + " -> " + target);
        } catch (Exception e) {
            android.util.Log.w("SystemVpn", "asset copy failed: " + asset, e);
        }
    }

    private String readAsset(String asset) {
        try (InputStream in = App.get().getAssets().open(asset)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int len;
            while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private void writeText(File file, String text) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    // ---------------- 通知 ----------------

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFY_ID, buildNotification(text));
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

    @Override
    public void onDestroy() {
        // 系统杀服务时兜底全停（进程将亡，尽量释放 fd）
        try {
            nativeStopAll();
        } catch (Throwable ignored) {
        }
        if (tunFd != null) {
            try {
                tunFd.close();
            } catch (IOException ignored) {
            }
            tunFd = null;
        }
        proxyState = false;
        vpnState = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
