package com.github.catvod.net;

import com.github.catvod.bean.Proxy;
import com.github.catvod.crawler.DebugEventLimiter;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Util;

import java.io.IOException;
import java.net.Authenticator;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

public class OkProxySelector extends ProxySelector {

    private static final long DEBUG_LOG_INTERVAL_MS = TimeUnit.SECONDS.toMillis(30);

    private final List<Proxy> proxy;
    private final ProxySelector system;
    private final DebugEventLimiter debugLogLimiter;
    private boolean authSet;

    public OkProxySelector() {
        proxy = new CopyOnWriteArrayList<>();
        // 保存"原来的"系统选择器：若全局默认已经被自己占用（重复构造），要取它背后那个，
        // 否则 fallback 会绕回自己形成死循环。
        ProxySelector current = ProxySelector.getDefault();
        system = current instanceof OkProxySelector ? ((OkProxySelector) current).system : current;
        debugLogLimiter = new DebugEventLimiter(64);
        Authenticator.setDefault(new ProxyAuthenticator(this));
    }

    /**
     * 装成 JVM 全局默认选择器。
     *
     * <p>这样所有"自己没有显式设置 proxySelector"的网络栈都会走壳内规则分流：
     * OkHttp 各类自建客户端（源 jar 里 new 的、Glide、Media3、更新/刮削/驱动检查等）、
     * 以及 HttpsURLConnection。不需要逐处去挂 selector。
     */
    public synchronized void install() {
        if (ProxySelector.getDefault() != this) {
            ProxySelector.setDefault(this);
            SpiderDebug.log("proxy", "installed as jvm default selector");
        }
    }

    public synchronized void addAll(List<Proxy> items) {
        if (items.isEmpty()) return;
        Authenticator.setDefault(new ProxyAuthenticator(this));
        items.forEach(Proxy::init);
        proxy.addAll(items);
        proxy.sort(null);
        SpiderDebug.log("proxy", "selector add rules=%s total=%s", items.size(), proxy.size());
    }

    public synchronized void remove(String name) {
        int before = proxy.size();
        proxy.removeIf(item -> item.getName().equals(name));
        int removed = before - proxy.size();
        if (removed > 0) SpiderDebug.log("proxy", "selector remove name=%s removed=%s total=%s", name, removed, proxy.size());
    }

    public synchronized void clear() {
        Authenticator.setDefault(null);
        proxy.clear();
        SpiderDebug.log("proxy", "selector clear");
    }

    public List<Proxy> getProxy() {
        return proxy;
    }

    private List<java.net.Proxy> fallback(URI uri) {
        return system != null ? system.select(uri) : List.of(java.net.Proxy.NO_PROXY);
    }

    @Override
    public List<java.net.Proxy> select(URI uri) {
        String host = uri.getHost();
        if (proxy.isEmpty()) return fallback(uri, "no-rule");
        if (host == null) return fallback(uri, "no-host");
        if (isDirectHost(host)) return fallback(uri, "local-target");
        for (Proxy item : proxy) {
            for (String rule : item.getHosts()) {
                if (!matches(host, rule)) continue;
                if (item.getProxies().isEmpty()) return fallback(uri, "empty-proxy");
                // 命中规则但上游端口根本没在监听（最常见：内嵌 mihomo/VPN 开关没开）时，
                // 硬走代理等于把本来能直连的域名一起弄挂 → 退回直连并把原因写进日志。
                if (!reachable(item.getProxies())) return fallback(uri, "upstream-down");
                logSelection(uri, "hit", host, rule, item.getName(), item.getProxies().size());
                return item.getProxies();
            }
        }
        return fallback(uri, "no-match");
    }

    /** 规则命中的上游是否真的在监听（结果有缓存，不会每请求都握手）。 */
    private boolean reachable(List<java.net.Proxy> selected) {
        for (java.net.Proxy item : selected) {
            if (item == null || item.type() == java.net.Proxy.Type.DIRECT) continue;
            if (!(item.address() instanceof java.net.InetSocketAddress)) continue;
            java.net.InetSocketAddress address = (java.net.InetSocketAddress) item.address();
            if (!ProxyHealth.isUp(address.getHostString(), address.getPort())) return false;
        }
        return true;
    }

    private List<java.net.Proxy> fallback(URI uri, String reason) {
        List<java.net.Proxy> selected = fallback(uri);
        logFallback(uri, reason, selected.size());
        return selected;
    }

    /**
     * 本机 / 局域网目标永远直连，绝不进代理。
     *
     * <p>规则里一旦出现 {@code *} 或命中本机地址，壳内的本地服务（{@code 127.0.0.1:9978}、
     * {@code http://192.168.x.x:9978}）、投屏/扫码等局域网设备就可能被自己绕进代理，
     * 轻则多一跳、重则整个本地通道不可用。
     */
    private boolean isDirectHost(String host) {
        return isLocalHost(host);
    }

