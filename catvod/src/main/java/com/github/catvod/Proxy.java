package com.github.catvod;

import com.github.catvod.utils.Util;

public class Proxy {

    private static int port = -1;
    private static volatile Router router;

    /**
     * 壳内"按设置里的 proxy 规则分流"的出口（由 app 侧注册）。
     *
     * <p>存在的原因：python 源（chaquo 里跑的 requests）走的是自己的网络栈，
     * JVM 默认 ProxySelector 管不到它。这里让 app 把一个判定函数注册进来，
     * python 侧就能按同一个规则决定"这个 URL 走不走代理"。
     */
    public interface Router {

        /** 返回形如 {@code http://127.0.0.1:7891} 的本地出口；空串表示直连。 */
        String forUrl(String url);
    }

    public static void setRouter(Router router) {
        Proxy.router = router;
    }

    /** 供 python 源使用：该 URL 是否需要走代理（空串 = 直连）。 */
    public static String shellProxyFor(String url) {
        Router current = router;
        if (current == null) return "";
        try {
            String value = current.forUrl(url);
            return value == null ? "" : value;
        } catch (Throwable e) {
            return "";
        }
    }

    public static void set(int port) {
        Proxy.port = port;
    }

    public static int getPort() {
        return port;
    }

    public static String getUrl(boolean local) {
        return "http://" + (local ? "127.0.0.1" : Util.getIp()) + ":" + getPort() + "/proxy";
    }
}
