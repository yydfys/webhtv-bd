package com.fongmi.android.tv.lab;

import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * 订阅下载与落盘（2026-11-11）。
 *
 * <p>设计约束（老大定的三条）：
 * <ol>
 *   <li>config.yaml 是用户的 —— 本类<b>绝不</b>碰 config.yaml；</li>
 *   <li>只有用户点「保存并拉取」时才联网拉订阅、才写 {@code providers/sub.yaml}；</li>
 *   <li>打开代理开关只读本地 {@code providers/sub.yaml}（模板已改 {@code type: file}，内核永不自己拉）。</li>
 * </ol>
 *
 * <p>安全策略：先下载 → 解析出节点 → <b>校验至少 1 个节点</b> → 备份旧文件 → 原子替换。
 * 任何一步失败都<b>保留原文件不动</b>，避免把可用节点洗成空文件。
 */
public final class SubscriptionStore {

    private static final String TAG = "SubStore";
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final String UA = "clash-verge/v2.0.0";

    private SubscriptionStore() {
    }

    /** 结果对象（给 UI 提示用） */
    public static final class Result {
        public final boolean ok;
        public final int count;
        public final String message;

        Result(boolean ok, int count, String message) {
            this.ok = ok;
            this.count = count;
            this.message = message;
        }
    }

    public static File subFile() {
        return new File(SystemVpnService.getHomeDir(), "providers/sub.yaml");
    }

    /** 本地 provider 里现有的节点数（纯 I/O 读文件，勿在主线程调用） */
    public static int countLocalNodes() {
        try {
            File f = subFile();
            if (!f.exists() || f.length() == 0) return 0;
            return countNodes(readText(f));
        } catch (Throwable e) {
            Log.w(TAG, "countLocalNodes failed: " + e.getMessage());
            return 0;
        }
    }

    /** 本地是否已有可用节点（供启动前判断，轻量） */
    public static boolean hasLocalNodes() {
        return countLocalNodes() > 0;
    }

