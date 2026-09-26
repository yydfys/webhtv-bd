package com.fongmi.android.tv.lab;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.ContextThemeWrapper;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;

/**
 * Ubuntu 虚拟系统的管理面板：安装 / 重装 / 卸载、镜像源与 apt 源选择、
 * 共享存储开关、以及进入容器终端。
 */
public final class LabUbuntuDialog {

    private LabUbuntuDialog() {
    }

    public static void show(Context context) {
        Context themed = new ContextThemeWrapper(context, R.style.Theme_App_Lab_DayNight_Dialog);
        View root = LayoutInflater.from(themed).inflate(R.layout.dialog_lab_ubuntu, null);

        View statusText = root.findViewById(R.id.statusText);
        View progressBox = root.findViewById(R.id.progressBox);
        View progressText = root.findViewById(R.id.progressText);
        LinearProgressIndicator progress = root.findViewById(R.id.progress);
        View installButton = root.findViewById(R.id.installButton);
        View uninstallButton = root.findViewById(R.id.uninstallButton);
        View terminalButton = root.findViewById(R.id.terminalButton);
        View advancedToggle = root.findViewById(R.id.advancedToggle);
        View advancedBox = root.findViewById(R.id.advancedBox);
        AutoCompleteTextView releaseDropdown = root.findViewById(R.id.releaseDropdown);
        AutoCompleteTextView rootfsDropdown = root.findViewById(R.id.rootfsSourceDropdown);
        View rootfsUrlLayout = root.findViewById(R.id.rootfsUrlLayout);
        EditText rootfsUrlInput = root.findViewById(R.id.rootfsUrlInput);
        AutoCompleteTextView aptDropdown = root.findViewById(R.id.aptSourceDropdown);
        View aptUrlLayout = root.findViewById(R.id.aptUrlLayout);
        EditText aptUrlInput = root.findViewById(R.id.aptUrlInput);
        MaterialSwitch sharedSwitch = root.findViewById(R.id.sharedStorageSwitch);
        progress.setIndeterminate(false);
        progress.setMax(100);

        String[] releases = LabUbuntu.releaseLabels();
        String[] sources = LabUbuntu.sourceLabels();
        releaseDropdown.setAdapter(new ArrayAdapter<>(themed, android.R.layout.simple_dropdown_item_1line, releases));
        rootfsDropdown.setAdapter(new ArrayAdapter<>(themed, android.R.layout.simple_dropdown_item_1line, sources));
        aptDropdown.setAdapter(new ArrayAdapter<>(themed, android.R.layout.simple_dropdown_item_1line, sources));
        releaseDropdown.setText(releases[LabUbuntu.getRelease(themed)], false);
        rootfsDropdown.setText(sources[LabUbuntu.getRootfsSource(themed)], false);
        aptDropdown.setText(sources[LabUbuntu.getAptSource(themed)], false);
        rootfsUrlInput.setText(LabUbuntu.getRootfsCustomUrl(themed));
        aptUrlInput.setText(LabUbuntu.getAptCustomUrl(themed));
        sharedSwitch.setChecked(LabUbuntu.getSharedStorage(themed));
        applyVisibility(rootfsUrlLayout, LabUbuntu.getRootfsSource(themed) == LabUbuntu.SRC_CUSTOM);
        applyVisibility(aptUrlLayout, LabUbuntu.getAptSource(themed) == LabUbuntu.SRC_CUSTOM);

        releaseDropdown.setOnItemClickListener((parent, view, position, id) -> LabUbuntu.setRelease(themed, position));
        rootfsDropdown.setOnItemClickListener((parent, view, position, id) -> {
            LabUbuntu.setRootfsSource(themed, position);
            applyVisibility(rootfsUrlLayout, position == LabUbuntu.SRC_CUSTOM);
        });
        aptDropdown.setOnItemClickListener((parent, view, position, id) -> {
            LabUbuntu.setAptSource(themed, position);
            applyVisibility(aptUrlLayout, position == LabUbuntu.SRC_CUSTOM);
        });
        sharedSwitch.setOnCheckedChangeListener((button, checked) -> LabUbuntu.setSharedStorage(themed, checked));
        advancedToggle.setOnClickListener(v -> {
            boolean expanded = advancedBox.getVisibility() == View.VISIBLE;
            advancedBox.setVisibility(expanded ? View.GONE : View.VISIBLE);
            ((android.widget.TextView) advancedToggle).setText(expanded ? "高级设置 ▾" : "高级设置 ▴");
        });

        AlertDialog dialog = new MaterialAlertDialogBuilder(themed, R.style.Theme_App_Lab_DayNight_Dialog)
                .setTitle("Ubuntu 虚拟系统")
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        final boolean[] busy = {false};

        Runnable refresh = () -> {
            boolean installed = LabUbuntu.installed(themed);
            boolean installing = LabUbuntu.isInstalling(themed);
            ((android.widget.TextView) statusText).setText(
                    installing ? "正在安装 ..." : (installed ? LabUbuntu.summary(themed) : "未安装"));
            ((android.widget.TextView) installButton).setText(installed ? "重装" : "安装");
            installButton.setEnabled(!busy[0] && !installing);
            uninstallButton.setEnabled(!busy[0] && installed && !installing);
            terminalButton.setEnabled(!busy[0] && installed);
            progressBox.setVisibility(installing || busy[0] ? View.VISIBLE : View.GONE);
        };
        refresh.run();

        installButton.setOnClickListener(v -> {
            boolean installed = LabUbuntu.installed(themed);
            new MaterialAlertDialogBuilder(themed)
                    .setTitle(installed ? "重装 Ubuntu 系统" : "安装 Ubuntu 系统")
                    .setMessage(installed
                            ? "Ubuntu 环境会被替换，手机共享存储中的文件不受影响。继续吗？"
                            : "将下载并解压 " + LabUbuntu.releaseLabel(LabUbuntu.getRelease(themed))
                            + " 到应用私有目录，请保持网络畅通。")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton("开始", (d, w) -> startInstall(themed, busy, dialog, progressBox, progressText, progress, refresh))
                    .show();
        });

        uninstallButton.setOnClickListener(v -> new MaterialAlertDialogBuilder(themed)
                .setTitle("卸载 Ubuntu")
                .setMessage("整个 Ubuntu 环境、已安装的软件与 Rootfs 里的文件都会被删除；"
                        + "手机共享存储与 lab 配置不受影响。")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("卸载", (d, w) -> {
                    LabUbuntu.uninstall(themed);
                    Toast.makeText(themed, "Ubuntu 环境已移除", Toast.LENGTH_SHORT).show();
                    refresh.run();
                })
                .show());

        terminalButton.setOnClickListener(v -> LabTerminalActivity.start(themed, "Ubuntu", null, LabUbuntu.shellCommand(themed)));

        dialog.show();
        // 遥控器（TV）下主动请求焦点，避免弹窗无焦点
        if (LabFocus.tv() && dialog.getWindow() != null) dialog.getWindow().getDecorView().post(installButton::requestFocus);
        // 下拉框 / 输入框 / 开关都要能被遥控器选中（之前只有安装按钮有焦点）
        LabFocus.enable(releaseDropdown, rootfsDropdown, aptDropdown, rootfsUrlInput, aptUrlInput, sharedSwitch);
        // 「取消」按钮同样要能被遥控选中，但面板默认焦点仍留在「安装」上
        LabFocus.fixDialogButtons(dialog, false);
    }

