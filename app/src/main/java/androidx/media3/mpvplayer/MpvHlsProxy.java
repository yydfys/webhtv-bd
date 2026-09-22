package androidx.media3.mpvplayer;

import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Base64;

import androidx.annotation.Nullable;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.AdBlockStatsStore;
import com.fongmi.android.tv.api.config.HlsRuleConfig;
import com.fongmi.android.tv.utils.HlsAdblockNotice;
import com.fongmi.android.tv.utils.HlsAdblockPipeline;
import com.fongmi.android.tv.utils.HlsManifestCleaner;
import com.fongmi.android.tv.utils.Notify;

import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackRouteRegistry;
import com.fongmi.android.tv.player.PlaybackResourceClassifier;
import com.fongmi.android.tv.player.PlaybackSystemConditionMonitor;
import com.fongmi.android.tv.player.PreloadPausePolicy;
import com.fongmi.android.tv.player.cache.PlaybackDiskBufferStore;
import com.fongmi.android.tv.player.mpv.MpvPreloadPolicy;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.PlaybackPerformanceCatalog;
import com.fongmi.android.tv.setting.PlaybackPerformanceSetting;
import com.fongmi.android.tv.setting.PreloadSetting;
import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.StringReader;
import java.io.StringWriter;
import org.xml.sax.InputSource;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response.Status;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.ResponseBody;

public final class MpvHlsProxy extends NanoHTTPD {

    private static final String TAG = "mpv-proxy";
    private static final String MIME_M3U8 = "application/vnd.apple.mpegurl; charset=utf-8";
    private static final String MIME_TS = "video/MP2T";
    private static final String MIME_BINARY = "application/octet-stream";
    private static final String CACHE_FILE_SUFFIX = ".bin";
    private static final String CACHE_META_SUFFIX = ".meta";
    private static final int PREFIX_SCAN_LIMIT = 64 * 1024;
    private static final long SESSION_TTL_MS = TimeUnit.MINUTES.toMillis(3);
    private static final long MIN_CACHE_FILE_BYTES = 1;
    private static final int MAX_PENDING_PRELOAD_SEGMENTS = 256;
    private static final Pattern DASH_BASE_URL = Pattern.compile("(<BaseURL\\b[^>]*>)(.*?)(</BaseURL>)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", Pattern.CASE_INSENSITIVE);

    private final OkHttpClient client;
    private final int kernel;
    private final MpvHlsCacheCoordinator cacheCoordinator;
    private final Map<Integer, Session> sessions;
    private final Map<Integer, SessionStats> sessionStats;
    private final Map<String, Target> targets;
    private final Map<Integer, CompletableFuture<DashHlsPlan>> dashHlsPlans;
    /** 仅 IJK 内核：AES-128 加密清单的服务端解密状态（MPV/其它内核不写入）。 */
    private final Map<Integer, HlsAesSession> aesSessions;
    private final AtomicLong nextId;
    private final java.util.Set<String> preloading;
    private final MpvHlsPreloadGate preloadGate;
    private final MpvHlsUpstreamEstimator upstreamEstimator;
    private final PlaybackDiskBufferStore diskBufferStore;
    private final AtomicInteger activePreloadTransfers;
    private ExecutorService preloadExecutor;
    private ExecutorService dashHlsPlanExecutor;
    private MpvHlsCacheCoordinator.ClientLease cacheClient;
    private PlaybackRouteRegistry.Registration routeRegistration;
    private int preloadThreads;
    private volatile int sessionId;
    private volatile boolean started;
    private volatile boolean automaticPreloadMode;
    private volatile boolean automaticResourceAllowed = true;
    private volatile boolean automaticTrafficAllowed;
    private volatile boolean playbackPaused;
    private volatile long lastManualPreloadPositionMs = Long.MIN_VALUE;
    private volatile long lastManualPreloadAtMs;

    public MpvHlsProxy() {
        this(PlayerSetting.MPV);
    }

    public MpvHlsProxy(int kernel) {
        super("127.0.0.1", 0);
        this.kernel = PlayerSetting.sanitizePlayer(kernel);
        cacheCoordinator = MpvHlsCacheCoordinator.shared(Path.cache("mpv_hls"));
        client = OkHttp.player().newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        sessions = new ConcurrentHashMap<>();
        sessionStats = new ConcurrentHashMap<>();
        targets = new ConcurrentHashMap<>();
        dashHlsPlans = new ConcurrentHashMap<>();
        aesSessions = new ConcurrentHashMap<>();
        nextId = new AtomicLong();
        preloading = ConcurrentHashMap.newKeySet();
        preloadGate = new MpvHlsPreloadGate();
        upstreamEstimator = new MpvHlsUpstreamEstimator();
        diskBufferStore = PlaybackDiskBufferStore.process();
        activePreloadTransfers = new AtomicInteger();
    }

    /**
     * DASH 转 HLS 的专用线程池。
     *
     * <p>开播流程跑在主线程上，而取 MPD、取 {@code SegmentBase@indexRange} 的 sidx 索引
     * 都是网络请求——主线程同步发网络请求，安卓必抛 {@code NetworkOnMainThreadException}，
     * 转换注定失败。所以转换整段丢到这个池子里跑，主线程只拿本地代理地址。
     */
    private synchronized ExecutorService dashHlsPlanExecutor() {
        if (dashHlsPlanExecutor == null || dashHlsPlanExecutor.isShutdown()) {
            dashHlsPlanExecutor = Executors.newFixedThreadPool(DASH_HLS_PLAN_THREADS, runnable -> {
                Thread thread = new Thread(runnable, "mpv-proxy-dashhls");
                thread.setDaemon(true);
                return thread;
            });
        }
        return dashHlsPlanExecutor;
    }

    private synchronized void releaseDashHlsPlanExecutor() {
        if (dashHlsPlanExecutor == null) return;
        dashHlsPlanExecutor.shutdownNow();
        dashHlsPlanExecutor = null;
    }

    public synchronized String proxy(String url, Map<String, String> headers) throws IOException {
        return proxy(url, headers, url);
    }

    HlsAdTimeline adTimeline(long selectedBitsPerSecond) {
        if (kernel != PlayerSetting.MPV || !Setting.isAdblock()) return HlsAdTimeline.NONE;
        SessionStats stats = sessionStats.get(sessionId);
        if (stats == null || !stats.vod) return HlsAdTimeline.NONE;
        return resolveAdTimeline(stats.directAdTimeline, stats.adTimelines,
                selectedBitsPerSecond, stats.variants.size());
    }

    static HlsAdTimeline resolveAdTimeline(
            @Nullable HlsAdTimeline direct,
            Map<HlsPlaylistRewriter.Variant, HlsAdTimeline> variants,
            long selectedBitsPerSecond, int declaredVariantCount) {
        if (direct != null) return direct;
        HlsAdTimeline candidate = null;
        int matched = 0;
        for (Map.Entry<HlsPlaylistRewriter.Variant, HlsAdTimeline> entry : variants.entrySet()) {
            HlsPlaylistRewriter.Variant variant = entry.getKey();
            if (variant.kind() != HlsPlaylistRewriter.VariantKind.STREAM) continue;
            if (selectedBitsPerSecond > 0 && selectedBitsPerSecond != variant.bandwidth()
                    && selectedBitsPerSecond != variant.averageBandwidth()) continue;
            if (candidate != null && !candidate.sameCuts(entry.getValue())) return HlsAdTimeline.NONE;
            candidate = entry.getValue();
            matched++;
        }
        // Probing a rendition does not mean it is selected. Until native selection
        // is known, require agreement from every declared regular video variant.
        if (selectedBitsPerSecond <= 0
                && (declaredVariantCount <= 0 || matched != declaredVariantCount)) return HlsAdTimeline.NONE;
        return candidate == null ? HlsAdTimeline.NONE : candidate;
    }

    public synchronized String proxy(
            String url, Map<String, String> headers, String mediaKey) throws IOException {
        ensureStarted();
        refreshCacheCoordinator();
        int id = ++this.sessionId;
        upstreamEstimator.reset();
        Session session = new Session(
                url, sanitize(headers), System.currentTimeMillis(),
                resolveMediaKey(mediaKey, url));
        diskBufferStore.reset(session.mediaKey());
        sessions.put(id, session);
        SessionStats stats = new SessionStats();
        stats.classification = PlaybackResourceClassifier.classify(
                baseUrl() + "/mpv/index.m3u8?s=" + id,
                url,
                "application/vnd.apple.mpegurl",
                "hls",
                Map.of(),
                null);
        sessionStats.put(id, stats);
        pruneExpiredSessions(session.createdAtMs);
        pruneCache();
        String proxyUrl = baseUrl() + "/mpv/index.m3u8?s=" + sessionId;
        SpiderDebug.log(TAG, "enabled session=%d url=%s headers=%s proxy=%s", sessionId, shortUrl(url), session.headers.keySet(), proxyUrl);
        return proxyUrl;
    }

    public synchronized String proxyDash(String url, Map<String, String> headers) throws IOException {
        return proxyDash(url, headers, url);
    }

    public synchronized String proxyDash(
            String url, Map<String, String> headers, String mediaKey) throws IOException {
        ensureStarted();
        refreshCacheCoordinator();
        int id = ++this.sessionId;
        upstreamEstimator.reset();
        Session session = new Session(
                url, sanitize(headers), System.currentTimeMillis(),
                resolveMediaKey(mediaKey, url));
        diskBufferStore.reset(session.mediaKey());
        sessions.put(id, session);
        SessionStats stats = new SessionStats();
        stats.classification = PlaybackResourceClassifier.classify(
                baseUrl() + "/mpv/index.mpd?s=" + id,
                url,
                "application/dash+xml",
                "dash",
                Map.of(),
                null);
        sessionStats.put(id, stats);
        pruneExpiredSessions(session.createdAtMs);
        String proxyUrl = baseUrl() + "/mpv/index.mpd?s=" + id;
        SpiderDebug.log(TAG, "dash enabled session=%d url=%s headers=%s proxy=%s", id, shortUrl(url), session.headers.keySet(), proxyUrl);
        return proxyUrl;
    }

    public synchronized String proxyFile(
            String url, Map<String, String> headers, String mediaKey) throws IOException {
        ensureStarted();
        refreshCacheCoordinator();
        int id = ++this.sessionId;
        upstreamEstimator.reset();
        Session session = new Session(
                url, sanitize(headers), System.currentTimeMillis(),
                resolveMediaKey(mediaKey, url));
        diskBufferStore.reset(session.mediaKey());
        sessions.put(id, session);
        String targetId = Long.toString(nextId.incrementAndGet());
        String proxyUrl = baseUrl() + "/mpv/item?s=" + id + "&id=" + targetId;
        targets.put(targetId, new Target(
                id, url, session.createdAtMs(), false, null,
                HlsPlaylistRewriter.UriRole.OTHER, 0, 0));
        SessionStats stats = new SessionStats();
        stats.vod = true;
        stats.classification = PlaybackResourceClassifier.classify(
                proxyUrl, url, null, null, Map.of(), null);
        sessionStats.put(id, stats);
        pruneExpiredSessions(session.createdAtMs());
        SpiderDebug.log(TAG,
                "file enabled session=%d sourceBytes=%d headers=%s proxy=%s",
                id,
                url == null ? 0 : url.getBytes(StandardCharsets.UTF_8).length,
                session.headers().keySet(),
                proxyUrl);
        return proxyUrl;
    }

    public synchronized void clear() {
        sessions.clear();
        sessionStats.clear();
        targets.clear();
        dashHlsPlans.clear();
        aesSessions.clear();
        upstreamEstimator.reset();
        lastManualPreloadPositionMs = Long.MIN_VALUE;
        lastManualPreloadAtMs = 0;
        cancelPreloads();
    }

    /** Auto-mode gate only; foreground proxy reads and cache hits remain available. */
    public boolean updateAutomaticPreloadControl(
            boolean automatic,
            boolean resourceAllowed,
            boolean trafficAllowed) {
        automaticPreloadMode = automatic;
        automaticResourceAllowed = resourceAllowed;
        automaticTrafficAllowed = trafficAllowed;
        preloadGate.setForegroundBlocking(!automatic || !playbackPaused);
        boolean allowed = desiredPreloadAllowed();
        MpvHlsPreloadGate.Transition transition = preloadGate.update(allowed);
        if (transition == MpvHlsPreloadGate.Transition.BLOCKED) {
            releasePreloadWork(true);
        }
        if (transition.changed()) {
            SpiderDebug.log(TAG,
                    "preload-gate mode=%s resource=%s traffic=%s state=%s action=%s active=%d",
                    automatic ? "automatic" : "manual",
                    resourceAllowed ? "allowed" : "blocked",
                    trafficAllowed ? "allowed" : "blocked",
                    allowed ? "allowed" : "blocked",
                    transition.label(),
                    preloading.size());
        }
        return transition.changed();
    }

    public void setPlaybackPaused(boolean paused) {
        boolean changed = playbackPaused != paused;
        playbackPaused = paused;
        if (automaticPreloadMode && kernel == PlayerSetting.MPV) {
            preloadGate.setForegroundBlocking(!paused);
            if (changed && !paused) releasePreloadWork(false);
        }
        boolean allowed = desiredPreloadAllowed();
        MpvHlsPreloadGate.Transition transition = preloadGate.update(allowed);
        if (transition == MpvHlsPreloadGate.Transition.BLOCKED) releasePreloadWork(true);
        if (transition.changed()) {
            SpiderDebug.log(TAG, "preload-pause paused=%s policy=%d state=%s action=%s",
                    paused,
                    PreloadSetting.getPausePreloadPolicy(kernel),
                    allowed ? "allowed" : "blocked",
                    transition.label());
        }
    }

    public synchronized void release() {
        clear();
        releaseDashHlsPlanExecutor();
        try {
            if (started) stop();
        } finally {
            if (routeRegistration != null) routeRegistration.close();
            routeRegistration = null;
            if (cacheClient != null) cacheClient.close();
            cacheClient = null;
            started = false;
        }
    }

    String diagnostics() {
        return "session " + sessionId
                + " / items " + targets.size()
                + " / cache " + formatBytes(cacheBytes())
                + "/" + formatBytes(cacheLimitBytes())
                + " / preload " + preloading.size()
                + "/" + (preloadGate.allowed() ? "allowed" : "blocked")
                + "/fg=" + preloadGate.foregroundRequests()
                + " / " + statsText();
    }

    @Nullable
    public PlaybackResourceClassifier.Classification resourceClassification() {
        SessionStats stats = sessionStats.get(sessionId);
        if (stats == null) return null;
        PlaybackResourceClassifier.Classification classification =
                stats.resourceClassification(SystemClock.elapsedRealtime());
        if (classification != null) return classification;
        Session session = sessions.get(sessionId);
        if (session == null) return null;
        return PlaybackResourceClassifier.classifyRequest(session.url, null, null);
    }

    public LiveLagSnapshot liveLagSnapshot(long nativeBufferedDurationMs) {
        if (kernel != PlayerSetting.IJK) return LiveLagSnapshot.unknown();
        SessionStats stats = sessionStats.get(sessionId);
        if (stats == null) return LiveLagSnapshot.unknown();
        long now = SystemClock.elapsedRealtime();
        PlaybackResourceClassifier.Classification classification =
                stats.resourceClassification(now);
        PlaybackAutoContext.StreamKind stream = classification == null
                ? PlaybackAutoContext.StreamKind.UNKNOWN
                : classification.streamKind();
        if (stream != PlaybackAutoContext.StreamKind.LIVE
                && stream != PlaybackAutoContext.StreamKind.LOW_LATENCY_LIVE) {
            return LiveLagSnapshot.unknown();
        }
        HlsProxyLiveLagTracker.Snapshot snapshot =
                stats.liveLagSnapshot(now, nativeBufferedDurationMs);
        return snapshot.known()
                ? new LiveLagSnapshot(true, snapshot.lowerBoundMs(),
                snapshot.nativeBufferedDurationMs(), snapshot.outsideWindow())
                : LiveLagSnapshot.unknown();
    }

    HlsVariantSnapshot variantSnapshot() {
        SessionStats stats = sessionStats.get(sessionId);
        if (stats == null) return HlsVariantSnapshot.empty();
        return stats.variantSnapshot();
    }

    public void preloadAround(long positionMs) {
        if (automaticPreloadMode || !shouldRequestManualPreload(positionMs)) return;
        Session owner = sessions.get(sessionId);
        SessionStats stats = sessionStats.get(sessionId);
        if (owner == null || stats == null || !stats.vod || stats.segments.isEmpty()) return;
        preloadSegments(owner, stats.segments, Math.max(0, positionMs) / 1000.0);
    }

    public void preloadWhilePaused(long positionMs) {
        preloadWhilePaused(positionMs, 0);
    }

    void preloadWhilePaused(long positionMs, long selectedBitsPerSecond) {
        if (!playbackPaused || !isPausePreloadAllowed()) return;
        Session owner = sessions.get(sessionId);
        SessionStats stats = sessionStats.get(sessionId);
        if (owner == null || stats == null || !stats.vod) return;
        List<HlsPlaylistRewriter.Segment> selected = automaticPreloadMode
                ? stats.segmentsForVariant(selectedBitsPerSecond) : stats.segments;
        if (selected.isEmpty()) return;
        preloadSegments(owner, selected, Math.max(0, positionMs) / 1000.0);
    }

