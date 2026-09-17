package com.github.catvod.net;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 上游代理的可用性判定（非阻塞 + 带缓存）。
 *
 * <p>用途：规则命中但上游代理不可用时，别把本来能直连的站弄挂——退回直连，并把原因打进日志。
 *
 * <p>🔴 设计铁律（2026-09-18 修复）：<b>本类的判定绝不允许在调用线程做任何 I/O</b>。
 * 以前这里是在调用线程里裸 {@code new Socket().connect(...)}，而这个判定会被网络栈
 * （含播放启动那条**主线程**路径）调用；安卓在主线程发 socket 会直接抛
 * {@code NetworkOnMainThreadException}，异常被 {@code catch (Throwable)} 吃掉 →
 * 误判“上游不通” → 本请求强制直连，并且这个 false 还会进缓存，把后面 15 秒内
 * **所有线程**的判定一起带偏。表现就是：mihomo 明明开着（7890 在听），
 * 日志却打印 {@code upstream 127.0.0.1:7890 unreachable -> direct}，业务全走直连。
 *
 * <p>现在的判定顺序：
 * <ol>
 *   <li><b>内核状态提供器</b>（{@link StatusProvider}，壳在启动时注入）：本机上游端口
 *       （如 mihomo {@code 127.0.0.1:7890}）由“内核在不在跑”直接决定——零 I/O、最准。
 *       提供器返回 {@link #STATE_UNKNOWN} 时才落回探测。</li>
 *   <li><b>缓存的探测结果</b>：首次未知时**乐观放行**（先让请求走代理），探测丢到
 *       专用后台线程上跑，结果写回缓存。成功缓存 {@link #UP_TTL_MS}，失败只缓存
 *       {@link #DOWN_TTL_MS}（一次误判不该污染一大片）。</li>
 * </ol>
 */
public final class ProxyHealth {

    /** 内核状态提供器：由壳（app 模块）在进程启动时注入，避免 catvod 反向依赖 app。 */
    public interface StatusProvider {

        /** 内核在跑 */
        int STATE_RUNNING = 1;

        /** 内核没跑 */
        int STATE_STOPPED = 0;

        /** 不知道，交给探测 */
        int STATE_UNKNOWN = -1;

        /** 该本机端口上的内核是否在跑（零 I/O，允许在主线程调用）。 */
        int coreState(int port);
    }

    private static final long UP_TTL_MS = 15000L;
    /** 失败结果只短期记忆：误判窗口要小。 */
    private static final long DOWN_TTL_MS = 2000L;
    private static final int TIMEOUT_MS = 800;
    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(new ThreadFactory() {

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "proxy-health");
            thread.setDaemon(true);
            return thread;
        }
    });
    private static volatile StatusProvider provider;

    private ProxyHealth() {
    }

    /** 注入内核状态提供器（壳启动时调用一次）。 */
    public static void setStatusProvider(StatusProvider value) {
        provider = value;
        CACHE.clear();
    }

    /** 供设置页/诊断用：强制下次重新判定。 */
    public static void invalidate() {
        CACHE.clear();
    }

    /**
     * 上游是否可用。<b>非阻塞</b>：任何时候都能安全地在主线程调用。
     *
     * @param host 上游主机（如 {@code 127.0.0.1}）
     * @param port 上游端口（如 {@code 7890}）
     */
    public static boolean isUp(String host, int port) {
        if (host == null || host.isEmpty() || port <= 0 || port > 65535) return false;
        if (isLoopback(host) && coreState(port) != StatusProvider.STATE_UNKNOWN) {
            return coreState(port) == StatusProvider.STATE_RUNNING;
        }
        Entry entry = CACHE.computeIfAbsent(host + ":" + port, key -> new Entry());
        long now = System.currentTimeMillis();
        synchronized (entry) {
            if (!entry.known) {
                // 乐观放行：让请求先走代理，后台探测再纠正（宁可多一跳，也不要误降级直连）。
                entry.known = true;
                entry.up = true;
                entry.at = now;
                scheduleProbe(entry, host, port);
                return true;
            }
            long ttl = entry.up ? UP_TTL_MS : DOWN_TTL_MS;
            if (now - entry.at >= ttl) {
                entry.at = now;
                entry.probing = true;
                scheduleProbe(entry, host, port);
            }
            return entry.up;
        }
    }

    private static int coreState(int port) {
        StatusProvider current = provider;
        if (current == null) return StatusProvider.STATE_UNKNOWN;
        try {
            int state = current.coreState(port);
            return state == StatusProvider.STATE_RUNNING || state == StatusProvider.STATE_STOPPED ? state : StatusProvider.STATE_UNKNOWN;
        } catch (Throwable e) {
            return StatusProvider.STATE_UNKNOWN;
        }
    }

    private static void scheduleProbe(Entry entry, String host, int port) {
        try {
            WORKER.execute(() -> {
                boolean up = probe(host, port);
                long at = System.currentTimeMillis();
                synchronized (entry) {
                    entry.known = true;
                    entry.up = up;
                    entry.at = at;
                    entry.probing = false;
                }
            });
        } catch (Throwable e) {
            synchronized (entry) {
                entry.probing = false;
            }
        }
    }

    /** 只允许在后台线程执行（WORKER）；调用线程绝不允许走到这里。 */
    private static boolean probe(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static boolean isLoopback(String host) {
        String value = host.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length() - 1);
        return "localhost".equals(value) || "::1".equals(value) || "0:0:0:0:0:0:0:1".equals(value) || value.startsWith("127.");
    }

    private static final class Entry {

        private boolean known;
        private boolean up;
        private boolean probing;
        private long at;
    }
}