    private static void startInstall(Context context, boolean[] busy, AlertDialog dialog, View progressBox,
                                     View progressText, LinearProgressIndicator progress, Runnable refresh) {
        busy[0] = true;
        progressBox.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        refresh.run();
        LabUbuntu.install(context, new LabEnv.InstallCallback() {
            @Override
            public void onProgress(String message) {
                App.post(() -> ((android.widget.TextView) progressText).setText(message));
            }

            @Override
            public void onDownloadProgress(long done, long total) {
                App.post(() -> {
                    if (total > 0) {
                        int percent = (int) Math.min(100, done * 100 / total);
                        progress.setIndeterminate(false);
                        progress.setProgressCompat(percent, true);
                        ((android.widget.TextView) progressText).setText("正在下载 " + LabEnv.formatSize(done) + " / " + LabEnv.formatSize(total));
                    } else {
                        progress.setIndeterminate(true);
                        ((android.widget.TextView) progressText).setText("正在下载 " + LabEnv.formatSize(done));
                    }
                });
            }

            @Override
            public void onUnzipProgress(long done, long total) {
                App.post(() -> {
                    if (total > 0) {
                        int percent = (int) Math.min(100, done * 100 / total);
                        progress.setIndeterminate(false);
                        progress.setProgressCompat(percent, true);
                        ((android.widget.TextView) progressText).setText("正在解压 " + LabEnv.formatSize(done) + " / " + LabEnv.formatSize(total));
                    } else {
                        ((android.widget.TextView) progressText).setText("正在解压 " + LabEnv.formatSize(done));
                    }
                });
            }

            @Override
            public void onFinalizing() {
                App.post(() -> {
                    progress.setIndeterminate(false);
                    progress.setProgressCompat(100, true);
                    ((android.widget.TextView) progressText).setText("正在完成安装 ...");
                });
            }

            @Override
            public void onDone() {
                App.post(() -> {
                    busy[0] = false;
                    progressBox.setVisibility(View.GONE);
                    refresh.run();
                    Toast.makeText(context, "Ubuntu 安装完成", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String message) {
                App.post(() -> {
                    busy[0] = false;
                    progressBox.setVisibility(View.GONE);
                    refresh.run();
                    Toast.makeText(context, TextUtils.isEmpty(message) ? "安装失败" : "安装失败：" + message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private static void applyVisibility(View view, boolean visible) {
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }
}
