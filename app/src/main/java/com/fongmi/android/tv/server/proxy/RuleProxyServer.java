package com.fongmi.android.tv.server.proxy;

import com.fongmi.android.tv.setting.ProxySetting;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Path;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 壳内"规则出口"：一个只听 {@code 127.0.0.1} 的本地 HTTP 代理，按设置里的 proxy 规则
 * 逐域名决定"走代理还是直连"。
 *
 * <p>为什么需要它：设置里的 proxy 规则是 Java 侧的东西（{@code OkProxySelector} + JVM 默认
 * 选择器），只能管住 OkHttp/URLConnection 这类走 Java 网络栈的请求。而壳内还有四条通道
 * 完全吃不到规则：
 * <ul>
 *   <li>WebView（Chromium 自己的网络栈，只认 ProxyController 给的一个固定代理）</li>
 *   <li>IJK / MPV 原生播放（ffmpeg 自己的 http，只认一个 {@code http_proxy} 参数）</li>
 *   <li>python 源（chaquo 里跑的 requests，不受 JVM 选择器影响）</li>
 *   <li>node 猫源（独立进程，libnode 自己的 http）</li>
 * </ul>
 * 这四条都只能接受"一个代理端点"，没法各自实现规则分流。所以规则判定放在这里：
 * 它们统一把请求交给本端点，由本端点按规则决定上游——命中规则的转发给上游代理，
 * 没命中的直接连目标，本机/局域网目标永远直连。
 *
 * <p>只监听回环地址。端点在 App 启动时拉起、进程内常驻：开关只决定"命中规则时走上游
 * 还是直连"（关掉开关时对每个域名都直连）。之所以不随开关停掉——IJK/MPV 这类通道只能配
 * 一个固定端点，端点一旦停了，残留的配置就会指向一个没人监听的端口而整条通道不可用。
 */
public final class RuleProxyServer {

    private static final int HEAD_LIMIT = 32 * 1024;
    private static final int MAX_CONNECTIONS = 96;
    private static final int CONNECT_TIMEOUT_MS = 10000;
    /** 空闲超时给大一点：播放暂停/缓冲时隧道可能几分钟没有数据，别把连接掐了。 */
    private static final int READ_TIMEOUT_MS = 300000;
    private static final long LOG_INTERVAL_MS = 30000L;
    private static final String TAG = "proxy";

    private static volatile RuleProxyServer instance;
    private static final Map<String, Long> LOG_AT = new HashMap<>();

