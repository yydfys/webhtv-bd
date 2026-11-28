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
import androidx.core.view.WindowCompat;
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
