package com.fongmi.android.tv.lab;

import android.content.SharedPreferences;

import com.fongmi.android.tv.App;

import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 终端显示偏好（照抄 VodPlus 实验室终端的做法）：**自动滚动 / 自动换行**两个开关，
 * 存全局 SharedPreferences，并广播给所有已打开的终端窗口 —— 一个窗口切换，其它窗口立刻同步。
 *
 * <p>{@code auto_scroll}：开启时新输出始终把视口拉到最新（跟随日志），关闭后停在当前位置方便回看。
 * <p>{@code auto_wrap}：开启时长行折行显示，关闭后长行不折、可横向拖动查看（适合看宽表格/JSON）。
 */
public final class LabTerminalPrefs {

    private static final String PREFS = "lab_terminal_preferences";
    private static final String KEY_SCROLL = "auto_scroll";
    private static final String KEY_WRAP = "auto_wrap";

    private static final CopyOnWriteArraySet<Listener> LISTENERS = new CopyOnWriteArraySet<>();

    private LabTerminalPrefs() {
    }

    public interface Listener {
        void onTerminalPrefsChanged();
    }

    private static SharedPreferences prefs() {
        return App.get().getSharedPreferences(PREFS, 0);
    }

    /** 默认开启：跟 VodPlus 一致（auto_scroll / auto_wrap 默认 true）。 */
    public static boolean autoScroll() {
        return prefs().getBoolean(KEY_SCROLL, true);
    }

    public static boolean autoWrap() {
        return prefs().getBoolean(KEY_WRAP, true);
    }

    public static void setAutoScroll(boolean value) {
        prefs().edit().putBoolean(KEY_SCROLL, value).apply();
        broadcast();
    }

    public static void setAutoWrap(boolean value) {
        prefs().edit().putBoolean(KEY_WRAP, value).apply();
        broadcast();
    }

    public static void toggleAutoScroll() {
        setAutoScroll(!autoScroll());
    }

    public static void toggleAutoWrap() {
        setAutoWrap(!autoWrap());
    }

    public static void addListener(Listener listener) {
        if (listener != null) LISTENERS.add(listener);
    }

    public static void removeListener(Listener listener) {
        if (listener != null) LISTENERS.remove(listener);
    }

    private static void broadcast() {
        for (Listener listener : LISTENERS) {
            listener.onTerminalPrefsChanged();
        }
    }
}
