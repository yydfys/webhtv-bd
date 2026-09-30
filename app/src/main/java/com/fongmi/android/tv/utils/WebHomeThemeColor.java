package com.fongmi.android.tv.utils;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 手机版「影视原生」模式详情页/播放页的背景色来源。
 *
 * 由 HomeWebController 在首页（HTML 主题页）加载后持续注入探针 JS（{@link #PROBE_JS}）：
 * meta[name=theme-color] → 可见容器 getComputedStyle().backgroundColor → 常见 CSS 变量，
 * 采到的主题色经 {@link #update(String)} 缓存（并落盘一次），供 VideoActivity 铺实色底。
 *
 * 采不到色时 {@link #getColor()} 返回 null，调用方保持原有表现（零回归）。
 */
public class WebHomeThemeColor {

    /** 探针脚本：返回主题色的 CSS 颜色字符串（采不到返回空串）。 */
    public static final String PROBE_JS = "(function(){try{"
            + "function ok(v){if(!v)return false;v=(''+v).toLowerCase().trim();"
            + "if(v===''||v==='transparent'||v==='none')return false;"
            + "if(v.indexOf('rgba(')===0){var p=v.substring(5,v.length-1).split(',');"
            + "if(p.length>3&&parseFloat(p[3])<=0.02)return false;}return true;}"
            + "var c='',m=document.querySelector('meta[name=\"theme-color\"]');"
            + "if(m){var v=m.getAttribute('content');if(v&&ok(v))c=(''+v).trim();}"
            + "if(!c){var s=['body','#app','#root','.app','.wrapper','.container','main'];"
            + "for(var i=0;i<s.length;i++){var e=document.querySelector(s[i]);if(!e)continue;"
            + "var b=getComputedStyle(e).backgroundColor;if(ok(b)){c=b;break;}}}"
            + "if(!c){var r=document.documentElement;if(r){var rs=getComputedStyle(r);"
            + "var k=['--body-bg','--bg','--background','--color-bg','--page-bg','--theme-color'];"
            + "for(var j=0;j<k.length;j++){var x=(rs.getPropertyValue(k[j])||'').trim();if(ok(x)){c=x;break;}}}}"
            + "return c||'';}catch(e){return '';}})()";

    private static final String PREF = "web_home_theme_color";
    private static final String KEY = "color";

    private static volatile int sColor = 0;
    private static SharedPreferences sPrefs;

    public static void init(Context context) {
        if (context == null || sPrefs != null) return;
        try {
            sPrefs = context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
            if (sColor == 0) sColor = sPrefs.getInt(KEY, 0);
        } catch (Throwable ignored) {
        }
    }

    /** 探针回调（evaluateJavascript 返回的是 JSON 字符串字面量）。 */
    public static void update(String raw) {
        int color = parse(unquote(raw));
        if (color == 0) return;
        sColor = color;
        try {
            if (sPrefs != null) sPrefs.edit().putInt(KEY, color).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 采不到色返回 null。 */
    public static Integer getColor() {
        int color = sColor;
        return color == 0 ? null : color;
    }

    private static String unquote(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return s;
    }

    /** 解析 CSS 颜色 → ARGB（alpha 一律取满，保证是实色底）。失败返回 0。 */
    private static int parse(String s) {
        if (s == null) return 0;
        s = s.trim().toLowerCase();
        if (s.isEmpty() || s.equals("transparent") || s.equals("none")) return 0;
        try {
            if (s.startsWith("#")) {
                String h = s.substring(1);
                if (h.length() == 3) h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
                if (h.length() == 6) return 0xFF000000 | (int) Long.parseLong(h, 16);
                if (h.length() == 8) {
                    long v = Long.parseLong(h, 16);
                    int alpha = (int) (v & 0xFFL);          // CSS 顺序 rrggbbaa
                    if (alpha == 0) return 0;
                    return 0xFF000000 | (int) (v >>> 8);
                }
                return 0;
            }
            if (s.startsWith("rgb")) {
                int a = s.indexOf('('), b = s.indexOf(')');
                if (a < 0 || b <= a) return 0;
                String[] p = s.substring(a + 1, b).split(",");
                if (p.length < 3) return 0;
                int r = clamp(Float.parseFloat(p[0].trim()));
                int g = clamp(Float.parseFloat(p[1].trim()));
                int bl = clamp(Float.parseFloat(p[2].trim()));
                int al = 255;
                if (p.length >= 4) {
                    float f = Float.parseFloat(p[3].trim());
                    al = clamp(f <= 1f ? f * 255f : f);
                    if (al == 0) return 0;
                }
                return 0xFF000000 | (r << 16) | (g << 8) | bl;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static int clamp(float v) {
        int i = (int) v;
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }
}
