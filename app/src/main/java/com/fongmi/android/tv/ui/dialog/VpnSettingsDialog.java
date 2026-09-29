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
import com.fongmi.android.tv.event.VpnStateEvent;
import com.fongmi.android.tv.lab.LabConfig;
import com.fongmi.android.tv.lab.LabFocus;
import com.fongmi.android.tv.lab.LabVpnActivity;
import com.fongmi.android.tv.lab.SystemVpnService;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.QRCode;
import com.fongmi.android.tv.utils.Util;
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
    private boolean syncing;

    private DialogVpnSettingsBinding binding;
    private MaterialSwitch mihomo;
    private MaterialSwitch vpn;
    private EditText subUrl;
    private int vpnStartingType = 0;
    private AlertDialog qrDialog;

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
        return new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_FixedLightDialog)
                .setTitle(R.string.vpn_dialog_title)
                .setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        mihomo = binding.mihomoSwitch;
        vpn = binding.vpnSwitch;
        subUrl = binding.subUrl;
        // 🔴 显示只认运行时状态：进程被杀后 mihomo 内核 / VPN 其实都已退出，
        // 不能再用持久化开关 OR 运行时状态（会把上次残留的 true 显示成"还开着"）。
        boolean mihomoOn = SystemVpnService.isProxyRunning();
        boolean vpnRunning = SystemVpnService.isVpnRunning();
        SystemVpnService.reconcileSwitches();   // 顺手清掉与真实状态不符的残留开关值
        vpn.setChecked(vpnRunning);
        mihomo.setChecked(mihomoOn);
        subUrl.setText(LabConfig.get().getSubUrl());
        refreshStatus();
        applyVpnDependency();
        setupVpnMode();
    }

    @Override
    protected void initEvent() {
        binding.positive.setOnClickListener(this::onPositive);
        binding.negative.setOnClickListener(this::onNegative);
        binding.qrBtn.setOnClickListener(this::onQr);
        binding.nodeRow.setOnClickListener(this::onNode);
        mihomo.setOnCheckedChangeListener((buttonView, isChecked) -> {
            // v590：TV 两个模式开关互斥（手机端仍是老逻辑：mihomo 关 → VPN 置灰并关闭）
            if (Util.isLeanback()) {
                applyModeMutex();
                return;
            }
            applyVpnDependency();
            refreshStatus();
        });
        vpn.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!Util.isLeanback()) refreshStatus();
        });
    }

    /** v590：TV 两个模式开关严格互斥 —— 只允许一个在跑。
     *  都关 → 两个都可选、不置灰；开了一个 → 另一个置灰且复位。手机端不参与。 */
    private void applyModeMutex() {
        if (!Util.isLeanback()) return;
        boolean auto = binding.autoStartSwitch.isChecked();
        boolean manual = binding.mihomoSwitch.isChecked();
        if (auto && manual) {
            // 理论不可达（开一个时会把另一个复位）；真出现以"手动"为准
            syncing = true;
            try {
                binding.autoStartSwitch.setChecked(false);
                LabConfig.get().setMihomoAutoStart(false);
            } finally {
                syncing = false;
            }
            auto = false;
        }
        binding.autoStartSwitch.setEnabled(!manual);
        binding.mihomoSwitch.setEnabled(!auto);
        syncRowState();
        refreshStatus();
    }

    /** mihomo 总开关关 → VPN 置灰并关闭 */
    private void applyVpnDependency() {
        boolean enabled = mihomo.isChecked();
        vpn.setEnabled(enabled);
        syncRowState();
        if (!enabled) vpn.setChecked(false);
    }

    private void refreshStatus() {
        if (!Util.isLeanback()) {
            binding.status.setText(SystemVpnService.getStateTextRes(vpnStartingType));
            return;
        }
        // v590：TV 状态文案跟着"模式开关"走 —— 点了就变，不等异步启动完成
        if (binding.autoStartSwitch.isChecked()) {
            binding.status.setText(R.string.vpn_mode_auto_running);
        } else if (binding.mihomoSwitch.isChecked()) {
            binding.status.setText(R.string.vpn_mode_manual_running);
        } else {
            binding.status.setText(SystemVpnService.getStateTextRes(vpnStartingType));
        }
    }

    /** 节点管理：浏览订阅节点、看延迟、手动切换 select 组 */
    private void onNode(View view) {
        NodeManageDialog.show(this);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onVpnStateEvent(VpnStateEvent event) {
        switch (event.type()) {
            case STARTING_PROXY:
                vpnStartingType = 1;
                break;
            case STARTING_VPN:
                vpnStartingType = 2;
                break;
            default:
                vpnStartingType = 0;
                break;
        }
        refreshStatus();
    }

    /** 扫码推送订阅地址：显示局域网二维码，手机扫码后用网页推订阅地址回来 */
    private void onQr(View view) {
        if (qrDialog != null && qrDialog.isShowing()) qrDialog.dismiss();
        final String value = Server.get().getAddress(4);
        Bitmap bitmap = QRCode.getPanelBitmap(value, 212, 2);
        View root = getLayoutInflater().inflate(R.layout.dialog_lab_qrcode, null, false);
        ImageView image = root.findViewById(R.id.qrImage);
        TextView text = root.findViewById(R.id.value);
        TextView content = root.findViewById(R.id.content);
        image.setImageBitmap(bitmap);
        text.setText(value);
        content.setText(R.string.vpn_scan_hint);
        content.setVisibility(View.VISIBLE);
        qrDialog = new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_FixedLightDialog)
                .setTitle(R.string.vpn_scan_title)
                .setView(root)
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
        qrDialog.setOnDismissListener(d -> qrDialog = null);
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

    /** 手机扫码推订阅地址 → ServerEvent.setting 广播 → 自动填入输入框 + 自动关闭二维码弹窗 */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        if (event.type() != ServerEvent.Type.SETTING) return;
        if (event.text() == null || event.text().trim().isEmpty()) return;
        subUrl.setText(event.text().trim());
        subUrl.setSelection(subUrl.getText().length());
        // 扫码方已确认设定订阅地址 → 二维码弹窗自动退去，无需手动点取消
        if (qrDialog != null && qrDialog.isShowing()) qrDialog.dismiss();
    }

    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
        // 遥控器（TV）下主动请求焦点，避免弹窗无焦点
                if (Util.isLeanback()) {
            setupTvRows();
            syncRowState();
            requestInitialFocus();
        }
        // 确定 / 取消 按钮使用统一的遥控器选中高亮样式（自定义按钮不走 AlertDialog 的按钮通道）
        LabFocus.styleButton(binding.negative);
        LabFocus.styleButton(binding.positive);
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
    }

    /**
     * v581 TV 双开关装配：
     *  · TV：只保留「mihomo 代理」（手动）+「mihomo 自启动」两个互斥开关，整行隐藏系统级 VPN（TV 不允许也不需要系统级代理）
     *  · 手机：保持原样（隐藏自启动行）
     * 任一开关打开 → 立刻走同一套启动序列：存开关 → 拉订阅 → 没跑就拉起内核。
     */
    private void setupVpnMode() {
        if (Util.isLeanback()) {
            LabConfig.get().setSystemVpn(false);
            if (SystemVpnService.isVpnRunning()) SystemVpnService.stopVpn(requireContext());
            binding.vpnRow.setVisibility(View.GONE);
            binding.vpnSwitch.setChecked(false);
            binding.autoStartRow.setVisibility(View.VISIBLE);
            boolean running = SystemVpnService.isProxyRunning();
            boolean auto = LabConfig.get().getMihomoAutoStart();
            syncing = true;
            try {
                // v590：两个模式开关互斥（只能有一个在跑）—— 初始态按"内核真在跑 + 持久开关"判定；
                // 都关时两个开关都可选、不置灰（见 applyModeMutex）
                binding.autoStartSwitch.setChecked(auto && running);
                binding.mihomoSwitch.setChecked(!auto && running);
                binding.killSwitch.setChecked(false);
            } finally {
                syncing = false;
            }
            applyModeMutex();
            // 遥控器焦点链：订阅地址 → 自启动 → 代理 → 节点管理 → 按钮
            binding.subUrl.setNextFocusDownId(R.id.autoStartSwitch);
            binding.qrBtn.setNextFocusDownId(R.id.autoStartSwitch);
            binding.autoStartSwitch.setNextFocusUpId(R.id.subUrl);
            binding.autoStartSwitch.setNextFocusDownId(R.id.mihomoSwitch);
            binding.mihomoSwitch.setNextFocusUpId(R.id.autoStartSwitch);
            binding.mihomoSwitch.setNextFocusDownId(R.id.nodeRow);
            binding.nodeRow.setNextFocusUpId(R.id.mihomoSwitch);
        } else {
            binding.autoStartRow.setVisibility(View.GONE);
            binding.killRow.setVisibility(View.GONE); // 杀内核开关仅 TV 版提供，手机端不受影响
            binding.subUrl.setNextFocusDownId(R.id.vpnSwitch);
            binding.qrBtn.setNextFocusDownId(R.id.vpnSwitch);
        }
        binding.autoStartSwitch.setOnClickListener(v -> onAutoStartClicked(binding.autoStartSwitch.isChecked()));
        binding.mihomoSwitch.setOnClickListener(v -> onManualClicked(binding.mihomoSwitch.isChecked()));
    }

    /** 自启动模式：开启 = 记开关 + 立刻点亮（拉订阅/检测内核/没跑就拉起）；关闭 = 停代理，回到手动待命。 */
    private void onAutoStartClicked(boolean on) {
        if (syncing || !Util.isLeanback()) return;
        syncing = true;
        try {
            LabConfig.get().setMihomoAutoStart(on);
            LabConfig.get().setMihomo(false);
            binding.mihomoSwitch.setChecked(false);   // 互斥：自启动开了，手动立刻复位（紧接着被置灰）
            if (on) startMihomoNow();
            else SystemVpnService.stopAll(requireContext());
        } finally {
            syncing = false;
        }
        applyModeMutex();
    }

    /** 手动模式：开启 = 记开关 + 立刻点亮 + 自启动互斥回落关闭；关闭 = 停代理。 */
    private void onManualClicked(boolean on) {
        if (syncing || !Util.isLeanback()) return;
        syncing = true;
        try {
            LabConfig.get().setMihomo(on);
            LabConfig.get().setMihomoAutoStart(false);
            binding.autoStartSwitch.setChecked(false);   // 互斥：手动开了，自启动立刻复位（紧接着被置灰）
            if (on) startMihomoNow();
            else SystemVpnService.stopAll(requireContext());   // 两个开关都关 = 停代理，避免"开关全灭内核还在跑"
        } finally {
            syncing = false;
        }
        applyModeMutex();
    }

    /** 与「确定」同一套启动动作，区别只是不关闭面板：先落订阅地址，再确保内核起来。 */
    private void startMihomoNow() {
        CharSequence cs = binding.subUrl.getText();
        String sub = cs == null ? "" : cs.toString().trim();
        if (!sub.isEmpty()) LabConfig.get().setSubUrl(sub);
        if (!SystemVpnService.isProxyRunning()) SystemVpnService.startProxy(requireContext());
    }

    // ---------------- v582: TV 焦点链（统一走"行"，手机端不改变行为） ----------------

    /** 行级选中 + 焦点链：整行走 ring，行内开关不抢焦点 */
    private void setupTvRows() {
        LabFocus.rowRing(binding.autoStartRow, binding.autoStartSwitch);
        LabFocus.rowRing(binding.mihomoRow, binding.mihomoSwitch);
        LabFocus.rowRing(binding.vpnRow, binding.vpnSwitch);
        LabFocus.rowRing(binding.nodeRow);
        LabFocus.rowRing(binding.killRow, binding.killSwitch);
        LabFocus.inputStroke(binding.subUrlLayout, 0xFF2F6FED);
        if (!Util.isLeanback()) return;
        binding.autoStartRow.setOnClickListener(v -> toggleSwitch(binding.autoStartSwitch));
        binding.mihomoRow.setOnClickListener(v -> toggleSwitch(binding.mihomoSwitch));
        binding.killRow.setOnClickListener(v -> toggleSwitch(binding.killSwitch));
        binding.vpnRow.setOnClickListener(v -> toggleSwitch(binding.vpnSwitch));
        binding.subUrl.setNextFocusDownId(R.id.autoStartRow);
        binding.qrBtn.setNextFocusDownId(R.id.autoStartRow);
        binding.autoStartRow.setNextFocusUpId(R.id.subUrl);
        binding.autoStartRow.setNextFocusDownId(R.id.mihomoRow);
        binding.mihomoRow.setNextFocusUpId(R.id.autoStartRow);
        binding.mihomoRow.setNextFocusDownId(R.id.killRow);
        binding.killRow.setNextFocusUpId(R.id.mihomoRow);
        binding.killRow.setNextFocusDownId(R.id.nodeRow);
        binding.nodeRow.setNextFocusUpId(R.id.killRow);
        binding.nodeRow.setNextFocusDownId(R.id.positive);
        binding.negative.setNextFocusUpId(R.id.nodeRow);
        binding.positive.setNextFocusUpId(R.id.nodeRow);
    }

    private void toggleSwitch(android.widget.CompoundButton sw) {
        if (sw == null || !sw.isEnabled()) return;
        boolean on = !sw.isChecked();
        sw.setChecked(on);
        if (sw == binding.mihomoSwitch) {
            onManualClicked(on);
        } else if (sw == binding.autoStartSwitch) {
            onAutoStartClicked(on);
        } else if (sw == binding.killSwitch) {
            onKillClicked();
        }
    }

    /** 「杀死 mihomo 进程」：一次性动作，彻底停内核并复位两个开关，保证显示状态与真实运行一致。 */
    private void onKillClicked() {
        if (syncing) return;
        syncing = true;
        try {
            SystemVpnService.stopAll(requireContext());
            LabConfig.get().setMihomo(false);
            LabConfig.get().setMihomoAutoStart(false);
            binding.mihomoSwitch.setChecked(false);
            binding.autoStartSwitch.setChecked(false);
            binding.killSwitch.setChecked(false);
            binding.killSwitch.setEnabled(true);
        } finally {
            syncing = false;
        }
        // v590：两个模式开关都回到"可选中"
        applyModeMutex();
    }

    /** 行可用性与开关可用性保持一致（TV） */
    private void syncRowState() {
        LabFocus.rowEnable(binding.mihomoRow, binding.mihomoSwitch.isEnabled());
        LabFocus.rowEnable(binding.autoStartRow, binding.autoStartSwitch.isEnabled());
        LabFocus.rowEnable(binding.vpnRow, binding.vpnSwitch.isEnabled());
        LabFocus.rowEnable(binding.killRow, binding.killSwitch.isEnabled());
    }

    /** 默认焦点：落在第一个可用行，避免投到不可选的控件 */
    private void requestInitialFocus() {
        int target = R.id.autoStartRow;
        if (!binding.autoStartRow.isFocusable()) {
            target = binding.mihomoRow.isFocusable() ? R.id.mihomoRow : R.id.subUrl;
        }
        LabFocus.focusFirstChildOnLayout(binding.getRoot(), target);
    }
}
