package com.fongmi.android.tv.utils;

import androidx.core.content.ContextCompat;
import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.server.proxy.RuleProxyServer;
import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.crawler.SpiderDebug;

/**
 * 让 WebView 也吃 proxy 规则。
 *
 * <p>WebView 用的是 Chromium 自己的网络栈，JVM 默认 ProxySelector 对它完全无效；
 * 它能接受的只有"一个固定代理 + 一份绕过名单"，没法自己按规则分流。
 * 所以这里把 WebView 指向壳内的规则出口（{@link RuleProxyServer}），
 * 由那个端点按设置里的规则决定每个域名走代理还是直连。
 *
 * <p>本机/局域网目标必须绕过：{@code 127.0.0.1:9978} 这类本地服务、投屏/扫码设备
 * 一旦被绕进代理，轻则多一跳、重则整条本地通道不可用。
 */
public final class WebViewProxy {

    private static final String TAG = "proxy";
    private static volatile boolean installed;

    private WebViewProxy() {
    }

    /** 按规则出口当前状态同步（关代理/未启动时清掉覆盖）。 */
    public static void sync() {
        // 规则出口是常驻的，所以这里必须自己看开关：开关关掉就别给 WebView 套代理，
        // 保持"不开代理时行为与以前完全一致"。
        if (!Setting.isShellProxy()) {
            clear();
            return;
        }
        int port = RuleProxyServer.port();
        if (port <= 0) {
            clear();
            return;
        }
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                SpiderDebug.log(TAG, "webview proxy override unsupported, skip");
                return;
            }
            ProxyConfig config = new ProxyConfig.Builder()
                    .addProxyRule("127.0.0.1:" + port)
                    .addBypassRule("<local>")
                    .addBypassRule("localhost")
                    .addBypassRule("127.0.0.1")
                    .addBypassRule("*.local")
                    .addBypassRule("*.lan")
                    .addBypassRule("192.168.*")
                    .addBypassRule("10.*")
                    .addBypassRule("172.16.*")
                    .addBypassRule("169.254.*")
                    .build();
            // setProxyOverride 要求在主线程调用（apply() 可能在启动线程里跑）→ 统一投到主线程执行
            ContextCompat.getMainExecutor(App.get()).execute(() -> {
                try {
                    ProxyController.getInstance().setProxyOverride(config, ContextCompat.getMainExecutor(App.get()), () -> {
                    });
                    if (!installed) SpiderDebug.log(TAG, "webview proxy override -> 127.0.0.1:%s", port);
                    installed = true;
                } catch (Throwable e) {
                    SpiderDebug.log(TAG, "webview proxy override failed error=%s", e.toString());
                }
            });
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "webview proxy override failed error=%s", e.toString());
        }
    }

    public static void clear() {
        try {
            if (!installed && !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return;
            ContextCompat.getMainExecutor(App.get()).execute(() -> {
                try {
                    ProxyController.getInstance().clearProxyOverride(ContextCompat.getMainExecutor(App.get()), () -> {
                    });
                    if (installed) SpiderDebug.log(TAG, "webview proxy override cleared");
                } catch (Throwable e) {
                    SpiderDebug.log(TAG, "webview proxy override clear failed error=%s", e.toString());
                }
                installed = false;
            });
        } catch (Throwable e) {
            installed = false;
        }
    }
}
