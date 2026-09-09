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
import com.fongmi.android.tv.event.VpnStateEvent;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
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
    private static final int NOTIFY_FAIL_ID = 101;
    private static final String ACTION_STOP = "vpn_stop";
    private static final String ACTION_START_PROXY = "start_proxy";
    private static final String ACTION_START_VPN = "start_vpn";
    private static final String ACTION_STOP_VPN = "stop_vpn";
    private static final String ACTION_STOP_ALL = "stop_all";
    private static final String ACTION_RESTART_PROXY = "restart_proxy";

    /** 与 lab CLI 版共用同一份配置：用户在 NAS 维护 / 订阅生成后放这里 */
    private static final String HOME_DIR = "/storage/emulated/0/WebHTV/mihomo";
    private static final String CONFIG_PATH = HOME_DIR + "/config.yaml";
    /** App 自动生成 config.yaml 的旁写标记：存在 = app 生成（可被订阅重生成覆盖）；
     *  不存在且 config.yaml 存在 = 用户手动放入（永不覆盖，订阅地址被忽略）。 */
    private static final String APP_GENERATED_MARKER = HOME_DIR + "/config.yaml.app_generated";
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

    /** 订阅变更后重启内核：不碰持久化开关，仅 native 全停再拉起 7890。
     *  restoreVpn=true 时在同一 worker 内顺序恢复 TUN（proxy 就绪 → 挂 TUN）。 */
    public static void restartProxy(Context context, boolean restoreVpn) {
        Intent intent = new Intent(context, SystemVpnService.class)
                .setAction(ACTION_RESTART_PROXY)
                .putExtra("restore_vpn", restoreVpn);
        if (Build.VERSION.SDK_INT >= 26) {
            ContextCompat.startForegroundService(context, intent);
        } else {
            context.startService(intent);
        }
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

    /** 当前运行态对应的字符串资源（设置页 / VPN 弹窗共用）。
     *  startingType：1=mihomo 正在启动，2=VPN 正在启动，0=无启动中态。
     *  实时布尔优先：启动完成一瞬间布尔置 true，即使事件尚未送达也显示正确态。 */
    public static int getStateTextRes(int startingType) {
        if (isVpnRunning()) return R.string.vpn_state_vpn;
        if (isProxyRunning()) return R.string.vpn_state_proxy;
        if (startingType == 1) return R.string.vpn_state_proxy_starting;
        if (startingType == 2) return R.string.vpn_state_vpn_starting;
        return R.string.vpn_state_off;
    }

    public static String getHomeDir() {
        return HOME_DIR;
    }

    public static String getConfigPath() {
        return CONFIG_PATH;
    }

    // ---------------- config 来源判断（供 LabActivity 保存订阅时使用） ----------------

    /** config.yaml 是否已存在 */
    public static boolean isConfigExists() {
        File cfg = new File(CONFIG_PATH);
        return cfg.exists() && cfg.length() > 0;
    }

    /** config.yaml 是否由 App 自动生成（有旁写标记） */
    public static boolean isAppGeneratedConfig() {
        return new File(APP_GENERATED_MARKER).exists();
    }

    /** 删除 App 生成的 config.yaml + 标记。仅限 app 生成；手动 config 永不删除。 */
    public static void deleteAppGeneratedConfig() {
        if (!isAppGeneratedConfig()) return;
        new File(CONFIG_PATH).delete();
        new File(APP_GENERATED_MARKER).delete();
        android.util.Log.i("SystemVpn", "app generated config deleted for resubscribe");
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
                VpnStateEvent.proxyStarting();
                startProxyInBackground();
                break;
            case ACTION_START_VPN:
                startForeground(NOTIFY_ID, buildNotification("正在启动系统代理…"));
                VpnStateEvent.vpnStarting();
                startVpnInBackground();
                break;
            case ACTION_STOP_VPN:
                stopVpnInternal();
                break;
            case ACTION_STOP_ALL:
                stopAllInternal();
                break;
            case ACTION_RESTART_PROXY:
                startForeground(NOTIFY_ID, buildNotification("正在应用新订阅…"));
                VpnStateEvent.proxyStarting();
                restartProxyInternal(intent.getBooleanExtra("restore_vpn", false));
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

    /** 订阅变更重启：native 全停 → 重新 ensureConfigAssets（新订阅会删旧重生成）→ 起 7890。
     *  不写 LabConfig 开关（开关状态由 UI 保持），只重载内核配置。
     *  restoreVpn=true 时顺序恢复 TUN（VpnService 已授权过则 establish 无需再弹窗）。 */
    private void restartProxyInternal(boolean restoreVpn) {
        Thread worker = new Thread(() -> {
            try {
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
                // 订阅变更时 LabActivity 已调用 deleteAppGeneratedConfig()，
                // ensureConfigAssets 会用新订阅重新生成 config + app 标记
                ensureConfigAssets();
                ensureGeoAssets();
                int rc = nativeStartProxy(CONFIG_PATH);
                if (rc != 0) throw new IOException("mihomo 内核重启失败 rc=" + rc);
                proxyState = true;
                VpnStateEvent.proxy();
                updateNotification("mihomo 代理运行中 · 127.0.0.1:7890");
                if (restoreVpn) {
                    // VPN 未授权时 establish 抛异常：只回滚 VPN，不连坐杀掉 7890
                    try {
                        startVpnInternal();
                    } catch (Exception vpnErr) {
                        android.util.Log.e("SystemVpn", "vpn restore failed after resubscribe: " + vpnErr.getMessage());
                        vpnState = false;
                        VpnStateEvent.proxy();
                        if (tunFd != null) {
                            try {
                                tunFd.close();
                            } catch (IOException ignored) {
                            }
                            tunFd = null;
                        }
                        updateNotification("mihomo 代理运行中 · 127.0.0.1:7890");
                    }
                }
            } catch (Exception e) {
                android.util.Log.e("SystemVpn", "proxy restart failed", e);
                proxyState = false;
                failStop(e.getMessage());
            }
        }, "mihomo-proxy-restart");
        worker.start();
    }

    private void startProxyInBackground() {
        Thread worker = new Thread(() -> {
            try {
                ensureConfigAssets();
                ensureGeoAssets();
                if (!proxyState) {
                    int rc = nativeStartProxy(CONFIG_PATH);
                    if (rc != 0) throw new IOException("mihomo 内核启动失败 rc=" + rc);
                    proxyState = true;
                    VpnStateEvent.proxy();
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
        boolean proxyWasRunning = proxyState;
        Thread worker = new Thread(() -> {
            try {
                ensureConfigAssets();
                ensureGeoAssets();
                if (!proxyState) {
                    int rc = nativeStartProxy(CONFIG_PATH);
                    if (rc != 0) throw new IOException("mihomo 内核启动失败 rc=" + rc);
                    proxyState = true;
                    VpnStateEvent.proxy();
                }
                startVpnInternal();
            } catch (Exception e) {
                android.util.Log.e("SystemVpn", "vpn start failed", e);
                vpnState = false;
                if (proxyWasRunning) {
                    // 🔴 proxy 原本就在跑：VPN 失败只回滚 VPN，保留 mihomo 7890，
                    // 不连带杀掉可用代理（2026-09-08 修复：点 VPN 后 7890 也消失）
                    nativeStopTun();
                    if (tunFd != null) {
                        try {
                            tunFd.close();
                        } catch (IOException ignored) {
                        }
                        tunFd = null;
                    }
                    VpnStateEvent.proxy();
                    updateNotification("mihomo 代理运行中 · 127.0.0.1:7890");
                } else {
                    proxyState = false;
                    failStop(e.getMessage());
                }
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
        builder.setMtu(9000);
        // 虚拟地址 + 全流量进 TUN（真正的系统级）
        // 与 CMFA 对齐：/30 子网 gateway=172.19.0.1，portal/dns=172.19.0.2（sing-tun system stack 必需）
        builder.addAddress("172.19.0.1", 30);
        // 🔴 系统 DNS 指向 TUN 内 portal 172.19.0.2：app 的 DNS 查询进 TUN →
        // sing_tun 的 any:53 hijack 接管 → mihomo fake-ip 解析，形成完整闭环。
        // 缺这行 → app 仍用原 WiFi/运营商 DNS，DNS 包不进 TUN，所有域名解析失败。
        builder.addDnsServer(InetAddress.getByName("172.19.0.2"));
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
            // 🔴 挂载失败时 fd 已 detach，必须主动 close，否则 TUN 路由残留成黑洞
            //（全流量进 TUN 但内核没接管 → 其它 app 全断网）
            try {
                ParcelFileDescriptor.adoptFd(fd).close();
            } catch (Exception ignored) {
            }
            tunFd = null;
            throw new IOException("mihomo TUN 挂载失败 rc=" + rc);
        }
        tunFd = null;

        vpnState = true;
        VpnStateEvent.vpn();
        updateNotification("系统级 VPN 运行中 · 全流量已代理");
    }

    private void stopVpnInternal() {
        vpnState = false;
        LabConfig.get().setSystemVpn(false);
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
            VpnStateEvent.proxy();
            updateNotification("mihomo 代理运行中 · 127.0.0.1:7890");
        } else {
            VpnStateEvent.off();
            stopForeground(true);
            stopSelf();
        }
    }

    private void stopAllInternal() {
        vpnState = false;
        proxyState = false;
        // 🔴 同步持久化开关：通知栏关闭/全停后，App 内 mihomo/VPN 开关跟随关闭
        LabConfig.get().setMihomo(false);
        LabConfig.get().setSystemVpn(false);
        nativeStopAll();
        if (tunFd != null) {
            try {
                tunFd.close();
            } catch (IOException ignored) {
            }
            tunFd = null;
        }
        VpnStateEvent.off();
        stopForeground(true);
        stopSelf();
    }

    private void failStop(String message) {
        try {
            vpnState = false;
            proxyState = false;
            LabConfig.get().setMihomo(false);
            LabConfig.get().setSystemVpn(false);
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
        VpnStateEvent.off();
        android.util.Log.e("SystemVpn", "fail: " + message);
        try {
            // 失败原因用独立 ID 发一条非 ongoing、可清除的通知，保留在通知栏让用户/老大
            // 看得到错误码（rc=-2 内核配置加载失败 / rc=-3 sing_tun 挂载失败 / establish 失败…），
            // 之后才撤掉前台服务主通知，避免"点了没反应 / 不知道为啥失败"。
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                Notification fail = new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentTitle("WebHTV 系统代理")
                        .setContentText("启动失败：" + message)
                        .setAutoCancel(true)
                        .build();
                manager.notify(NOTIFY_FAIL_ID, fail);
            }
        } catch (Throwable ignored) {
        }
        // 失败也先前台化再停，避免二次崩溃
        startForeground(NOTIFY_ID, buildNotification("系统代理启动失败"));
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
     * 确保 config.yaml 就绪（订阅优先，手动 config 永不覆盖）。
     *
     * 策略（2026-09-08 老大定稿）：
     *   - config.yaml 已存在：
     *       · 用户手动放入（无 app 标记）→ 保留，订阅地址被忽略（UI 层提示）
     *       · App 生成（有标记）→ 保留；订阅变化时由 LabActivity 删旧重生成，
     *         这里绝不重复覆盖/重复解析
     *   - config.yaml 不存在：
     *       · 填了订阅 → 用订阅模板生成 + 旁写 app_generated 标记
     *       · 没填订阅 → 不自动生成直连模板（等用户在 UI 填订阅；UI 层已阻止无订阅启动）
     */
    private void ensureConfigAssets() {
        File dir = new File(HOME_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            android.util.Log.w("SystemVpn", "mkdirs failed: " + HOME_DIR);
        }
        File cfg = new File(CONFIG_PATH);
        if (cfg.exists() && cfg.length() > 0) {
            // 🔴 2026-09-09：App 生成的旧版模板 config（无 external-controller）自动重生成，
            // 让节点管理 UI（9090 API）、allow-lan、节点总组等新字段生效。
            // 手动 config（无 app 标记）永不覆盖。
            if (isAppGeneratedConfig()) {
                String content = readText(cfg);
                if (content == null || !content.contains("external-controller")) {
                    android.util.Log.i("SystemVpn", "app config is legacy template, deleting for regenerate");
                    cfg.delete();
                } else {
                    return;
                }
            } else {
                return;
            }
        }
        String sub = LabConfig.get().getSubUrl();
        if (TextUtils.isEmpty(sub)) {
            android.util.Log.w("SystemVpn", "no config.yaml and no subscription, skip generating");
            return;
        }
        try {
            String template = readAsset("mihomo/config_sub_template.yaml");
            if (template == null) {
                throw new IOException("read config_sub_template.yaml failed");
            }
            template = template.replace("__SUB_URL__", sub.trim());
            writeText(new File(CONFIG_PATH), template);
            writeText(new File(APP_GENERATED_MARKER), "");
            android.util.Log.i("SystemVpn", "config generated from subscription template + app_generated marker");
        } catch (Exception e) {
            android.util.Log.w("SystemVpn", "config generate failed: " + e.getMessage(), e);
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

    private String readText(File file) {
        try (InputStream in = new FileInputStream(file)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int len;
            while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- 通知 ----------------

    private void updateNotification(String text) {
        Notification noti = buildNotification(text);
        // 🔴 前台服务通知必须用 startForeground() 更新内容：
        //   manager.notify() 对已 startForeground 的服务在 MIUI/ColorOS 等 ROM
        //   上不刷新通知栏，表现为"点了 VPN 通知不切换"。startForeground 幂等，
        //   重复调用只是更新内容，不会重新触发 5 秒前台时限。
        try {
            startForeground(NOTIFY_ID, noti);
        } catch (Throwable t) {
            android.util.Log.e("SystemVpn", "updateNotification startForeground failed", t);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.notify(NOTIFY_ID, noti);
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
