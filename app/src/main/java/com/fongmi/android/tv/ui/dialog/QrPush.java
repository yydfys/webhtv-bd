package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.lab.LabFocus;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.utils.QRCode;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

/**
 * 电视端「扫码推送」通用入口（设置里各输入框右侧的二维码图标）。
 * <p>
 * 流程：电视端弹出二维码（指向 App 内置网页的「设置」面板）→ 手机扫码后在「配置」框里粘贴文本并确定
 * → App 服务端广播 {@link ServerEvent} → 这里把文本回填到发起方的输入框。
 * <p>
 * slot 用来区分同一个弹窗里的多个输入框（否则一次推送会把所有监听者的输入框都填一遍）。
 * 扫码页会把 slot 预填进隐藏的「名称」框，因此服务端回来的 name 字段即 slot。
 */
public final class QrPush {

    /** TMDB 数据配置面板：API Key / Access Token 输入框 */
    public static final String SLOT_TMDB_KEY = "tmdb_key";
    /** TMDB 数据配置面板：OMDb API Key 输入框 */
    public static final String SLOT_OMDB_KEY = "omdb_key";
    /** AI 通用配置面板：API 服务端点 / 基地址 输入框 */
    public static final String SLOT_AI_ENDPOINT = "ai_endpoint";
    /** AI 通用配置面板：API KEY 输入框 */
    public static final String SLOT_AI_KEY = "ai_key";

    /** 推送结果的回调，在 UI 线程执行。 */
    public interface OnText {
        void onText(String text);
    }

    private QrPush() {
    }

    /**
     * @param activity 宿主 Activity
     * @param slot     槽位标识（同时也是扫码页隐藏的「名称」值），同一个弹窗内必须唯一
     * @param hint     二维码下方的提示文案（告诉用户要粘贴什么）
     * @param listener 收到推送文本后的回调
     */
    public static void show(Activity activity, String slot, String hint, OnText listener) {
        if (activity == null || slot == null || slot.isEmpty()) return;
        new Receiver(activity, slot, hint, listener).open();
    }

    private static final class Receiver {

        private final Activity activity;
        private final String slot;
        private final String hint;
        private final OnText listener;
        private AlertDialog dialog;

        private Receiver(Activity activity, String slot, String hint, OnText listener) {
            this.activity = activity;
            this.slot = slot;
            this.hint = hint;
            this.listener = listener;
        }

        private void open() {
            String value = Server.get().getAddress(4) + "&slot=" + slot;
            View root = activity.getLayoutInflater().inflate(R.layout.dialog_lab_qrcode, null, false);
            ImageView image = root.findViewById(R.id.qrImage);
            TextView content = root.findViewById(R.id.content);
            TextView address = root.findViewById(R.id.value);
            if (image != null) image.setImageBitmap(QRCode.getPanelBitmap(value, 212, 2));
            if (content != null) content.setText(hint == null ? "" : hint);
            if (address != null) address.setText(value);
            dialog = new MaterialAlertDialogBuilder(activity, R.style.Theme_WebHTV_LightDialog)
                    .setTitle("扫码推送")
                    .setView(root)
                    .setNegativeButton("关闭", null)
                    .setOnDismissListener(d -> EventBus.getDefault().unregister(this))
                    .create();
            dialog.show();
            LightDialog.apply(dialog);
            LabFocus.fixDialogButtons(dialog, false);
            focusable();
            EventBus.getDefault().register(this);
        }

        /** 让遥控器能聚焦到二维码弹窗（否则弹窗打开后返回键之外按不动）。 */
        private void focusable() {
            Window window = dialog == null ? null : dialog.getWindow();
            View decor = window == null ? null : window.getDecorView();
            if (decor == null) return;
            decor.setFocusableInTouchMode(true);
            decor.requestFocus();
        }

        @Subscribe(threadMode = ThreadMode.MAIN)
        public void onServerEvent(ServerEvent event) {
            if (event == null || event.type() != ServerEvent.Type.SETTING) return;
            if (!slot.equals(event.name())) return;
            String text = event.text() == null ? "" : event.text().trim();
            if (text.isEmpty()) return;
            if (listener != null) listener.onText(text);
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
        }
    }
}
