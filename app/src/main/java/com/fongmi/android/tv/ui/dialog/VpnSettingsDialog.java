package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogVpnSettingsBinding;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.lab.LabConfig;
import com.fongmi.android.tv.lab.LabVpnActivity;
import com.fongmi.android.tv.lab.SystemVpnService;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.QRCode;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import android.content.Intent;
import android.net.Uri;

/** VPN 代理设置面板（mobile + leanback 共用）。
 *  mihomo 代理总开关 + 订阅地址输入（支持扫码推送）+ 系统级 VPN 二级开关。 */
public class VpnSettingsDialog extends BaseAlertDialog {

    private DialogVpnSettingsBinding binding;
    private MaterialSwitch mihomo;
    private MaterialSwitch vpn;
    private EditText subUrl;

    public static void show(Fragment fragment) {
        new VpnSettingsDialog().show(fragment.getChildFragmentManager(), null);
    }

    public static void show(FragmentActivity activity) {
        new VpnSettingsDialog().show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogVpnSettingsBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.vpn_dialog_title)
                .setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        mihomo = binding.mihomoSwitch;
        vpn = binding.vpnSwitch;
        subUrl = binding.subUrl;
        boolean vpnRunning = SystemVpnService.isVpnRunning() || LabConfig.get().getSystemVpn();
        boolean mihomoOn = LabConfig.get().getMihomo() || SystemVpnService.isProxyRunning();
        vpn.setChecked(vpnRunning);
        mihomo.setChecked(mihomoOn);
        subUrl.setText(LabConfig.get().getSubUrl());
        refreshStatus();
        applyVpnDependency();
    }

    @Override
    protected void initEvent() {
        binding.positive.setOnClickListener(this::onPositive);
        binding.negative.setOnClickListener(this::onNegative);
        binding.qrBtn.setOnClickListener(this::onQr);
        mihomo.setOnCheckedChangeListener((buttonView, isChecked) -> {
            applyVpnDependency();
            refreshStatus();
        });
        vpn.setOnCheckedChangeListener((buttonView, isChecked) -> refreshStatus());
    }

    /** mihomo 总开关关 → VPN 置灰并关闭 */
    private void applyVpnDependency() {
        boolean enabled = mihomo.isChecked();
        vpn.setEnabled(enabled);
        if (!enabled) vpn.setChecked(false);
    }

    private void refreshStatus() {
        if (SystemVpnService.isVpnRunning()) {
            binding.status.setText(R.string.vpn_state_vpn);
        } else if (SystemVpnService.isProxyRunning()) {
            binding.status.setText(R.string.vpn_state_proxy);
        } else {
            binding.status.setText(R.string.vpn_state_off);
        }
    }

    /** 扫码推送订阅地址：显示局域网二维码，手机扫码后用网页推订阅地址回来 */
    private void onQr(View view) {
        final String value = Server.get().getAddress(4);
        Bitmap bitmap = QRCode.getLightBitmap(value, 480, 0);
        View root = getLayoutInflater().inflate(R.layout.dialog_lab_qrcode, null, false);
        ImageView image = root.findViewById(R.id.qrImage);
        TextView text = root.findViewById(R.id.value);
        TextView content = root.findViewById(R.id.content);
        image.setImageBitmap(bitmap);
        text.setText(value);
        content.setText(R.string.vpn_scan_hint);
        content.setVisibility(View.VISIBLE);
        new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.vpn_scan_title)
                .setView(root)
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    private void onPositive(View view) {
        boolean mihomoOn = mihomo.isChecked();
        boolean vpnOn = vpn.isChecked() && mihomoOn;
        LabConfig.get().setMihomo(mihomoOn);
        LabConfig.get().setSystemVpn(vpnOn);
        String sub = subUrl.getText() == null ? "" : subUrl.getText().toString().trim();
        String prevSub = LabConfig.get().getSubUrl();
        LabConfig.get().setSubUrl(sub);
        // 🔴 BUG 修复：mihomo 开 + VPN 关 + VPN 实际在跑 → 及时停 TUN 保留 7890 代理
        if (mihomoOn) {
            boolean cfgExists = SystemVpnService.isConfigExists();
            boolean cfgApp = SystemVpnService.isAppGeneratedConfig();
            boolean subChanged = !sub.isEmpty() && !sub.equals(prevSub);
            boolean needRestart = false;
            if (subChanged && cfgExists) {
                if (cfgApp) {
                    SystemVpnService.deleteAppGeneratedConfig();
                    needRestart = true;
                } else {
                    Notify.show("检测到手动 config.yaml，订阅地址已被忽略");
                }
            }
            if (!cfgExists && !sub.isEmpty() && !SystemVpnService.isAppGeneratedConfig()) {
                needRestart = true;
            }
            if (!cfgExists && sub.isEmpty() && !cfgApp) {
                LabConfig.get().setMihomo(false);
                LabConfig.get().setSystemVpn(false);
                Notify.show("请先填写订阅地址，或手动放置 config.yaml");
            }
            boolean proxyRunning = SystemVpnService.isProxyRunning();
            if (needRestart) {
                SystemVpnService.restartProxy(requireContext(), vpnOn);
            } else if (!proxyRunning) {
                SystemVpnService.startProxy(requireContext());
                if (vpnOn) LabVpnActivity.start(requireContext());
            } else if (vpnOn && !SystemVpnService.isVpnRunning()) {
                LabVpnActivity.start(requireContext());
            } else if (!vpnOn && SystemVpnService.isVpnRunning()) {
                // 🔴 BUG 修复（核心）：关 VPN → 只撤 TUN，保留 127.0.0.1:7890 代理模式
                SystemVpnService.stopVpn(requireContext());
            }
        } else {
            SystemVpnService.stopAll(requireContext());
        }
        dismiss();
    }

    private void onNegative(View view) {
        dismiss();
    }

    /** 手机扫码推订阅地址 → ServerEvent.setting 广播 → 自动填入输入框 */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        if (event.type() != ServerEvent.Type.SETTING) return;
        if (event.text() == null || event.text().trim().isEmpty()) return;
        subUrl.setText(event.text().trim());
        subUrl.setSelection(subUrl.getText().length());
    }

    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
    }
}
