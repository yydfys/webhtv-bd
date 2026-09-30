package com.fongmi.android.tv.utils;

import com.github.catvod.utils.Prefers;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 记录 WebUI（HTML 主题）当前实际生效的基色，供「影视原生模式」详情页背景跟随主题。
 * 仅手机版参与，TV 版不受影响。
 */
public final class WebThemeAppearance {

    private static final String KEY_COLOR = "web_theme_base_color";
    private static final String KEY_DARK = "web_theme_base_dark";

    private static final String[] BACKGROUND_KEYS = {
            "var:--body-bg", "var:--bg", "var:--background", "var:--color-bg",
            "var:--page-bg", "var:--theme-color", "var:--accent-color"
    };

    private WebThemeAppearance() {
    }

    /** 手机版且已有主题色时才生效。 */
    public static boolean isActive() {
        return Util.isMobile() && hasColor();
    }

    public static boolean hasColor() {
        return getColor() != 0;
    }

    public static int getColor() {
        return Prefers.getInt(KEY_COLOR, 0);
    }

    public static boolean isDark() {
        return Prefers.getInt(KEY_DARK, 1) == 1;
    }

    /** 主题上报入口（JS payload）：优先 color，其次常见 CSS 变量名；解析失败保留上一次的值。 */
    public static void applyPayload(JsonObject payload) {
        if (payload == null) return;
        String background = str(payload, "color");
        if (parse(background) < 0) {
            for (String key : BACKGROUND_KEYS) {
                String value = str(payload, key);
                if (parse(value) >= 0) {
                    background = value;
                    break;
                }
            }
        }
        apply(background, str(payload, "text"));
    }

    /** 主题上报入口：background / text 为 CSS 颜色串，解析失败时保留上一次的值。 */
    public static void apply(String background, String text) {
        int color = parse(background);
        if (color < 0) return;
        int foreground = parse(text);
        boolean dark = foreground >= 0 ? luminance(foreground) > 0.55f : luminance(color) < 0.5f;
        Prefers.put(KEY_COLOR, 0xFF000000 | color);
        Prefers.put(KEY_DARK, dark ? 1 : 0);
    }

    /** 解析 CSS 颜色（#rgb / #rrggbb / rgb() / rgba()），无效或全透明返回 -1。 */
    public static int parse(String value) {
        if (value == null) return -1;
        String text = value.trim().toLowerCase();
        if (text.isEmpty() || text.startsWith("var(") || text.startsWith("url(")) return -1;
        if ("transparent".equals(text) || "none".equals(text) || "inherit".equals(text) || "initial".equals(text) || "currentcolor".equals(text) || "unset".equals(text)) return -1;
        if (text.startsWith("#")) return parseHex(text.substring(1));
        if (text.startsWith("rgb")) return parseRgb(text);
        return -1;
    }

    private static String str(JsonObject payload, String key) {
        JsonElement element = payload.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return "";
        try {
            return element.getAsString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static int parseHex(String hex) {
        if (hex.length() == 3) hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
        if (hex.length() != 6) return -1;
        try {
            return (int) (Long.parseLong(hex, 16) & 0xFFFFFFL);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int parseRgb(String text) {
        int start = text.indexOf('(');
        int end = text.indexOf(')');
        if (start < 0 || end < start) return -1;
        String[] parts = text.substring(start + 1, end).split(",");
        if (parts.length < 3) return -1;
        if (parts.length > 3 && alpha(parts[3]) <= 0.02f) return -1;
        int r = channel(parts[0]);
        int g = channel(parts[1]);
        int b = channel(parts[2]);
        if (r < 0 || g < 0 || b < 0) return -1;
        return (r << 16) | (g << 8) | b;
    }

    private static int channel(String value) {
        String text = value.trim();
        if (text.isEmpty()) return -1;
        try {
            if (text.endsWith("%")) return clamp(Math.round(Float.parseFloat(text.substring(0, text.length() - 1)) * 255f / 100f));
            return clamp(Integer.parseInt(text));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static float alpha(String value) {
        String text = value.trim();
        try {
            return text.endsWith("%") ? Float.parseFloat(text.substring(0, text.length() - 1)) / 100f : Float.parseFloat(text);
        } catch (NumberFormatException e) {
            return 1f;
        }
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static float luminance(int rgb) {
        return (((rgb >> 16) & 0xFF) * 0.299f + ((rgb >> 8) & 0xFF) * 0.587f + (rgb & 0xFF) * 0.114f) / 255f;
    }
}
