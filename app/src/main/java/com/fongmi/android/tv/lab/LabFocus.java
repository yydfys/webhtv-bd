package com.fongmi.android.tv.lab;

import android.content.Context;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.Util;

import java.util.ArrayList;
import java.util.List;

/**
 * 实验室（lab）界面在电视端的遥控器焦点支持。
 * 所有方法在手机端都是空操作，保证手机版的行为与外观完全不变。
 */
public final class LabFocus {

    private static final int FOCUS_COLOR = 0xFF2F6FED;

    private LabFocus() {
    }

    /** 是否电视端（遥控器） */
    public static boolean tv() {
        return Util.isLeanback();
    }

    /** 让控件可被遥控器选中，并给出可见的焦点反馈 */
    public static void enable(View... views) {
        if (!tv()) return;
        for (View view : views) {
            if (view == null) continue;
            view.setFocusable(true);
            view.setFocusableInTouchMode(true);
        }
    }

    /** 依次挑第一个「可见且可用」的控件并请求焦点（跳过隐藏/禁用项，避免焦点落在看不见的按钮上） */
    public static void firstShown(View... views) {
        if (!tv() || views == null) return;
        View fallback = null;
        for (View view : views) {
            if (view == null) continue;
            if (fallback == null) fallback = view;
            if (view.isShown() && view.isEnabled()) {
                view.requestFocus();
                return;
            }
        }
        // 全部隐藏/禁用时的兜底：随便给一个控件请求焦点，保证面板里至少有一个焦点位
        if (fallback != null) fallback.requestFocus();
    }

    /** 工具栏图标按钮：换成带蓝色焦点环的背景（手机端保留原涟漪背景） */
    public static void toolbarButton(View... views) {
        if (!tv()) return;
        for (View view : views) {
            if (view == null) continue;
            view.setBackgroundResource(R.drawable.selector_lab_tool_btn);
            enable(view);
        }
    }

    /** 命令卡片 / 列表开关：换成带蓝色焦点环的背景（手机端保留原背景） */
    public static void commandCard(View... views) {
        // 所有环境下命令条都要能被遥控器/键盘选中；手机端触摸点击不受影响
        for (View view : views) {
            if (view != null) view.setFocusable(true);
        }
        if (!tv()) return;
        for (View view : views) {
            if (view == null) continue;
            view.setBackgroundResource(R.drawable.selector_lab_command_card);
            enable(view);
        }
    }

    /** 让 parent 内第一个子项拿到焦点（优先子项里的 targetId 控件，如命令行的「运行」按钮） */
    public static void focusFirstChild(ViewGroup parent, int targetId) {
        if (!tv() || parent == null || parent.getChildCount() == 0) return;
        View child = parent.getChildAt(0);
        View target = targetId == 0 ? null : child.findViewById(targetId);
        (target != null ? target : child).requestFocus();
    }

    /** 数据异步送达时也能落到第一个命令行的「运行」按钮（最多重试 6 次） */
    public static void focusFirstChildWhenReady(ViewGroup parent, int targetId) {
        focusFirstChildWhenReady(parent, targetId, 0);
    }

    private static void focusFirstChildWhenReady(ViewGroup parent, int targetId, int attempt) {
        if (!tv() || parent == null) return;
        parent.post(() -> {
            if (parent.getChildCount() > 0) focusFirstChild(parent, targetId);
            else if (attempt < 6) parent.postDelayed(() -> focusFirstChildWhenReady(parent, targetId, attempt + 1), 200);
        });
    }

    /** 弹窗按钮（取消/确定等）：电视端可聚焦 + 蓝色焦点反馈 + 面板底部可下移到按钮 */
    public static void fixDialogButtons(AlertDialog dialog, boolean focusNegative) {
        if (!tv() || dialog == null) return;
        View negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        View neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        View positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        styleButton(negative);
        styleButton(neutral);
        styleButton(positive);
        View bar = negative != null ? negative : (positive != null ? positive : neutral);
        if (bar == null) return;
        View decor = dialog.getWindow() != null ? dialog.getWindow().getDecorView() : null;
        if (decor instanceof ViewGroup) wireDownToBar((ViewGroup) decor, bar);
        if (focusNegative) bar.requestFocus();
    }

    public static void styleButton(View view) {
        if (view == null || !tv()) return;
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        final ColorStateList tint = view.getBackgroundTintList();
        final ColorStateList text = view instanceof TextView ? ((TextView) view).getTextColors() : null;
        view.setOnFocusChangeListener((v, hasFocus) -> {
            try {
                if (tint != null) v.setBackgroundTintList(hasFocus ? ColorStateList.valueOf(FOCUS_COLOR) : tint);
            } catch (Throwable ignored) {
            }
            if (text != null && v instanceof TextView) {
                TextView tv = (TextView) v;
                if (hasFocus) tv.setTextColor(Color.WHITE);
                else tv.setTextColor(text);
            }
        });
        if (view instanceof Button) ((Button) view).setAllCaps(false);
    }

