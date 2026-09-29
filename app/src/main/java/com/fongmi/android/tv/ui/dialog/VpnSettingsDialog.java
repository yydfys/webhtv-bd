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
    private boolean qrAutoSuppressed;   // v591 TV only: suppress auto QR right after it closes

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
        binding.saveSubBtn.setOnClickListener(this::onSaveSub);   // v591 TV only (gone on mobile)
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
                // v592：内核真的退了 → 模式开关必须跟着回到关闭（手动模式不留持久状态，自启动模式仍保留）
                if (Util.isLeanback()) syncModeSwitchesFromRuntime();
                break;
        }
        refreshStatus();
    }

    /** v592：模式开关跟随内核真实状态 —— 内核在跑就按持久化的模式点亮，内核没了两个开关都回关闭。 */
    private void syncModeSwitchesFromRuntime() {
        if (syncing) return;
        boolean running = SystemVpnService.isCoreRunning();
        boolean auto = running && LabConfig.get().getMihomoAutoStart();
        syncing = true;
        try {
            binding.autoStartSwitch.setChecked(auto);
            binding.mihomoSwitch.setChecked(running && !auto);
        } finally {
            syncing = false;
        }
        applyModeMutex();
    }

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
        // v592：TV 走新流程（模式校验 → 拉服务 → 关面板）；手机端维持原逻辑，零改动
        if (Util.isLeanback()) {
            onPositiveTv();
            return;
        }
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

    /** v592 TV「确定」：没选模式就提示；内核没跑就拉；最后保存模式状态并关面板。 */
    private void onPositiveTv() {
        boolean auto = binding.autoStartSwitch.isChecked();
        boolean manual = binding.mihomoSwitch.isChecked();
        if (!auto && !manual) {
            Notify.show(R.string.vpn_mode_required);   // 面板保留，让用户接着选模式
            return;
        }
        CharSequence cs = binding.subUrl.getText();
        String sub = cs == null ? "" : cs.toString().trim();
        if (!sub.isEmpty()) LabConfig.get().setSubUrl(sub);
        String persisted = LabConfig.get().getSubUrl();
        boolean hasSub = !sub.isEmpty() || (persisted != null && !persisted.trim().isEmpty());
        // 内核没跑、又没配置、又没订阅地址 → 起不来，直接提示（避免点了确定却什么都没发生）
        if (!SystemVpnService.isCoreRunning() && !SystemVpnService.isConfigExists() && !hasSub) {
            Notify.show(R.string.vpn_sub_empty);
            return;
        }
        // 保存模式状态（壳子重启后按这个模式跑：自启动模式会自动拉起内核，手动模式保持关闭）
        LabConfig.get().setMihomoAutoStart(auto);
        LabConfig.get().setMihomo(manual);
        // 有服务就不重复拉取，避免多服务共存
        startMihomoNow();
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
            setupTvSubscriptionRow();   // v592：TV 订阅行（「保存订阅」按钮；确定/取消 走底部的按钮）
            // v592：模式开关一律以"内核真在跑"为准 —— 手动模式在服务退出后必须回到关闭态
            boolean running = SystemVpnService.isCoreRunning();
            boolean auto = running && LabConfig.get().getMihomoAutoStart();
            syncing = true;
            try {
                // 两个模式开关互斥（只能有一个在跑）；都关时两个开关都可选、不置灰（见 applyModeMutex）
                binding.autoStartSwitch.setChecked(auto);
                binding.mihomoSwitch.setChecked(running && !auto);
                // v592：杀死内核不再用开关按钮 —— 整行可点，点了弹确认框（见 setupTvRows/onKillClicked）
                binding.killSwitch.setVisibility(View.GONE);
            } finally {
                syncing = false;
            }
            applyModeMutex();
            // v592：焦点链统一在 onStart → setupTvRows() 里一次性设置
            //（订阅框 → 保存订阅 → 自启动模式 → 手动模式 → 杀进程 → 节点管理 → 取消/确定）
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

    /** 「确定」/模式开关共用的启动动作，区别只是不关闭面板：先落订阅地址，再确保内核起来。 */
    private void startMihomoNow() {
        CharSequence cs = binding.subUrl.getText();
        String sub = cs == null ? "" : cs.toString().trim();
        if (!sub.isEmpty()) LabConfig.get().setSubUrl(sub);
        // v592：内核真在跑就不再拉取（避免同时起多个内核进程），只保留模式状态刷新
        if (!SystemVpnService.isCoreRunning()) SystemVpnService.startProxy(requireContext());
    }

    // ---------------- v582: TV 焦点链（统一走"行"，手机端不改变行为） ----------------

    // ---------------- v591: TV subscription row (TV only, mobile untouched) ----------------

    /**
     * v591 TV 订阅区装配：
     *  · 隐藏「扫码推送」图标（订阅框聚焦会自动弹二维码，图标冗余）
     *  · 订阅框右侧放「保存订阅」：丢订阅缓存 + （没服务就先起服务）重新拉取订阅节点
     *  · 底部仍是「确定 / 取消」：确定 = 校验模式 → 保存模式状态 → 没服务就拉服务 → 关面板；取消 = 只关面板
     *  · 订阅框获得焦点自动弹出二维码；关掉弹窗后焦点回落不会重弹，焦点离开再回来才再弹
     */
    private void setupTvSubscriptionRow() {
        binding.qrBtn.setVisibility(View.GONE);
        binding.saveSubBtn.setVisibility(View.VISIBLE);
        binding.negative.setNextFocusRightId(R.id.positive);
        binding.positive.setNextFocusLeftId(R.id.negative);
        LabFocus.styleButton(binding.saveSubBtn);
        // v592：订阅框 ⇄「保存订阅」焦点链（右 = 保存订阅；上 = 回到订阅框；下 = 自启动模式行）
        binding.subUrl.setNextFocusRightId(R.id.saveSubBtn);
        binding.subUrl.setNextFocusDownId(R.id.autoStartRow);
        binding.saveSubBtn.setNextFocusLeftId(R.id.subUrl);
        binding.saveSubBtn.setNextFocusRightId(View.NO_ID);
        binding.saveSubBtn.setNextFocusUpId(R.id.subUrl);
        binding.saveSubBtn.setNextFocusDownId(R.id.autoStartRow);
        binding.subUrl.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                qrAutoSuppressed = false;   // 焦点离开订阅框 → 重新武装
                return;
            }
            if (qrAutoSuppressed) return;
            if (qrDialog != null && qrDialog.isShowing()) return;
            qrAutoSuppressed = true;
            onQr(v);
        });
    }

    /**
     * v592 TV「保存订阅」：①内核在跑 → 删订阅缓存 → 直接重拉订阅；
     * ②内核没跑 → 删缓存 → 起内核（顺带拉订阅）→ 节点信息更新到本地。
     */
    private void onSaveSub(View view) {
        String sub = subUrl.getText() == null ? "" : subUrl.getText().toString().trim();
        if (sub.isEmpty()) {
            Notify.show(R.string.vpn_sub_empty);
            return;
        }
        LabConfig.get().setSubUrl(sub);
        if (SystemVpnService.isConfigExists() && !SystemVpnService.isAppGeneratedConfig()) {
            // 手动放置的 config.yaml 优先级最高：只提示，不删用户自己的配置
            Notify.show(R.string.vpn_sub_manual_config);
            return;
        }
        if (SystemVpnService.isAppGeneratedConfig()) SystemVpnService.deleteAppGeneratedConfig();
        SystemVpnService.deleteSubCache();
        Notify.show(R.string.vpn_sub_pulling);
        boolean restoreVpn = SystemVpnService.isVpnRunning();
        if (SystemVpnService.isCoreRunning()) SystemVpnService.restartProxy(requireContext(), restoreVpn);
        else SystemVpnService.startProxy(requireContext());
    }

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
        binding.killRow.setOnClickListener(v -> onKillClicked());   // v592：整行点击 → 确认弹窗
        binding.vpnRow.setOnClickListener(v -> toggleSwitch(binding.vpnSwitch));
        binding.subUrl.setNextFocusDownId(R.id.autoStartRow);
        binding.qrBtn.setNextFocusDownId(R.id.autoStartRow);
        binding.autoStartRow.setNextFocusUpId(R.id.subUrl);
        binding.autoStartRow.setNextFocusDownId(R.id.mihomoRow);
        binding.mihomoRow.setNextFocusUpId(R.id.autoStartRow);
        binding.mihomoRow.setNextFocusDownId(R.id.killRow);
        binding.killRow.setNextFocusUpId(R.id.mihomoRow);    // v592：杀进程行 = 纯动作行，不参与开关互斥
        binding.killRow.setNextFocusDownId(R.id.nodeRow);
        binding.nodeRow.setNextFocusUpId(R.id.killRow);
        binding.nodeRow.setNextFocusDownId(R.id.negative);   // v592：确定按钮回来了（下行 → 取消/确定）
        binding.negative.setNextFocusUpId(R.id.nodeRow);
        binding.negative.setNextFocusRightId(R.id.positive);
        binding.positive.setNextFocusLeftId(R.id.negative);
    }

    private void toggleSwitch(android.widget.CompoundButton sw) {
        if (sw == null || !sw.isEnabled()) return;
        boolean on = !sw.isChecked();
        sw.setChecked(on);
        if (sw == binding.mihomoSwitch) {
            onManualClicked(on);
        } else if (sw == binding.autoStartSwitch) {
            onAutoStartClicked(on);
        }
    }

    /** v592：「杀死 mihomo 进程」= 整行点击 → 先弹确认框；确认才干净停内核并复位两个模式开关。 */
    private void onKillClicked() {
        if (syncing) return;
        new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_FixedLightDialog)
                .setTitle(R.string.vpn_kill_confirm_title)
                .setMessage(R.string.vpn_kill_confirm_message)
                .setPositiveButton(R.string.dialog_positive, (d, w) -> doKillMihomo())
                .setNegativeButton(R.string.dialog_negative, null)   // 取消：只关掉这个弹窗，不动内核
                .show();
    }

    /** 确认后：一次性停干净（TUN + 内核 + 通知），并复位两个模式开关，避免多服务共存。 */
    private void doKillMihomo() {
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
        // 两个模式开关都回到"可选中"
        applyModeMutex();
        refreshStatus();
        Notify.show(R.string.vpn_kill_done);
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
