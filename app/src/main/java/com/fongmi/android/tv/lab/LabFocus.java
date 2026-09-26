package com.fongmi.android.tv.lab;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
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
        style(negative);
        style(neutral);
        style(positive);
        View bar = negative != null ? negative : (positive != null ? positive : neutral);
        if (bar == null) return;
        View decor = dialog.getWindow() != null ? dialog.getWindow().getDecorView() : null;
        if (decor instanceof ViewGroup) wireDownToBar((ViewGroup) decor, bar);
        if (focusNegative) bar.requestFocus();
    }

    private static void style(View view) {
        if (view == null) return;
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

    private static void collectFocusable(ViewGroup parent, List<View> out) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof ViewGroup) collectFocusable((ViewGroup) child, out);
            else if (child.isFocusable() && child.isShown()) out.add(child);
        }
    }
}
