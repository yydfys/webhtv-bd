package com.fongmi.android.tv.lab;

import android.text.TextUtils;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 实验室代理统一入口：谁说了算 + 怎么注入。
 *
 * <p>优先级（从高到低）：
 * <ol>
 *   <li><b>脚本自带代理配置</b>——脚本内部写死的地址、或它自己在进程内赋值/删除
 *       https_proxy。引擎不干预它：脚本自己赋值时天然盖过我们注入的值，
 *       它自己删掉时也不会被我们"复活"（同一个进程内它说了算）。</li>
 *   <li><b>命令变量里显式填的代理</b>——lab.json 里 {@code {proxy}} / {@code {proxy_addr}}
 *       这类命令变量，用户填了就按用户填的走（多数脚本走命令行参数，参数本身优先）。</li>
 *   <li><b>壳子「全局代理」开关</b>——只当默认值：命令变量没填地址时才用它。</li>
 * </ol>
 *
 * <p>另一条铁律：<b>直连时也必须显式清空</b> http_proxy / https_proxy / all_proxy。
 * 只有清空才能把「没配代理 = 直连」钉死——Python requests（trust_env 默认 true）
 * 会主动去捡环境变量，不清空就可能偷偷走代理。
 *
 * <p>注入点同样关键：Ubuntu 条目跑在 proot 里，proot 用 {@code env -i} 起了个干净环境，
 * <b>宿主侧设的 env 进不了容器</b>。所以容器条目的代理是在 proot 的 env 参数里注入的
 * （见 {@link LabUbuntu#prootCommand(android.content.Context, String, Map)}）。
 */
public final class LabProxy {

    /** 兜底 no_proxy：本机 / 回环地址永远不走代理，避免把 lxserver、推送这类本地服务绕进代理。 */
    public static final String DEFAULT_NO_PROXY = "localhost,127.0.0.1,::1";

    private LabProxy() {
    }

    /** 本条命令实际生效的代理地址；返回 "" 表示直连（需要显式清空 env）。 */
    public static String resolve(Map<String, String> vars) {
        // 条目变量显式写了 direct/none/直连 → 该命令强制直连，盖过全局开关
        if (hasDirectToken(vars)) return "";
        String fromItem = fromVars(vars);
        if (!TextUtils.isEmpty(fromItem)) return fromItem;
        return fromGlobal();
    }

    /**
     * 命令变量里显式要求直连的写法：{@code direct} / {@code none} / {@code off} / {@code 直连}。
     * 给"全局开关开着、但某条命令想直连"的场景留一个口子。
     */
    public static boolean hasDirectToken(Map<String, String> vars) {
        if (vars == null || vars.isEmpty()) return false;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
            if (!key.contains("proxy")) continue;
            if (key.contains("no_proxy") || key.contains("noproxy")) continue;
            String value = entry.getValue();
            if (TextUtils.isEmpty(value)) continue;
            String v = value.trim().toLowerCase(Locale.ROOT);
            if ("direct".equals(v) || "none".equals(v) || "off".equals(v) || "直连".equals(value.trim())) {
                return true;
            }
        }
        return false;
    }

    /** 全局开关提供的默认代理地址；开关关着 / 端口非法 → ""（直连）。 */
    public static String fromGlobal() {
        LabConfig config = LabConfig.get();
        if (config == null || !config.getGlobalProxy()) return "";
        int port = config.getGlobalProxyPort();
        if (port <= 0) return "";
        return "http://127.0.0.1:" + port;
    }

    /**
     * 命令变量里显式填写的代理地址（第 2 优先级）。
     *
     * <p>只认地址类变量（proxy / proxy_addr 等），跳过 no_proxy 与纯数字的 proxy_port，
     * 也跳过没填值的变量——留空即"跟随全局默认值"。
     */
    public static String fromVars(Map<String, String> vars) {
        if (vars == null || vars.isEmpty()) return "";
        String hit = "";
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
            if (!key.contains("proxy")) continue;
            if (key.contains("no_proxy") || key.contains("noproxy")) continue;
            String value = entry.getValue();
            if (TextUtils.isEmpty(value) || !looksLikeProxy(value)) continue;
            // proxy_addr 优先于 proxy，两者都填时取地址更明确的那个
            if (TextUtils.isEmpty(hit) || key.contains("addr")) hit = value.trim();
        }
        return hit;
    }

    private static boolean looksLikeProxy(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        return v.startsWith("http://") || v.startsWith("https://") || v.startsWith("socks")
                || v.startsWith("127.0.0.1:") || v.startsWith("localhost:");
    }

    /** no_proxy 配置（用户在设置里可改，默认本机 + 回环）。 */
    public static String noProxy() {
        LabConfig config = LabConfig.get();
        String value = config == null ? null : config.getGlobalProxyNoProxy();
        return TextUtils.isEmpty(value) ? DEFAULT_NO_PROXY : value;
    }

    /**
     * 容器内需要的代理环境变量（在 proot 的 {@code env -i} 参数里注入）。
     *
     * <p>直连时给的是空值 —— 不是"不设置"，而是**显式清空**：
     * {@code env -i http_proxy= ...} 会把容器内的 http_proxy 明确置空。
     */
    public static Map<String, String> containerEnv(Map<String, String> vars) {
        Map<String, String> env = new LinkedHashMap<>();
        String proxy = resolve(vars);
        if (TextUtils.isEmpty(proxy)) {
            env.put("http_proxy", "");
            env.put("https_proxy", "");
            env.put("HTTP_PROXY", "");
            env.put("HTTPS_PROXY", "");
            env.put("all_proxy", "");
            env.put("ALL_PROXY", "");
        } else {
            env.put("http_proxy", proxy);
            env.put("https_proxy", proxy);
            env.put("HTTP_PROXY", proxy);
            env.put("HTTPS_PROXY", proxy);
            // 不做 7891 这种"猜端口"的推导：all_proxy 用同一个地址，mihomo mixed-port 两者都吃
            env.put("all_proxy", proxy);
            env.put("ALL_PROXY", proxy);
        }
        env.put("no_proxy", noProxy());
        env.put("NO_PROXY", noProxy());
        return env;
    }

    /**
     * 宿主进程（非 Ubuntu 条目，直接跑 Android shell）的环境变量注入。
     * 同样遵守"直连也要显式清空"。
     */
    public static void applyToProcessEnv(Map<String, String> env, LabModels.Item item, Map<String, String> vars) {
        if (env == null) return;
        // 代理本体不吃代理，防止自己把自己绕死
        if (item != null && item.name != null && "mihomo".equalsIgnoreCase(item.name)) return;
        Map<String, String> proxyEnv = containerEnv(vars);
        for (Map.Entry<String, String> entry : proxyEnv.entrySet()) {
            env.put(entry.getKey(), entry.getValue());
        }
    }

    /** 给 Lab 面板显示用：这条命令当前走什么代理、依据是哪一级。 */
    public static String describe(Map<String, String> vars) {
        if (hasDirectToken(vars)) return "直连（命令变量指定 direct）";
        String fromItem = fromVars(vars);
        if (!TextUtils.isEmpty(fromItem)) return fromItem + " （命令变量）";
        String global = fromGlobal();
        if (!TextUtils.isEmpty(global)) return global + " （全局开关默认值）";
        return "直连（已显式清空 http_proxy / https_proxy / all_proxy）";
    }
}