    private boolean shouldRequestManualPreload(long positionMs) {
        long position = Math.max(0, positionMs);
        long now = SystemClock.elapsedRealtime();
        long previousPosition = lastManualPreloadPositionMs;
        if (previousPosition != Long.MIN_VALUE
                && Math.abs(position - previousPosition) < TimeUnit.SECONDS.toMillis(10)
                && now - lastManualPreloadAtMs < TimeUnit.SECONDS.toMillis(10)) {
            return false;
        }
        lastManualPreloadPositionMs = position;
        lastManualPreloadAtMs = now;
        return true;
    }

    void requestAutomaticPreload(long positionMs, long selectedBitsPerSecond) {
        if (!automaticPreloadMode
                || !automaticResourceAllowed
                || !automaticTrafficAllowed
                || preloadGate.foregroundRequests() > 0) return;
        Session owner = sessions.get(sessionId);
        SessionStats stats = sessionStats.get(sessionId);
        if (owner == null || stats == null || !stats.vod) return;
        List<HlsPlaylistRewriter.Segment> selected =
                stats.segmentsForVariant(selectedBitsPerSecond);
        if (selected.isEmpty()) return;
        preloadSegments(owner, selected, Math.max(0, positionMs) / 1000.0);
    }

    void cancelAutomaticPreloadForDiscontinuity() {
        if (!automaticPreloadMode || kernel != PlayerSetting.MPV) return;
        automaticTrafficAllowed = false;
        preloadGate.update(false);
        releasePreloadWork(true);
        SpiderDebug.log(TAG,
                "preload discontinuity action=cancel active=%d transfers=%d",
                preloading.size(), activePreloadTransfers.get());
    }

