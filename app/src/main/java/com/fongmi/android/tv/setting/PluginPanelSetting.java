package com.fongmi.android.tv.setting;

import com.github.catvod.utils.Prefers;

/**
 * 插件面板的弹出方式（只对手机端生效，电视端恒为右侧滑出）。
 *
 * <p>手机竖屏屏幕窄，右侧滑出的面板最多只有屏幕 2/3 宽，插件返回的 html 里若含视频（如预告片），
 * 播放器会被挤成一条窄带。改成从底部向上滑出、左右铺满后，可用宽度直接翻倍。默认走底部，
 * 想换回侧滑可在「设置 → 增强功能 → 插件管理」里关掉。
 */
public final class PluginPanelSetting {

    private static final String KEY_BOTTOM_SHEET = "plugin_panel_bottom_sheet";

    public static boolean isBottomSheet() {
        return Prefers.getBoolean(KEY_BOTTOM_SHEET, true);
    }

    public static void putBottomSheet(boolean bottom) {
        Prefers.put(KEY_BOTTOM_SHEET, bottom);
    }

    private PluginPanelSetting() {
    }
}