    /** 本机 / 局域网目标判定（对外公开：本地规则出口与 python 源判定复用同一套语义）。 */
    public static boolean isLocalHost(String host) {
        if (host == null) return true;
        String value = host.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length() - 1);
        int zone = value.indexOf('%');
        if (zone > 0) value = value.substring(0, zone);
        if (value.isEmpty()) return true;
        if ("localhost".equals(value) || "127.0.0.1".equals(value) || "0.0.0.0".equals(value) || "::1".equals(value) || "::".equals(value)) return true;
        if (value.endsWith(".local") || value.endsWith(".lan") || value.endsWith(".home") || value.endsWith(".internal")) return true;
        if (value.indexOf(':') >= 0) return value.startsWith("fe80:") || value.startsWith("fd") || value.startsWith("fc");
        if (value.indexOf('.') < 0) return true;
        String[] parts = value.split("\\.");
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return false;
            for (int i = 0; i < part.length(); i++) if (Character.digit(part.charAt(i), 10) < 0) return false;
        }
        int a = Integer.parseInt(parts[0]);
        int b = Integer.parseInt(parts[1]);
        if (a == 0 || a == 10 || a == 127) return true;
        if (a == 192 && b == 168) return true;
        if (a == 169 && b == 254) return true;
        return a == 172 && b >= 16 && b <= 31;
    }

    /**
     * 规则匹配：域名按"自身或子域后缀"精确比对，通配/正则规则沿用宽松匹配。
     *
     * <p>原来的 {@code contains} 语义会把 {@code x.com} 命中 {@code xx.com}、
     * {@code github.com} 命中 {@code notgithub.com}，等于把不该代理的域名也送进代理。
     */
    private boolean matches(String host, String rule) {
        return matchesHost(host, rule);
    }

    /**
     * 规则匹配（对外公开：壳内其它通道——本地规则出口、直连判断、python 源——都复用它，
     * 保证"走不走代理"只有一套语义）。
     */
    public static boolean matchesHost(String host, String rule) {
        if (host == null || rule == null) return false;
        String value = rule.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return false;
        if ("*".equals(value)) return true;
        if (value.indexOf('*') >= 0 || value.indexOf('|') >= 0 || value.indexOf('(') >= 0 || value.indexOf('[') >= 0 || value.indexOf('^') >= 0 || value.indexOf('$') >= 0) return Util.containOrMatch(host, value);
        while (value.startsWith(".")) value = value.substring(1);
        if (value.isEmpty()) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals(value) || h.endsWith("." + value)) return true;
        // 不带点号的裸关键字（历史/手写规则，如 google）保留"包含"语义，避免升级后老规则失效
        return value.indexOf('.') < 0 && h.contains(value);
    }

    @Override
    public void connectFailed(URI uri, SocketAddress socketAddress, IOException e) {
        logConnectFailed(uri, socketAddress, e);
        if (system != null) system.connectFailed(uri, socketAddress, e);
    }

    private void logSelection(URI uri, String reason, String host, String rule, String name, int count) {
        if (!SpiderDebug.isEnabled()) return;
        DebugEventLimiter.Decision decision = acquire("select", reason, uri, null);
        if (!decision.allowed()) return;
        SpiderDebug.log("proxy", "select hit uri=%s host=%s ruleHash=%s nameHash=%s proxyCount=%s suppressed=%s", OkHttpLogPolicy.redactUri(uri), OkHttpLogPolicy.redactHost(host), OkHttpLogPolicy.redactHost(rule), OkHttpLogPolicy.redactHost(name), count, decision.suppressedCount());
    }

    private void logFallback(URI uri, String reason, int count) {
        if (!SpiderDebug.isEnabled()) return;
        DebugEventLimiter.Decision decision = acquire("fallback", reason, uri, null);
        if (!decision.allowed()) return;
        SpiderDebug.log("proxy", "select fallback reason=%s uri=%s proxyCount=%s suppressed=%s", reason, OkHttpLogPolicy.redactUri(uri), count, decision.suppressedCount());
    }

    private void logConnectFailed(URI uri, SocketAddress address, IOException error) {
        if (!SpiderDebug.isEnabled()) return;
        String addressType = address == null ? "none" : address.getClass().getSimpleName();
        DebugEventLimiter.Decision decision = acquire("failure", addressType, uri, error);
        if (!decision.allowed()) return;
        SpiderDebug.log("proxy", "connectFailed uri=%s addressType=%s error=%s suppressed=%s", OkHttpLogPolicy.redactUri(uri), addressType, OkHttpLogPolicy.errorChain(error), decision.suppressedCount());
    }

    private DebugEventLimiter.Decision acquire(String event, String detail, URI uri, Throwable error) {
        String scheme = uri == null || uri.getScheme() == null ? "" : uri.getScheme();
        String host = uri == null || uri.getHost() == null ? "" : uri.getHost();
        String errorType = error == null ? "" : error.getClass().getName();
        String key = event + '|' + detail + '|' + scheme + '|' + host + '|' + errorType;
        long nowMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        return debugLogLimiter.acquire(key, nowMs, DEBUG_LOG_INTERVAL_MS);
    }
}
