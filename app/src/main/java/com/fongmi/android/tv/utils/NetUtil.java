package com.fongmi.android.tv.utils;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 局域网地址探测（IPv4 / IPv6 双栈）。
 *
 * <p>历史问题：壳内 9978 服务原先只监听 IPv4 通配地址（{@code 0.0.0.0}），
 * 纯 IPv6 局域网（或手机只拿到 IPv6 地址时）访问不到；同时对外展示的
 * 地址也只有 IPv4 一条。这里统一收口：
 *
 * <ul>
 *   <li>{@link #lanHost()}：给「给用户看 / 拼 URL」用的主机部分，
 *       IPv4 优先，只有拿不到 IPv4 时才回退 IPv6，并且 IPv6 一律带方括号
 *       （{@code [2408:...]}），可以直接拼进 {@code http://host:port}；</li>
 *   <li>{@link #isIpv6(String)}：判断主机部分是不是 IPv6（含带括号写法）。</li>
 * </ul>
 *
 * <p>只做只读枚举，不申请任何权限；任何异常都返回空串，由调用方回落。
 */
public final class NetUtil {

    private NetUtil() {
    }

    /**
     * 返回适合拼进 URL 的局域网主机部分。
     * IPv4 形如 {@code 192.168.1.7}；IPv6 形如 {@code [2408:8207:1234::5]}。
     * 都没有时返回空串。
     */
    public static String lanHost() {
        List<InetAddress> addresses = addresses();
        String ipv4 = pickIpv4(addresses);
        if (!ipv4.isEmpty()) return ipv4;
        String ipv6 = pickIpv6(addresses);
        return ipv6.isEmpty() ? "" : bracket(ipv6);
    }

    /** 不带括号的 IPv6 原始地址（拿不到返回空串），用于日志或特殊拼接。 */
    public static String lanIpv6() {
        return pickIpv6(addresses());
    }

    public static boolean isIpv6(String host) {
        if (host == null || host.isEmpty()) return false;
        String value = host;
        if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length() - 1);
        return value.indexOf(':') >= 0;
    }

    /** 拼装 {@code http://host:port[/path]}，IPv6 自动加方括号。 */
    public static String httpUrl(String host, int port, String path) {
        if (host == null || host.isEmpty()) host = "127.0.0.1";
        String normalized = isIpv6(host) ? bracket(stripBrackets(host)) : host;
        return "http://" + normalized + ":" + port + (path == null ? "" : path);
    }

    private static String bracket(String host) {
        return "[" + stripBrackets(host) + "]";
    }

    private static String stripBrackets(String host) {
        if (host.startsWith("[") && host.endsWith("]")) return host.substring(1, host.length() - 1);
        return host;
    }

    private static List<InetAddress> addresses() {
        List<InetAddress> result = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (nif == null || !nif.isUp() || nif.isLoopback() || nif.isPointToPoint()) continue;
                for (InetAddress address : Collections.list(nif.getInetAddresses())) {
                    if (address == null || address.isLoopbackAddress() || address.isMulticastAddress()) continue;
                    if (address.isAnyLocalAddress()) continue;
                    // IPv4 的 169.254.x.x 链路本地地址不可用作局域网地址，直接排除
                    if (address instanceof Inet4Address && address.isLinkLocalAddress()) continue;
                    result.add(address);
                }
            }
        } catch (Exception ignored) {
            // 枚举失败（权限/竞态）一律当没有地址，由调用方回落
        }
        return result;
    }

    private static String pickIpv4(List<InetAddress> addresses) {
        String fallback = "";
        for (InetAddress address : addresses) {
            if (!(address instanceof Inet4Address)) continue;
            String host = address.getHostAddress();
            if (host == null || host.isEmpty()) continue;
            if (isPrivateV4(host)) return host;
            if (fallback.isEmpty() && !address.isLinkLocalAddress()) fallback = host;
        }
        return fallback;
    }

    private static String pickIpv6(List<InetAddress> addresses) {
        String linkLocal = "";
        for (InetAddress address : addresses) {
            if (!(address instanceof Inet6Address)) continue;
            String host = address.getHostAddress();
            if (host == null || host.isEmpty()) continue;
            int scope = host.indexOf('%');
            if (scope > 0) host = host.substring(0, scope);
            if (address.isLinkLocalAddress()) {
                // 链路本地地址（fe80::）同一网段可用，优先级低于全局地址
                if (linkLocal.isEmpty()) linkLocal = host;
            } else {
                return host;
            }
        }
        return linkLocal;
    }

    private static boolean isPrivateV4(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) return false;
        try {
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            if (a == 10) return true;
            if (a == 192 && b == 168) return true;
            if (a == 172 && b >= 16 && b <= 31) return true;
            if (a == 100 && b >= 64 && b <= 127) return true; // 运营商级 NAT / 部分随身 WiFi
            return false;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
