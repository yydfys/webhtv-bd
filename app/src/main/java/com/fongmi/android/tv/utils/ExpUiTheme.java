package com.fongmi.android.tv.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 读取「实验替代UI源」(exp_ui / pro.jar) 在 HTML 主题页里写入的
 * SharedPreferences("exp_ui_preferences")，由壳子(App)侧还原同一套「主题色系」背景：
 * 实色底 + 光晕(radial halo)，用于影视原生模式的详情页 / 播放页。
 *
 * <p>写入方(pro.jar ExperimentUiSpider.saveUserPreference)与读取方(本类)同进程共享
 * 同一个 SharedPreferences 文件，因此用户切一次 HTML 主题色系后，这里立刻能读到。</p>
 *
 * <p>刻意不做兜底：没有 bg_color 就返回 null，调用方保持原样。</p>
 */
public class ExpUiTheme {

    /** aa.json 里该源站点的 key */
    public static final String SITE_KEY = "exp_ui";

    private static final String PREF_NAME = "exp_ui_preferences";
    private static final String KEY_THEME = "theme_key";
    private static final String KEY_BG = "bg_color";
    private static final String KEY_SPOTS = "spots_json";
    private static final String KEY_HALO = "halo_running";

    private static final int DEFAULT_SPOT_COLOR = 0xFF64FFDA;

    private ExpUiTheme() {
    }

    /** 当前站点是不是「实验替代UI源」 */
    public static boolean isSite(String key) {
        return key != null && SITE_KEY.equalsIgnoreCase(key.trim());
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /** 用户是否已经在主题页切过一次（有 bg_color 才算） */
    public static boolean isActive(Context context) {
        try {
            return prefs(context).contains(KEY_BG);
        } catch (Throwable e) {
            return false;
        }
    }

    /** 主题实色底；没有则 0 */
    public static int baseColor(Context context) {
        try {
            return parseColor(prefs(context).getString(KEY_BG, null));
        } catch (Throwable e) {
            return 0;
        }
    }

    /**
     * 生成一块「主题色系」背景。没有主题色时返回 null —— 调用方什么都不做（不兜底）。
     */
    public static Drawable background(Context context) {
        try {
            SharedPreferences sp = prefs(context);
            if (!sp.contains(KEY_BG)) return null;
            int base = parseColor(sp.getString(KEY_BG, null));
            if (base == 0) return null;
            boolean halo = sp.getBoolean(KEY_HALO, true);
            List<float[]> spots = parseSpots(sp.getString(KEY_SPOTS, null));
            if (!halo || spots.isEmpty()) return new SolidDrawable(base);
            return new HaloDrawable(base, spots, isPureWhite(sp.getString(KEY_THEME, null), base));
        } catch (Throwable e) {
            return null;
        }
    }

    private static boolean isPureWhite(String themeKey, int base) {
        if (themeKey != null && "purewhite".equalsIgnoreCase(themeKey.trim())) return true;
        return Color.red(base) > 200 && Color.green(base) > 200 && Color.blue(base) > 200;
    }

    private static int parseColor(String hex) {
        if (TextUtils.isEmpty(hex)) return 0;
        try {
            return Color.parseColor(hex.trim());
        } catch (Throwable e) {
            return 0;
        }
    }

    private static List<float[]> parseSpots(String json) {
        List<float[]> list = new ArrayList<>();
        if (TextUtils.isEmpty(json)) return list;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                float x = (float) item.optDouble("x", 0.5d);
                float y = (float) item.optDouble("y", 0.5d);
                int color = parseRgb(item.optString("c", null));
                list.add(new float[]{x, y, color});
            }
        } catch (Throwable ignored) {
        }
        return list;
    }

    private static int parseRgb(String value) {
        if (TextUtils.isEmpty(value)) return DEFAULT_SPOT_COLOR;
        try {
            String[] parts = value.split(",");
            if (parts.length < 3) return DEFAULT_SPOT_COLOR;
            return Color.rgb(clamp(Integer.parseInt(parts[0].trim())), clamp(Integer.parseInt(parts[1].trim())), clamp(Integer.parseInt(parts[2].trim())));
        } catch (Throwable e) {
            return DEFAULT_SPOT_COLOR;
        }
    }

    private static int clamp(int value) {
        return value < 0 ? 0 : (value > 255 ? 255 : value);
    }

    /** 纯实色底（光晕关闭 / 没有光晕点时使用） */
    public static class SolidDrawable extends Drawable {

        private final Paint paint = new Paint();

        public SolidDrawable(int color) {
            paint.setColor(color);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.isEmpty()) return;
            canvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.bottom, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            paint.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }

    /**
     * 主题色系背景：实色底 + 多个径向光晕。
     * 算法与 pro.jar(ExperimentUiSpider.DynamicHaloDrawable) 保持一致的静态相位。
     */
    public static class HaloDrawable extends Drawable {

        private final Paint solid = new Paint();
        private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final Matrix matrix = new Matrix();
        private final List<float[]> spots;
        private final RadialGradient[] gradients;
        private final boolean pureWhite;

        public HaloDrawable(int base, List<float[]> spots, boolean pureWhite) {
            this.spots = spots;
            this.pureWhite = pureWhite;
            solid.setColor(base);
            halo.setAlpha(190);
            gradients = new RadialGradient[spots.size()];
            for (int i = 0; i < spots.size(); i++) {
                gradients[i] = new RadialGradient(0f, 0f, 1f, new int[]{(int) spots.get(i)[2], 0x00000000}, new float[]{0f, 0.8f}, Shader.TileMode.CLAMP);
            }
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.isEmpty()) return;
            if (pureWhite) {
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                paint.setShader(new LinearGradient(0f, bounds.top, 0f, bounds.bottom, 0xFF4A505B, 0xFF32373F, Shader.TileMode.CLAMP));
                canvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.bottom, paint);
                return;
            }
            canvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.bottom, solid);
            int save = canvas.save();
            canvas.clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom);
            float width = bounds.width();
            float height = bounds.height();
            float min = Math.min(width, height) * 0.95f;
            for (int i = 0; i < spots.size(); i++) {
                float[] spot = spots.get(i);
                float index = i;
                float dx = (float) Math.sin(index * 1.3f) * 0.14f;
                float dy = (float) Math.cos(index * 0.8f) * 0.12f;
                float cx = bounds.left + (spot[0] + dx) * width;
                float cy = bounds.top + (spot[1] + dy) * height;
                float radius = ((float) Math.sin(index) * 0.15f + 0.95f) * min;
                if (radius < 1f) radius = 1f;
                matrix.reset();
                matrix.setTranslate(cx, cy);
                matrix.postScale(radius, radius);
                gradients[i].setLocalMatrix(matrix);
                halo.setShader(gradients[i]);
                canvas.drawCircle(cx, cy, radius, halo);
            }
            canvas.restoreToCount(save);
        }

        @Override
        public void setAlpha(int alpha) {
            /* 背景整体透明度不参与主题色系渲染 */
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }
}