    PreloadRuntimeSnapshot preloadRuntimeSnapshot(long nowElapsedMs) {
        SessionStats stats = sessionStats.get(sessionId);
        if (stats == null || !stats.vod) {
            return new PreloadRuntimeSnapshot(
                    PreloadSetting.isPreload(kernel),
                    false,
                    0,
                    false,
                    false,
                    -1,
                    -1,
                    0,
                    0,
                    "none",
                    preloadGate.foregroundRequests(),
                    false,
                    false,
                    false,
                    false,
                    0,
                    0,
                    0,
                    0,
                    preloading.size());
        }
        refreshCacheCoordinator();
        MpvHlsUpstreamEstimator.Snapshot throughput =
                upstreamEstimator.snapshot(nowElapsedMs);
        MpvHlsCacheCoordinator.PreloadCapacitySnapshot preloadCapacity =
                cacheCoordinator.preloadSnapshot(configuredPreloadLimitBytes());
        MpvHlsCacheCoordinator.CapacitySnapshot capacity =
                preloadCapacity.capacity();
        boolean storageKnown = capacity.policy().state()
                != com.fongmi.android.tv.player.cache.DiskCacheCapacityPolicy.State.UNAVAILABLE;
        boolean cacheEnabled = capacity.policy().state()
                != com.fongmi.android.tv.player.cache.DiskCacheCapacityPolicy.State.DISABLED;
        boolean budgetAvailable = preloadCapacity.allowed()
                || !preloading.isEmpty() || activePreloadTransfers.get() > 0;
        return new PreloadRuntimeSnapshot(
                PreloadSetting.isPreload(kernel),
                stats != null && stats.vod,
                throughput.bitsPerSecond(),
                throughput.known(),
                throughput.fresh(),
                throughput.sampledAtElapsedMs(),
                throughput.ageMs(),
                throughput.acceptedSamples(),
                throughput.rejectedSamples(),
                throughput.lastRejectReason().label(),
                preloadGate.foregroundRequests(),
                cacheEnabled,
                storageKnown,
                budgetAvailable,
                capacity.circuitOpen(),
                capacity.physicalBytes(),
                capacity.reservedBytes(),
                capacity.policy().newWriteBudgetBytes(),
                capacity.policy().effectiveCapacityBytes(),
                preloading.size());
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            String path = session.getUri();
            if (path == null) return error(Status.NOT_FOUND, "missing path");
            if (path.startsWith("/mpv/index.m3u8")) return servePlaylist(session);
            if (path.startsWith("/mpv/index.mpd")) return serveDash(session);
            if (path.startsWith("/mpv/dashhls/")) return serveDashHls(session);
            if (path.startsWith("/mpv/dash-item/")) return serveItem(session, dashItemId(path));
            if (path.startsWith("/mpv/item")) return serveItem(session, null);
            return error(Status.NOT_FOUND, "not found");
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "serve failed errorType=%s", e.getClass().getSimpleName());
            return error(Status.INTERNAL_ERROR, "proxy failure");
        }
    }

    private void ensureStarted() throws IOException {
        if (started) return;
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
        routeRegistration = PlaybackRouteRegistry.registerAppService(getListeningPort(), PlaybackRouteRegistry.AppOwner.HLS_PROXY);
        cacheClient = cacheCoordinator.registerClient(configuredCacheLimitBytes(), this::cancelPreloadsForCircuit);
        started = true;
        if (cacheCoordinator.isCircuitOpen()) cancelPreloadsForCircuit();
    }

    private void refreshCacheCoordinator() {
        if (cacheClient != null) cacheClient.update(configuredCacheLimitBytes());
    }

    private void cancelPreloadsForCircuit() {
        preloadGate.invalidate();
        releasePreloadWork(false);
    }

    private ForegroundLease beginForegroundRequest() {
        boolean managed = automaticPreloadMode && kernel == PlayerSetting.MPV;
        if (managed && preloadGate.foregroundStarted()) {
            releasePreloadWork(false);
            SpiderDebug.log(TAG,
                    "preload foreground action=cancel generation=invalidated active=%d",
                    preloading.size());
        }
        return new ForegroundLease(managed);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + getListeningPort();
    }

    private Response servePlaylist(IHTTPSession httpSession) throws IOException {
        int id = parseSessionId(httpSession);
        Session session = sessions.get(id);
        if (session == null || TextUtils.isEmpty(session.url)) return error(Status.NOT_FOUND, "expired playlist");
        ForegroundLease foreground = beginForegroundRequest();
        try (foreground;
             okhttp3.Response response = fetch(session, session.url, null, false)) {
            if (!response.isSuccessful()) {
                recordPlaylistResponse(id, response.code(), session.url, null);
                SpiderDebug.log(TAG, "playlist error session=%d code=%d url=%s", id, response.code(), shortUrl(session.url));
                return error(toStatus(response.code()), "playlist http " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) return error(Status.INTERNAL_ERROR, "empty playlist body");
            String text = body.string();
            recordPlaylistResponse(id, response.code(), session.url, text);
            if (!looksLikePlaylist(text)) {
                SpiderDebug.log(TAG, "invalid playlist session=%d code=%d bytes=%d url=%s", id, response.code(), text.length(), shortUrl(session.url));
                return error(Status.BAD_REQUEST, "invalid playlist");
            }
            text = applyAdblock(text, id, session.url, null, true);
            String rewritten = rewritePlaylist(response.request().url().toString(), text, id, null);
            byte[] data = rewritten.getBytes(StandardCharsets.UTF_8);
            SpiderDebug.log(TAG, "playlist session=%d code=%d bytes=%d rewritten=%d url=%s", id, response.code(), text.length(), data.length, shortUrl(session.url));
            return noCache(newFixedLengthResponse(Status.OK, MIME_M3U8, new ByteArrayInputStream(data), data.length));
        }
    }

    private Response serveDash(IHTTPSession httpSession) throws IOException {
        int id = parseSessionId(httpSession);
        Session session = sessions.get(id);
        if (session == null || TextUtils.isEmpty(session.url)) return error(Status.NOT_FOUND, "expired dash");
        Manifest manifest = readManifest(session);
        if (manifest.code < 200 || manifest.code >= 300 || manifest.text.isEmpty()) return error(toStatus(manifest.code), "dash http " + manifest.code);
        String manifestUrl = manifest.baseUrl;
        String text = manifest.text;
        if (!text.toLowerCase(Locale.US).contains("<mpd")) return error(Status.BAD_REQUEST, "invalid dash");
        SessionStats stats = stats(id);
        stats.classification = PlaybackResourceClassifier.classifyDash(
                started && getListeningPort() > 0 ? baseUrl() + "/mpv/index.mpd?s=" + id : null,
                session.url,
                text,
                SystemClock.elapsedRealtime());
        text = rewriteSegmentBase(session, manifestUrl, text);
        Matcher matcher = DASH_BASE_URL.matcher(text);
        StringBuffer rewritten = new StringBuffer();
        int count = 0;
        while (matcher.find()) {
            String target = resolve(manifestUrl, decodeXml(matcher.group(2).trim()));
            String local = proxyDashItemUrl(target, id).replace("&", "&amp;");
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(matcher.group(1) + local + matcher.group(3)));
            count++;
        }
        matcher.appendTail(rewritten);
        byte[] data = rewritten.toString().getBytes(StandardCharsets.UTF_8);
        SpiderDebug.log(TAG, "dash manifest session=%d code=%d bytes=%d baseUrls=%d embedded=%s url=%s", id, manifest.code, data.length, count, manifest.embedded, shortUrl(session.url));
        return noCache(newFixedLengthResponse(Status.OK, "application/dash+xml; charset=utf-8", new ByteArrayInputStream(data), data.length));
    }

    /**
     * 清单来源与解析基准。
     *
     * <p>{@code baseUrl} 为空表示这份清单是内嵌的（data: URI），里面只能出现绝对地址；
     * 看到空基准一律原样返回，不去做相对解析。
     */
    private static final class Manifest {

        final int code;
        final String baseUrl;
        final String text;
        final boolean embedded;

        Manifest(int code, String baseUrl, String text, boolean embedded) {
            this.code = code;
            this.baseUrl = baseUrl;
            this.text = text;
            this.embedded = embedded;
        }
    }

    private Manifest readManifest(Session session) throws IOException {
        String embedded = embeddedManifest(session.url);
        if (embedded != null) return new Manifest(200, "", embedded, true);
        try (okhttp3.Response response = fetch(session, session.url, null, false)) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) return new Manifest(response.code(), "", "", false);
            return new Manifest(response.code(), response.request().url().toString(), body.string(), false);
        }
    }

    /**
     * 解码 data: URI 形式的清单。
     *
     * <p>有些源（典型是哔哩go 那类）把整份 DASH 清单 base64 塞进一个 data: 地址，长度几十 KB。
     * 这种地址没有任何可请求的上游，OkHttp 取不了、native 也塞不进去（超过 1024 字节会被包成
     * ijklongurl: 而丢头/丢 range），只能在这里本地解码成清单文本再走正常代理流程。
     */
    @Nullable
    private static String embeddedManifest(String url) {
        if (url == null || !url.regionMatches(true, 0, "data:", 0, 5)) return null;
        int comma = url.indexOf(',');
        if (comma < 0) return null;
        String meta = url.substring(0, comma).toLowerCase(Locale.US);
        String payload = url.substring(comma + 1);
        try {
            if (meta.contains(";base64")) return new String(Base64.decode(payload, Base64.DEFAULT), StandardCharsets.UTF_8);
            return Uri.decode(payload);
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "embedded manifest decode failed errorType=%s", e.getClass().getSimpleName());
            return null;
        }
    }

    private String rewriteSegmentBase(Session session, String manifestUrl, String text) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(text)));
            pruneDashAlternatives(document);
            NodeList representations = document.getElementsByTagNameNS("*", "Representation");
            int converted = 0;
            for (int i = 0; i < representations.getLength(); i++) {
                Element representation = (Element) representations.item(i);
                Element baseUrl = directChild(representation, "BaseURL");
                Element segmentBase = directChild(representation, "SegmentBase");
                Element initialization = segmentBase == null ? null : directChild(segmentBase, "Initialization");
                if (baseUrl == null || segmentBase == null || initialization == null) continue;
                ByteRange index = ByteRange.parse(segmentBase.getAttribute("indexRange"));
                ByteRange init = ByteRange.parse(initialization.getAttribute("range"));
                if (index == null || init == null) continue;
                String target = resolve(manifestUrl, baseUrl.getTextContent().trim());
                Sidx sidx = fetchSidx(session, target, index);
                if (sidx == null || sidx.ranges.isEmpty()) continue;
                List<SidxRange> groups = sidx.grouped(60_000);
                String namespace = segmentBase.getNamespaceURI();
                Element segmentList = document.createElementNS(namespace, "SegmentList");
                segmentList.setAttribute("timescale", Long.toString(sidx.timescale));
                segmentList.setAttribute("duration", Long.toString(groups.get(0).duration));
                Element initNode = document.createElementNS(namespace, "Initialization");
                initNode.setAttribute("range", init.start + "-" + (init.end + 1));
                segmentList.appendChild(initNode);
                for (SidxRange range : groups) {
                    Element segment = document.createElementNS(namespace, "SegmentURL");
                    segment.setAttribute("mediaRange", range.start + "-" + (range.end + 1));
                    segmentList.appendChild(segment);
                }
                representation.replaceChild(segmentList, segmentBase);
                converted++;
            }
            if (converted == 0) return text;
            StringWriter output = new StringWriter();
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.transform(new DOMSource(document), new StreamResult(output));
            SpiderDebug.log(TAG, "dash SegmentBase grouped representations=%d url=%s", converted, shortUrl(manifestUrl));
            return output.toString();
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "dash SegmentBase grouping skipped errorType=%s", e.getClass().getSimpleName());
            return text;
        }
    }

    private void pruneDashAlternatives(Document document) {
        NodeList sets = document.getElementsByTagNameNS("*", "AdaptationSet");
        List<Node> remove = new ArrayList<>();
        boolean videoKept = false;
        boolean audioKept = false;
        for (int i = 0; i < sets.getLength(); i++) {
            Element set = (Element) sets.item(i);
            Element representation = directChild(set, "Representation");
            Element component = directChild(set, "ContentComponent");
            String type = component == null ? "" : component.getAttribute("contentType");
            if (TextUtils.isEmpty(type) && representation != null) {
                String mime = representation.getAttribute("mimeType");
                if (mime.startsWith("video/")) type = "video";
                else if (mime.startsWith("audio/")) type = "audio";
            }
            if ("video".equals(type)) {
                if (videoKept) remove.add(set);
                else videoKept = true;
            } else if ("audio".equals(type)) {
                if (audioKept) remove.add(set);
                else audioKept = true;
            }
        }
        for (Node node : remove) if (node.getParentNode() != null) node.getParentNode().removeChild(node);
    }

    /** 内嵌 DASH 转本地 HLS 时每个分片的目标时长（毫秒）。 */
    private static final long DASH_HLS_SEGMENT_MS = 15_000;

    /** DASH 转 HLS 的并发线程数。 */
    private static final int DASH_HLS_PLAN_THREADS = 2;

    /** DASH 转 HLS 完成后，播放端等结果的上限（毫秒）。 */
    private static final long DASH_HLS_PLAN_TIMEOUT_MS = 15_000L;

    /** ISO-8601 时长（MPD 的 mediaPresentationDuration，形如 PT1H23M45.6S）。 */
    private static final Pattern DASH_DURATION = Pattern.compile(
            "P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?");

    /**
     * 内嵌 DASH 清单（data: URI）转成本地 HLS 播放。
     *
     * <p>壳里的 IJK 自带 ffmpeg 没编 dash 解复用器，把 MPD 直接交给 native 必报
     * 1003 / ERROR_CODE_UNSPECIFIED；这里把 MPD 的视频轨、音频轨各自翻成 HLS 分片清单
     * （fMP4 分片 + {@code #EXT-X-MAP} 初始化段），让 IJK 走它本来就支持的 hls 解复用器。
     *
     * <p>转换整段丢后台线程跑（取 MPD、取 sidx 索引都是网络请求，在主线程上发必抛
     * NetworkOnMainThreadException），这里立刻返回本地 HLS 地址；播放端真正请求
     * index.m3u8 端点时再等转换结果，转不出来就报 502。
     */
    public synchronized String proxyDashHls(
            String url, Map<String, String> headers, String mediaKey) throws IOException {
        ensureStarted();
        refreshCacheCoordinator();
        int id = ++this.sessionId;
        upstreamEstimator.reset();
        Session session = new Session(
                url, sanitize(headers), System.currentTimeMillis(),
                resolveMediaKey(mediaKey, url));
        diskBufferStore.reset(session.mediaKey());
        sessions.put(id, session);
        SessionStats stats = new SessionStats();
        stats.classification = PlaybackResourceClassifier.classify(
                baseUrl() + "/mpv/dashhls/index.m3u8?s=" + id,
                url,
                MIME_M3U8,
                "hls",
                Map.of(),
                null);
        sessionStats.put(id, stats);
        pruneExpiredSessions(session.createdAtMs);
        pruneCache();
        CompletableFuture<DashHlsPlan> future = new CompletableFuture<>();
        dashHlsPlans.put(id, future);
        dashHlsPlanExecutor().execute(() -> {
            try {
                future.complete(buildDashHlsPlan(id, session));
            } catch (Throwable e) {
                future.completeExceptionally(e);
                SpiderDebug.log(TAG, "dash-hls failed session=%d errorType=%s url=%s",
                        id, e.getClass().getSimpleName(), shortUrl(url));
            }
        });
        String proxyUrl = baseUrl() + "/mpv/dashhls/index.m3u8?s=" + id;
        SpiderDebug.log(TAG, "dash-hls enabled session=%d url=%s", id, shortUrl(url));
        return proxyUrl;
    }

    /**
     * 解析 MPD 并翻出本地 HLS 计划。
     *
     * <p>分片边界来自 {@code SegmentBase@indexRange} 指向的 sidx 索引（哔哩go 那类源都是这种），
     * 按 {@link #DASH_HLS_SEGMENT_MS} 合并成段；索引确实拿不到时才退化成"整文件一段"，
     * 但如果 MPD 声明了索引却取失败，则直接失败（不能让上游忽略 Range 时把整部片子当一段灌下去）。
     */
    private DashHlsPlan buildDashHlsPlan(int id, Session session) throws IOException {
        Manifest manifest = readManifest(session);
        if (manifest.code < 200 || manifest.code >= 300 || TextUtils.isEmpty(manifest.text)) {
            throw new IOException("dash http " + manifest.code);
        }
        String text = manifest.text;
        if (!text.toLowerCase(Locale.US).contains("<mpd")) throw new IOException("invalid dash");
        DashHlsTrack video = null;
        DashHlsTrack audio = null;
        long totalMs = 0;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(text)));
            pruneDashAlternatives(document);
            totalMs = parseDashDurationMs(document.getDocumentElement().getAttribute("mediaPresentationDuration"));
            NodeList sets = document.getElementsByTagNameNS("*", "AdaptationSet");
            for (int i = 0; i < sets.getLength(); i++) {
                Element set = (Element) sets.item(i);
                String type = dashContentType(set);
                if ("video".equals(type) && video == null) video = readDashHlsTrack(session, manifest, set);
                else if ("audio".equals(type) && audio == null) audio = readDashHlsTrack(session, manifest, set);
            }
        } catch (Throwable e) {
            if (e instanceof IOException io) throw io;
            throw new IOException(e);
        }
        if (video == null) throw new IOException("dash no video track");
        String videoPlaylist = buildDashHlsPlaylist(id, video, "v", totalMs);
        String audioPlaylist = audio == null ? null : buildDashHlsPlaylist(id, audio, "a", totalMs);
        String master = audioPlaylist == null ? videoPlaylist : buildDashHlsMaster(id, video, audio);
        SpiderDebug.log(TAG,
                "dash-hls plan session=%d video=%d/%s audio=%s%s url=%s",
                id,
                video.segmentCount(),
                video.codecs,
                audio == null ? "none" : Integer.toString(audio.segmentCount()),
                audio == null ? "" : "/" + audio.codecs,
                shortUrl(session.url));
        return new DashHlsPlan(master, videoPlaylist, audioPlaylist, video, audio);
    }

    @Nullable
    private DashHlsTrack readDashHlsTrack(Session session, Manifest manifest, Element set) {
        Element representation = chooseDashRepresentation(set);
        if (representation == null) return null;
        Element baseUrl = directChild(representation, "BaseURL");
        if (baseUrl == null) baseUrl = directChild(set, "BaseURL");
        if (baseUrl == null) return null;
        String target = resolve(manifest.baseUrl, baseUrl.getTextContent().trim());
        if (TextUtils.isEmpty(target)) return null;
        String mime = representation.getAttribute("mimeType");
        if (TextUtils.isEmpty(mime)) mime = set.getAttribute("mimeType");
        String codecs = representation.getAttribute("codecs");
        if (TextUtils.isEmpty(codecs)) codecs = set.getAttribute("codecs");
        long bandwidth = Math.max(
                parseLongValue(representation.getAttribute("bandwidth"), 0),
                parseLongValue(set.getAttribute("bandwidth"), 0));
        Element segmentBase = directChild(representation, "SegmentBase");
        if (segmentBase == null) segmentBase = directChild(set, "SegmentBase");
        if (segmentBase == null) {
            return new DashHlsTrack(target, mime, codecs, bandwidth, null, null, 0);
        }
        Element initialization = directChild(segmentBase, "Initialization");
        ByteRange init = initialization == null ? null : ByteRange.parse(initialization.getAttribute("range"));
        ByteRange index = ByteRange.parse(segmentBase.getAttribute("indexRange"));
        if (index == null) {
            return new DashHlsTrack(target, mime, codecs, bandwidth, null, null, 0);
        }
        Sidx sidx;
        try {
            sidx = fetchSidx(session, target, index);
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "dash-hls sidx failed errorType=%s url=%s", e.getClass().getSimpleName(), shortUrl(target));
            return null;
        }
        if (sidx == null || sidx.ranges.isEmpty()) {
            SpiderDebug.log(TAG, "dash-hls sidx unusable url=%s", shortUrl(target));
            return null;
        }
        return new DashHlsTrack(target, mime, codecs, bandwidth, init, sidx.grouped(DASH_HLS_SEGMENT_MS), sidx.timescale);
    }

    /**
     * 同一条轨的多个 Representation 里挑一个：先比编码能不能解（h264 &gt; hevc &gt; vp9，av01 垫底），
     * 同档次挑带宽大的。没写 codecs 属性的按中性处理，绝不因为缺属性就整轨放弃。
     */
    @Nullable
    private static Element chooseDashRepresentation(Element set) {
        Element chosen = null;
        int bestScore = -1;
        long bestBandwidth = -1;
        for (Node node = set.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element representation)) continue;
            if (!"Representation".equals(representation.getLocalName())) continue;
            int score = dashCodecScore(representation.getAttribute("codecs"));
            long bandwidth = parseLongValue(representation.getAttribute("bandwidth"), 0);
            if (score > bestScore || (score == bestScore && bandwidth >= bestBandwidth)) {
                bestScore = score;
                bestBandwidth = bandwidth;
                chosen = representation;
            }
        }
        return chosen;
    }

    private static int dashCodecScore(String codecs) {
        String value = codecs == null ? "" : codecs.toLowerCase(Locale.US).trim();
        if (TextUtils.isEmpty(value)) return 1;
        if (value.startsWith("avc") || value.startsWith("h264")) return 5;
        if (value.startsWith("hev") || value.startsWith("hvc")) return 4;
        if (value.startsWith("vp09") || value.startsWith("vp9")) return 3;
        if (value.startsWith("mp4a") || value.startsWith("aac")) return 5;
        if (value.startsWith("opus") || value.startsWith("flac") || value.startsWith("ac-3") || value.startsWith("ec-3")) return 4;
        if (value.startsWith("av01")) return 0;
        return 2;
    }

    /** 判断 AdaptationSet 是视频还是音频：先看 contentType/mimeType，再按 codecs、宽高兜底。 */
    private static String dashContentType(Element set) {
        String type = set.getAttribute("contentType");
        if (!TextUtils.isEmpty(type)) return type.toLowerCase(Locale.US);
        Element component = directChild(set, "ContentComponent");
        if (component != null && !TextUtils.isEmpty(component.getAttribute("contentType"))) {
            return component.getAttribute("contentType").toLowerCase(Locale.US);
        }
        String mime = set.getAttribute("mimeType");
        Element representation = directChild(set, "Representation");
        if (TextUtils.isEmpty(mime) && representation != null) mime = representation.getAttribute("mimeType");
        if (mime.startsWith("video/")) return "video";
        if (mime.startsWith("audio/")) return "audio";
        if (representation == null) return "";
        String codecs = representation.getAttribute("codecs").toLowerCase(Locale.US);
        if (codecs.startsWith("mp4a") || codecs.startsWith("aac") || codecs.startsWith("opus")
                || codecs.startsWith("flac") || codecs.startsWith("ac-3") || codecs.startsWith("ec-3")) {
            return "audio";
        }
        if (codecs.startsWith("avc") || codecs.startsWith("hev") || codecs.startsWith("hvc")
                || codecs.startsWith("vp0") || codecs.startsWith("av01")) {
            return "video";
        }
        if (representation.hasAttribute("height") || representation.hasAttribute("width")) return "video";
        return "";
    }

    private String buildDashHlsPlaylist(int id, DashHlsTrack track, String kind, long totalMs) {
        int count = track.segmentCount();
        StringBuilder builder = new StringBuilder(count * 96 + 320);
        builder.append("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-INDEPENDENT-SEGMENTS\n");
        double longest = 0;
        for (int i = 0; i < count; i++) longest = Math.max(longest, dashSegmentSeconds(track, i, totalMs));
        builder.append("#EXT-X-TARGETDURATION:").append((long) Math.max(1, Math.ceil(longest))).append('\n');
        builder.append("#EXT-X-MEDIA-SEQUENCE:0\n");
        if (!track.wholeFile() && track.init != null) {
            builder.append("#EXT-X-MAP:URI=\"").append(dashHlsUrl(id, "init", kind, -1)).append("\"\n");
        }
        for (int i = 0; i < count; i++) {
            builder.append(String.format(Locale.US, "#EXTINF:%.3f,\n", dashSegmentSeconds(track, i, totalMs)));
            builder.append(dashHlsUrl(id, "seg", kind, i)).append('\n');
        }
        builder.append("#EXT-X-ENDLIST\n");
        return builder.toString();
    }

    private double dashSegmentSeconds(DashHlsTrack track, int index, long totalMs) {
        if (track.wholeFile()) return totalMs > 0 ? totalMs / 1000.0 : 600.0;
        long timescale = track.timescale;
        if (timescale <= 0) return 6.0;
        return track.segments.get(index).duration() / (double) timescale;
    }

    private String buildDashHlsMaster(int id, DashHlsTrack video, DashHlsTrack audio) {
        long bandwidth = video.bandwidth > 0 ? video.bandwidth : 2_000_000L;
        StringBuilder builder = new StringBuilder(512);
        builder.append("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-INDEPENDENT-SEGMENTS\n");
        builder.append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"audio\",DEFAULT=YES,AUTOSELECT=YES,URI=\"")
                .append(dashHlsUrl(id, "media.m3u8", "a", -1)).append("\"\n");
        builder.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(bandwidth);
        if (!TextUtils.isEmpty(video.codecs) && !TextUtils.isEmpty(audio.codecs)) {
            builder.append(",CODECS=\"").append(video.codecs).append(',').append(audio.codecs).append('"');
        }
        builder.append(",AUDIO=\"aud\"\n").append(dashHlsUrl(id, "media.m3u8", "v", -1)).append('\n');
        return builder.toString();
    }

    private String dashHlsUrl(int id, String action, String kind, int index) {
        StringBuilder builder = new StringBuilder(baseUrl()).append("/mpv/dashhls/").append(action)
                .append("?s=").append(id).append("&t=").append(kind);
        if (index >= 0) builder.append("&i=").append(index);
        return builder.toString();
    }

    /** 等后台线程把 DASH 翻成 HLS。跑在请求线程上（NanoHTTPD 工作线程），不碰主线程。 */
    private DashHlsPlan awaitDashHlsPlan(int id) throws IOException {
        CompletableFuture<DashHlsPlan> future = dashHlsPlans.get(id);
        if (future == null) throw new IOException("session missing");
        try {
            return future.get(DASH_HLS_PLAN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IOException("plan timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException(cause == null ? String.valueOf(e) : String.valueOf(cause));
        }
    }

    private Response serveDashHls(IHTTPSession httpSession) throws IOException {
        String path = httpSession.getUri();
        String action = dashHlsAction(path);
        int id = parseSessionId(httpSession);
        Session session = sessions.get(id);
        if (action == null || session == null || dashHlsPlans.get(id) == null) {
            if (action == null) return error(Status.NOT_FOUND, "not found");
            dashHlsPlans.remove(id);
            return error(Status.NOT_FOUND, "expired dash-hls");
        }
        DashHlsPlan plan;
        try {
            plan = awaitDashHlsPlan(id);
        } catch (IOException e) {
            dashHlsPlans.remove(id);
            SpiderDebug.log(TAG, "dash-hls serve failed session=%d errorType=%s reason=%s",
                    id, e.getClass().getSimpleName(), e.getMessage());
            return error(Status.INTERNAL_ERROR, "dash-hls unavailable");
        }
        if ("index".equals(action)) {
            recordPlaylistResponse(id, 200, "dash-hls:index", plan.master);
            return noCache(textResponse(plan.master));
        }
        String kind = dashHlsKind(httpSession.getParms().get("t"));
        DashHlsTrack track = dashHlsTrack(plan, kind);
        if (track == null) return error(Status.NOT_FOUND, "missing dash track");
        if ("media".equals(action)) {
            String text = "a".equals(kind) ? plan.audioPlaylist : plan.videoPlaylist;
            if (text == null) return error(Status.NOT_FOUND, "missing dash playlist");
            recordPlaylistResponse(id, 200, "dash-hls:" + kind, text);
            return noCache(textResponse(text));
        }
        if ("init".equals(action)) {
            if (track.init == null) return error(Status.NOT_FOUND, "missing dash init");
            return serveDashHlsRange(id, session, track, track.init.start(), track.init.end(), true, true);
        }
        if (track.wholeFile()) return serveDashHlsRange(id, session, track, 0, 0, false, false);
        int index = parseIntValue(httpSession.getParms().get("i"), -1);
        if (index < 0 || index >= track.segments.size()) return error(Status.NOT_FOUND, "missing dash segment");
        SidxRange segment = track.segments.get(index);
        return serveDashHlsRange(id, session, track, segment.start(), segment.end(), true, false);
    }

    /** dash-hls 端点分派：index/media/init/seg，未知返回 null。 */
    @Nullable
    private static String dashHlsAction(@Nullable String path) {
        if (path == null) return null;
        if (path.endsWith("index.m3u8")) return "index";
        if (path.endsWith("media.m3u8")) return "media";
        if (path.endsWith("init")) return "init";
        if (path.endsWith("seg")) return "seg";
        return null;
    }

    /**
     * 按字节区间取上游分片，并且**总是**把它还原成一个完整的 200 小资源给播放器。
     *
     * <p>清单里不带 {@code EXT-X-BYTERANGE}，分片的 range 全在本方法内部消化，播放器不需要
     * 处理 range/206/seek 语义（IJK 的 hls 路径对这几件事最不稳）。上游若无视 Range 直接
     * 返回整文件，这里自己跳过前缀再截断，绝不把整部片子灌给播放器。
     */
    private Response serveDashHlsRange(
            int id, Session session, DashHlsTrack track,
            long start, long end, boolean ranged, boolean init) throws IOException {
        String rangeHeader = ranged ? "bytes=" + start + "-" + end : null;
        okhttp3.Response response = fetch(session, track.url, rangeHeader, true);
        ResponseBody body = response.body();
        if (body == null) {
            recordItemResponse(id, response.code(), track.url);
            response.close();
            return error(Status.INTERNAL_ERROR, "empty dash segment");
        }
        if (!response.isSuccessful()) {
            int code = response.code();
            response.close();
            recordItemResponse(id, code, track.url);
            SpiderDebug.log(TAG, "dash-hls segment error session=%d init=%s code=%d range=%s url=%s",
                    id, init, code, rangeHeader, shortUrl(track.url));
            return error(toStatus(code), "dash segment http " + code);
        }
        long length = body.contentLength();
        InputStream source = body.byteStream();
        if (ranged && dashHlsTrimNeeded(response.code(), response.header("Content-Range"))) {
            skipFully(source, start);
            length = end - start + 1;
            source = new LimitedInputStream(source, length);
        }
        InputStream stream = new CloseResponseInputStream(source, response);
        String mime = TextUtils.isEmpty(track.mime) ? MIME_BINARY : track.mime;
        Response result = length < 0
                ? newChunkedResponse(Status.OK, mime, stream)
                : newFixedLengthResponse(Status.OK, mime, stream, length);
        result.addHeader("Access-Control-Allow-Origin", "*");
        result.addHeader("Cache-Control", "no-cache");
        result.addHeader("Connection", "close");
        recordItemResponse(id, response.code(), track.url);
        SpiderDebug.log(TAG, "dash-hls segment session=%d init=%s code=%d range=%s length=%d url=%s",
                id, init, response.code(), rangeHeader, length, shortUrl(track.url));
        return result;
    }

    /** 上游收到 Range 却不当回事（返回 200 且没有 Content-Range）时，得自己跳过前缀再截断。 */
    private static boolean dashHlsTrimNeeded(int code, @Nullable String contentRange) {
        return code != 206 && TextUtils.isEmpty(contentRange);
    }

    private Response textResponse(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        return newFixedLengthResponse(Status.OK, MIME_M3U8, new ByteArrayInputStream(data), data.length);
    }

    private static String dashHlsKind(@Nullable String value) {
        return "a".equals(value) ? "a" : "v";
    }

    @Nullable
    private static DashHlsTrack dashHlsTrack(DashHlsPlan plan, String kind) {
        return "a".equals(kind) ? plan.audio : plan.video;
    }

    private static long parseDashDurationMs(String value) {
        if (TextUtils.isEmpty(value)) return 0;
        try {
            Matcher matcher = DASH_DURATION.matcher(value.trim());
            if (!matcher.find()) return 0;
            long ms = parseLongValue(matcher.group(1), 0) * 86_400_000L;
            ms += parseLongValue(matcher.group(2), 0) * 3_600_000L;
            ms += parseLongValue(matcher.group(3), 0) * 60_000L;
            String seconds = matcher.group(4);
            if (!TextUtils.isEmpty(seconds)) ms += (long) (Double.parseDouble(seconds) * 1000);
            return ms;
        } catch (Throwable e) {
            return 0;
        }
    }

    private static long parseLongValue(@Nullable String value, long fallback) {
        if (TextUtils.isEmpty(value)) return fallback;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseIntValue(@Nullable String value, int fallback) {
        if (TextUtils.isEmpty(value)) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 一条轨（视频或音频）转 HLS 所需的全部信息。 */
    private static final class DashHlsTrack {

        private final String url;
        private final String mime;
        private final String codecs;
        private final long bandwidth;
        @Nullable
        private final ByteRange init;
        @Nullable
        private final List<SidxRange> segments;
        private final long timescale;

        private DashHlsTrack(
                String url, String mime, String codecs, long bandwidth,
                @Nullable ByteRange init, @Nullable List<SidxRange> segments, long timescale) {
            this.url = url;
            this.mime = mime;
            this.codecs = codecs;
            this.bandwidth = bandwidth;
            this.init = init;
            this.segments = segments;
            this.timescale = timescale;
        }

        private boolean wholeFile() {
            return segments == null;
        }

        private int segmentCount() {
            return segments == null ? 1 : segments.size();
        }
    }

    private static final class DashHlsPlan {

        private final String master;
        private final String videoPlaylist;
        @Nullable
        private final String audioPlaylist;
        private final DashHlsTrack video;
        @Nullable
        private final DashHlsTrack audio;

        private DashHlsPlan(
                String master, String videoPlaylist, @Nullable String audioPlaylist,
                DashHlsTrack video, @Nullable DashHlsTrack audio) {
            this.master = master;
            this.videoPlaylist = videoPlaylist;
            this.audioPlaylist = audioPlaylist;
            this.video = video;
            this.audio = audio;
        }
    }

    @Nullable
    private Sidx fetchSidx(Session session, String url, ByteRange range) throws IOException {
        try (okhttp3.Response response = fetch(session, url, "bytes=" + range.start + "-" + range.end, true)) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) return null;
            byte[] data = body.bytes();
            long absoluteStart = response.code() == 206 ? range.start : 0;
            String contentRange = response.header("Content-Range");
            if (contentRange != null) {
                Matcher matcher = CONTENT_RANGE.matcher(contentRange);
                if (matcher.find()) absoluteStart = Long.parseLong(matcher.group(1));
            }
            return Sidx.parse(data, absoluteStart);
        }
    }

    @Nullable
    private static Element directChild(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getLocalName())) return element;
        }
        return null;
    }

    private Response serveItem(IHTTPSession httpSession, @Nullable String forcedId) throws IOException {
        String id = forcedId == null ? httpSession.getParms().get("id") : forcedId;
        if (id != null && id.endsWith("/")) id = id.substring(0, id.length() - 1);
        Target target = id == null ? null : targets.get(id);
        Session owner = target == null ? null : sessions.get(target.sessionId);
        if (target == null || owner == null) return error(Status.NOT_FOUND, "expired item");
        ForegroundLease foreground = beginForegroundRequest();
        boolean foregroundHandedOff = false;
        try {
            String range = requestHeader(httpSession, "range");
            boolean targetPlaylist = MpvHlsSegmentContentPolicy.isPlaylist(
                    target.role(), target.url, null);
            if (targetPlaylist) recordSelectedVariant(target);
            if (kernel == PlayerSetting.IJK
                    && !targetPlaylist
                    && target.role() == HlsPlaylistRewriter.UriRole.MEDIA_SEGMENT) {
                stats(target.sessionId()).observeLiveMediaRequest(
                        target.url(), SystemClock.elapsedRealtime());
            }
            HlsAesSession aes = aesSessions.get(target.sessionId);
            boolean aesSegment = aes != null
                    && !aes.abandoned()
                    && !targetPlaylist
                    && target.role() == HlsPlaylistRewriter.UriRole.MEDIA_SEGMENT;
            String forwardedRange = targetPlaylist || aesSegment ? null : range;
            if (!targetPlaylist && target.cacheable && !aesSegment) {
                Response cached = serveCached(owner, target.url, range, foreground);
                if (cached != null) {
                    recordCachedTarget(owner, target);
                    foregroundHandedOff = true;
                    SpiderDebug.log(TAG, "cache hit id=%s range=%s", id, range);
                    return cached;
                }
            }
            okhttp3.Response response = fetch(owner, target.url, forwardedRange, !targetPlaylist);
            ResponseBody body = response.body();
            if (body == null) {
                recordItemResponse(target.sessionId, response.code(), target.url);
                response.close();
                return error(Status.INTERNAL_ERROR, "empty item body");
            }
            String finalUrl = response.request().url().toString();
            MediaType type = body.contentType();
            if (targetPlaylist || MpvHlsSegmentContentPolicy.isPlaylist(
                    target.role(), finalUrl, type == null ? null : type.toString())) {
                recordSelectedVariant(target);
                try (response; body) {
                    if (!response.isSuccessful()) {
                        recordPlaylistResponse(target.sessionId, response.code(), target.url, null);
                        SpiderDebug.log(TAG, "nested playlist error id=%s code=%d url=%s", id, response.code(), shortUrl(target.url));
                        return error(toStatus(response.code()), "nested playlist http " + response.code());
                    }
                    String text = body.string();
                    recordPlaylistResponse(target.sessionId, response.code(), target.url, text);
                    if (!looksLikePlaylist(text)) {
                        SpiderDebug.log(TAG, "invalid nested playlist id=%s code=%d bytes=%d url=%s", id, response.code(), text.length(), shortUrl(target.url));
                        return error(Status.BAD_REQUEST, "invalid playlist");
                    }
                    text = applyAdblock(text, target.sessionId, target.url, target.variant(),
                            target.role() == HlsPlaylistRewriter.UriRole.VARIANT_PLAYLIST
                                    && target.variant() != null
                                    && target.variant().kind() == HlsPlaylistRewriter.VariantKind.STREAM);
                    String rewritten = rewritePlaylist(finalUrl, text, target.sessionId, target.variant());
                    byte[] data = rewritten.getBytes(StandardCharsets.UTF_8);
                    SpiderDebug.log(TAG, "nested playlist id=%s code=%d bytes=%d url=%s", id, response.code(), data.length, shortUrl(target.url));
                    return noCache(newFixedLengthResponse(Status.OK, MIME_M3U8, new ByteArrayInputStream(data), data.length));
                }
            }

            boolean mayStripPngPrefix = MpvHlsSegmentContentPolicy.shouldProbePngPrefix(
                    type == null ? null : type.toString(),
                    target.role() == HlsPlaylistRewriter.UriRole.MEDIA_SEGMENT);
            if (aesSegment) {
                Response decrypted = serveAesSegment(owner, target, aes, response, body, range);
                if (decrypted != null) {
                    recordItemResponse(target.sessionId, response.code(), target.url);
                    response.close();
                    foregroundHandedOff = true;
                    return decrypted;
                }
            }
            recordItemResponse(target.sessionId, response.code(), target.url);
            long contentLength = body.contentLength();
            String mime = mayStripPngPrefix ? MIME_TS : mediaMimeFor(target.url, finalUrl, type);
            InputStream source = body.byteStream();
            if (automaticPreloadMode && kernel == PlayerSetting.MPV
                    && response.isSuccessful() && !mayStripPngPrefix
                    && target.cacheable && stats(target.sessionId).vod) {
                source = new UpstreamTelemetryInputStream(
                        source, contentLength, upstreamEstimator);
            }
            if (mayStripPngPrefix) {
                source = new PngPrefixStrippingInputStream(source, target.url);
            } else {
                source = maybeCacheStreaming(owner, target, source, response.code(), forwardedRange, response.header("Content-Range"), contentLength, mime);
            }
            InputStream stream = new ForegroundInputStream(
                    new CloseResponseInputStream(source, response), foreground);
            foregroundHandedOff = true;
            try {
                Response.IStatus streamingStatus = streamingStatus(response, forwardedRange);
                Response result = mayStripPngPrefix || contentLength < 0
                        ? newChunkedResponse(streamingStatus, mime, stream)
                        : newFixedLengthResponse(streamingStatus, mime, stream, contentLength);
                addStreamingHeaders(result, response, forwardedRange);
                SpiderDebug.log(TAG, "item id=%s code=%d range=%s contentRange=%s length=%d mime=%s url=%s",
                        id, response.code(), forwardedRange, response.header("Content-Range"), contentLength, mime, shortUrl(target.url));
                return result;
            } catch (Throwable error) {
                try {
                    stream.close();
                } catch (Throwable ignored) {
                }
                if (error instanceof IOException io) throw io;
                throw new IOException(error);
            }
        } finally {
            if (!foregroundHandedOff) foreground.close();
        }
    }

    /**
     * AES-128 分片：整段取回 → 服务端解密 → 按播放器要的 Range 从明文里切出去。
     *
     * <p>密钥在这一步懒取（清单阶段不取）；返回 null 表示这次不接手（密钥未到手、
     * 没有对应序号、上游失败、体积超限、解不出明文等），调用方照旧走直通逻辑。
     */
    @Nullable
    private Response serveAesSegment(
            Session owner,
            Target target,
            HlsAesSession aes,
            okhttp3.Response response,
            ResponseBody body,
            @Nullable String range) {
        byte[] iv = aes.ivFor(target.url());
        if (iv == null) {
            SpiderDebug.log(TAG, "aes passthrough reason=no-index url=%s", shortUrl(target.url()));
            return null;
        }
        byte[] key = ensureAesKey(owner, aes);
        if (key == null) {
            // 密钥还没到手：这一片原样直通，不阻塞、不报错，等下一片（或冷却结束后）再试。
            SpiderDebug.log(TAG, "aes passthrough reason=key-pending url=%s", shortUrl(target.url()));
            return null;
        }
        if (!response.isSuccessful()) return null;
        long declared = body.contentLength();
        if (declared > HlsAesSession.MAX_SEGMENT_BYTES) {
            SpiderDebug.log(TAG, "aes passthrough reason=oversize length=%d url=%s", declared, shortUrl(target.url()));
            return null;
        }
        byte[] raw;
        try {
            raw = readAll(body.byteStream(), HlsAesSession.MAX_SEGMENT_BYTES);
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "aes read failed errorType=%s url=%s", e.getClass().getSimpleName(), shortUrl(target.url()));
            return null;
        }
        if (raw == null) {
            SpiderDebug.log(TAG, "aes passthrough reason=read-failed url=%s", shortUrl(target.url()));
            return null;
        }
        byte[] plain = null;
        byte[] winnerIv = null;
        for (byte[] candidateIv : aes.candidateIvsFor(iv, target.url())) {
            byte[] attempt = HlsAesEncryption.decrypt(raw, key, candidateIv);
            if (attempt != null && HlsAesEncryption.looksLikePlainSegment(attempt)) {
                plain = attempt;
                winnerIv = candidateIv;
                break;
            }
        }
        if (plain == null) {
            // 候选 IV 全解不出可识别明文：按改动前的行为原样直通（上层照旧处理原始字节），
            // 而不是丢一个错误块——「清单声明 AES 实则没加密」的源靠这一步保住可播。
            // 连续多片解不出的会话会被判定不值得接管，后续分片连读都不再读。
            aes.noteSegmentFailure();
            SpiderDebug.log(TAG, "aes passthrough reason=undecryptable bytes=%d url=%s",
                    raw == null ? -1 : raw.length, shortUrl(target.url()));
            return null;
        }
        // 记住这次命中哪种 IV 取法，后续分片直接命中，不再逐片试探。
        aes.noteSegmentSuccess(winnerIv, target.url());
        boolean recoveredIv = !Arrays.equals(winnerIv, iv);
        if (recoveredIv) {
            SpiderDebug.log(TAG, "aes iv recovered session=%d url=%s", target.sessionId, shortUrl(target.url()));
        }
        Range parsed = parseRange(range, plain.length);
        if (range != null && parsed == null) {
            Response invalid = error(Status.RANGE_NOT_SATISFIABLE, "invalid range");
            invalid.addHeader("Content-Range", "bytes */" + plain.length);
            return invalid;
        }
        long start = parsed == null ? 0 : parsed.start;
        long end = parsed == null ? plain.length - 1 : parsed.end;
        byte[] slice = start == 0 && end == plain.length - 1
                ? plain : Arrays.copyOfRange(plain, (int) start, (int) end + 1);
        Response result = newFixedLengthResponse(
                parsed == null ? Status.OK : Status.PARTIAL_CONTENT, MIME_TS,
                new ByteArrayInputStream(slice), slice.length);
        result.addHeader("Access-Control-Allow-Origin", "*");
        result.addHeader("Cache-Control", "no-cache");
        result.addHeader("Connection", "close");
        result.addHeader("Accept-Ranges", "bytes");
        if (parsed != null) {
            result.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + plain.length);
        }
        SpiderDebug.log(TAG, "aes segment range=%s bytes=%d->%d iv=%s url=%s",
                range, raw.length, slice.length, recoveredIv ? "recovered" : "primary", shortUrl(target.url()));
        return result;
    }

    private okhttp3.Response fetch(Session session, String url, @Nullable String range, boolean identityEncoding) throws IOException {
        Request.Builder builder = new Request.Builder().url(url);
        for (Map.Entry<String, String> entry : session.headers.entrySet()) {
            if (TextUtils.isEmpty(entry.getKey()) || TextUtils.isEmpty(entry.getValue())) continue;
            builder.header(entry.getKey(), entry.getValue());
        }
        if (identityEncoding) builder.header("Accept-Encoding", "identity");
        if (!TextUtils.isEmpty(range)) builder.header("Range", range);
        return client.newCall(builder.build()).execute();
    }

    private String applyAdblock(String text, int session, String url,
            @Nullable HlsPlaylistRewriter.Variant variant, boolean videoPlaylist) {
        if (!Setting.isAdblock() || !isVodPlaylist(text)) return text;
        if (HlsAdblockPipeline.isCoreM3u8Proxy(url)) return text;
        if (kernel == PlayerSetting.MPV && !videoPlaylist) return text;
        try {
            List<HlsManifestCleaner.Rule> rules = HlsRuleConfig.getRules();
            boolean legacyFallback = HlsRuleConfig.isLegacyFallbackEnabled();
            HlsAdblockPipeline.Outcome outcome = HlsAdblockPipeline.apply(url, text, rules, legacyFallback);
            if (kernel == PlayerSetting.MPV) {
                HlsAdTimeline timeline = HlsAdTimeline.from(text, outcome.manifest());
                SessionStats stats = sessionStats.get(session);
                if (stats != null) {
                    if (variant == null) stats.directAdTimeline = timeline;
                    else stats.adTimelines.merge(variant, timeline,
                            (previous, next) -> previous.sameCuts(next) ? previous : HlsAdTimeline.NONE);
                }
                if (!TextUtils.equals(outcome.manifest(), text)) {
                    SpiderDebug.log(TAG,
                            "adblock planned session=%d ranges=%d cuts=%s mode=source-timeline-seek reason=%s url=%s",
                            session, timeline.ranges().size(), timeline.ranges(), timeline.reason(), shortUrl(url));
                }
                // Keep timestamps, implicit AES IVs, byte ranges and rendition
                // synchronization intact; MpvPlayer skips the detected time ranges.
                // Notify as soon as a valid removal plan is available so MPV has the
                // same user-visible adblock confirmation as Exo. Runtime skip
                // accounting remains separate and is recorded only after playback.
                if (!TextUtils.equals(outcome.manifest(), text)) {
                    recordAndNotifyAdblock(url, outcome);
                }
                return text;
            }
            if (!TextUtils.equals(outcome.manifest(), text)) {
                SpiderDebug.log(TAG, "adblock filtered session=%d bytes=%d->%d structured=%s legacy=%s url=%s",
                        session, text.length(), outcome.manifest().length(), outcome.structured(), outcome.legacy(), shortUrl(url));
            }
            recordAndNotifyAdblock(url, outcome);
            return outcome.manifest();
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "adblock ignored session=%d errorType=%s", session, e.getClass().getSimpleName());
            return text;
        }
    }

    private void recordAndNotifyAdblock(String url, HlsAdblockPipeline.Outcome outcome) {
        if (!outcome.structured() && !outcome.legacy()) return;
        okhttp3.HttpUrl parsed = okhttp3.HttpUrl.parse(url);
        if (parsed == null) return;
        long fallbackCount = outcome.legacy() ? Math.max(1, outcome.removedSegments()) : 0;
        String pipeline = kernel == PlayerSetting.IJK ? "IJK" : "MPV";
        AdBlockStatsStore.recordBlocks(parsed.host(), pipeline, outcome.ruleCounts(), fallbackCount,
                parsed.host(), outcome.removedDurationSec(), outcome.removedSegmentDetails());
        if (!HlsAdblockNotice.shouldNotify(url, System.currentTimeMillis())) return;
        int removed = outcome.removedSegments() > 0 ? outcome.removedSegments() : (int) fallbackCount;
        String message = outcome.structured() && outcome.removedDurationSec() > 0
                ? String.format(Locale.US, "已跳过 %d 个广告片段（%.1f 秒）", removed, outcome.removedDurationSec())
                : "已跳过 " + removed + " 个广告片段";
        App.post(() -> Notify.show(message));
    }

    private String rewritePlaylist(String playlistUrl, String text, int session, @Nullable HlsPlaylistRewriter.Variant inheritedVariant) {
        HlsPlaylistRewriter.Result result = HlsPlaylistRewriter.rewrite(text, inheritedVariant, (uri, cacheable, context) -> {
            String targetUrl = resolve(playlistUrl, uri);
            HlsPlaylistRewriter.Variant targetVariant = context.variant();
            String rewrittenUrl = proxyItemUrl(
                    targetUrl, session, cacheable, targetVariant,
                    context.role(), context.startSeconds(),
                    context.durationSeconds());
            return new HlsPlaylistRewriter.MappedUri(targetUrl, rewrittenUrl);
        });
        HlsPlaylistRewriter.Result details = result;
        String output = result.text();
        HlsAesSession aes = installAes(playlistUrl, text, session, result);
        if (aes != null) {
            // 服务端已解密：清单里不再暴露 #EXT-X-KEY，分片按明文 TS 下发；
            // 分片列表一并清空，避免预加载/缓存去碰加密字节。
            output = HlsAesEncryption.stripKeyTags(output);
            details = new HlsPlaylistRewriter.Result(output, List.of(), result.variants(), List.of());
            SpiderDebug.log(TAG, "aes enabled session=%d keyUri=%s iv=%s segments=%d url=%s",
                    session, shortUrl(aes.keyUrl),
                    aes.declaredIvVerified ? "declared" : "sequence+" + aes.sequenceOffset,
                    result.segments().size(), shortUrl(playlistUrl));
        }
        recordPlaylistDetails(session, playlistUrl, text, details, inheritedVariant);
        if (!result.variants().isEmpty()) {
            SpiderDebug.log(TAG, "master playlist preserved variants session=%d variants=%d", session, result.variants().size());
        }
        return output;
    }

    /**
     * 给 IJK 内核的 AES-128 清单装上服务端解密。
     *
     * <p>清单阶段只做本地解析（登记「待取密钥」会话），调用方随后把 {@code #EXT-X-KEY}
     * 行剥掉；密钥在下第一片时懒取（带重试与冷却），分片逐片自愈。清单响应路径上
     * <b>零上游请求</b>——此前在这里同步取密钥并整片探测，源站分片大或慢时会把清单
     * 响应拖到播放器超时（IJK 报超时、MPV 失败），还会提前消费掉签名分片。
     */
    @Nullable
    private HlsAesSession installAes(
            String playlistUrl,
            String text,
            int sessionId,
            HlsPlaylistRewriter.Result result) {
        if (kernel != PlayerSetting.IJK) return null;
        HlsAesEncryption.KeyInfo info = HlsAesEncryption.parse(text);
        if (info == null || !info.usable()) return null;
        List<HlsPlaylistRewriter.Segment> segments = result.segments();
        if (segments.isEmpty()) return null;
        for (HlsPlaylistRewriter.Segment segment : segments) {
            // 分片共享同一文件靠 BYTERANGE 切片时，URL 与序号的对应关系会歧义，不接手。
            if (segment.byteRange()) return null;
        }
        Session owner = sessions.get(sessionId);
        if (owner == null) return null;
        String hostFormKeyUrl = resolveKeyUrl(playlistUrl, info.keyUri);
        String pathFormKeyUrl = resolve(playlistUrl, info.keyUri);
        HlsAesSession existing = aesSessions.get(sessionId);
        if (existing != null
                && (existing.matchesKey(hostFormKeyUrl) || existing.matchesKey(pathFormKeyUrl))) {
            existing.updateSequence(info.mediaSequence);
            existing.recordSegments(segments);
            return existing;
        }
        // 清单返回路径上不做任何上游请求：这里只登记「待取密钥」会话，取密钥与 IV 校验
        // 全部推迟到真正取分片时懒执行。此前在这里同步取密钥（最多 2 次）再整片探测
        // （最多再 2 次、含 300ms 退避），源站分片大或慢时会把清单响应拖到播放器超时，
        // 且首片探测会提前消费签名分片，造成「首播有进度无画面、重播正常」。
        //
        // 两种密钥地址写法都记着（补全协议的主机形式优先，回退常规相对路径解析）：
        // URI="play.hhuus.com/play/xx/enc.key" 当相对路径拼会被上游 403。
        HlsAesSession candidate = new HlsAesSession(
                hostFormKeyUrl, pathFormKeyUrl, info.declaredIv, info.mediaSequence);
        candidate.recordSegments(segments);
        aesSessions.put(sessionId, candidate);
        SpiderDebug.log(TAG, "aes pending session=%d keyUri=%s iv=%s segments=%d url=%s",
                sessionId, shortUrl(hostFormKeyUrl),
                info.declaredIv == null ? "sequence" : "declared",
                segments.size(), shortUrl(playlistUrl));
        return candidate;
    }

    /**
     * 懒取 AES 密钥（只在真正取分片时调用）：取到即缓存 16 字节，取不到进 5s 冷却，
     * 连续失败达到上限就整会话放弃接管。返回 null 时调用方原样直通。
     */
    @Nullable
    private byte[] ensureAesKey(Session session, HlsAesSession aes) {
        synchronized (aes) {
            if (aes.keyAvailable()) return aes.key();
            long now = SystemClock.elapsedRealtime();
            if (aes.inKeyCooldown(now)) return null;
            byte[] key = fetchAesKey(session, aes.keyUrl);
            if (key == null && aes.hasFallbackKeyUrl()) {
                key = fetchAesKey(session, aes.fallbackKeyUrl);
            }
            if (key == null) {
                aes.noteKeyFailure(now);
                SpiderDebug.log(TAG, "aes key unavailable keyUri=%s cooldownMs=%d",
                        shortUrl(aes.keyUrl), HlsAesSession.KEY_RETRY_COOLDOWN_MS);
                return null;
            }
            aes.installKey(key);
            SpiderDebug.log(TAG, "aes key ready keyUri=%s", shortUrl(aes.keyUrl));
            return key;
        }
    }

    @Nullable
    private byte[] fetchAesKey(Session session, String keyUrl) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            byte[] key = fetchAesKeyOnce(session, keyUrl);
            if (key != null) return key;
            if (attempt == 1) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return null;
    }

    @Nullable
    private byte[] fetchAesKeyOnce(Session session, String keyUrl) {
        try (okhttp3.Response response = fetch(session, keyUrl, null, true)) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) return null;
            byte[] key = body.bytes();
            return key.length == 16 ? key : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private String proxyItemUrl(
            String targetUrl,
            int session,
            boolean cacheable,
            @Nullable HlsPlaylistRewriter.Variant variant,
            HlsPlaylistRewriter.UriRole role,
            double startSeconds,
            double durationSeconds) {
        String id = MpvHlsTargetIdentity.stableId(
                session, targetUrl, cacheable, variant, role);
        targets.put(id, new Target(
                session, targetUrl, System.currentTimeMillis(), cacheable,
                variant, role, startSeconds, durationSeconds));
        return baseUrl() + "/mpv/item?s=" + session + "&id=" + id;
    }

    private String proxyDashItemUrl(String targetUrl, int session) {
        String id = Long.toString(nextId.incrementAndGet());
        targets.put(id, new Target(
                session, targetUrl, System.currentTimeMillis(), true, null,
                HlsPlaylistRewriter.UriRole.OTHER, 0, 0));
        return baseUrl() + "/mpv/dash-item/" + id + "/media.m4s";
    }

    @Nullable
    private String dashItemId(String path) {
        String prefix = "/mpv/dash-item/";
        if (path == null || !path.startsWith(prefix)) return null;
        String value = path.substring(prefix.length());
        int slash = value.indexOf('/');
        return slash < 0 ? value : value.substring(0, slash);
    }

    private String decodeXml(String value) {
        return value.replace("&amp;", "&").replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">");
    }

    @Nullable
    private Response serveCached(
            Session session,
            String url,
            @Nullable String rangeHeader,
            ForegroundLease foreground) throws IOException {
        File file = cacheFile(session, url);
        if (!file.isFile() || file.length() < MIN_CACHE_FILE_BYTES) return null;
        long length = file.length();
        Range range = parseRange(rangeHeader, length);
        if (rangeHeader != null && range == null) {
            foreground.close();
            Response response = error(Status.RANGE_NOT_SATISFIABLE, "invalid range");
            response.addHeader("Content-Range", "bytes */" + length);
            return response;
        }
        long start = range == null ? 0 : range.start;
        long end = range == null ? length - 1 : range.end;
        MpvHlsCacheCoordinator.ReadLease input = cacheCoordinator.openRead(file);
        if (input == null) return null;
        try {
            skipFully(input, start);
            InputStream stream = new ForegroundInputStream(
                    new LimitedInputStream(input, end - start + 1), foreground);
            Response response = newFixedLengthResponse(range == null ? Status.OK : Status.PARTIAL_CONTENT, cacheMime(url, file), stream, end - start + 1);
            response.addHeader("Access-Control-Allow-Origin", "*");
            response.addHeader("Cache-Control", "no-cache");
            response.addHeader("Connection", "close");
            response.addHeader("Accept-Ranges", "bytes");
            if (range != null) response.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + length);
            return response;
        } catch (Throwable error) {
            try {
                input.close();
            } catch (Throwable ignored) {
            }
            foreground.close();
            if (error instanceof IOException io) throw io;
            throw new IOException(error);
        }
    }

    private InputStream maybeCacheStreaming(Session session, Target target, InputStream source, int status, @Nullable String range, @Nullable String contentRange, long contentLength, String mime) {
        refreshCacheCoordinator();
        if (!shouldWriteThroughCache(target, status, range, contentRange, contentLength)) return source;
        File file = cacheFile(session, target.url);
        if (file.isFile() && file.length() >= MIN_CACHE_FILE_BYTES) {
            recordCachedTarget(session, target);
            return source;
        }
        String key = file.getName();
        MpvHlsCacheCoordinator.ReservationDecision decision = cacheCoordinator.tryReserve(
                key, file, contentLength, configuredCacheLimitBytes(), MpvHlsCacheCoordinator.WriterType.FOREGROUND);
        if (!decision.granted()) return source;
        MpvHlsCacheCoordinator.WriteReservation reservation = decision.reservation();
        try {
            return new CacheWritingInputStream(
                    source, new FileOutputStream(reservation.tempFile()),
                    reservation, mime, session, target);
        } catch (Throwable e) {
            reservation.fail(e);
            logCacheFailure("foreground-open", e);
            return source;
        }
    }

    private boolean shouldWriteThroughCache(Target target, int status, @Nullable String range, @Nullable String contentRange, long contentLength) {
        if (target == null || !target.cacheable) return false;
        if (!isCacheEnabled() || !stats(target.sessionId).vod) return false;
        if (!isHttpUrl(target.url)) return false;
        if (contentLength < MIN_CACHE_FILE_BYTES) return false;
        if (contentLength > configuredCacheLimitBytes()) return false;
        if (status == 200) return true;
        return status == 206 && isCompleteRangeResponse(range, contentRange, contentLength);
    }

    private synchronized void preloadSegments(Session session, List<HlsPlaylistRewriter.Segment> segments, double startSeconds) {
        refreshCacheCoordinator();
        long preloadGeneration = preloadGate.acquire();
        if (preloadGeneration < 0
                || automaticPreloadMode && activePreloadTransfers.get() > 0
                || !PreloadSetting.isPreload(kernel)
                || !isPausePreloadAllowed()
                || configuredPreloadLimitBytes() <= 0
                || !cacheCoordinator.canStartPreload(configuredPreloadLimitBytes())
                || segments.isEmpty()) return;
        ExecutorService executor = getPreloadExecutor();
        int remainingSubmissions = resolvePreloadSubmissionBudget(preloading.size());
        if (remainingSubmissions <= 0) return;
        double submittedSeconds = 0;
        double batchSeconds = Math.max(0, PreloadSetting.getPreloadTimeSeconds(kernel));
        double aheadSeconds = preloadAheadWindowSeconds();
        double windowEndSeconds = Double.isInfinite(aheadSeconds)
                ? Double.POSITIVE_INFINITY : startSeconds + aheadSeconds;
        for (HlsPlaylistRewriter.Segment segment : segments) {
            if (!preloadGate.allows(preloadGeneration)) break;
            if (segment.endSeconds() <= startSeconds) continue;
            if (segment.startSeconds() <= startSeconds && segment.endSeconds() > startSeconds) continue;
            if (segment.byteRange()) continue;
            if (segment.startSeconds() >= windowEndSeconds) break;
            if (submittedSeconds >= batchSeconds) break;
            if (!preloadSegment(session, segment, preloadGeneration, executor)) continue;
            submittedSeconds += Math.max(0, segment.durationSeconds());
            if (--remainingSubmissions <= 0) break;
        }
    }

    private boolean preloadSegment(
            Session session,
            HlsPlaylistRewriter.Segment segment,
            long preloadGeneration,
            ExecutorService executor) {
        String url = segment.uri();
        if (!preloadGate.allows(preloadGeneration) || !isHttpUrl(url)) return false;
        File file = cacheFile(session, url);
        if (file.isFile() && file.length() > 0) {
            recordCachedSegment(session, segment);
            return false;
        }
        String key = file.getName();
        if (cacheCoordinator.isKeyBusyOrCached(key, file)) return false;
        if (!preloading.add(key)) return false;
        try {
            executor.execute(() -> {
                activePreloadTransfers.incrementAndGet();
                try {
                    if (prefetchToCache(session, url, file, preloadGeneration)) {
                        recordCachedSegment(session, segment);
                    }
                } catch (Throwable e) {
                    SpiderDebug.log(TAG, "preload failed errorType=%s action=stop-cache-write", e.getClass().getSimpleName());
                } finally {
                    activePreloadTransfers.updateAndGet(value -> Math.max(0, value - 1));
                    preloading.remove(key);
                }
            });
            return true;
        } catch (Throwable error) {
            preloading.remove(key);
            SpiderDebug.log(TAG, "preload submit failed errorType=%s action=stop-cache-write", error.getClass().getSimpleName());
            return false;
        }
    }

    private synchronized ExecutorService getPreloadExecutor() {
        int threads = MpvPreloadPolicy.resolveExecutorThreads(
                PlaybackPerformanceSetting.isAuto(
                        kernel,
                        PlaybackPerformanceCatalog.PRELOAD_THREADS),
                PreloadSetting.getPreloadThreads(kernel));
        if (preloadExecutor != null && preloadThreads == threads) return preloadExecutor;
        releasePreloadExecutor();
        preloading.clear();
        preloadThreads = threads;
        return preloadExecutor = Executors.newFixedThreadPool(threads);
    }

    private synchronized void cancelPreloads() {
        preloadGate.invalidate();
        releasePreloadWork(false);
    }

    private synchronized void releasePreloadWork(boolean invalidate) {
        if (invalidate) preloadGate.invalidate();
        releasePreloadExecutor();
        preloading.clear();
    }

    private synchronized void releasePreloadExecutor() {
        if (preloadExecutor == null) return;
        preloadExecutor.shutdownNow();
        preloadExecutor = null;
    }

    private boolean prefetchToCache(
            Session session,
            String url,
            File file,
            long preloadGeneration) throws IOException {
        refreshCacheCoordinator();
        if (!preloadGate.allows(preloadGeneration)
                || !isPausePreloadAllowed()
                || configuredPreloadLimitBytes() <= 0
                || !cacheCoordinator.canStartPreload(configuredPreloadLimitBytes())
                || (file.isFile() && file.length() > 0)) return false;
        try (okhttp3.Response response = fetch(session, url, null, true)) {
            if (!preloadGate.allows(preloadGeneration)) return false;
            ResponseBody body = response.body();
            // The caller supplies parsed media segments, including *.m3u8?ts=... URLs.
            if (!response.isSuccessful() || body == null) return false;
            long upstreamLength = body.contentLength();
            if (upstreamLength < MIN_CACHE_FILE_BYTES) return false;
            boolean stripPngPrefix = MpvHlsSegmentContentPolicy.shouldProbePngPrefix(
                    body.contentType() == null ? null : body.contentType().toString(), true);
            InputStream source = body.byteStream();
            long cacheLength = upstreamLength;
            String cacheMime = mediaMimeFromUrl(url, body.contentType());
            if (stripPngPrefix) {
                PngPrefixStrippingInputStream stripping =
                        new PngPrefixStrippingInputStream(source, url);
                int strippedBytes = stripping.initializeAndGetStrippedPrefixBytes();
                cacheLength = MpvHlsSegmentContentPolicy.strippedContentLength(
                        upstreamLength, strippedBytes);
                if (cacheLength < MIN_CACHE_FILE_BYTES) return false;
                source = stripping;
                cacheMime = MIME_TS;
            }
            if (cacheLength > configuredPreloadLimitBytes()) return false;
            String key = file.getName();
            MpvHlsCacheCoordinator.ReservationDecision decision = cacheCoordinator.tryReserve(
                    key, file, cacheLength, configuredPreloadLimitBytes(), MpvHlsCacheCoordinator.WriterType.PREFETCH);
            if (!decision.granted()) return false;
            return writeCacheFile(source, decision.reservation(), cacheLength,
                    cacheMime, preloadGeneration);
        }
    }

    private boolean writeCacheFile(InputStream input, MpvHlsCacheCoordinator.WriteReservation reservation,
                                   long expectedLength, String mime,
                                   long preloadGeneration) throws IOException {
        long written = 0;
        OutputStream output;
        try {
            output = new FileOutputStream(reservation.tempFile());
        } catch (Throwable error) {
            reservation.fail(error);
            logCacheFailure("prefetch-open", error);
            return false;
        }
        try (InputStream in = input; OutputStream out = output) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (!preloadGate.allows(preloadGeneration) || !isPausePreloadAllowed()) {
                    reservation.abort();
                    return false;
                }
                if (!reservation.canWrite(read)) {
                    reservation.abort();
                    return false;
                }
                try {
                    out.write(buffer, 0, read);
                } catch (Throwable error) {
                    reservation.fail(error);
                    logCacheFailure("prefetch-write", error);
                    return false;
                }
                written += read;
                if (!reservation.recordWritten(read)) {
                    reservation.abort();
                    return false;
                }
            }
        } catch (Throwable error) {
            reservation.abort();
            if (error instanceof IOException io) throw io;
            throw new IOException(error);
        }
        if (expectedLength > 0 && written != expectedLength) {
            reservation.abort();
            return false;
        }
        try {
            boolean committed = preloadGate.commitIfAllowed(
                    preloadGeneration,
                    () -> reservation.commit(mime));
            if (committed) {
                SpiderDebug.log(TAG, "cache-write action=preload-commit bytes=%d", written);
            } else {
                reservation.abort();
            }
            return committed;
        } catch (Throwable error) {
            reservation.fail(error);
            logCacheFailure("prefetch-commit", error);
            return false;
        }
    }

    private void recordCachedSegment(
            Session session, HlsPlaylistRewriter.Segment segment) {
        diskBufferStore.recordCompleted(
                session.mediaKey(),
                secondsToMilliseconds(segment.startSeconds()),
                secondsToMilliseconds(segment.endSeconds()));
    }

    private void recordCachedTarget(Session session, Target target) {
        if (target == null
                || target.role() != HlsPlaylistRewriter.UriRole.MEDIA_SEGMENT
                || target.durationSeconds() <= 0) return;
        diskBufferStore.recordCompleted(
                session.mediaKey(),
                secondsToMilliseconds(target.startSeconds()),
                secondsToMilliseconds(target.endSeconds()));
    }

    private File cacheFile(Session session, String url) {
        return new File(cacheDir(), Util.md5(url + "\n" + session.headers) + CACHE_FILE_SUFFIX);
    }

    private File cacheDir() {
        return Path.cache("mpv_hls");
    }

    private boolean isCacheEnabled() {
        return configuredCacheLimitBytes() > 0;
    }

    private long cacheLimitBytes() {
        return cacheCoordinator.effectiveCapacityBytes(configuredCacheLimitBytes());
    }

    private long configuredCacheLimitBytes() {
        long playCache = Math.max(0, PlayerSetting.getPlayCacheSize(kernel));
        return Math.max(playCache, configuredPreloadLimitBytes());
    }

    private long configuredPreloadLimitBytes() {
        return PreloadSetting.isPreload(kernel)
                ? Math.max(0, PreloadSetting.getPreloadSizeBytes(kernel)) : 0;
    }

    private double preloadAheadWindowSeconds() {
        int seconds = PreloadSetting.getPreloadAheadSeconds(kernel);
        return seconds == PreloadSetting.WHOLE_MEDIA_AHEAD_SECONDS
                ? Double.POSITIVE_INFINITY : Math.max(0, seconds);
    }

    private boolean desiredPreloadAllowed() {
        if (!isPausePreloadAllowed()) return false;
        if (!automaticPreloadMode) return true;
        return automaticResourceAllowed && (playbackPaused || automaticTrafficAllowed);
    }

    private boolean isPausePreloadAllowed() {
        return PreloadPausePolicy.evaluate(
                !playbackPaused,
                PreloadSetting.getPausePreloadPolicy(kernel),
                PlaybackSystemConditionMonitor.process().currentNetworkSnapshot()).allowed();
    }

    private void pruneCache() {
        cacheCoordinator.prune(configuredCacheLimitBytes());
    }

    private long cacheBytes() {
        return cacheCoordinator.cacheBytes();
    }

    private int parseSessionId(IHTTPSession session) {
        try {
            return Integer.parseInt(session.getParms().get("s"));
        } catch (Throwable e) {
            return sessionId;
        }
    }

    private void pruneExpiredSessions(long now) {
        for (Map.Entry<Integer, Session> entry : sessions.entrySet()) {
            if (entry.getKey() == sessionId) continue;
            if (now - entry.getValue().createdAtMs > SESSION_TTL_MS) {
                sessions.remove(entry.getKey());
                sessionStats.remove(entry.getKey());
                aesSessions.remove(entry.getKey());
            }
        }
        for (Integer key : new ArrayList<>(dashHlsPlans.keySet())) {
            if (!sessions.containsKey(key)) dashHlsPlans.remove(key);
        }
        for (Integer key : new ArrayList<>(aesSessions.keySet())) {
            if (!sessions.containsKey(key)) aesSessions.remove(key);
        }
        for (Map.Entry<String, Target> entry : targets.entrySet()) {
            Target target = entry.getValue();
            if (sessions.containsKey(target.sessionId)) continue;
            if (now - target.createdAtMs > SESSION_TTL_MS) targets.remove(entry.getKey());
        }
    }

    private void recordPlaylistResponse(int session, int status, String url, @Nullable String text) {
        SessionStats stats = stats(session);
        stats.seenPlaylist = true;
        stats.playlistRequests++;
        recordStatus(stats, status, url);
        if (isVodPlaylist(text)) stats.vod = true;
    }

    private void recordPlaylistDetails(
            int session,
            String playlistUrl,
            String text,
            HlsPlaylistRewriter.Result result,
            @Nullable HlsPlaylistRewriter.Variant inheritedVariant) {
        SessionStats stats = stats(session);
        Session owner = sessions.get(session);
        if (owner != null) {
            PlaybackResourceClassifier.Classification classification = PlaybackResourceClassifier.classifyHls(
                    playerPlaylistUri(session), owner.url, text, SystemClock.elapsedRealtime());
            stats.observeHls(playlistUrl, classification);
        }
        long now = SystemClock.elapsedRealtime();
        PlaybackResourceClassifier.Classification effective =
                stats.resourceClassification(now);
        stats.vod = effective.streamKind()
                == PlaybackAutoContext.StreamKind.VOD;
        String playlistKey = playlistUrl == null
                ? "direct" : Util.md5(playlistUrl);
        if (kernel == PlayerSetting.IJK
                && !result.mediaUnits().isEmpty()
                && (effective.streamKind() == PlaybackAutoContext.StreamKind.LIVE
                || effective.streamKind()
                == PlaybackAutoContext.StreamKind.LOW_LATENCY_LIVE)) {
            stats.observeLivePlaylist(
                    playlistKey, result.mediaUnits(), now);
        } else if (kernel == PlayerSetting.IJK
                && !result.mediaUnits().isEmpty()) {
            stats.clearLivePlaylist(playlistKey);
        }
        String upper = text.toUpperCase(Locale.US);
        if (upper.contains("#EXT-X-BYTERANGE:") || upper.contains("BYTERANGE=")) stats.hasByteRange = true;
        if (!result.variants().isEmpty()) stats.recordVariants(result.variants());
        if (stats.vod && !result.segments().isEmpty()) {
            stats.recordSegments(inheritedVariant, result.segments());
        } else if (!stats.vod) {
            stats.clearSegments(inheritedVariant);
        }
    }

    private void recordSelectedVariant(Target target) {
        HlsPlaylistRewriter.Variant variant = target.variant();
        if (variant == null
                || variant.kind() != HlsPlaylistRewriter.VariantKind.STREAM) return;
        SessionStats stats = stats(target.sessionId());
        if (!stats.recordSelectedVariant(variant)) return;
        SpiderDebug.log(TAG, "variant requested session=%d peak=%d average=%d resolution=%dx%d", target.sessionId(), variant.bandwidth(), variant.averageBandwidth(), variant.width(), variant.height());
    }

    private void recordItemResponse(int session, int status, String url) {
        SessionStats stats = stats(session);
        stats.itemRequests++;
        recordStatus(stats, status, url);
    }

    private String playerPlaylistUri(int session) {
        if (!started || getListeningPort() <= 0) return null;
        return baseUrl() + "/mpv/index.m3u8?s=" + session;
    }

    private SessionStats stats(int session) {
        SessionStats stats = sessionStats.get(session);
        if (stats != null) return stats;
        stats = new SessionStats();
        SessionStats existing = sessionStats.putIfAbsent(session, stats);
        return existing == null ? stats : existing;
    }

    private void recordStatus(SessionStats stats, int status, String url) {
        stats.lastStatus = status;
        stats.lastUrl = url;
        if (status >= 400) stats.lastErrorAtMs = System.currentTimeMillis();
    }

    private String statsText() {
        SessionStats stats = sessionStats.get(sessionId);
        if (stats == null) return "playlist -";
        String type = stats.vod ? "vod" : stats.seenPlaylist ? "live" : "-";
        String status = stats.lastStatus <= 0 ? "-" : String.valueOf(stats.lastStatus);
        return "playlist " + type + " p" + stats.playlistRequests + "/i" + stats.itemRequests + " last " + status + stats.variantText() + (stats.hasByteRange ? " byterange" : "");
    }

    private static boolean isVodPlaylist(String text) {
        return text != null && text.toUpperCase(Locale.US).contains("#EXT-X-ENDLIST");
    }

    private static boolean isHttpUrl(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.US);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    @Nullable
    private static Range parseRange(@Nullable String header, long length) {
        if (TextUtils.isEmpty(header)) return null;
        String value = header.trim().toLowerCase(Locale.US);
        if (!value.startsWith("bytes=") || length <= 0) return null;
        String spec = value.substring("bytes=".length()).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) return null;
        try {
            long start;
            long end;
            String left = spec.substring(0, dash).trim();
            String right = spec.substring(dash + 1).trim();
            if (left.isEmpty()) {
                long suffix = Long.parseLong(right);
                if (suffix <= 0) return null;
                start = Math.max(0, length - suffix);
                end = length - 1;
            } else {
                start = Long.parseLong(left);
                end = right.isEmpty() ? length - 1 : Long.parseLong(right);
            }
            if (start < 0 || end < start || start >= length) return null;
            return new Range(start, Math.min(end, length - 1));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isCompleteRangeResponse(@Nullable String range, @Nullable String contentRange, long contentLength) {
        if (TextUtils.isEmpty(range) || TextUtils.isEmpty(contentRange) || contentLength <= 0) return false;
        String value = range.trim().toLowerCase(Locale.US);
        if (!value.startsWith("bytes=0-")) return false;
        Matcher matcher = CONTENT_RANGE.matcher(contentRange.trim());
        if (!matcher.matches()) return false;
        try {
            long start = Long.parseLong(matcher.group(1));
            long end = Long.parseLong(matcher.group(2));
            String totalText = matcher.group(3);
            if ("*".equals(totalText)) return false;
            long total = Long.parseLong(totalText);
            return start == 0 && end >= start && end + 1 == total && total == contentLength;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void skipFully(InputStream input, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped <= 0) {
                if (input.read() == -1) throw new IOException("unexpected EOF");
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "-";
        if (bytes < 1024L * 1024L) return String.format(Locale.US, "%.1fKB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.US, "%.1fMB", bytes / 1024.0 / 1024.0);
        return String.format(Locale.US, "%.1fGB", bytes / 1024.0 / 1024.0 / 1024.0);
    }

    /**
     * 密钥地址解析：识别源站常见的「裸主机名 + 路径」写法（如
     * {@code play.example.com/xx/enc.key}，没有 {@code https://}）并补全协议，
     * 其余情况交回 {@link #resolve(String, String)} 的常规相对路径解析。
     *
     * <p>只用于 AES 密钥地址：这类地址几乎不会出现「带点的目录名」，误判风险低；调用方
     * 取不到密钥时还会退回常规解析再试一次，双向兜底。
     */
    private String resolveKeyUrl(String playlistUrl, String keyUri) {
        String fallback = resolve(playlistUrl, keyUri);
        String trimmed = keyUri == null ? "" : keyUri.trim();
        if (trimmed.isEmpty()) return fallback;
        String scheme = "https";
        int schemeEnd = playlistUrl == null ? -1 : playlistUrl.indexOf("://");
        if (schemeEnd > 0) scheme = playlistUrl.substring(0, schemeEnd);
        if (trimmed.startsWith("//")) return scheme + ":" + trimmed;
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed;
        int slash = trimmed.indexOf('/');
        if (slash > 0 && !trimmed.startsWith("/") && !trimmed.startsWith(".")) {
            String head = trimmed.substring(0, slash);
            if (head.indexOf('.') > 0) return scheme + "://" + trimmed;
        }
        return fallback;
    }

    private String resolve(String baseUrl, String uri) {
        // 内嵌清单（data:）没有可解析的基准，里面的地址一律按原样使用。
        if (TextUtils.isEmpty(baseUrl)) return uri;
        try {
            URI parsed = URI.create(uri);
            if (parsed.isAbsolute()) return uri;
            return URI.create(baseUrl).resolve(parsed).toString();
        } catch (Throwable e) {
            return uri;
        }
    }

    private static boolean looksLikePlaylist(String text) {
        if (TextUtils.isEmpty(text)) return false;
        String value = text.trim();
        return value.startsWith("#EXTM3U") || value.contains("\n#EXTM3U");
    }

    private static String mediaMime(@Nullable MediaType type) {
        String value = type == null ? "" : type.toString();
        if (isPngMime(type)) return MIME_TS;
        return TextUtils.isEmpty(value) ? MIME_BINARY : value;
    }

    private static String mediaMimeFor(String targetUrl, String finalUrl, @Nullable MediaType type) {
        String mime = mediaMimeFromUrl(finalUrl, type);
        return MIME_BINARY.equals(mime) ? mediaMimeFromUrl(targetUrl, type) : mime;
    }

    private static String mediaMimeFromUrl(String url, @Nullable MediaType type) {
        String value = mediaMime(type);
        if (!MIME_BINARY.equals(value)) return value;
        String lower = stripQuery(url).toLowerCase(Locale.US);
        if (lower.endsWith(".ts") || lower.endsWith(".m2ts")) return MIME_TS;
        if (lower.endsWith(".mp4") || lower.endsWith(".m4s") || lower.endsWith(".m4v") || lower.endsWith(".cmfv")) return "video/mp4";
        if (lower.endsWith(".aac")) return "audio/aac";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".m4a") || lower.endsWith(".cmfa")) return "audio/mp4";
        if (lower.endsWith(".vtt") || lower.endsWith(".webvtt")) return "text/vtt";
        if (lower.endsWith(".srt")) return "application/x-subrip";
        return MIME_BINARY;
    }

    private String cacheMime(String url, File file) {
        File meta = metaFile(file);
        if (meta.isFile() && meta.length() > 0 && meta.length() < 256) {
            try (InputStream input = new FileInputStream(meta)) {
                byte[] data = new byte[(int) meta.length()];
                int read = input.read(data);
                String value = read <= 0 ? "" : new String(data, 0, read, StandardCharsets.UTF_8).trim();
                if (!TextUtils.isEmpty(value)) return value;
            } catch (Throwable ignored) {
            }
        }
        return mediaMimeFromUrl(url, null);
    }

    private File metaFile(File file) {
        return new File(file.getParentFile(), file.getName() + CACHE_META_SUFFIX);
    }

    private static String stripQuery(String url) {
        String value = url == null ? "" : url;
        int query = value.indexOf('?');
        if (query >= 0) value = value.substring(0, query);
        int fragment = value.indexOf('#');
        if (fragment >= 0) value = value.substring(0, fragment);
        return value;
    }

    @Nullable
    private static String requestHeader(IHTTPSession session, String name) {
        if (session == null || session.getHeaders() == null) return null;
        for (Map.Entry<String, String> entry : session.getHeaders().entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return null;
    }

    private static void addStreamingHeaders(Response result, okhttp3.Response upstream, @Nullable String range) {
        result.addHeader("Access-Control-Allow-Origin", "*");
        result.addHeader("Cache-Control", "no-cache");
        result.addHeader("Connection", "close");
        copyHeader(result, upstream, "Content-Range");
        copyHeader(result, upstream, "ETag");
        copyHeader(result, upstream, "Last-Modified");
        String acceptRanges = upstream.header("Accept-Ranges");
        if (!TextUtils.isEmpty(acceptRanges)) {
            result.addHeader("Accept-Ranges", acceptRanges);
        } else if (!TextUtils.isEmpty(range) || upstream.code() == 206) {
            result.addHeader("Accept-Ranges", "bytes");
        }
    }

    private static void copyHeader(Response result, okhttp3.Response upstream, String name) {
        String value = upstream.header(name);
        if (!TextUtils.isEmpty(value)) result.addHeader(name, value);
    }

    private static boolean isPngMime(@Nullable MediaType type) {
        String value = type == null ? "" : type.toString();
        return value.toLowerCase(Locale.US).contains("image/png");
    }

    private static Response noCache(Response response) {
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Cache-Control", "no-cache");
        response.addHeader("Connection", "close");
        return response;
    }

    private static Response error(Response.IStatus status, String text) {
        return noCache(newFixedLengthResponse(status, MIME_PLAINTEXT, text == null ? "" : text));
    }

    private static Response.IStatus toStatus(int code) {
        Status status = Status.lookup(code);
        return status != null ? status : Status.OK;
    }

    private static Response.IStatus streamingStatus(okhttp3.Response upstream, @Nullable String requestedRange) {
        if (!TextUtils.isEmpty(requestedRange) && !TextUtils.isEmpty(upstream.header("Content-Range"))) {
            return Status.PARTIAL_CONTENT;
        }
        return toStatus(upstream.code());
    }

    private static Map<String, String> sanitize(Map<String, String> input) {
        Map<String, String> result = new LinkedHashMap<>();
        if (input == null) return result;
        for (Map.Entry<String, String> entry : input.entrySet()) {
            if (TextUtils.isEmpty(entry.getKey()) || TextUtils.isEmpty(entry.getValue())) continue;
            result.put(entry.getKey().trim(), entry.getValue().trim());
        }
        return result;
    }

    private static String shortUrl(String value) {
        if (value == null || value.length() <= 120) return value;
        return value.substring(0, 120) + "...";
    }

    private static String resolveMediaKey(String mediaKey, String url) {
        return mediaKey == null || mediaKey.isBlank() ? (url == null ? "" : url) : mediaKey;
    }

    private static long secondsToMilliseconds(double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0) return 0;
        double milliseconds = seconds * 1000d;
        return milliseconds >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.round(milliseconds);
    }

    private static double finiteNonNegative(double value) {
        return Double.isFinite(value) ? Math.max(0, value) : 0;
    }

    private void logCacheFailure(String action, Throwable error) {
        String type = error == null ? "unknown" : error.getClass().getSimpleName();
        MpvHlsCacheCoordinator.CapacitySnapshot snapshot = cacheCoordinator.snapshot(configuredCacheLimitBytes());
        SpiderDebug.log(TAG, "cache-write action=%s errorType=%s policy=%s physicalBytes=%d reservedBytes=%d effectiveBytes=%d circuit=%s",
                action, type, snapshot.policy().state(), snapshot.physicalBytes(), snapshot.reservedBytes(),
                snapshot.policy().effectiveCapacityBytes(), snapshot.circuitOpen());
    }

    private record Session(
            String url, Map<String, String> headers, long createdAtMs, String mediaKey) {
    }

    private record Target(
            int sessionId,
            String url,
            long createdAtMs,
            boolean cacheable,
            @Nullable HlsPlaylistRewriter.Variant variant,
            HlsPlaylistRewriter.UriRole role,
            double startSeconds,
            double durationSeconds) {

        private Target {
            role = role == null
                    ? HlsPlaylistRewriter.UriRole.OTHER : role;
            startSeconds = finiteNonNegative(startSeconds);
            durationSeconds = finiteNonNegative(durationSeconds);
        }

        private double endSeconds() {
            return startSeconds + durationSeconds;
        }
    }

    /** IJK 专用：AES-128 清单的解密状态（密钥、IV 取法、分片序号表）。 */
    private static final class HlsAesSession {

        private static final long MAX_SEGMENT_BYTES = 64L * 1024 * 1024;
        /** 密钥取不到时的冷却窗口：期间该会话的分片直接直通，不重复打源站。 */
        static final long KEY_RETRY_COOLDOWN_MS = 5_000L;
        /** 密钥连续取失败上限，达到即判定该会话不值得接手。 */
        private static final int MAX_KEY_FAILURES = 3;
        /** 连续解不出明文的片数上限，达到即放弃接管（后续分片全部直通）。 */
        private static final int MAX_SEGMENT_FAILURES = 3;

        /** 补全协议的主机形式密钥地址（源站常见写法，优先尝试）。 */
        private final String keyUrl;
        /** 常规相对路径解析出的密钥地址（回退用）。 */
        @Nullable
        private final String fallbackKeyUrl;
        private final byte[] declaredIv;
        private final Map<String, Integer> segmentIndex = new ConcurrentHashMap<>();
        private volatile long mediaSequence;
        private volatile int sequenceOffset;
        private volatile boolean declaredIvVerified;
        /** 懒取的密钥；未取到为 null。 */
        private volatile byte[] key;
        /** 密钥取失败后的可重试时间点（elapsedRealtime 基准）。 */
        private volatile long keyRetryAfterMs;
        private volatile int keyFailures;
        private volatile int segmentFailures;
        private volatile boolean abandoned;

        HlsAesSession(
                String keyUrl,
                @Nullable String fallbackKeyUrl,
                @Nullable byte[] declaredIv,
                long mediaSequence) {
            this.keyUrl = keyUrl;
            this.fallbackKeyUrl = fallbackKeyUrl;
            this.declaredIv = declaredIv;
            this.mediaSequence = mediaSequence;
        }

        /** 本次清单指向的密钥地址是否还是这个会话认得的那一个。 */
        boolean matchesKey(String candidate) {
            return keyUrl.equals(candidate)
                    || (fallbackKeyUrl != null && fallbackKeyUrl.equals(candidate));
        }

        boolean keyAvailable() {
            return key != null;
        }

        byte[] key() {
            return key;
        }

        boolean hasFallbackKeyUrl() {
            return fallbackKeyUrl != null && !fallbackKeyUrl.equals(keyUrl);
        }

        boolean inKeyCooldown(long now) {
            return now < keyRetryAfterMs;
        }

        void installKey(byte[] value) {
            key = value;
            keyFailures = 0;
        }

        void noteKeyFailure(long now) {
            keyRetryAfterMs = now + KEY_RETRY_COOLDOWN_MS;
            if (++keyFailures >= MAX_KEY_FAILURES) abandoned = true;
        }

        boolean abandoned() {
            return abandoned;
        }

        void noteSegmentFailure() {
            if (++segmentFailures >= MAX_SEGMENT_FAILURES) abandoned = true;
        }

        /** 记住这次命中的 IV 取法，后续分片直接命中，不再逐片试探。 */
        void noteSegmentSuccess(@Nullable byte[] winnerIv, String url) {
            segmentFailures = 0;
            if (winnerIv == null) return;
            if (declaredIv != null && Arrays.equals(winnerIv, declaredIv)) {
                declaredIvVerified = true;
                return;
            }
            Integer index = segmentIndex.get(url);
            if (index == null) return;
            long base = mediaSequence + index;
            for (int offset : new int[]{0, -1, 1}) {
                long sequence = base + offset;
                if (sequence < 0) continue;
                if (Arrays.equals(HlsAesEncryption.ivForSequence(sequence), winnerIv)) {
                    sequenceOffset = offset;
                    declaredIvVerified = false;
                    return;
                }
            }
        }

        void updateSequence(long sequence) {
            mediaSequence = sequence;
        }

        void recordSegments(List<HlsPlaylistRewriter.Segment> segments) {
            Map<String, Integer> indices = new LinkedHashMap<>();
            for (int i = 0; i < segments.size(); i++) {
                indices.putIfAbsent(segments.get(i).uri(), i);
            }
            segmentIndex.clear();
            segmentIndex.putAll(indices);
        }

        /** 分片 IV：逐片判定出「声明 IV」可用就用它，否则用「媒体序号 + 分片下标 + 偏移」推。 */
        @Nullable
        byte[] ivFor(String url) {
            if (declaredIvVerified && declaredIv != null) return declaredIv;
            Integer index = segmentIndex.get(url);
            if (index == null) return null;
            long sequence = mediaSequence + index + sequenceOffset;
            return sequence < 0 ? null : HlsAesEncryption.ivForSequence(sequence);
        }

        /**
         * 候选 IV 序列（逐片自愈用）：判定出的 IV 优先，再退清单声明 IV 与「媒体序号 ±1」推的 IV。
         *
         * <p>清单不声明 IV、或源侧序号起点与清单对不上时，只靠首片探测一次容易误判；多备几个
         * 候选，解出合法明文就用。全部解不出时由调用方原样直通，不会把能播的源弄坏。
         */
        List<byte[]> candidateIvsFor(@Nullable byte[] primaryIv, String url) {
            List<byte[]> candidates = new ArrayList<>(4);
            if (primaryIv != null) candidates.add(primaryIv);
            if (declaredIv != null && !sameIv(declaredIv, primaryIv)) candidates.add(declaredIv);
            Integer index = segmentIndex.get(url);
            if (index != null) {
                long sequence = mediaSequence + index + sequenceOffset;
                for (int offset : new int[]{0, -1, 1}) {
                    long candidateSequence = sequence + offset;
                    if (candidateSequence < 0) continue;
                    byte[] iv = HlsAesEncryption.ivForSequence(candidateSequence);
                    if (sameIv(iv, primaryIv) || containsIv(candidates, iv)) continue;
                    candidates.add(iv);
                }
            }
            return candidates;
        }

        private static boolean sameIv(byte[] left, @Nullable byte[] right) {
            return left != null && right != null && Arrays.equals(left, right);
        }

        private static boolean containsIv(List<byte[]> candidates, byte[] iv) {
            for (byte[] candidate : candidates) {
                if (Arrays.equals(candidate, iv)) return true;
            }
            return false;
        }
    }

    /** 整段读入内存并设上限（AES 分片必须整体解密，不能边流边解）。 */
    @Nullable
    private static byte[] readAll(InputStream input, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count == 0) continue;
            total += count;
            if (total > limit) return null;
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    private record Range(long start, long end) {
    }

    private final class ForegroundLease implements AutoCloseable {

        private final boolean managed;
        private boolean closed;

        private ForegroundLease(boolean managed) {
            this.managed = managed;
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            if (managed) preloadGate.foregroundEnded();
        }
    }

    private static final class ForegroundInputStream extends FilterInputStream {

        private final ForegroundLease foreground;
        private boolean closed;

        private ForegroundInputStream(InputStream input, ForegroundLease foreground) {
            super(input);
            this.foreground = foreground;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value == -1) close();
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int value = super.read(buffer, offset, length);
            if (value == -1) close();
            return value;
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            try {
                super.close();
            } finally {
                foreground.close();
            }
        }
    }

    private static final class UpstreamTelemetryInputStream extends FilterInputStream {

        private final long expectedLength;
        private final MpvHlsUpstreamEstimator estimator;
        private final long estimatorGeneration;
        private long firstReadAtNanos = -1;
        private long lastReadAtNanos = -1;
        private long blockedReadNanos;
        private long bytes;
        private boolean eof;
        private boolean finished;

        private UpstreamTelemetryInputStream(
                InputStream input,
                long expectedLength,
                MpvHlsUpstreamEstimator estimator) {
            super(input);
            this.expectedLength = expectedLength;
            this.estimator = estimator;
            this.estimatorGeneration = estimator.generation();
        }

        @Override
        public int read() throws IOException {
            long started = System.nanoTime();
            markStarted(started);
            int value = super.read();
            long ended = System.nanoTime();
            recordReadTiming(started, ended);
            if (value == -1) {
                eof = true;
                finish(true);
            } else {
                bytes++;
                finishIfExpectedLengthReached();
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            long started = System.nanoTime();
            markStarted(started);
            int value = super.read(buffer, offset, length);
            long ended = System.nanoTime();
            recordReadTiming(started, ended);
            if (value == -1) {
                eof = true;
                finish(true);
            } else if (value > 0) {
                bytes = saturatedAdd(bytes, value);
                finishIfExpectedLengthReached();
            }
            return value;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                finish(eof || expectedLength > 0 && bytes == expectedLength);
            }
        }

        private void markStarted(long started) {
            if (firstReadAtNanos < 0) firstReadAtNanos = started;
        }

        private void recordReadTiming(long started, long ended) {
            lastReadAtNanos = Math.max(started, ended);
            blockedReadNanos = saturatedAdd(
                    blockedReadNanos, Math.max(0, ended - started));
        }

        private void finishIfExpectedLengthReached() {
            if (expectedLength > 0 && bytes == expectedLength) finish(true);
        }

        private void finish(boolean complete) {
            if (finished) return;
            finished = true;
            long wallNanos = firstReadAtNanos < 0 || lastReadAtNanos < firstReadAtNanos
                    ? 0 : lastReadAtNanos - firstReadAtNanos;
            estimator.recordForeground(
                    estimatorGeneration,
                    bytes,
                    blockedReadNanos,
                    wallNanos,
                    complete,
                    SystemClock.elapsedRealtime());
        }

        private static long saturatedAdd(long first, long second) {
            return second > 0 && first > Long.MAX_VALUE - second
                    ? Long.MAX_VALUE : first + second;
        }
    }

    private final class CacheWritingInputStream extends FilterInputStream {

        private final MpvHlsCacheCoordinator.WriteReservation reservation;
        private final long expectedLength;
        private final String mime;
        private final Session session;
        private final Target target;
        private OutputStream cache;
        private long written;
        private boolean completed;

        CacheWritingInputStream(InputStream in, OutputStream cache,
                                MpvHlsCacheCoordinator.WriteReservation reservation,
                                String mime, Session session, Target target) {
            super(in);
            this.cache = cache;
            this.reservation = reservation;
            this.expectedLength = reservation.expectedBytes();
            this.mime = mime;
            this.session = session;
            this.target = target;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value == -1) {
                finishCache();
            } else {
                writeCacheByte(value);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read == -1) {
                finishCache();
            } else if (read > 0) {
                writeCache(buffer, offset, read);
            }
            return read;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                if (!completed) abortCache();
            }
        }

        private void writeCacheByte(int value) {
            OutputStream out = cache;
            if (out == null) return;
            try {
                if (!reservation.canWrite(1)) {
                    disableCache(null);
                    return;
                }
                out.write(value);
                written++;
                if (!reservation.recordWritten(1)) disableCache(null);
                else checkCacheProgress();
            } catch (Throwable e) {
                disableCache(e);
            }
        }

        private void writeCache(byte[] buffer, int offset, int length) {
            OutputStream out = cache;
            if (out == null) return;
            try {
                if (!reservation.canWrite(length)) {
                    disableCache(null);
                    return;
                }
                out.write(buffer, offset, length);
                written += length;
                if (!reservation.recordWritten(length)) disableCache(null);
                else checkCacheProgress();
            } catch (Throwable e) {
                disableCache(e);
            }
        }

        private void checkCacheProgress() {
            if (expectedLength > 0 && written > expectedLength) {
                disableCache(new IOException("cache item exceeds expected length"));
            } else if (expectedLength > 0 && written == expectedLength) {
                finishCache();
            }
        }

        private void finishCache() {
            if (completed) return;
            completed = true;
            OutputStream out = cache;
            cache = null;
            try {
                if (out != null) out.close();
                if (expectedLength > 0 && written != expectedLength) {
                    reservation.abort();
                    return;
                }
                if (reservation.commit(mime)) {
                    recordCachedTarget(session, target);
                    SpiderDebug.log(TAG, "cache-write action=foreground-commit bytes=%d", written);
                }
            } catch (Throwable e) {
                reservation.fail(e);
                logCacheFailure("foreground-commit", e);
            }
        }

        private void disableCache(@Nullable Throwable error) {
            if (completed) return;
            completed = true;
            OutputStream out = cache;
            cache = null;
            closeQuietly(out);
            reservation.fail(error);
            logCacheFailure("foreground-drop", error);
        }

        private void abortCache() {
            OutputStream out = cache;
            cache = null;
            closeQuietly(out);
            reservation.abort();
        }

        private void closeQuietly(@Nullable OutputStream out) {
            if (out == null) return;
            try {
                out.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private record ByteRange(long start, long end) {

        @Nullable
        private static ByteRange parse(String value) {
            if (TextUtils.isEmpty(value)) return null;
            int separator = value.indexOf('-');
            if (separator <= 0 || separator >= value.length() - 1) return null;
            try {
                long start = Long.parseLong(value.substring(0, separator).trim());
                long end = Long.parseLong(value.substring(separator + 1).trim());
                return start >= 0 && end >= start ? new ByteRange(start, end) : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    private record SidxRange(long start, long end, long duration) {
    }

    private static final class Sidx {

        private final long timescale;
        private final List<SidxRange> ranges;

        private Sidx(long timescale, List<SidxRange> ranges) {
            this.timescale = timescale;
            this.ranges = ranges;
        }

        private List<SidxRange> grouped(long targetMs) {
            long target = Math.max(1, targetMs * timescale / 1000);
            List<SidxRange> result = new ArrayList<>();
            long start = -1;
            long end = -1;
            long duration = 0;
            for (SidxRange range : ranges) {
                if (start < 0) start = range.start;
                end = range.end;
                duration += range.duration;
                if (duration >= target) {
                    result.add(new SidxRange(start, end, duration));
                    start = -1;
                    end = -1;
                    duration = 0;
                }
            }
            if (start >= 0) result.add(new SidxRange(start, end, duration));
            return result;
        }

        @Nullable
        private static Sidx parse(byte[] data, long absoluteStart) {
            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
            for (int position = 0; position + 8 <= data.length; ) {
                long size = uint32(buffer, position);
                int headerSize = 8;
                if (size == 1) {
                    if (position + 16 > data.length) return null;
                    size = buffer.getLong(position + 8);
                    headerSize = 16;
                } else if (size == 0) {
                    size = data.length - position;
                }
                if (size < headerSize || size > data.length - position) return null;
                if (buffer.getInt(position + 4) == 0x73696478) return parseBox(buffer, position, size, headerSize, absoluteStart + position);
                position += (int) size;
            }
            return null;
        }

        @Nullable
        private static Sidx parseBox(ByteBuffer buffer, int position, long boxSize, int headerSize, long absoluteBoxStart) {
            int cursor = position + headerSize;
            int limit = position + (int) boxSize;
            if (cursor + 20 > limit) return null;
            int version = buffer.get(cursor) & 0xFF;
            cursor += 8;
            long timescale = uint32(buffer, cursor);
            cursor += 4;
            if (timescale <= 0) return null;
            long firstOffset;
            if (version == 0) {
                if (cursor + 8 > limit) return null;
                cursor += 4;
                firstOffset = uint32(buffer, cursor);
                cursor += 4;
            } else if (version == 1) {
                if (cursor + 16 > limit) return null;
                cursor += 8;
                firstOffset = buffer.getLong(cursor);
                cursor += 8;
            } else return null;
            if (firstOffset < 0 || cursor + 4 > limit) return null;
            cursor += 2;
            int count = buffer.getShort(cursor) & 0xFFFF;
            cursor += 2;
            long segmentStart = absoluteBoxStart + boxSize + firstOffset;
            List<SidxRange> ranges = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                if (cursor + 12 > limit) return null;
                long reference = uint32(buffer, cursor);
                cursor += 4;
                long size = reference & 0x7FFFFFFFL;
                long duration = uint32(buffer, cursor);
                cursor += 8;
                if ((reference & 0x80000000L) == 0 && size > 0) ranges.add(new SidxRange(segmentStart, segmentStart + size - 1, duration));
                segmentStart += size;
            }
            return ranges.isEmpty() ? null : new Sidx(timescale, ranges);
        }

        private static long uint32(ByteBuffer buffer, int offset) {
            return buffer.getInt(offset) & 0xFFFFFFFFL;
        }
    }

    private static final class SessionStats {
        private volatile boolean seenPlaylist;
        private volatile boolean vod;
        private volatile boolean hasByteRange;
        private volatile int playlistRequests;
        private volatile int itemRequests;
        private volatile int lastStatus;
        private volatile long lastErrorAtMs;
        private volatile String lastUrl;
        private volatile PlaybackResourceClassifier.Classification classification;
        private final HlsManifestTimelineTracker hlsTimeline = new HlsManifestTimelineTracker();
        private final HlsProxyLiveLagTracker liveLag =
                new HlsProxyLiveLagTracker();
        private volatile HlsPlaylistRewriter.Variant selectedVariant;
        private volatile HlsAdTimeline directAdTimeline;
        private final Map<HlsPlaylistRewriter.Variant, HlsAdTimeline> adTimelines =
                new ConcurrentHashMap<>();
        private volatile int variantCount;
        private volatile List<HlsVariant> variants = List.of();
        private volatile List<HlsPlaylistRewriter.Segment> segments = List.of();
        private final Map<Long, List<HlsPlaylistRewriter.Segment>> variantSegments =
                new ConcurrentHashMap<>();

        private void recordVariants(List<HlsPlaylistRewriter.VariantEntry> variants) {
            variantCount = variants.size();
            this.variants = buildVariantLadder(variants);
        }

        private synchronized boolean recordSelectedVariant(HlsPlaylistRewriter.Variant variant) {
            if (variant.equals(selectedVariant)) return false;
            selectedVariant = variant;
            return true;
        }

        private void recordSegments(
                @Nullable HlsPlaylistRewriter.Variant variant,
                List<HlsPlaylistRewriter.Segment> segments) {
            List<HlsPlaylistRewriter.Segment> safe = segments == null
                    ? List.of() : List.copyOf(segments);
            long bits = variantBits(variant);
            if (bits > 0) variantSegments.put(bits, safe);
            else this.segments = safe;
        }

        private void clearSegments(@Nullable HlsPlaylistRewriter.Variant variant) {
            long bits = variantBits(variant);
            if (bits > 0) variantSegments.remove(bits);
            else segments = List.of();
        }

        private List<HlsPlaylistRewriter.Segment> segmentsForVariant(
                long selectedBitsPerSecond) {
            return resolveAutomaticPreloadSegments(
                    variantSegments, segments, selectedBitsPerSecond);
        }

        private static long variantBits(
                @Nullable HlsPlaylistRewriter.Variant variant) {
            if (variant == null
                    || variant.kind() != HlsPlaylistRewriter.VariantKind.STREAM) return 0;
            return Math.max(0, variant.bandwidth() > 0
                    ? variant.bandwidth() : variant.averageBandwidth());
        }

        private String variantText() {
            HlsPlaylistRewriter.Variant variant = selectedVariant;
            if (variant == null) return variantCount == 0 ? "" : " variants " + variantCount;
            return " variant " + variant.width() + "x" + variant.height() + " peak " + variant.bandwidth() + " average " + variant.averageBandwidth();
        }

        private HlsVariantSnapshot variantSnapshot() {
            return new HlsVariantSnapshot(variants, variantCount);
        }

        private void observeHls(
                String playlistUrl,
                PlaybackResourceClassifier.Classification observation) {
            hlsTimeline.observe(playlistUrl == null ? "direct" : Util.md5(playlistUrl), observation);
        }

        private PlaybackResourceClassifier.Classification resourceClassification(
                long nowElapsedMs) {
            PlaybackResourceClassifier.Classification fallback = classification;
            return hlsTimeline.snapshot(fallback, nowElapsedMs).classification();
        }

        private void observeLivePlaylist(
                String playlistKey,
                List<HlsPlaylistRewriter.MediaUnit> mediaUnits,
                long observedAtElapsedMs) {
            liveLag.observePlaylist(
                    playlistKey, mediaUnits, observedAtElapsedMs);
        }

        private void clearLivePlaylist(String playlistKey) {
            liveLag.clearPlaylist(playlistKey);
        }

        private void observeLiveMediaRequest(
                String uri,
                long requestedAtElapsedMs) {
            liveLag.observeMediaRequest(uri, requestedAtElapsedMs);
        }

        private HlsProxyLiveLagTracker.Snapshot liveLagSnapshot(
                long nowElapsedMs,
                long nativeBufferedDurationMs) {
            return liveLag.snapshot(nowElapsedMs, nativeBufferedDurationMs);
        }
    }

    public record LiveLagSnapshot(
            boolean known,
            long lowerBoundMs,
            long nativeBufferedDurationMs,
            boolean outsideWindow) {

        public LiveLagSnapshot {
            lowerBoundMs = Math.max(0, lowerBoundMs);
            nativeBufferedDurationMs = Math.max(0,
                    nativeBufferedDurationMs);
        }

        public static LiveLagSnapshot unknown() {
            return new LiveLagSnapshot(false, 0, 0, false);
        }
    }

    record HlsVariant(
            long bandwidthBitsPerSecond,
            long averageBandwidthBitsPerSecond,
            int width,
            int height) {

        private static HlsVariant from(HlsPlaylistRewriter.Variant variant) {
            return new HlsVariant(
                    Math.max(0, variant.bandwidth()),
                    Math.max(0, variant.averageBandwidth()),
                    Math.max(0, variant.width()),
                    Math.max(0, variant.height()));
        }

        long selectionBitsPerSecond() {
            return bandwidthBitsPerSecond > 0
                    ? bandwidthBitsPerSecond : averageBandwidthBitsPerSecond;
        }
    }

    record HlsVariantSnapshot(
            List<HlsVariant> variants,
            int declaredVariantCount) {

        HlsVariantSnapshot {
            variants = variants == null ? List.of() : List.copyOf(variants);
            declaredVariantCount = Math.max(0, declaredVariantCount);
        }

        static HlsVariantSnapshot empty() {
            return new HlsVariantSnapshot(List.of(), 0);
        }
    }

    record PreloadRuntimeSnapshot(
            boolean preloadConfigured,
            boolean vod,
            long upstreamBitsPerSecond,
            boolean throughputKnown,
            boolean throughputFresh,
            long throughputSampleAtElapsedMs,
            long throughputAgeMs,
            int acceptedThroughputSamples,
            int rejectedThroughputSamples,
            String lastThroughputRejectReason,
            int foregroundRequests,
            boolean cacheEnabled,
            boolean cacheStorageKnown,
            boolean cacheBudgetAvailable,
            boolean cacheCircuitOpen,
            long cachePhysicalBytes,
            long cacheReservedBytes,
            long cacheNewWriteBudgetBytes,
            long cacheEffectiveCapacityBytes,
            int preloadTasks) {

        PreloadRuntimeSnapshot {
            upstreamBitsPerSecond = Math.max(0, upstreamBitsPerSecond);
            acceptedThroughputSamples = Math.max(0, acceptedThroughputSamples);
            rejectedThroughputSamples = Math.max(0, rejectedThroughputSamples);
            lastThroughputRejectReason = lastThroughputRejectReason == null
                    ? "none" : lastThroughputRejectReason;
            foregroundRequests = Math.max(0, foregroundRequests);
            cachePhysicalBytes = Math.max(0, cachePhysicalBytes);
            cacheReservedBytes = Math.max(0, cacheReservedBytes);
            cacheNewWriteBudgetBytes = Math.max(0, cacheNewWriteBudgetBytes);
            cacheEffectiveCapacityBytes = Math.max(0, cacheEffectiveCapacityBytes);
            preloadTasks = Math.max(0, preloadTasks);
        }
    }

    static List<HlsVariant> buildVariantLadder(
            List<HlsPlaylistRewriter.VariantEntry> variants) {
        Map<Long, HlsVariant> byBitrate = new LinkedHashMap<>();
        if (variants != null) {
            for (HlsPlaylistRewriter.VariantEntry entry : variants) {
                HlsPlaylistRewriter.Variant variant = entry == null
                        ? null : entry.variant();
                if (variant == null
                        || variant.kind()
                        != HlsPlaylistRewriter.VariantKind.STREAM) continue;
                HlsVariant safe = HlsVariant.from(variant);
                if (safe.selectionBitsPerSecond() <= 0) continue;
                byBitrate.putIfAbsent(safe.selectionBitsPerSecond(), safe);
            }
        }
        List<HlsVariant> ladder = new ArrayList<>(byBitrate.values());
        ladder.sort(java.util.Comparator.comparingLong(
                HlsVariant::selectionBitsPerSecond));
        return List.copyOf(ladder);
    }

    static List<HlsPlaylistRewriter.Segment> resolveAutomaticPreloadSegments(
            Map<Long, List<HlsPlaylistRewriter.Segment>> variantSegments,
            List<HlsPlaylistRewriter.Segment> directSegments,
            long selectedBitsPerSecond) {
        Map<Long, List<HlsPlaylistRewriter.Segment>> variants =
                variantSegments == null ? Map.of() : variantSegments;
        List<HlsPlaylistRewriter.Segment> direct = directSegments == null
                ? List.of() : directSegments;
        long selected = Math.max(0, selectedBitsPerSecond);
        if (!variants.isEmpty()) {
            if (selected <= 0) {
                if (variants.size() != 1) return List.of();
                return List.copyOf(variants.values().iterator().next());
            }
            List<HlsPlaylistRewriter.Segment> exact = variants.get(selected);
            if (exact != null) return List.copyOf(exact);
            return List.of();
        }
        return List.copyOf(direct);
    }

    static int resolvePreloadSubmissionBudget(int pendingTasks) {
        return Math.max(0, MAX_PENDING_PRELOAD_SEGMENTS - Math.max(0, pendingTasks));
    }

    /**
     * Resolves the actual native-selected track bitrate against the proxy ladder.
     * Proxy child-playlist request order is not selection evidence because FFmpeg
     * parses every child playlist in an HLS master.
     */
    @Nullable
    static HlsVariant resolveSelectedVariant(
            List<HlsVariant> variants,
            long selectedBitsPerSecond) {
        long selected = Math.max(0, selectedBitsPerSecond);
        if (selected <= 0) return null;
        if (variants != null) {
            for (HlsVariant variant : variants) {
                if (variant != null
                        && variant.selectionBitsPerSecond() == selected) {
                    return variant;
                }
            }
        }
        return new HlsVariant(selected, 0, 0, 0);
    }

    private static final class LimitedInputStream extends FilterInputStream {

        private long remaining;
        private boolean closed;

        LimitedInputStream(InputStream in, long length) {
            super(in);
            remaining = Math.max(0, length);
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                close();
                return -1;
            }
            int value = super.read();
            if (value != -1) remaining--;
            else close();
            if (remaining <= 0) close();
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                close();
                return -1;
            }
            int read = super.read(buffer, offset, (int) Math.min(length, remaining));
            if (read != -1) remaining -= read;
            else close();
            if (remaining <= 0) close();
            return read;
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            super.close();
        }
    }

    private static final class CloseResponseInputStream extends FilterInputStream {

        private final okhttp3.Response response;

        CloseResponseInputStream(InputStream in, okhttp3.Response response) {
            super(in);
            this.response = response;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                response.close();
            }
        }
    }

    static final class PngPrefixStrippingInputStream extends InputStream {

        private final InputStream upstream;
        private final String url;
        private final boolean diagnostics;
        private byte[] prefix;
        private int prefixOffset;
        private int prefixLength;
        private int strippedPrefixBytes;
        private boolean initialized;

        PngPrefixStrippingInputStream(InputStream upstream, String url) {
            this(upstream, url, true);
        }

        PngPrefixStrippingInputStream(
                InputStream upstream, String url, boolean diagnostics) {
            this.upstream = upstream;
            this.url = url;
            this.diagnostics = diagnostics;
        }

        @Override
        public int read() throws IOException {
            ensureInitialized();
            if (prefixOffset < prefixLength) return prefix[prefixOffset++] & 0xFF;
            return upstream.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            ensureInitialized();
            if (prefixOffset < prefixLength) {
                int count = Math.min(length, prefixLength - prefixOffset);
                System.arraycopy(prefix, prefixOffset, buffer, offset, count);
                prefixOffset += count;
                return count;
            }
            return upstream.read(buffer, offset, length);
        }

        @Override
        public void close() throws IOException {
            upstream.close();
        }

        int initializeAndGetStrippedPrefixBytes() throws IOException {
            ensureInitialized();
            return strippedPrefixBytes;
        }

        private void ensureInitialized() throws IOException {
            if (initialized) return;
            initialized = true;
            prefix = readPrefix();
            prefixLength = prefix.length;
            int stripOffset = MpvHlsSegmentContentPolicy.findPngWrappedTransportStreamOffset(prefix, prefixLength);
            if (stripOffset > 0 && stripOffset < prefixLength) {
                prefixOffset = stripOffset;
                strippedPrefixBytes = stripOffset;
                if (diagnostics) {
                    SpiderDebug.log(TAG,
                            "strip png prefix offset=%d prefixBytes=%d url=%s",
                            stripOffset, prefixLength, shortUrl(url));
                }
            } else {
                prefixOffset = 0;
                strippedPrefixBytes = 0;
            }
        }

        private byte[] readPrefix() throws IOException {
            byte[] buffer = new byte[PREFIX_SCAN_LIMIT];
            int length = 0;
            while (length < buffer.length) {
                int read = upstream.read(buffer, length, buffer.length - length);
                if (read == -1) break;
                length += read;
                if (length >= 8 && !MpvHlsSegmentContentPolicy.startsWithPngSignature(buffer, length)) break;
                int offset = MpvHlsSegmentContentPolicy.findPngWrappedTransportStreamOffset(buffer, length);
                if (offset > 0 && length > offset + 188) break;
            }
            byte[] result = new byte[length];
            System.arraycopy(buffer, 0, result, 0, length);
            return result;
        }

    }
}