    /**
     * 下载订阅并写入 providers/sub.yaml。**阻塞方法，必须在子线程调用**。
     *
     * @param url 订阅地址（http/https）
     */
    public static Result refresh(String url) {
        if (url == null || url.trim().isEmpty()) return new Result(false, 0, "empty_url");
        String body;
        try {
            body = download(url.trim());
        } catch (Throwable e) {
            Log.w(TAG, "download failed: " + e);
            return new Result(false, 0, "download:" + shortMsg(e));
        }
        String yaml;
        try {
            yaml = normalize(body);
        } catch (Throwable e) {
            Log.w(TAG, "normalize failed: " + e);
            return new Result(false, 0, "parse:" + shortMsg(e));
        }
        int count = countNodes(yaml);
        if (count <= 0) return new Result(false, 0, "no_nodes");

        File target = subFile();
        try {
            File dir = target.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) throw new IllegalStateException("mkdirs failed");

            // ① 先写临时文件并落盘 —— 这一步失败时原 sub.yaml 一个字节都没动
            File tmp = new File(dir, "sub.yaml.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(yaml.getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.getFD().sync();
            }

            // ② 备份旧文件（只留一份 .bak，避免堆积）
            File bak = new File(dir, "sub.yaml.bak");
            boolean hadOld = target.exists() && target.length() > 0;
            if (hadOld) {
                if (bak.exists()) //noinspection ResultOfMethodCallIgnored
                    bak.delete();
                //noinspection ResultOfMethodCallIgnored
                target.renameTo(bak);
            }

            // ③ 原子替换；万一失败就把备份还原回来
            if (!tmp.renameTo(target)) {
                if (hadOld && bak.exists()) //noinspection ResultOfMethodCallIgnored
                    bak.renameTo(target);
                throw new IllegalStateException("rename failed");
            }
            Log.i(TAG, "subscription saved: nodes=" + count + " bytes=" + target.length());
            return new Result(true, count, "ok");
        } catch (Throwable e) {
            Log.e(TAG, "save failed: " + e);
            return new Result(false, 0, "save:" + shortMsg(e));
        }
    }

    // ---------------- 下载 ----------------

    private static String download(String url) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            int code = conn.getResponseCode();
            if (code >= 400) throw new IllegalStateException("HTTP " + code);
            InputStream in = conn.getInputStream();
            String enc = conn.getContentEncoding();
            if (enc != null && enc.toLowerCase().contains("gzip")) in = new GZIPInputStream(in);
            byte[] data = readAll(in);
            String text = new String(data, StandardCharsets.UTF_8);
            return stripBom(text);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[16384];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_BYTES) throw new IllegalStateException("too_large");
            bos.write(buf, 0, n);
        }
        in.close();
        return bos.toByteArray();
    }

    // ---------------- 规范化 ----------------

    /**
     * 把订阅内容转成 mihomo 可吃的「纯节点列表」（以 proxies: 开头）。
     * 支持两种形态：①clash 完整配置（抽 proxies 段）②base64/明文的分享链接（vless/trojan/ss/vmess）。
     */
    static String normalize(String raw) throws Exception {
        String text = stripBom(raw).replace("\r\n", "\n").replace('\r', '\n');

        // ① clash 配置：直接抽 proxies 段（最保真，节点参数一个不动）
        String block = extractProxiesBlock(text);
        if (block != null && countNodes(block) > 0) return "proxies:\n" + block;

        // ② 分享链接：base64 先解一层
        String links = text;
        String b64 = text.replaceAll("\\s", "");
        if (b64.matches("^[A-Za-z0-9+/=_-]+$") && b64.length() > 24) {
            try {
                String decoded = decodeBase64(b64);
                if (decoded.contains("://")) links = decoded;
            } catch (Throwable ignored) {
            }
        }
        List<String> out = new ArrayList<>();
        for (String line : links.split("\n")) {
            String s = line.trim();
            if (!s.contains("://")) continue;
            try {
                String y = parseShareLink(s);
                if (y != null) out.add(y);
            } catch (Throwable e) {
                Log.w(TAG, "skip link: " + e.getMessage());
            }
        }
        if (out.isEmpty()) throw new IllegalStateException("unrecognized_format");
        StringBuilder sb = new StringBuilder("proxies:\n");
        for (String s : out) sb.append(s).append('\n');
        return sb.toString();
    }

    /** 抽顶层 proxies: 段（返回以 "  - " 开头的缩进块；找不到返回 null） */
    private static String extractProxiesBlock(String text) {
        String[] lines = text.split("\n", -1);
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (l.startsWith("proxies:") || l.matches("^\\s+proxies:\\s*$")) {
                start = i;
                // 形如 proxies: [] / proxies: [xxx] 的内联写法 → 视为空
                String rest = l.substring(l.indexOf("proxies:") + 8).trim();
                if (!rest.isEmpty()) return null;
                break;
            }
        }
        if (start < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = start + 1; i < lines.length; i++) {
            String l = lines[i];
            if (l.trim().isEmpty()) {
                sb.append('\n');
                continue;
            }
            boolean indented = l.startsWith(" ") || l.startsWith("\t");
            if (!indented) break;              // 回到顶层 key → proxies 段结束
            sb.append(l).append('\n');
        }
        // 去掉尾部空行
        String s = sb.toString();
        while (s.endsWith("\n\n")) s = s.substring(0, s.length() - 1);
        return s.isEmpty() ? null : s;
    }

    /** 统计节点条数（- name: 或 - {name:） */
    static int countNodes(String yaml) {
        if (yaml == null) return 0;
        int n = 0;
        for (String line : yaml.split("\n")) {
            String t = line.trim();
            if (t.startsWith("- name:") || t.startsWith("- {name:") || t.startsWith("- { name:")) n++;
        }
        return n;
    }

    // ---------------- 分享链接 → mihomo 节点 ----------------

    private static String parseShareLink(String link) throws Exception {
        String scheme = link.substring(0, link.indexOf("://")).toLowerCase();
        switch (scheme) {
            case "vless":
                return vless(link);
            case "trojan":
                return trojan(link);
            case "ss":
                return shadowsocks(link);
            case "vmess":
                return vmess(link);
            default:
                return null;
        }
    }

    private static String vless(String link) throws Exception {
        String body = link.substring("vless://".length());
        int hash = body.indexOf('#');
        String name = hash >= 0 ? urlDecode(body.substring(hash + 1)) : "vless";
        String rest = hash >= 0 ? body.substring(0, hash) : body;
        int q = rest.indexOf('?');
        String main = q >= 0 ? rest.substring(0, q) : rest;
        String query = q >= 0 ? rest.substring(q + 1) : "";

        int at = main.lastIndexOf('@');
        if (at < 0) return null;
        String uuid = main.substring(0, at);
        String[] hp = splitHostPort(main.substring(at + 1));
        if (hp == null) return null;

        String sni = param(query, "sni", param(query, "host", ""));
        String net = param(query, "type", "tcp");
        String path = param(query, "path", "/");
        String host = param(query, "host", sni);
        boolean tls = "tls".equalsIgnoreCase(param(query, "security", "none"));
        boolean insecure = "1".equals(param(query, "allowInsecure", "0"));

        StringBuilder sb = new StringBuilder();
        sb.append("  - name: ").append(quote(name)).append('\n');
        sb.append("    type: vless\n");
        sb.append("    server: ").append(hp[0]).append('\n');
        sb.append("    port: ").append(hp[1]).append('\n');
        sb.append("    uuid: ").append(uuid).append('\n');
        sb.append("    udp: true\n");
        sb.append("    tls: ").append(tls).append('\n');
        if (tls) {
            if (!sni.isEmpty()) sb.append("    servername: ").append(quote(sni)).append('\n');
            if (insecure) sb.append("    skip-cert-verify: true\n");
            sb.append("    client-fingerprint: chrome\n");
        }
        if ("ws".equalsIgnoreCase(net)) {
            sb.append("    network: ws\n");
            sb.append("    ws-opts:\n");
            sb.append("      path: ").append(quote(path)).append('\n');
            if (!host.isEmpty()) {
                sb.append("      headers:\n");
                sb.append("        Host: ").append(quote(host)).append('\n');
            }
        } else if ("grpc".equalsIgnoreCase(net)) {
            sb.append("    network: grpc\n");
            sb.append("    grpc-opts:\n");
            sb.append("      grpc-service-name: ").append(quote(param(query, "serviceName", ""))).append('\n');
        }
        return sb.toString();
    }

    private static String trojan(String link) throws Exception {
        String body = link.substring("trojan://".length());
        int hash = body.indexOf('#');
        String name = hash >= 0 ? urlDecode(body.substring(hash + 1)) : "trojan";
        String rest = hash >= 0 ? body.substring(0, hash) : body;
        int q = rest.indexOf('?');
        String main = q >= 0 ? rest.substring(0, q) : rest;
        String query = q >= 0 ? rest.substring(q + 1) : "";

        int at = main.indexOf('@');
        if (at < 0) return null;
        String pass = main.substring(0, at);
        String[] hp = splitHostPort(main.substring(at + 1));
        if (hp == null) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("  - name: ").append(quote(name)).append('\n');
        sb.append("    type: trojan\n");
        sb.append("    server: ").append(hp[0]).append('\n');
        sb.append("    port: ").append(hp[1]).append('\n');
        sb.append("    password: ").append(quote(pass)).append('\n');
        sb.append("    udp: true\n");
        String sni = param(query, "sni", param(query, "peer", hp[0]));
        if (!sni.isEmpty()) sb.append("    sni: ").append(quote(sni)).append('\n');
        if ("1".equals(param(query, "allowInsecure", param(query, "insecure", "0"))))
            sb.append("    skip-cert-verify: true\n");
        if ("ws".equalsIgnoreCase(param(query, "type", ""))) {
            sb.append("    network: ws\n");
            sb.append("    ws-opts:\n");
            sb.append("      path: ").append(quote(param(query, "path", "/"))).append('\n');
        }
        return sb.toString();
    }

    private static String shadowsocks(String link) throws Exception {
        String body = link.substring("ss://".length());
        int hash = body.indexOf('#');
        String name = hash >= 0 ? urlDecode(body.substring(hash + 1)) : "ss";
        String rest = hash >= 0 ? body.substring(0, hash) : body;
        int q = rest.indexOf('?');
        String main = q >= 0 ? rest.substring(0, q) : rest;

        String userinfo;
        String hostport;
        int at = main.lastIndexOf('@');
        if (at >= 0) {
            userinfo = main.substring(0, at);
            hostport = main.substring(at + 1);
        } else {
            // 老格式：整个 ss://base64(method:pass@host:port)
            String decoded = decodeBase64(main);
            at = decoded.lastIndexOf('@');
            if (at < 0) return null;
            userinfo = decoded.substring(0, at);
            hostport = decoded.substring(at + 1);
        }
        String method;
        String password;
        String dec = userinfo;
        if (!dec.contains(":")) {
            try {
                dec = decodeBase64(dec);
            } catch (Throwable ignored) {
            }
        }
        int c = dec.indexOf(':');
        if (c < 0) return null;
        method = dec.substring(0, c);
        password = dec.substring(c + 1);
        String[] hp = splitHostPort(hostport);
        if (hp == null) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("  - name: ").append(quote(name)).append('\n');
        sb.append("    type: ss\n");
        sb.append("    server: ").append(hp[0]).append('\n');
        sb.append("    port: ").append(hp[1]).append('\n');
        sb.append("    cipher: ").append(quote(method)).append('\n');
        sb.append("    password: ").append(quote(password)).append('\n');
        sb.append("    udp: true\n");
        return sb.toString();
    }

    private static String vmess(String link) throws Exception {
        String json = decodeBase64(link.substring("vmess://".length()));
        JSONObject o = new JSONObject(json);
        String host = o.optString("add", "");
        int port = o.optInt("port", 443);
        if (host.isEmpty() || port <= 0) return null;
        String name = o.optString("ps", "vmess");
        String net = o.optString("net", "tcp");
        String tls = o.optString("tls", "");

        StringBuilder sb = new StringBuilder();
        sb.append("  - name: ").append(quote(name)).append('\n');
        sb.append("    type: vmess\n");
        sb.append("    server: ").append(host).append('\n');
        sb.append("    port: ").append(port).append('\n');
        sb.append("    uuid: ").append(o.optString("id", "")).append('\n');
        sb.append("    alterId: ").append(o.optInt("aid", 0)).append('\n');
        sb.append("    cipher: auto\n");
        sb.append("    udp: true\n");
        if ("tls".equalsIgnoreCase(tls)) {
            sb.append("    tls: true\n");
            String sni = o.optString("sni", o.optString("host", ""));
            if (!sni.isEmpty()) sb.append("    servername: ").append(quote(sni)).append('\n');
        }
        if ("ws".equalsIgnoreCase(net)) {
            sb.append("    network: ws\n");
            sb.append("    ws-opts:\n");
            sb.append("      path: ").append(quote(o.optString("path", "/"))).append('\n');
            String h = o.optString("host", "");
            if (!h.isEmpty()) {
                sb.append("      headers:\n");
                sb.append("        Host: ").append(quote(h)).append('\n');
            }
        }
        return sb.toString();
    }

    // ---------------- 小工具 ----------------

    private static String[] splitHostPort(String s) {
        int c = s.lastIndexOf(':');
        if (c <= 0) return null;
        String host = s.substring(0, c).trim();
        String port = s.substring(c + 1).replace("/", "").trim();
        if (host.isEmpty() || port.isEmpty()) return null;
        try {
            Integer.parseInt(port);
        } catch (Throwable e) {
            return null;
        }
        return new String[]{host, port};
    }

    private static String param(String query, String key, String def) {
        if (query == null || query.isEmpty()) return def;
        for (String kv : query.split("&")) {
            int e = kv.indexOf('=');
            if (e < 0) continue;
            if (!kv.substring(0, e).equalsIgnoreCase(key)) continue;
            String v = kv.substring(e + 1);
            try {
                return URLDecoder.decode(v, "UTF-8");
            } catch (Throwable ignored) {
                return v;
            }
        }
        return def;
    }

    private static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Throwable e) {
            return s;
        }
    }

    /** YAML 双引号转义 */
    private static String quote(String s) {
        if (s == null) s = "";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * 纯 Java base64 解码（不依赖 {@code java.util.Base64}）——minSdk 24 上用 java.util.Base64
     * 会在 Android 7.x（API 24/25）抛 NoClassDefFoundError，故这里自己实现。
     * 支持标准表 + urlsafe（-_）+ 无填充 + 任意空白。
     */
    private static String decodeBase64(String s) {
        if (s == null) throw new IllegalArgumentException("null base64");
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') continue;
            if (c == '=') break;
            if (c == '-') c = '+';
            else if (c == '_') c = '/';
            int v = B64.indexOf(c);
            if (v < 0) throw new IllegalArgumentException("bad base64 char");
            sb.append((char) v);
        }
        int n = sb.length();
        if (n % 4 == 1) throw new IllegalArgumentException("bad base64 length");
        byte[] out = new byte[n * 3 / 4];
        int oi = 0;
        for (int i = 0; i < n; i += 4) {
            int b0 = sb.charAt(i);
            int b1 = i + 1 < n ? sb.charAt(i + 1) : 0;
            int b2 = i + 2 < n ? sb.charAt(i + 2) : 0;
            int b3 = i + 3 < n ? sb.charAt(i + 3) : 0;
            out[oi++] = (byte) ((b0 << 2) | (b1 >> 4));
            if (i + 2 < n) out[oi++] = (byte) (((b1 & 0x0f) << 4) | (b2 >> 2));
            if (i + 3 < n) out[oi++] = (byte) (((b2 & 0x03) << 6) | b3);
        }
        return new String(out, 0, oi, StandardCharsets.UTF_8);
    }

    private static final String B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    private static String stripBom(String s) {
        if (s == null) return "";
        return s.startsWith("\uFEFF") ? s.substring(1) : s;
    }

    private static String readText(File f) throws Exception {
        try (InputStream in = new java.io.FileInputStream(f)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        }
    }

    private static String shortMsg(Throwable e) {
        String m = e == null ? "" : e.getMessage();
        if (m == null) m = e == null ? "" : e.getClass().getSimpleName();
        return m.length() > 60 ? m.substring(0, 60) : m;
    }
}