    private final ServerSocket server;
    private final ExecutorService workers = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "shell-proxy");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger live = new AtomicInteger();

    private RuleProxyServer(ServerSocket server) {
        this.server = server;
        Thread accept = new Thread(this::accept, "shell-proxy-accept");
        accept.setDaemon(true);
        accept.start();
    }

    /** 确保端点就绪（已起则不动）；{@code on=false} 表示停掉，正常流程不再用，留给排障。 */
    public static synchronized void sync(boolean on) {
        if (!on) {
            stop();
            return;
        }
        if (instance != null) return;
        start();
    }

    public static synchronized void stop() {
        RuleProxyServer current = instance;
        instance = null;
        if (current != null) current.close();
    }

    /** 端点端口；未启动返回 -1。 */
    public static int port() {
        RuleProxyServer current = instance;
        return current == null ? -1 : current.server.getLocalPort();
    }

    public static String url() {
        int port = port();
        return port <= 0 ? "" : "http://127.0.0.1:" + port;
    }

    /**
     * 状态文件：给"另一个进程/另一种运行时"用的出口信息。
     *
     * <p>node 猫源跑在独立进程里（libnode），拿不到 Java 的判定函数，只能读这个文件；
     * 端口是动态分配的，所以必须落盘而不是写死。python 源走 {@code Proxy.shellProxyFor}，
     * 不需要这个文件。
     */
    public static File stateFile() {
        return new File(Path.files(), "proxy_state.json");
    }

    public static void writeState(boolean enabled) {
        int port = port();
        boolean on = enabled && port > 0;
        String json = "{\"enabled\":" + on + ",\"port\":" + (on ? port : 0) + "}";
        File target = stateFile();
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Throwable e) {
            log("state-failed", "error=%s", e.toString());
            return;
        }
        // 先写临时文件再改名：node 侧读数时不会读到半截内容
        if (!temp.renameTo(target) && !(target.delete() && temp.renameTo(target))) log("state-failed", "rename failed");
    }

    private static void start() {
        try {
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            RuleProxyServer server = new RuleProxyServer(socket);
            instance = server;
            SpiderDebug.log(TAG, "rule endpoint listening port=%s", socket.getLocalPort());
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "rule endpoint start failed error=%s", e.toString());
        }
    }

    private void close() {
        try {
            server.close();
        } catch (Throwable ignored) {
        }
        workers.shutdownNow();
        SpiderDebug.log(TAG, "rule endpoint stopped");
    }

    private void accept() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                if (live.incrementAndGet() > MAX_CONNECTIONS) {
                    live.decrementAndGet();
                    close(client);
                    continue;
                }
                workers.execute(() -> {
                    try {
                        handle(client);
                    } catch (Throwable e) {
                        log("conn-error", "error=%s", e.toString());
                    } finally {
                        live.decrementAndGet();
                        close(client);
                    }
                });
            } catch (Throwable e) {
                if (server.isClosed()) return;
                log("accept-error", "error=%s", e.toString());
            }
        }
    }

    private void handle(Socket client) throws IOException {
        client.setSoTimeout(READ_TIMEOUT_MS);
        client.setTcpNoDelay(true);
        // 注意：这里绝不能套 BufferedInputStream——它会预读把紧随头部的请求体/隧道数据
        // 吞进缓冲区，后面换流做透传时那部分字节就丢了（POST 变空体、CONNECT 隧道直接坏）。
        // 一个字节一个字节读，只取头部。
        String head = readHead(client.getInputStream());
        if (head == null || head.isEmpty()) return;
        int end = head.indexOf("\r\n");
        String[] request = (end < 0 ? head : head.substring(0, end)).split(" ");
        if (request.length < 2) return;
        String method = request[0].toUpperCase(Locale.ROOT);
        String target = request[1];
        boolean tunnel = "CONNECT".equals(method);
        String host;
        int port;
        String path = "/";
        if (tunnel) {
            host = hostOf(target);
            port = portOf(target, 443);
        } else {
            host = hostOf(target);
            port = portOf(target, 80);
            int slash = target.indexOf('/', target.indexOf("//") + 3);
            if (target.contains("://") && slash > 0) path = target.substring(slash);
            // 兜底：少数客户端用 origin-form（请求行只有路径），目标在 Host 头里
            if (host.isEmpty()) {
                String header = headerValue(head, "host");
                host = hostOf(header);
                port = portOf(header, port);
            }
        }
        if (host == null || host.isEmpty()) {
            respond(client, "400 Bad Request");
            return;
        }
        String upstream = ProxySetting.upstreamForHost(host);
        Socket remote = connect(upstream, host, port);
        if (remote == null) {
            log("dial-failed", "target=%s:%s upstream=%s", host, port, upstream.isEmpty() ? "direct" : upstream);
            respond(client, "502 Bad Gateway");
            return;
        }
        try {
            remote.setSoTimeout(READ_TIMEOUT_MS);
            if (upstream.isEmpty()) {
                if (tunnel) {
                    write(client, "HTTP/1.1 200 Connection Established\r\n\r\n");
                } else {
                    write(remote, originForm(head, method, path));
                }
                log("direct", "target=%s:%s", host, port);
            } else if (tunnel) {
                boolean established = isSocks(upstream) ? socks(remote, host, port) : tunnel(remote, host, port);
                if (!established) {
                    log("tunnel-failed", "target=%s:%s upstream=%s", host, port, upstream);
                    respond(client, "502 Bad Gateway");
                    return;
                }
                write(client, "HTTP/1.1 200 Connection Established\r\n\r\n");
                log("proxy", "target=%s:%s upstream=%s", host, port, upstream);
            } else if (isSocks(upstream)) {
                if (!socks(remote, host, port)) {
                    respond(client, "502 Bad Gateway");
                    return;
                }
                write(remote, originForm(head, method, path));
                log("proxy", "target=%s:%s upstream=%s", host, port, upstream);
            } else {
                // 普通 HTTP 请求交给 http 代理：请求行保持绝对 URI 原样转发即可
                write(remote, head);
                log("proxy", "target=%s:%s upstream=%s", host, port, upstream);
            }
            pipe(client, remote);
        } finally {
            close(remote);
        }
    }

    private Socket connect(String upstream, String host, int port) {
        Socket socket = new Socket();
        try {
            if (upstream.isEmpty()) {
                socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            } else {
                socket.connect(new InetSocketAddress(upstreamHost(upstream), upstreamPort(upstream)), CONNECT_TIMEOUT_MS);
            }
            return socket;
        } catch (Throwable e) {
            close(socket);
            return null;
        }
    }

    private boolean tunnel(Socket remote, String host, int port) {
        try {
            write(remote, "CONNECT " + host + ":" + port + " HTTP/1.1\r\nHost: " + host + ":" + port + "\r\nProxy-Connection: keep-alive\r\n\r\n");
            String head = readHead(remote.getInputStream());
            return head != null && head.startsWith("HTTP/") && head.contains(" 200");
        } catch (Throwable e) {
            return false;
        }
    }

    private boolean socks(Socket remote, String host, int port) {
        try {
            OutputStream out = remote.getOutputStream();
            InputStream in = remote.getInputStream();
            out.write(new byte[]{5, 1, 0});
            out.flush();
            byte[] greeting = new byte[2];
            readFully(in, greeting);
            if (greeting[0] != 5 || greeting[1] != 0) return false;
            byte[] name = host.getBytes(StandardCharsets.US_ASCII);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            buffer.write(new byte[]{5, 1, 0, 3, (byte) name.length});
            buffer.write(name);
            buffer.write((port >> 8) & 0xFF);
            buffer.write(port & 0xFF);
            out.write(buffer.toByteArray());
            out.flush();
            byte[] reply = new byte[4];
            readFully(in, reply);
            if (reply[1] != 0) return false;
            int length = reply[3] == 1 ? 4 : reply[3] == 4 ? 16 : (in.read() & 0xFF);
            readFully(in, new byte[length + 2]);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private void pipe(Socket client, Socket remote) {
        Thread back = new Thread(() -> copy(remote, client), "shell-proxy-back");
        back.setDaemon(true);
        back.start();
        copy(client, remote);
        closeQuietly(remote);
        closeQuietly(client);
        try {
            back.join(500);
        } catch (InterruptedException ignored) {
        }
    }

    private void copy(Socket from, Socket to) {
        byte[] buffer = new byte[16384];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = new BufferedOutputStream(to.getOutputStream(), 32768);
            int length;
            while ((length = in.read(buffer)) > 0) {
                out.write(buffer, 0, length);
                out.flush();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 把绝对 URI 的请求行改写为 origin-form（直连/走 socks 时用）。
     *
     * <p>同时剥掉 Connection / Proxy-* 头并强制 {@code Connection: close}：客户端把我们当
     * http 代理时，同一条连接上的后续请求仍是绝对 URI 形式，而直连的目标服务器不认这种
     * 请求行；一次性连接（响应完即断）最稳，代价只是不复用连接。
     */
    private String originForm(String head, String method, String path) {
        int end = head.indexOf("\r\n");
        if (end < 0) return head;
        StringBuilder out = new StringBuilder();
        out.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        boolean upgrade = false;
        for (String line : head.substring(end + 2).split("\r\n")) {
            if (line.isEmpty()) continue;
            String key = line.toLowerCase(Locale.ROOT);
            if (key.startsWith("proxy-connection:") || key.startsWith("proxy-authorization:")) continue;
            if (key.startsWith("connection:")) {
                // 客户端要升级协议（WebSocket）时必须把 Connection: Upgrade 带过去，
                // 否则握手被目标服务器拒掉。
                if (line.toLowerCase(Locale.ROOT).contains("upgrade")) upgrade = true;
                continue;
            }
            if (key.startsWith("upgrade:") || key.startsWith("sec-websocket-")) upgrade = true;
            out.append(line).append("\r\n");
        }
        out.append("Connection: ").append(upgrade ? "Upgrade" : "close").append("\r\n\r\n");
        return out.toString();
    }

    /**
     * 只读到头部结束（{@code \r\n\r\n}）为止，一个字节一个字节读，保证不会把紧随其后的
     * 请求体/隧道数据吃掉——后面是原始透传。
     */
    private String readHead(InputStream in) throws IOException {
        byte[] buffer = new byte[HEAD_LIMIT];
        int size = 0;
        while (size < buffer.length) {
            int current = in.read();
            if (current < 0) break;
            buffer[size++] = (byte) current;
            if (size >= 4 && buffer[size - 4] == '\r' && buffer[size - 3] == '\n' && buffer[size - 2] == '\r' && buffer[size - 1] == '\n') break;
            if (size >= 2 && buffer[size - 2] == '\n' && buffer[size - 1] == '\n') break;
        }
        String text = new String(buffer, 0, size, StandardCharsets.ISO_8859_1);
        return text.trim().isEmpty() ? null : text;
    }

    private void respond(Socket client, String status) {
        try {
            write(client, "HTTP/1.1 " + status + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        } catch (Throwable ignored) {
        }
    }

    private void write(Socket socket, String text) {
        try {
            OutputStream out = socket.getOutputStream();
            out.write(text.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        } catch (Throwable ignored) {
        }
    }

    private void readFully(InputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = in.read(buffer, offset, buffer.length - offset);
            if (read < 0) throw new IOException("unexpected eof");
            offset += read;
        }
    }

    private static boolean isSocks(String upstream) {
        return upstream != null && upstream.toLowerCase(Locale.ROOT).startsWith("socks");
    }

    private static String upstreamHost(String upstream) {
        String value = upstream.substring(upstream.indexOf("://") + 3);
        int end = value.indexOf(':');
        return end < 0 ? value : value.substring(0, end);
    }

    private static int upstreamPort(String upstream) {
        String value = upstream.substring(upstream.indexOf("://") + 3);
        int end = value.indexOf(':');
        try {
            return end < 0 ? 7890 : Integer.parseInt(value.substring(end + 1).replaceAll("[^0-9].*$", ""));
        } catch (Throwable e) {
            return 7890;
        }
    }

    private static String headerValue(String head, String name) {
        for (String line : head.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            if (line.substring(0, colon).trim().equalsIgnoreCase(name)) return line.substring(colon + 1).trim();
        }
        return "";
    }

    private static String hostOf(String target) {
        String value = target;
        int scheme = value.indexOf("://");
        if (scheme > 0) value = value.substring(scheme + 3);
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        int at = value.lastIndexOf('@');
        if (at >= 0) value = value.substring(at + 1);
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            return end < 0 ? value : value.substring(1, end);
        }
        int colon = value.lastIndexOf(':');
        return colon < 0 ? value : value.substring(0, colon);
    }

    private static int portOf(String target, int fallback) {
        String value = target;
        int scheme = value.indexOf("://");
        if (scheme > 0) value = value.substring(scheme + 3);
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        int at = value.lastIndexOf('@');
        if (at >= 0) value = value.substring(at + 1);
        if (value.startsWith("[")) {
            int end = value.indexOf("]:");
            if (end < 0) return fallback;
            value = value.substring(end + 2);
        } else {
            int colon = value.lastIndexOf(':');
            if (colon < 0) return fallback;
            value = value.substring(colon + 1);
        }
        try {
            return Integer.parseInt(value);
        } catch (Throwable e) {
            return fallback;
        }
    }

    private static void close(Socket socket) {
        try {
            if (socket != null) socket.close();
        } catch (Throwable ignored) {
        }
    }

    private static void closeQuietly(Socket socket) {
        close(socket);
    }

    private static void log(String event, String format, Object... args) {
        long now = System.currentTimeMillis();
        synchronized (LOG_AT) {
            Long last = LOG_AT.get(event);
            if (last != null && now - last < LOG_INTERVAL_MS) return;
            LOG_AT.put(event, now);
        }
        SpiderDebug.log(TAG, "rule-endpoint " + event + " " + format, args);
    }
}
