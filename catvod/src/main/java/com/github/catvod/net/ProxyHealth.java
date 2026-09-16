package com.github.catvod.net;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 上游代理的可用性探测（带缓存）。
 *
 * <p>用途只有一个：<b>规则命中但代理不可用时别把能直连的站弄挂</b>。
 * 典型场景是设置了 {@code http://127.0.0.1:7890}（内嵌 mihomo 混合口）却没把
 * mihomo/VPN 开关打开——此时命中规则的域名如果硬走代理必然连接失败，
 * 不如退回直连（至少没被墙的域名还能用），并把原因打进日志。
 *
 * <p>探测是一次 TCP 握手，结果缓存 {@link #TTL_MS} 毫秒：同一上游每 15 秒最多
 * 真连一次，避免每个请求都去握手。
 */
public final class ProxyHealth {

    private static final long TTL_MS = 15000L;
    private static final int TIMEOUT_MS = 800;
    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

    private ProxyHealth() {
    }

    public static boolean isUp(String host, int port) {
        if (host == null || host.isEmpty() || port <= 0 || port > 65535) return false;
        Entry entry = CACHE.computeIfAbsent(host + ":" + port, key -> new Entry());
        long now = System.currentTimeMillis();
        synchronized (entry) {
            if (now - entry.at < TTL_MS) return entry.up;
            entry.at = now;
            entry.up = probe(host, port);
            return entry.up;
        }
    }

    /** 供设置页/诊断用：强制下次重新探测。 */
    public static void invalidate() {
        CACHE.clear();
    }

    private static boolean probe(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static final class Entry {

        private long at;
        private boolean up;
    }
}
