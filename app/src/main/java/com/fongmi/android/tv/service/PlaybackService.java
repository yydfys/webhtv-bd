package com.fongmi.android.tv.service;

import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.session.CommandButton;
import androidx.media3.session.DefaultMediaNotificationProvider;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaLibraryService;
import androidx.media3.session.MediaLibraryService.MediaLibrarySession;
import androidx.media3.session.MediaSession;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionCommands;
import androidx.media3.session.SessionError;
import androidx.media3.session.SessionResult;
import androidx.media3.session.SessionToken;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.browse.BrowseTree;
import com.fongmi.android.tv.cache.CacheScheduler;
import com.fongmi.android.tv.event.ActionEvent;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.playback.PlaybackEventCollector;
import com.fongmi.android.tv.player.PlaybackOwnershipKeys;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.engine.PlaySpec;
import com.fongmi.android.tv.player.lyrics.DesktopLyricsWindow;
import com.fongmi.android.tv.player.lyrics.LyricsController;
import com.fongmi.android.tv.player.lyrics.LyricsLine;
import com.fongmi.android.tv.player.lyrics.LyricsResult;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.ui.audio.AudioHistory;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.Util;
import com.github.catvod.crawler.SpiderDebug;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class PlaybackService extends MediaLibraryService implements MediaLibrarySession.Callback, PlayerManager.Callback {

    public static final String LOCAL_BIND_ACTION = BuildConfig.APPLICATION_ID.concat(".LOCAL_BIND");

    private static final SessionCommand COMMAND_REPEAT = new SessionCommand(ActionEvent.REPEAT, Bundle.EMPTY);
    private static final long AUDIO_HISTORY_SYNC_INTERVAL = 5000L;

    private static volatile boolean running;

    private final List<PlayerCallback> playerCallbacks = new CopyOnWriteArrayList<>();
    private final IBinder binder = new LocalBinder();
    private final Runnable audioHistoryTicker = new Runnable() {
        @Override
        public void run() {
            syncAudioHistoryProgress(false);
            scheduleAudioHistorySync();
        }
    };

    private NavigationCallback navigationCallback;
    private MediaLibrarySession session;
private AudioHistory.Record audioHistoryRecord;
    private DesktopLyricsWindow desktopLyrics;
    private Runnable onNewBinding;
    /** 宿主（Activity）注册的「播放被系统/通知栏停掉」回调，用于关掉残留的画中画小窗。 */
    private Runnable onPlaybackStopped;
    private String savedAudioHistoryTrack;
    private long lastAudioHistorySync;
    private boolean externalBound;
    private boolean keepAlive;
    private PlayerManager player;
    private String navigationKey;
    private Player exoPlayer;

    public static boolean isRunning() {
        return running;
    }

    /**
     * 原源租约由播放服务持有：Activity 暂时离开不释放正在消费的代理，
     * 真正停播／替换媒体／退出才释放。
     */
    private static volatile com.fongmi.android.tv.playback.origin.OriginSessionClient originLease;
    private boolean originKeepAlive;
    private boolean originPreviousKeepAlive;
    private String originPlaybackKey;

    private com.fongmi.android.tv.bean.History originHistory;
    private final Runnable originProgressTicker = new Runnable() {
        @Override public void run() {
            if (originLease == null || originLease.isClosed() || player == null || player.isReleased()) return;
            if (releaseOriginIfMediaReplaced()) return;
            if (originHistory != null && !com.fongmi.android.tv.setting.Setting.isIncognito()
                    && com.fongmi.android.tv.playback.origin.OriginProgressWriter.write(originHistory,
                    player.getPlayer().getCurrentPosition(), player.getPlayer().getDuration())) {
                if (originLease != null) originLease.progressCommitted();
            }
            App.post(this, 5000);
        }
    };

    public static void holdOriginLease(com.fongmi.android.tv.playback.origin.OriginSessionClient client,
                                       com.fongmi.android.tv.bean.History history) {
        holdOriginLease(client);
        PlaybackService service = Server.get().getService();
        if (service != null) {
            if (!service.originKeepAlive) service.originPreviousKeepAlive = service.keepAlive;
            service.originKeepAlive = true;
            service.keepAlive = true;
            service.originHistory = history;
            service.originPlaybackKey = client.playbackKey();
            App.removeCallbacks(service.originProgressTicker);
            App.post(service.originProgressTicker, 5000);
        }
    }

    public static void holdOriginLease(com.fongmi.android.tv.playback.origin.OriginSessionClient client) {
        if (originLease != null && originLease != client) releaseOriginLease();
        originLease = client;
    }

    public static com.fongmi.android.tv.playback.origin.OriginSessionClient originLease() { return originLease; }

    public static void releaseOriginLease() {
        com.fongmi.android.tv.playback.origin.OriginSessionClient client = originLease;
        originLease = null;
        PlaybackService service = Server.get().getService();
        if (service != null) { App.removeCallbacks(service.originProgressTicker); service.originHistory = null; service.originPlaybackKey = null; if (service.originKeepAlive) { service.originKeepAlive = false; service.keepAlive = service.originPreviousKeepAlive; } }
        if (client != null) try {
            client.close();
        } catch (Throwable ignored) {
        }
    }

    private boolean releaseOriginIfMediaReplaced() {
        if (originLease != null && originPlaybackKey != null && player != null
                && !java.util.Objects.equals(originPlaybackKey, player.getKey())) {
            releaseOriginLease();
            return true;
        }
        return false;
    }

    public static boolean canContinueInBackground() {
        PlaybackService service = Server.get().getService();
        PlayerManager manager = service == null ? null : service.player;
        return PlaybackExitPolicy.canContinueInBackground(running, manager != null && !manager.isReleased(), manager != null && !manager.isEmpty());
    }

    /**
     * 当前播放的是否「音频型内容」（纯音频 / 音乐 / 有声书，没有视频轨）。
     *
     * <p>这是「退出壳子仍可继续后台播」的<b>唯一</b>例外：视频一律随壳子退出而停播并撤掉通知栏卡片，
     * 音频型内容按用户的后台播放设置继续。判定复用 {@link LyricsController#isAudioContent(PlayerManager)}，
     * 与本 App 各处「这是不是音频内容」的口径保持同一处实现，避免出现"歌词页当它是音乐、
     * 退出逻辑当它是视频"这种自相矛盾。
     *
     * <p>服务没跑、引擎已释放、队列为空一律返回 {@code false}：没有东西在播时不存在例外。
     */
    public static boolean isAudioContentPlaying() {
        PlaybackService service = Server.get().getService();
        PlayerManager manager = service == null ? null : service.player;
        if (!running || manager == null || manager.isReleased() || manager.isEmpty()) return false;
        try {
            return LyricsController.isAudioContent(manager);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void shutdown(Context context) {
        PlaybackService service = Server.get().getService();
        if (service != null) service.shutdown();
        context.stopService(new Intent(context, PlaybackService.class));
    }

    public void setPlaybackStoppedHook(Runnable hook) {
        onPlaybackStopped = hook;
    }

    public void clearPlaybackStoppedHook(Runnable hook) {
        if (onPlaybackStopped == hook) onPlaybackStopped = null;
    }

    public void replaceBinding(Runnable callback) {
        if (onNewBinding != null) onNewBinding.run();
        onNewBinding = callback;
    }

    @Nullable
    public SessionToken getSessionToken() {
        return session == null ? null : session.getToken();
    }

    public PlayerManager player() {
        return player;
    }

    private String serviceState() {
        return "running=" + running +
                " player=" + (player != null && !player.isReleased()) +
                " key=" + (player == null || player.isReleased() ? null : player.getKey()) +
                " callbacks=" + playerCallbacks.size() +
                " external=" + externalBound +
                " navigation=" + (navigationCallback != null) +
                " navigationKey=" + navigationKey +
                " session=" + (session != null);
    }

    private boolean hasNavigationCallback() {
        return navigationCallback != null;
    }

    private boolean isPlayerAvailable() {
        return running && player != null && !player.isReleased();
    }

    @Override
    public void onCreate() {
        long start = System.currentTimeMillis();
        super.onCreate();
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-flow", "service onCreate start");
        running = true;
        player = new PlayerManager(this);
        desktopLyrics = new DesktopLyricsWindow(this);
        PlaybackEventCollector.get().setPlayer(player);
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-flow", "service player ready cost=%dms", System.currentTimeMillis() - start);
        exoPlayer = player.getPlayer();
        exoPlayer.addListener(listener);
        session = new MediaLibrarySession.Builder(this, wrap(exoPlayer), this).build();
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-flow", "service session ready cost=%dms", System.currentTimeMillis() - start);
        session.setSessionActivity(buildDefaultIntent());
        EventBus.getDefault().register(this);
        Server.get().setService(this);
        setupNotification();
        // Direct SessionToken controllers bypass MediaSessionService binding, so register the session for media notifications.
        addSession(session);
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-flow", "service onCreate end cost=%dms", System.currentTimeMillis() - start);
    }

    private PendingIntent buildDefaultIntent() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (intent == null) intent = new Intent();
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void setupNotification() {
        DefaultMediaNotificationProvider provider = new DefaultMediaNotificationProvider.Builder(this).build();
        session.setMediaButtonPreferences(ImmutableList.of(buildRepeatButton(), buildStopButton()));
        provider.setSmallIcon(R.drawable.ic_notification);
        setMediaNotificationProvider(provider);
    }

    /**
     * v622：媒体通知与前台服务只给音频型内容。
     *
     * <p>视频不再走系统通知栏：视频只有「全屏」和「画中画小窗」两种形态，离开界面由小窗接管，
     * 不存在「后台只剩声音、靠通知栏控制」的场景，因此既不需要媒体通知，也不需要把服务提到前台。
     * 音频（音乐 / 有声书）保持原样：通知栏常驻 + 按设置后台继续播放。
     *
     * <p>通知与 {@code startForeground} 只发生在 {@code super} 的默认实现里
     * （media3 {@code MediaSessionService.onUpdateNotificationAsync} → {@code MediaNotificationManager}），
     * 不调 super 就完全不发通知、也不会把服务提到前台。
     */
    @Override
    public ListenableFuture<Void> onUpdateNotificationAsync(@NonNull MediaSession session, boolean startInForegroundRequired) {
        if (!isAudioContentPlaying() && prefersPiPOverNotification()) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            return Futures.<Void>immediateFuture(null);
        }
        return super.onUpdateNotificationAsync(session, startInForegroundRequired);
    }

    /**
     * 手机端：系统画中画真的可用时，视频用「自动小窗」代替「后台播 + 通知栏」。
     *
     * <p>分档规则（2026-11-28 老大定）：
     * <ul>
     *   <li>画中画可用 → 忽略「后台播放」开关，视频一律自动进小窗，并且不发通知栏卡片；</li>
     *   <li>画中画不可用（Android 8 以下 / 设备不支持 / 系统里关掉了本 App 的画中画权限）
     *       → 回退原兜底：「后台播放」开关开=后台继续播且保留通知栏卡片，关=退后台暂停；</li>
     *   <li>音频型内容与 TV 端一律返回 false，行为保持原样（音频走音符/有声书通知设置，TV 仍发通知）。</li>
     * </ul>
     */
    private boolean prefersPiPOverNotification() {
        if (Util.isLeanback()) return false;
        return pictureInPictureAvailable();
    }

    /**
     * 系统是否允许本 App 使用画中画。
     *
     * <p>三个条件缺一不可：API ≥ 26、设备声明了画中画特性、AppOps 的 {@code PICTURE_IN_PICTURE}
     * 未被用户关掉（系统设置里关掉会变成 {@code MODE_IGNORED}）。
     * 任何一项查不出来都按「不可用」处理：宁可退回通知栏兜底，也不让视频在后台无声无息地播。
     */
    private boolean pictureInPictureAvailable() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
            Context context = getApplicationContext();
            if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false;
            AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) return false;
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), context.getPackageName());
            return mode != AppOpsManager.MODE_IGNORED && mode != AppOpsManager.MODE_ERRORED;
        } catch (Throwable t) {
            return false;
        }
    }

    private CommandButton buildStopButton() {
        return new CommandButton.Builder(CommandButton.ICON_STOP).setPlayerCommand(Player.COMMAND_STOP).setDisplayName(getString(R.string.play_stop)).build();
    }

    private CommandButton buildRepeatButton() {
        return new CommandButton.Builder(player.isRepeatOne() ? CommandButton.ICON_REPEAT_ONE : CommandButton.ICON_REPEAT_OFF).setSessionCommand(COMMAND_REPEAT).setDisplayName(getString(R.string.play_repeat)).build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-lifecycle", "service startCommand action=%s flags=%d startId=%d %s", intent == null ? null : intent.getAction(), flags, startId, serviceState());
        if (intent != null) handleAction(intent.getAction());
        return super.onStartCommand(intent, flags, startId);
    }

    private void handleAction(String action) {
        if (!isPlayerAvailable()) return;
        if (ActionEvent.PLAY.equals(action)) player.play();
        else if (ActionEvent.PAUSE.equals(action)) player.pause();
        else if (ActionEvent.PREV.equals(action)) dispatchPrev();
        else if (ActionEvent.NEXT.equals(action)) dispatchNext();
        else if (ActionEvent.STOP.equals(action)) dispatchStop();
        else if (ActionEvent.AUDIO.equals(action)) dispatchAudio();
        else if (ActionEvent.REPEAT.equals(action)) dispatchRepeat();
        else if (ActionEvent.REPLAY.equals(action)) dispatchReplay();
    }

    private boolean isLocalBind(Intent intent) {
        return LOCAL_BIND_ACTION.equals(intent != null ? intent.getAction() : null);
    }

    private boolean isExternalBind(Intent intent) {
        return "android.media.browse.MediaBrowserService".equals(intent != null ? intent.getAction() : null);
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-lifecycle", "service bind action=%s local=%s external=%s %s", intent == null ? null : intent.getAction(), isLocalBind(intent), isExternalBind(intent), serviceState());
        if (isLocalBind(intent)) return binder;
        if (isExternalBind(intent)) externalBound = true;
        return super.onBind(intent);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-lifecycle", "service unbind action=%s local=%s external=%s before %s", intent == null ? null : intent.getAction(), isLocalBind(intent), isExternalBind(intent), serviceState());
        if (isExternalBind(intent)) releaseExternal();
        if (isLocalBind(intent)) tryShutdown();
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-lifecycle", "service unbind after %s", serviceState());
        return super.onUnbind(intent);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-lifecycle", "service taskRemoved root=%s %s", rootIntent, serviceState());
        // 最近任务被划掉＝用户退出壳子：
        //   音频型内容 → 例外保留（听歌/听书场景，用户就是要在后台听）；
        //   其余（视频为主）→ 强制关停，不停就不撤前台通知，通知栏会一直挂着"还在播"。
        // 这条路径不看 keepAlive 与导航回调：任务都没了，那两者只可能是指向已销毁页面的残影。
        if (isAudioContentPlaying()) {
            if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-lifecycle", "service taskRemoved keepAudio %s", serviceState());
            return;
        }
        shutdown();
    }

    @Override
    public void onDisconnected(@NonNull MediaSession session, @NonNull MediaSession.ControllerInfo controller) {
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-lifecycle", "service controller disconnected package=%s %s", controller.getPackageName(), serviceState());
        if (controller.getPackageName().equals(getPackageName())) return;
        tryShutdown();
    }

    @Override
    public void onDestroy() {
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-lifecycle", "service destroy before %s", serviceState());
        running = false;
        releaseOriginLease();
        syncAudioHistoryProgress(true);
        clearAudioHistoryRecord();
        player.prepareTerminalRelease();
        PlaybackEventCollector.get().onStop(player);
        if (desktopLyrics != null) desktopLyrics.release();
        releaseSession();
        player.stop();
        player.release();
        // The playback caches are only reclaimable once no player holds them, so the configured
        // total limit converges here. Nothing else marks "playback ended" for both engines.
        CacheScheduler.get().onPlaybackEnded();
        removeForeground();
        Server.get().clearService(this);
        EventBus.getDefault().unregister(this);
        super.onDestroy();
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-lifecycle", "service destroy after running=%s", running);
    }

    private void stopAndClear() {
        syncAudioHistoryProgress(true);
        clearAudioHistoryRecord();
        PlaybackEventCollector.get().onStop(player);
        player.stop();
        player.clearMediaItems();
    }

    public void suspend() {
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-lifecycle", "service suspend %s", serviceState());
        releaseOriginLease();
        stopAndClear();
        removeForeground();
    }

    public void shutdown() {
        if (!running) return;
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-lifecycle", "service shutdown %s", serviceState());
        running = false;
        keepAlive = false;
        releaseOriginLease();
        syncAudioHistoryProgress(true);
        clearAudioHistoryRecord();
        player.prepareTerminalRelease();
        stopAndClear();
        removeForeground();
        stopSelf();
    }

    private void tryShutdown() {
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-lifecycle", "service tryShutdown %s", serviceState());
        if (keepAlive || originLease != null && !originLease.isClosed()) return;
        if (!hasNavigationCallback() && !hasExternalClient()) shutdown();
    }

    private void releaseExternal() {
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-lifecycle", "service releaseExternal before %s", serviceState());
        externalBound = false;
        saveProgress();
        BrowseTree.clear();
        tryShutdown();
        if (SpiderDebug.isEnabled()) SpiderDebug.log("playback-lifecycle", "service releaseExternal after %s", serviceState());
    }

    private void releaseSession() {
        if (session == null) return;
        session.release();
        session = null;
    }

    private void removeForeground() {
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private void saveProgress() {
        if (hasNavigationCallback() || session == null) return;
        if (BrowseTree.saveProgress(player.getPosition(), player.getDuration(), player)) {
            session.notifyChildrenChanged("VOD", 0, null);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (event.isPlayerPerformance()) {
            if (isPlayerAvailable()) player.applyPerformanceSettings();
        } else if (session == null) {
            return;
        } else if (event.isVod()) {
            BrowseTree.clearVod();
            session.notifyChildrenChanged("VOD", 0, null);
        } else if (event.isLive()) {
            BrowseTree.clearLive();
            session.notifyChildrenChanged("LIVE", 0, null);
        }
    }

    @Nullable
    @Override
    public MediaLibrarySession onGetSession(@NonNull MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    @NonNull
    @Override
    public MediaSession.ConnectionResult onConnect(@NonNull MediaSession session, @NonNull MediaSession.ControllerInfo controller) {
        SessionCommands commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon().add(COMMAND_REPEAT).build();
        return new MediaLibrarySession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands).build();
    }

    @NonNull
    @Override
    public ListenableFuture<SessionResult> onCustomCommand(@NonNull MediaSession session, @NonNull MediaSession.ControllerInfo controller, @NonNull SessionCommand customCommand, @NonNull Bundle args) {
        if (COMMAND_REPEAT.customAction.equals(customCommand.customAction)) {
            dispatchRepeat();
            return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
        }
        return MediaLibrarySession.Callback.super.onCustomCommand(session, controller, customCommand, args);
    }

    public boolean hasExternalClient() {
        return externalBound;
    }

    public void setKeepAlive(boolean keepAlive) {
        this.keepAlive = keepAlive;
    }

    public boolean isKeepAlive() {
        return keepAlive;
    }

    public void setAudioHistoryRecord(@Nullable AudioHistory.Record record) {
        if (record == null || !record.canUse()) {
            clearAudioHistoryRecord();
            return;
        }
        boolean changed = audioHistoryRecord == null || !TextUtils.equals(record.trackKey(), audioHistoryRecord.trackKey());
        audioHistoryRecord = record;
        if (changed) {
            savedAudioHistoryTrack = null;
            lastAudioHistorySync = 0;
        }
        updateAudioHistoryForReady();
        scheduleAudioHistorySync();
    }

    public void clearAudioHistoryRecord() {
        App.removeCallbacks(audioHistoryTicker);
        audioHistoryRecord = null;
        savedAudioHistoryTrack = null;
        lastAudioHistorySync = 0;
    }

    public void syncAudioHistoryProgress(boolean force) {
        if (!canSyncAudioHistory()) return;
        updateAudioHistoryForReady();
        long position = player.getPosition();
        long duration = player.getDuration();
        if (position <= 0 || duration <= 0) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastAudioHistorySync < AUDIO_HISTORY_SYNC_INTERVAL) return;
        lastAudioHistorySync = now;
        AudioHistory.syncProgress(audioHistoryRecord, position, duration);
    }

    private boolean canSyncAudioHistory() {
        return audioHistoryRecord != null && player != null && !player.isReleased() && TextUtils.equals(player.getKey(), audioHistoryRecord.playbackKey());
    }

    private void updateAudioHistoryForReady() {
        if (!canSyncAudioHistory() || player.getPlaybackState() != Player.STATE_READY) return;
        String track = audioHistoryRecord.trackKey();
        if (TextUtils.equals(track, savedAudioHistoryTrack)) return;
        savedAudioHistoryTrack = track;
        AudioHistory.saveTrack(audioHistoryRecord, player.getPosition(), player.getDuration());
    }

    private void scheduleAudioHistorySync() {
        App.removeCallbacks(audioHistoryTicker);
        if (canSyncAudioHistory() && player.isPlaying()) App.post(audioHistoryTicker, AUDIO_HISTORY_SYNC_INTERVAL);
    }

    public void setSessionActivity(PendingIntent pendingIntent) {
        if (session != null) session.setSessionActivity(pendingIntent);
    }

    public void setPlaybackForeground(boolean foreground) {
        if (player != null) player.setPlaybackForeground(foreground);
        if (desktopLyrics != null) desktopLyrics.setForeground(foreground);
    }

    public void setDesktopLyricsAudioContent(boolean audioContent) {
        if (desktopLyrics != null) desktopLyrics.setAudioContent(audioContent);
    }

    public void setDesktopLyricsSnapshot(LyricsResult result, List<LyricsLine> lines) {
        if (desktopLyrics != null) desktopLyrics.setLyricsSnapshot(result, lines);
    }

    public void resetSessionActivity() {
        setSessionActivity(buildDefaultIntent());
    }

    public void setNavigationCallback(NavigationCallback navigationCallback, String key) {
        this.navigationCallback = navigationCallback;
        this.navigationKey = key;
    }

    public void clearNavigationCallback(NavigationCallback expected) {
        if (navigationCallback != expected) return;
        setNavigationCallback(null, null);
    }

    /**
     * 导航归属：本服务记录的 {@link #navigationKey} 是否就是当前播放会话。
     *
     * <p>自动连播时服务自己用「浏览下一集」的 {@code VE:...} 键起播，而 {@code navigationKey}
     * 仍是页面写入的记录键；两者字符串不等但同属一次会话。旧实现按字符串判假，后果是下一次
     * ENDED 被当成「无主」事件，服务直接再 {@code navigateItem(1)} 跳一集，连播变成连跳。
     */
    private boolean isNavigationOwner() {
        return isPlayerAvailable() && PlaybackOwnershipKeys.sameSession(navigationKey, player.getKey());
    }

    public void addPlayerCallback(PlayerCallback callback) {
        playerCallbacks.add(callback);
    }

    public void removePlayerCallback(PlayerCallback callback) {
        playerCallbacks.remove(callback);
    }

    public boolean hasPlayerCallback() {
        return !playerCallbacks.isEmpty();
    }

    public void dispatchPrev() {
        dispatchNavigate(NavigationCallback::onPrev, -1);
    }

    public void dispatchNext() {
        dispatchNavigate(NavigationCallback::onNext, 1);
    }

    private void dispatchNavigate(Consumer<NavigationCallback> action, int delta) {
        if (!isPlayerAvailable()) return;
        if (hasNavigationCallback() && isNavigationOwner()) dispatch(action);
        else navigateItem(delta);
    }

    public void dispatchStop() {
        if (!isPlayerAvailable()) return;
        if (player.getPlaybackState() == Player.STATE_IDLE) return;
        if (hasNavigationCallback() && isNavigationOwner()) dispatch(NavigationCallback::onStop);
        else stopAndClear();
    }

    public void dispatchRepeat() {
        if (!isPlayerAvailable()) return;
        player.setRepeatOne(!player.isRepeatOne());
    }

    public void dispatchReplay() {
        if (!isPlayerAvailable()) return;
        if (hasNavigationCallback() && isNavigationOwner()) dispatch(NavigationCallback::onReplay);
        else {
            player.seekTo(0);
            player.play();
        }
    }

    public void dispatchAudio() {
        if (!isPlayerAvailable()) return;
        dispatch(NavigationCallback::onAudio);
    }

    private void dispatch(Consumer<NavigationCallback> action) {
        NavigationCallback callback = navigationCallback;
        if (callback != null) App.post(() -> {
            if (navigationCallback == callback) action.accept(callback);
        });
    }

    private void navigateItem(int delta) {
        MediaItem current = player.getCurrentMediaItem();
        if (current == null) return;
        Task.submit(() -> {
            try {
                MediaItem next = BrowseTree.navigate(current.mediaId, delta);
                if (next == null || next.localConfiguration == null) return;
                Result result = BrowseTree.consumeBrowseResult(next.mediaId);
                if (result == null || !isRunning()) return;
                App.post(() -> startBrowse(next, result, 0));
            } catch (Exception ignored) {
            }
        });
    }

    private boolean isSameItem(MediaItem item) {
        if (item == null || item.localConfiguration == null) return false;
        return item.localConfiguration.uri.toString().equals(player.getUrl());
    }

    private void interceptItem(@NonNull MediaItem item, long startPositionMs) {
        if (isSameItem(item)) return;
        playViaManager(item, startPositionMs);
    }

    private void interceptItems(@NonNull List<MediaItem> items, int startIndex, long startPositionMs) {
        if (items.isEmpty()) return;
        int idx = (startIndex >= 0 && startIndex < items.size()) ? startIndex : 0;
        interceptItem(items.get(idx), startPositionMs > 0 ? startPositionMs : 0);
    }

    private ForwardingPlayer wrap(Player base) {
        return new ForwardingPlayer(base) {
            @Override
            public void setMediaItem(@NonNull MediaItem item) {
                interceptItem(item, 0);
            }

            @Override
            public void setMediaItem(@NonNull MediaItem item, boolean resetPosition) {
                interceptItem(item, 0);
            }

            @Override
            public void setMediaItem(@NonNull MediaItem item, long startPositionMs) {
                interceptItem(item, startPositionMs);
            }

            @Override
            public void setMediaItems(@NonNull List<MediaItem> items) {
                interceptItems(items, 0, 0);
            }

            @Override
            public void setMediaItems(@NonNull List<MediaItem> items, boolean resetPosition) {
                interceptItems(items, 0, 0);
            }

            @Override
            public void setMediaItems(@NonNull List<MediaItem> items, int startIndex, long startPositionMs) {
                interceptItems(items, startIndex, startPositionMs);
            }

            @Override
            public void seekToPrevious() {
                dispatchPrev();
            }

            @Override
            public void seekToPreviousMediaItem() {
                dispatchPrev();
            }

            @Override
            public void seekToNext() {
                dispatchNext();
            }

            @Override
            public void seekToNextMediaItem() {
                dispatchNext();
            }

            @Override
            public void stop() {
                // Standard MediaSession STOP controls playback only. Activity navigation is reserved for
                // explicit in-app exit actions so stray system, remote, or Bluetooth STOP events cannot
                // close the playback screen.
                if (!isPlayerAvailable() || player.getPlaybackState() == Player.STATE_IDLE) return;
                stopAndClear();
                Runnable hook = onPlaybackStopped;
                if (hook != null) hook.run();
            }

            @NonNull
            @Override
            public Commands getAvailableCommands() {
                return super.getAvailableCommands().buildUpon().add(COMMAND_SEEK_TO_PREVIOUS).add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).add(COMMAND_SEEK_TO_NEXT).add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).add(COMMAND_SEEK_BACK).add(COMMAND_SEEK_FORWARD).add(COMMAND_STOP).add(COMMAND_SET_REPEAT_MODE).build();
            }
        };
    }

    private void playViaManager(MediaItem item, long startPositionMs) {
        if (item == null || item.localConfiguration == null) return;
        Result result = BrowseTree.consumeBrowseResult(item.mediaId);
        if (result != null) startBrowse(item, result, startPositionMs);
    }

    private void startBrowse(MediaItem item, Result result, long startPositionMs) {
        player.browse(PlaySpec.from(result, item.mediaId, item.mediaMetadata));
        if (startPositionMs > 0) player.seekTo(startPositionMs);
    }

    @Override
    public void onPrepare() {
        if (desktopLyrics != null) desktopLyrics.refresh(player);
        playerCallbacks.forEach(PlayerCallback::onPrepare);
    }

    @Override
    public void onTracksChanged() {
        if (desktopLyrics != null) desktopLyrics.refresh(player);
        playerCallbacks.forEach(PlayerCallback::onTracksChanged);
    }

    @Override
    public void onTitlesChanged() {
        if (desktopLyrics != null) desktopLyrics.refresh(player);
        playerCallbacks.forEach(PlayerCallback::onTitlesChanged);
    }

    @Override
    public boolean onSourceHttpError(int statusCode, String msg) {
        for (PlayerCallback callback : playerCallbacks) {
            if (callback.onSourceHttpError(statusCode, msg)) return true;
        }
        return false;
    }

    @Override
    public void onError(String msg) {
        playerCallbacks.forEach(callback -> callback.onError(msg));
    }

    @Override
    public void onReload(String msg) {
        playerCallbacks.forEach(callback -> callback.onReload(msg));
    }

    @Override
    public void onPlayerRenderRequired() {
        playerCallbacks.forEach(PlayerCallback::onPlayerRenderRequired);
    }

    @Override
    public void onPlayerOutputPending() {
        playerCallbacks.forEach(PlayerCallback::onPlayerOutputPending);
    }

    @Override
    public void onPlayerOutputReady() {
        playerCallbacks.forEach(PlayerCallback::onPlayerOutputReady);
    }

    @Override
    public void onExoFirstFrame() {
        playerCallbacks.forEach(PlayerCallback::onExoFirstFrame);
    }

    @Override
    public void onSubtitleSelected(Sub sub) {
        playerCallbacks.forEach(callback -> callback.onSubtitleSelected(sub));
    }

    @Override
    public void onPlayerRebuild(Player newPlayer, boolean resetVideoSurface) {
        exoPlayer.removeListener(listener);
        exoPlayer = newPlayer;
        exoPlayer.addListener(listener);
        PlaybackEventCollector.get().setPlayer(player);
        if (session != null) session.setPlayer(wrap(newPlayer));
        playerCallbacks.forEach(callback -> callback.onPlayerRebuild(newPlayer, resetVideoSurface));
    }

    private final Player.Listener listener = new Player.Listener() {
        @Override
public void onIsPlayingChanged(boolean isPlaying) {
            PlaybackEventCollector.get().onIsPlayingChanged(player, isPlaying);
            if (desktopLyrics != null) desktopLyrics.update(player);
            if (isPlaying) scheduleAudioHistorySync();
            else syncAudioHistoryProgress(true);
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            PlaybackEventCollector.get().onPlaybackStateChanged(player, state);
            if (desktopLyrics != null) desktopLyrics.update(player);
            if (state == Player.STATE_READY) {
                App.post(() -> releaseOriginIfMediaReplaced());
                updateAudioHistoryForReady();
                scheduleAudioHistorySync();
            }
            if (state == Player.STATE_ENDED) {
                syncAudioHistoryProgress(true);
                boolean ownerHandlesNavigation = hasNavigationCallback() && isNavigationOwner();
                if (SpiderDebug.isEnabled()) SpiderDebug.log("audio-auto-next", "service ended owner=%s navigation=%s key=%s navigationKey=%s action=%s", isNavigationOwner(), hasNavigationCallback(), player.getKey(), navigationKey, ownerHandlesNavigation ? "defer-to-owner" : "browse-next");
                if (!ownerHandlesNavigation) navigateItem(1);
            }
        }

        @Override
        public void onRepeatModeChanged(int repeatMode) {
            if (session != null) session.setMediaButtonPreferences(ImmutableList.of(buildRepeatButton(), buildStopButton()));
        }
    };

    @NonNull
    @Override
    public ListenableFuture<LibraryResult<MediaItem>> onGetLibraryRoot(@NonNull MediaLibrarySession session, @NonNull MediaSession.ControllerInfo browser, @Nullable MediaLibraryService.LibraryParams params) {
        return Futures.immediateFuture(LibraryResult.ofItem(BrowseTree.getRootItem(), params));
    }

    @NonNull
    @Override
    public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetChildren(@NonNull MediaLibrarySession session, @NonNull MediaSession.ControllerInfo browser, @NonNull String parentId, int page, int pageSize, @Nullable MediaLibraryService.LibraryParams params) {
        return Task.executor().submit(() -> LibraryResult.ofItemList(BrowseTree.getChildren(parentId), params));
    }

    @NonNull
    @Override
    public ListenableFuture<LibraryResult<Void>> onSearch(@NonNull MediaLibrarySession session, @NonNull MediaSession.ControllerInfo browser, @NonNull String query, @Nullable MediaLibraryService.LibraryParams params) {
        Task.execute(() -> {
            ImmutableList<MediaItem> results = BrowseTree.search(query);
            App.post(() -> session.notifySearchResultChanged(browser, query, results.size(), params));
        });
        return Futures.immediateFuture(LibraryResult.ofVoid(params));
    }

    @NonNull
    @Override
    public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetSearchResult(@NonNull MediaLibrarySession session, @NonNull MediaSession.ControllerInfo browser, @NonNull String query, int page, int pageSize, @Nullable MediaLibraryService.LibraryParams params) {
        return Futures.immediateFuture(LibraryResult.ofItemList(BrowseTree.getSearchResult(), params));
    }

    @NonNull
    @Override
    public ListenableFuture<LibraryResult<MediaItem>> onGetItem(@NonNull MediaLibrarySession session, @NonNull MediaSession.ControllerInfo browser, @NonNull String mediaId) {
        MediaItem item = BrowseTree.getItem(mediaId);
        return Futures.immediateFuture(item != null ? LibraryResult.ofItem(item, null) : LibraryResult.ofError(SessionError.ERROR_BAD_VALUE));
    }

    @NonNull
    @Override
    public ListenableFuture<MediaSession.MediaItemsWithStartPosition> onSetMediaItems(@NonNull MediaSession session, @NonNull MediaSession.ControllerInfo controller, @NonNull List<MediaItem> mediaItems, int startIndex, long startPositionMs) {
        saveProgress();
        return Task.executor().submit(() -> {
            List<MediaItem> resolved = mediaItems.stream().map(BrowseTree::resolveOrKeep).toList();
            int index = resolved.isEmpty() ? 0 : Math.min(Math.max(startIndex, 0), resolved.size() - 1);
            long position = BrowseTree.consumeResumePosition();
            return new MediaSession.MediaItemsWithStartPosition(resolved, index, position);
        });
    }

    public interface PlayerCallback {

        default void onPrepare() {
        }

        default void onTracksChanged() {
        }

        default void onTitlesChanged() {
        }

        default boolean onSourceHttpError(int statusCode, String msg) {
            return false;
        }

        default void onError(String msg) {
        }

        default void onReload(String msg) {
        }

        default void onPlayerRenderRequired() {
        }

        default void onPlayerOutputPending() {
        }

        default void onPlayerOutputReady() {
        }

        default void onExoFirstFrame() {
        }

        default void onSubtitleSelected(Sub sub) {
        }

        default void onPlayerRebuild(Player player, boolean resetVideoSurface) {
        }
    }

    public interface NavigationCallback {

        default void onPrev() {
        }

        default void onNext() {
        }

        default void onStop() {
        }

        default void onReplay() {
        }

        default void onAudio() {
        }
    }

    public class LocalBinder extends Binder {

        public PlaybackService getService() {
            return PlaybackService.this;
        }
    }
}
