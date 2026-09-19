package com.fongmi.android.tv.setting;

import android.net.Uri;
import android.text.TextUtils;

import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.server.proxy.RuleProxyServer;
import com.fongmi.android.tv.utils.WebViewProxy;
import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.bean.Proxy;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.net.OkProxySelector;
import com.github.catvod.net.ProxyHealth;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProxySetting {

    private static final String NAME = "app";
    private static final int MAX_SUGGESTION_URLS = 200;
    private static final Pattern URL_PATTERN = Pattern.compile("(?i)(?:https?:)?//[^\\s\"'<>\\\\]+");
    private static volatile String cachedKey;
    private static volatile List<Proxy> cachedRules;
    private static final java.util.Map<String, Long> upstreamLogAt = new java.util.concurrent.ConcurrentHashMap<>();

    /** 上游不可达只提示一次（30 秒节流），避免每个请求刷一行日志。 */
    private static void logUnreachable(String host, int port) {
        long now = System.currentTimeMillis();
        String key = host + ":" + port;
        Long last = upstreamLogAt.get(key);
        if (last != null && now - last < 30000) return;
        upstreamLogAt.put(key, now);
        SpiderDebug.log("proxy", "upstream %s:%s unreachable -> direct", host, port);
    }

    public static void apply() {
        // 先把自己装成 JVM 全局默认选择器，再刷规则：这样壳内所有没显式挂 selector 的
        // 网络栈（源 jar 自建 OkHttp、Glide 图片、Media3、更新/刮削/驱动检查等）也一起吃规则。
        // 关代理时保持安装状态即可——规则清空后 select() 直接回退系统默认（= 直连）。
        OkHttp.selector().install();
        OkHttp.selector().remove(NAME);
        OkHttp.closeIdleConnections();
        // 注册给 python 源用的判定入口（chaquo 里的 requests 吃不到 JVM 选择器）
        com.github.catvod.Proxy.setRouter(ProxySetting::shellProxyForUrl);
        boolean enabled = Setting.isShellProxy();
        List<Proxy> rules = enabled ? rules() : List.of();
        if (!enabled) {
            SpiderDebug.log("proxy", "app proxy disabled");
        } else if (rules.isEmpty()) {
            SpiderDebug.log("proxy", "app proxy enabled but no valid rules defaultUrl=%s rulesLength=%s", safeUrl(Setting.getShellProxyUrl()), Setting.getShellProxyRules().length());
        } else {
            OkHttp.selector().addAll(rules);
            SpiderDebug.log("proxy", "app proxy enabled rules=%s defaultUrl=%s", rules.size(), safeUrl(Setting.getShellProxyUrl()));
        }
        // 壳内四条吃不到 Java 选择器的通道（WebView / IJK / MPV）统一走本地规则出口。
        // 端点进程内常驻（开关关掉也留着监听）：开关只决定"命中规则时走上游还是直连"，
        // 这样 IJK/MPV 这类"只能配一个固定端点"的通道不会出现"端点已停但配置还指着它"的
        // 断裂；开关关闭时端点对每个域名都直连，功能上等价于没开代理。
        boolean active = enabled && !rules.isEmpty();
        RuleProxyServer.sync(true);
        // 设置一变就先作废上游健康缓存：否则"刚打开 VPN/内核"后的头几秒还会按旧结论
        // （上游不可用）走直连，看着像没生效。
        com.github.catvod.net.ProxyHealth.invalidate();
        if (active) WebViewProxy.sync();
        else WebViewProxy.clear();
        // node 侧读状态文件的 enabled 字段：关掉时它走原路，等于没打补丁
        RuleProxyServer.writeState(active);
    }

    /**
     * 本地规则出口：该 URL 命中规则时返回端点地址（形如 {@code http://127.0.0.1:7891}），
     * 否则空串表示直连。供 IJK/MPV/python/node 这些"只能配一个代理端点"的通道使用。
     */
    public static String shellProxyForUrl(String url) {
        return shellProxyForHost(TextUtils.isEmpty(url) ? "" : host(url));
    }

    public static String shellProxyForHost(String host) {
        return upstreamForHost(host).isEmpty() ? "" : RuleProxyServer.url();
    }

    /**
     * 该主机的规则上游（形如 {@code http://127.0.0.1:7890} / {@code socks5://host:port}），
     * 空串表示直连——本机、局域网、未命中规则、开关关闭都会返回空串。
     */
    public static String upstreamForHost(String host) {
        if (!Setting.isShellProxy()) return "";
        if (TextUtils.isEmpty(host) || OkProxySelector.isLocalHost(host)) return "";
        for (Proxy item : rules()) {
            for (String rule : item.getHosts()) {
                if (OkProxySelector.matchesHost(host, rule)) return upstream(item);
            }
        }
        return "";
    }

    /**
     * 规则缓存：WebView / 播放器 / python / node 每条请求都要问一次"这个域名走不走代理"，
     * 每次都重新解析一遍规则 JSON 太亏。用原始串当 key，设置一变（apply 里重设）自然失效。
     */
    private static List<Proxy> rules() {
        String key = Setting.isShellProxy() + "\n" + Setting.getShellProxyRules() + "\n" + Setting.getShellProxyUrl() + "\n" + Setting.getShellProxyHosts();
        List<Proxy> cached = cachedRules;
        if (cached != null && key.equals(cachedKey)) return cached;
        List<Proxy> parsed = getRules();
        cachedKey = key;
        cachedRules = parsed;
        return parsed;
    }

    private static String upstream(Proxy item) {
        List<java.net.Proxy> proxies = item.getProxies();
        if (proxies.isEmpty()) {
            // 规则缓存里的对象可能还没被 addAll 初始化过
            item.init();
            proxies = item.getProxies();
        }
        for (java.net.Proxy proxy : proxies) {
            if (proxy.type() == java.net.Proxy.Type.DIRECT) continue;
            if (!(proxy.address() instanceof InetSocketAddress)) continue;
            InetSocketAddress address = (InetSocketAddress) proxy.address();
            if (address.getPort() <= 0) continue;
            // 上游写成规则出口自己会死循环，直接当直连处理
            if (address.getPort() == RuleProxyServer.port() && isLoopback(address.getHostString())) continue;
            // 上游不可达（开关开着但 VPN/内核没起）→ 当直连处理：宁可该走代理的漏成直连，
            // 也不能把命中规则的域名全堵死（那是整条通道不可用）。
            if (!ProxyHealth.isUp(address.getHostString(), address.getPort())) {
                logUnreachable(address.getHostString(), address.getPort());
                return "";
            }
            return (proxy.type() == java.net.Proxy.Type.SOCKS ? "socks5" : "http") + "://" + address.getHostString() + ":" + address.getPort();
        }
        return "";
    }

    private static boolean isLoopback(String host) {
        if (TextUtils.isEmpty(host)) return false;
        String value = host.trim().toLowerCase(Locale.ROOT);
        return "localhost".equals(value) || "127.0.0.1".equals(value) || "::1".equals(value) || "0.0.0.0".equals(value);
    }

    public static List<Proxy> getRules() {
        String rules = Setting.getShellProxyRules().trim();
        if (!TextUtils.isEmpty(rules)) return parse(rules, cleanUrl(Setting.getShellProxyUrl()));
        return legacy();
    }

    public static List<Proxy> getRules(String rules, String defaultUrl) {
        String text = rules == null ? "" : rules.trim();
        String url = cleanUrl(defaultUrl);
        return TextUtils.isEmpty(text) ? List.of() : parse(text, url);
    }

    public static Suggestion suggest(Site site) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        LinkedHashSet<String> hosts = new LinkedHashSet<>();
        collectSiteUrls(site, urls);
        collectDebugUrls(urls);
        for (String url : urls) {
            String host = host(url);
            if (isSuggestedHost(host)) hosts.add(host);
        }
        return new Suggestion(new ArrayList<>(hosts), new ArrayList<>(urls));
    }

    public static String firstTestHost(List<Proxy> rules) {
        for (Proxy proxy : rules) {
            for (String host : proxy.getHosts()) {
                String value = cleanRuleHost(host);
                if (isUsefulHost(value)) return value;
            }
        }
        return "";
    }

    private static List<Proxy> legacy() {
        String url = cleanUrl(Setting.getShellProxyUrl());
        if (TextUtils.isEmpty(url) || !isValid(url)) return List.of();
        return Proxy.arrayFrom(legacy(url));
    }

    private static void collectSiteUrls(Site site, Set<String> urls) {
        if (site == null || site.isEmpty()) return;
        addUrls(site.getApi(), urls);
        addUrls(site.getExt(), urls);
        addUrls(site.getJar(), urls);
        addUrls(site.getClick(), urls);
        addUrls(site.getPlayUrl(), urls);
        addUrls(site.getHomePage(), urls);
        addLocalFileUrls(site.getExt(), urls);
        addLocalFileUrls(site.getHomePage(), urls);
        for (String category : site.getCategories()) addUrls(category, urls);
        for (Map.Entry<String, String> entry : site.getHeader().entrySet()) {
            addUrls(entry.getKey(), urls);
            addUrls(entry.getValue(), urls);
        }
    }

    private static void collectDebugUrls(Set<String> urls) {
        for (String origin : DebugLogStore.observedOrigins()) addUrls(origin, urls);
    }

    private static void addLocalFileUrls(String source, Set<String> urls) {
        String path = localPath(source);
        if (TextUtils.isEmpty(path)) return;
        File file = Path.local(path);
        if (file.isDirectory()) file = new File(file, "index.html");
        if (!file.isFile()) return;
        addUrls(Path.read(file), urls);
    }

    private static String localPath(String source) {
        String value = source == null ? "" : source.trim();
        if (TextUtils.isEmpty(value)) return "";
        if (value.startsWith("file://")) return value.substring("file://".length());
        Uri uri = Uri.parse(value);
        String path = uri.getPath();
        if (!isLocalHost(uri.getHost()) || path == null || !path.startsWith("/file/")) return "";
        return path.substring("/file/".length());
    }

    private static void addUrls(String text, Set<String> urls) {
        if (TextUtils.isEmpty(text) || urls.size() >= MAX_SUGGESTION_URLS) return;
        Matcher matcher = URL_PATTERN.matcher(text.replace("\\/", "/"));
        while (matcher.find() && urls.size() < MAX_SUGGESTION_URLS) {
            String url = cleanCandidateUrl(matcher.group());
            if (url.startsWith("//")) url = "https:" + url;
            if (url.startsWith("http://") || url.startsWith("https://")) urls.add(url);
        }
    }

    private static String cleanCandidateUrl(String url) {
        String value = url == null ? "" : url.trim().replace("&amp;", "&");
        while (!value.isEmpty() && ".,;:)]}'\"".indexOf(value.charAt(value.length() - 1)) >= 0) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String host(String url) {
        try {
            return normalizeHost(Uri.parse(url).getHost());
        } catch (Exception e) {
            return "";
        }
    }

    private static String cleanRuleHost(String host) {
        String value = normalizeHost(host);
        if (value.contains("*") || value.contains("^") || value.contains("$") || value.contains("|") || value.contains("[") || value.contains("(") || value.contains("\\")) return "";
        return value;
    }

    private static String normalizeHost(String host) {
        String value = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        while (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static boolean isUsefulHost(String host) {
        if (host == null || host.isEmpty() || isLocalHost(host)) return false;
        return host.contains(".");
    }

    static boolean isSuggestedHost(String host) {
        String value = normalizeHost(host);
        return isUsefulHost(value) && !isIpLiteral(value);
    }

    private static boolean isIpLiteral(String host) {
        String value = normalizeHost(host);
        if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length() - 1);
        int zone = value.indexOf('%');
        if (zone >= 0) value = value.substring(0, zone);
        if (value.indexOf(':') >= 0) return isIpv6Literal(value);
        if (value.indexOf('.') < 0) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '.' && !Character.isDigit(c)) return false;
        }
        return true;
    }

    private static boolean isIpv6Literal(String value) {
        int colons = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ':') {
                colons++;
            } else if (c != '.' && Character.digit(c, 16) < 0) {
                return false;
            }
        }
        return colons >= 2;
    }

    private static boolean isLocalHost(String host) {
        String value = normalizeHost(host);
        return "localhost".equals(value) || "127.0.0.1".equals(value) || "0.0.0.0".equals(value) || "::1".equals(value);
    }

    private static List<Proxy> parse(String rules, String defaultUrl) {
        try {
            if (Json.isArray(rules)) return Proxy.arrayFrom(normalize(Json.parse(rules).getAsJsonArray(), defaultUrl));
            if (Json.isObj(rules)) return parseObject(Json.parse(rules).getAsJsonObject(), defaultUrl);
            return Proxy.arrayFrom(parseLines(rules, defaultUrl));
        } catch (Exception e) {
            SpiderDebug.log("proxy", "parse failed rulesLength=%s error=%s", rules.length(), e.getMessage());
            return List.of();
        }
    }

    private static List<Proxy> parseObject(JsonObject object, String defaultUrl) {
        if (object.has("proxy")) return Proxy.arrayFrom(normalize(object.getAsJsonArray("proxy"), defaultUrl));
        JsonArray array = new JsonArray();
        array.add(object);
        return Proxy.arrayFrom(normalize(array, defaultUrl));
    }

    private static JsonArray normalize(JsonArray input, String defaultUrl) {
        JsonArray output = new JsonArray();
        for (int i = 0; i < input.size(); i++) {
            if (!input.get(i).isJsonObject()) continue;
            JsonObject object = input.get(i).getAsJsonObject().deepCopy();
            object.addProperty("name", NAME);
            fillDefaultUrl(object, defaultUrl);
            output.add(object);
        }
        return output;
    }

    private static void fillDefaultUrl(JsonObject object, String defaultUrl) {
        if (object.has("urls") && object.get("urls").isJsonArray() && !object.getAsJsonArray("urls").isEmpty()) return;
        if (TextUtils.isEmpty(defaultUrl)) return;
        object.add("urls", urls(defaultUrl));
    }

    private static JsonArray parseLines(String rules, String defaultUrl) {
        JsonArray array = new JsonArray();
        int index = 0;
        for (String line : rules.split("\\r?\\n")) {
            JsonObject object = parseLine(line.trim(), ++index, defaultUrl);
            if (object != null) array.add(object);
        }
        return array;
    }

    private static JsonObject parseLine(String line, int index, String defaultUrl) {
        if (TextUtils.isEmpty(line) || line.startsWith("#")) return null;
        String[] parts = line.split("\\s+", 2);
        String hosts = parts.length > 1 ? parts[0].trim() : line.trim();
        String urls = parts.length > 1 ? parts[1].trim() : defaultUrl;
        if (parts.length == 1 && looksLikeProxyUrl(hosts)) {
            urls = hosts;
            hosts = "*";
        }
        if (TextUtils.isEmpty(hosts) || TextUtils.isEmpty(urls)) return null;
        JsonObject object = new JsonObject();
        object.addProperty("name", NAME);
        object.add("hosts", array(hosts));
        object.add("urls", array(urls));
        return object;
    }

    private static JsonArray legacy(String url) {
        JsonObject object = new JsonObject();
        object.addProperty("name", NAME);
        object.add("hosts", hosts());
        object.add("urls", urls(url));
        JsonArray array = new JsonArray();
        array.add(object);
        return array;
    }

    private static JsonArray hosts() {
        return array(Setting.getShellProxyHosts());
    }

    private static JsonArray urls(String url) {
        return array(url);
    }

    private static JsonArray array(String text) {
        JsonArray array = new JsonArray();
        for (String item : text.split(",")) {
            String value = item.trim();
            if (!TextUtils.isEmpty(value)) array.add(value);
        }
        if (array.isEmpty()) array.add("*");
        return array;
    }

    public static String cleanUrl(String url) {
        String value = url == null ? "" : url.trim();
        return "socks5://".equalsIgnoreCase(value) ? "" : value;
    }

    private static String safeUrl(String url) {
        String value = cleanUrl(url);
        if (TextUtils.isEmpty(value)) return "";
        Uri uri = Uri.parse(value);
        return uri.getScheme() + "://" + uri.getHost() + ":" + uri.getPort();
    }

    public static boolean isValid(String url) {
        url = cleanUrl(url);
        if (TextUtils.isEmpty(url)) return false;
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        return scheme != null && (scheme.startsWith("http") || scheme.startsWith("socks")) && uri.getHost() != null && uri.getPort() > 0;
    }

    private static boolean looksLikeProxyUrl(String text) {
        Uri uri = Uri.parse(text);
        String scheme = uri.getScheme();
        return scheme != null && (scheme.startsWith("http") || scheme.startsWith("socks"));
    }

    public static boolean isValidRules(String rules, String defaultUrl) {
        String text = rules == null ? "" : rules.trim();
        String url = cleanUrl(defaultUrl);
        if (TextUtils.isEmpty(text)) return TextUtils.isEmpty(url) || isValid(url);
        if (!TextUtils.isEmpty(url) && !isValid(url)) return false;
        List<Proxy> items = parse(text, url);
        if (items.isEmpty()) return false;
        for (Proxy proxy : items) {
            proxy.init();
            if (proxy.getHosts().isEmpty() || proxy.getProxies().isEmpty()) return false;
        }
        return true;
    }

    public static boolean isValidRules(String rules) {
        return isValidRules(rules, Setting.getShellProxyUrl());
    }

    public static int count() {
        try {
            return getRules().size();
        } catch (Throwable e) {
            SpiderDebug.log("proxy", "count failed error=%s", e.toString());
            return 0;
        }
    }

    public record Suggestion(List<String> hosts, List<String> urls) {

        public boolean isEmpty() {
            return hosts.isEmpty();
        }
    }

}