    /** 把弹窗内容区最下方可聚焦控件与按钮条串起来，保证遥控器「下」能到按钮 */
    private static void wireDownToBar(ViewGroup content, View bar) {
        content.post(() -> {
            List<View> list = new ArrayList<>();
            collectFocusable(content, list);
            View bottom = null;
            int max = Integer.MIN_VALUE;
            for (View v : list) {
                if (v == bar || v.getId() == bar.getId()) continue;
                int[] loc = new int[2];
                v.getLocationOnScreen(loc);
                int center = loc[1] + v.getHeight() / 2;
                if (center > max) {
                    max = center;
                    bottom = v;
                }
            }
            if (bottom != null) bottom.setNextFocusDownId(bar.getId());
            bar.setNextFocusUpId(bottom != null ? bottom.getId() : View.NO_ID);
        });
    }

    /**
     * 等 RecyclerView 子项真正布局完成再抢焦点。
     * 只判断 childCount > 0 并不够：此刻子项可能还是 0x0，requestFocus() 会静默失败，
     * 焦点就会落到窗口里第一个可聚焦控件（通常是顶部的返回箭头）上。
     */
    public static void focusFirstChildOnLayout(final ViewGroup parent, final int targetId) {
        if (!tv() || parent == null) return;
        parent.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                View child = targetId <= 0 ? (parent.getChildCount() > 0 ? parent.getChildAt(0) : null) : parent.findViewById(targetId);
                if (child == null || !child.isLaidOut() || child.getWidth() <= 0) return;
                ViewTreeObserver observer = parent.getViewTreeObserver();
                if (observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
                child.requestFocus();
            }
        });
    }

    /** 给没有统一选中态的行/图标补 12dp 圆角选中环（仅 TV 生效，手机端保持原生水波纹）。 */
    public static void ring(View... views) {
        if (!tv()) return;
        for (View view : views) {
            if (view == null) continue;
            view.setFocusable(true);
            view.setFocusableInTouchMode(true);
            view.setBackgroundResource(R.drawable.selector_lab_tool_btn);
        }
    }

    /**
     * Toolbar 右上角菜单图标（关于实验室 / 导入包 / 实验室配置源）统一选中环。
     * 菜单项是 Toolbar 动态生成的 ActionMenuItemView，只能在布局完成后遍历补样式。
     */
    public static void toolbarMenu(final ViewGroup toolbar) {
        if (!tv() || toolbar == null) return;
        toolbar.postDelayed(new Runnable() {
            private int tries;

            @Override
            public void run() {
                List<View> items = new ArrayList<>();
                collectMenuIcons(toolbar, items);
                if (items.isEmpty() && tries++ < 8) {
                    toolbar.postDelayed(this, 120);
                    return;
                }
                for (View item : items) ring(item);
            }
        }, 120);
    }

    private static void collectMenuIcons(ViewGroup parent, List<View> out) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof ViewGroup) collectMenuIcons((ViewGroup) child, out);
            else if (child.getClass().getName().endsWith("ActionMenuItemView")) out.add(child);
        }
    }

    private static void collectFocusable(ViewGroup parent, List<View> out) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof ViewGroup) collectFocusable((ViewGroup) child, out);
            else if (child.isFocusable() && child.isShown()) out.add(child);
        }
    }

    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** 弹窗内容比屏幕还高时按屏幕高度收口，保证底部按钮不被挤出屏幕（手机 / TV 通用） */
    public static void capHeight(final View root, final int reserveDp) {
        if (root == null) return;
        // 弹窗刚 show 出来时还没完成布局，getHeight() 会是 0 —— 所以必须等第一次真实布局后再收口，
        // 否则收口会被静默跳过，底部按钮依旧被挤出屏幕。
        root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                try {
                    int natural = root.getHeight();
                    if (natural <= 0) return; // 还没量出高度，等下一轮布局
                    ViewTreeObserver observer = root.getViewTreeObserver();
                    if (observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
                    Context context = root.getContext();
                    int screen = context.getResources().getDisplayMetrics().heightPixels;
                    int maxHeight = screen - dp(context, reserveDp);
                    if (natural <= maxHeight) return;
                    ViewGroup.LayoutParams params = root.getLayoutParams();
                    if (params == null) return;
                    params.height = maxHeight;
                    root.setLayoutParams(params);
                } catch (Throwable ignored) {
                }
            }
        });
    }
}
