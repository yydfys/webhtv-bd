package com.fongmi.android.tv.server;

import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.player.PlaybackRouteRegistry;
import com.fongmi.android.tv.utils.NetUtil;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.Proxy;

public class Server {

    /** IPv6 通配地址；Android/Linux 默认双栈（bindv6only=0），同端口同时收 IPv4 与 IPv6。 */
    private static final String IPV6_ANY = "::";

    private volatile PlaybackService service;
    private volatile Nano nano;
    private volatile boolean manage;
    private PlaybackRouteRegistry.Registration routeRegistration;

    private static class Loader {
        static volatile Server INSTANCE = new Server();
    }

    public static Server get() {
        return Loader.INSTANCE;
    }

    public PlaybackService getService() {
        return service;
    }

    public synchronized void setService(PlaybackService service) {
        this.service = service;
    }

    public synchronized void clearService(PlaybackService expected) {
        if (service == expected) service = null;
    }

    public boolean isRunning() {
        return nano != null;
    }

    public String getAddress() {
        return getAddress(false);
    }

    public String getAddress(int tab) {
        return getAddress(false) + "?tab=" + tab;
    }

    public String getAddress(String path) {
        return getAddress(true) + path;
    }

    public String getAddress(boolean local) {
        // IPv6 地址必须带方括号（[2408::5]:9978），否则 URL 解析会把冒号当端口分隔符
        return NetUtil.httpUrl(local ? "127.0.0.1" : NetUtil.lanHost(), Proxy.getPort(), null);
    }

    public synchronized void startManage() {
        manage = true;
        start();
    }

    public synchronized void stopManage() {
        manage = false;
        if (service == null) stop();
    }

    public synchronized void start() {
        if (nano != null) return;
        for (int i = 9978; i < 9999; i++) {
            // 双栈优先：绑定 "::"（IPv6 通配）。Android/Linux 默认 bindv6only=0，
            // 同一端口既能收 IPv4 也能收 IPv6；纯 IPv6 局域网里 MT / 浏览器才连得上。
            Nano candidate = startOn(IPV6_ANY, i);
            if (candidate != null && !probeIpv4(i)) {
                // 极少数 ROM 把 IPV6_V6ONLY 打开 → 双栈失效，IPv4 客户端会连不上。
                // 探测到 IPv4 回环不通就放弃这次绑定，退回纯 IPv4 通配地址。
                try {
                    candidate.stop();
                } catch (Throwable ignored) {
                }
                candidate = null;
            }
            if (candidate == null) candidate = startOn(null, i);
            if (candidate == null) continue;
            nano = candidate;
            Proxy.set(i);
            if (routeRegistration != null) routeRegistration.close();
            routeRegistration = PlaybackRouteRegistry.registerAppService(i, PlaybackRouteRegistry.AppOwner.MAIN_SERVER);
            break;
        }
    }

    private Nano startOn(String hostname, int port) {
        Nano candidate = hostname == null ? new Nano(port) : new Nano(hostname, port);
        try {
            // 读超时：500ms 太短，keep-alive 连接空闲超时会被服务端直接掐断，
            // 且客户端（如 MT 管理器的 WebDAV 上传）在 1s 内才发请求体时会被判超时，
            // 表现为客户端 "unexpected end of stream"。这里放宽到 30s。
            candidate.start(30000);
            return candidate;
        } catch (Throwable e) {
            try {
                candidate.stop();
            } catch (Throwable ignored) {
            }
            return null;
        }
    }

    /** 双栈绑定后的自检：IPv4 回环能否握手（拿不到结果一律按「不通」处理，走 IPv4 回退）。 */
    private boolean probeIpv4(int port) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), port), 800);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }


    public void stop() {
        Nano expected = nano;
        Task.execute(() -> {
            synchronized (this) {
                if (manage || service != null || nano != expected) return;
                stopNow();
            }
        });
    }

    public synchronized void shutdown() {
        manage = false;
        service = null;
        stopNow();
    }

    private void stopNow() {
        if (nano != null) nano.stop();
        nano = null;
        if (routeRegistration != null) routeRegistration.close();
        routeRegistration = null;
    }
}
