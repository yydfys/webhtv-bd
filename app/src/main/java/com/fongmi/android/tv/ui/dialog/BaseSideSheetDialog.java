package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDialogFragment;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.sidesheet.SideSheetDialog;

public abstract class BaseSideSheetDialog extends AppCompatDialogFragment {

    private boolean fullscreen;

    protected abstract ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container);

    protected abstract int getWidth();

    /**
     * 是否改成「从底部向上滑出」。默认 false = 从右侧滑出，行为与改动前完全一致。
     */
    protected boolean isBottomSheet() {
        return false;
    }

    /**
     * 底部模式占屏幕高度的比例（默认 2/3）。
     */
    protected float getBottomSheetHeightRatio() {
        return 0.66f;
    }

    protected boolean edgeToEdgeOnFullscreen() {
        return false;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = isBottomSheet() ? createBottomSheet() : createSideSheet();
        Window window = dialog.getWindow();
        if (window == null) return dialog;
        fullscreen = Util.isFullscreen(getActivity());
        if (fullscreen) {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            Util.hideSystemUI(window);
            if (edgeToEdgeOnFullscreen()) WindowCompat.setDecorFitsSystemWindows(window, false);
        }
        return dialog;
    }

    private Dialog createSideSheet() {
        SideSheetDialog dialog = new SideSheetDialog(requireContext());
        dialog.getBehavior().setDraggable(false);
        return dialog;
    }

    /**
     * 底部弹出的容器。Material 的默认 shapeAppearance 自带顶部圆角，这里不再自绘背景，
     * 深浅色都跟着主题走。
     */
    private Dialog createBottomSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        dialog.getBehavior().setDraggable(false);
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return getBinding(inflater, container).getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initView();
        initEvent();
    }

    protected void initView() {
    }

    protected void initEvent() {
    }

    @Override
    public void onStart() {
        super.onStart();
        applyWindow();
        if (isBottomSheet()) {
            applyBottomSheet();
            return;
        }
        FrameLayout sheet = getDialog().findViewById(com.google.android.material.R.id.m3_side_sheet);
        if (sheet == null) return;
        if (edgeToEdgeOnFullscreen() && fullscreen) {
            if (sheet.getParent() instanceof ViewGroup) ((ViewGroup) sheet.getParent()).setPadding(0, 0, 0, 0);
            sheet.setPadding(0, 0, 0, 0);
        }
        ViewGroup.LayoutParams params = sheet.getLayoutParams();
        params.width = getWidth();
        if (edgeToEdgeOnFullscreen() && fullscreen && params instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) params).setMargins(0, 0, 0, 0);
        }
        sheet.setLayoutParams(params);
    }

    /**
     * 底部模式：窗口贴底、左右铺满、高度取屏高的 {@link #getBottomSheetHeightRatio()}。
     *
     * <p>窗口先定高，再把 m3 的 sheet 容器拉成 MATCH_PARENT——容器默认 wrap_content，
     * 不定高会按内容缩回去（WebView 内容还没有尺寸，缩成一条缝）。
     */
    private void applyBottomSheet() {
        Dialog dialog = getDialog();
        if (dialog == null || dialog.getWindow() == null) return;
        Window window = dialog.getWindow();
        window.setGravity(Gravity.BOTTOM);
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, bottomSheetHeight());
        View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (!(sheet instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) sheet;
        ViewGroup.LayoutParams params = group.getLayoutParams();
        params.width = ViewGroup.LayoutParams.MATCH_PARENT;
        params.height = ViewGroup.LayoutParams.MATCH_PARENT;
        group.setLayoutParams(params);
        if (group.getParent() instanceof ViewGroup) {
            ((ViewGroup) group.getParent()).setPadding(0, 0, 0, 0);
        }
        applySystemBarPadding(group);
    }

    /**
     * 给底部弹层让出系统栏：底部留出导航栏高度（手势条/三键条都不压字），横屏三键导航时左右各让一截。
     *
     * <p><b>为什么用 inset 而不是写死一个 dp。</b>导航栏高度随机型/横竖屏/手势或三键而变，
     * 写死必然在某些机器上要么贴住、要么空一截。这里取的是系统给的 inset：窗口若已由框架让位，
     * 收到的 inset 就是 0，不会重复留白；只有真的压在导航栏上才会补上。
     *
     * <p>内边距加在 sheet 容器上，与插件 html 自己的 padding 叠加，不影响内容滚动。
     */
    private void applySystemBarPadding(@NonNull ViewGroup group) {
        ViewCompat.setOnApplyWindowInsetsListener(group, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars()
                    | WindowInsetsCompat.Type.displayCutout());
            // 底部至少留 8dp 呼吸位：手势导航 inset 很小，纯按 inset 会让最后一行贴着圆角边缘
            view.setPadding(bars.left, 0, bars.right, Math.max(bars.bottom, ResUtil.dp2px(8)));
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(group);
    }

    private int bottomSheetHeight() {
        int screen = ResUtil.getScreenHeight(requireContext());
        return Math.max(ResUtil.dp2px(240), Math.round(screen * getBottomSheetHeightRatio()));
    }

    private void applyWindow() {
        if (!fullscreen || getDialog() == null || getDialog().getWindow() == null) return;
        Window window = getDialog().getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        Util.hideSystemUI(window);
        if (!edgeToEdgeOnFullscreen()) return;
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
    }
}
