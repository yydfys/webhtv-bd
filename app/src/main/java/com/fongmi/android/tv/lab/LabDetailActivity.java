package com.fongmi.android.tv.lab;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivityLabDetailBinding;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LabDetailActivity extends AppCompatActivity implements LabCommandAdapter.Listener {

    private static final String EXTRA_ITEM = "item";

    private ActivityLabDetailBinding mBinding;
    private LabCommandAdapter commandAdapter;
    private LabModels.Item item;
    private String itemName;
    /** check_command 轮询结果：命令 id → 是否在跑（容器条目跨进程重启也能亮状态）。 */
    private final Map<String, Boolean> checkState = new HashMap<>();
    private boolean checkBusy = false;
    /** 容器条目的“环境已装好”状态（install.check_command 通过）。 */
    private boolean installDone = false;
    private final android.os.Handler mRefreshHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable mRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (item != null) updateButtons();
            pollCheckState();
            mRefreshHandler.postDelayed(this, 2000);
        }
    };

    public static void start(Context context, String itemName) {
        Intent intent = new Intent(context, LabDetailActivity.class);
        intent.putExtra(EXTRA_ITEM, itemName);
        context.startActivity(intent);
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(Setting.wrapDisplay(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mBinding = ActivityLabDetailBinding.inflate(getLayoutInflater());
        setContentView(mBinding.getRoot());
        setSupportActionBar(mBinding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        itemName = getIntent().getStringExtra(EXTRA_ITEM);
        mBinding.toolbar.setTitleTextColor(Color.WHITE);
        mBinding.toolbar.setNavigationOnClickListener(v -> finish());
        mBinding.btnDownload.setOnClickListener(v -> onDownload());
        mBinding.btnUninstall.setOnClickListener(v -> onUninstall());
        mBinding.btnTerminal.setOnClickListener(v -> openTerminal());
        mBinding.btnAddCommand.setOnClickListener(v -> LabCommandEditDialog.show(this, item, null, this::reload));
        mBinding.btnRefreshCommand.setOnClickListener(v -> onRefreshCommands());
        commandAdapter = new LabCommandAdapter(this, this);
        mBinding.commandRecycler.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
        mBinding.commandRecycler.setAdapter(commandAdapter);
        reload();
    }

    private void reload() {
        LabConfig.get().reload(new LabConfig.LoadCallback() {
            @Override
            public void onLoaded(LabModels.LabRoot root) {
                item = findItem(root);
                if (item == null) {
                    Toast.makeText(LabDetailActivity.this, "找不到模块: " + itemName, Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                LabConfig.get().applyCommandOverrides(item);
                bind();
            }

            @Override
            public void onError(String message) {
                Toast.makeText(LabDetailActivity.this, "配置加载失败: " + message, Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }

    private LabModels.Item findItem(LabModels.LabRoot root) {
        if (root == null || root.lists == null) return null;
        for (LabModels.Item i : root.lists) {
            if (itemName != null && itemName.equals(i.name)) return i;
        }
        return null;
    }

    private void bind() {
        mBinding.toolbar.setTitle(item.name);
        mBinding.version.setText(item.name + " " + displayVersion());
        mBinding.info.setText(item.info == null ? "" : item.info);
        if (item.icon != null && !item.icon.isEmpty()) {
            Glide.with(mBinding.icon).load(item.icon).placeholder(R.drawable.ic_logo).error(R.drawable.ic_logo).into(mBinding.icon);
        }
        commandAdapter.setItem(item);
        boolean hasCommands = commandAdapter.getItemCount() > 0;
        mBinding.emptyCommand.setVisibility(hasCommands ? View.GONE : View.VISIBLE);
        mBinding.commandRecycler.setVisibility(hasCommands ? View.VISIBLE : View.GONE);
        invalidateOptionsMenu();
        updateButtons();
        // 「终端」类条目（terminal_auto_open）：进详情页即附着容器终端，不在中间页停留
        if (item.terminal_auto_open && item.isUbuntu() && LabUbuntu.installed(this)) {
            String shell = LabUbuntu.shellCommand(this);
            if (shell != null && !shell.isEmpty()) {
                LabTerminalActivity.start(this, item.name, item.name, shell);
                finish();
            }
        }
    }

    private String displayVersion() {
        return LabEnv.displayVersion(item);
    }

    private void updateButtons() {
        // 安装状态一律以 LabEnv.installed() 为准（ubuntu 看标记 + rootfs 内二进制），
        // 不再叠加 installDone：那条走 proot 检测，proot 有个风吹草动就会误判成"已装好"。
        boolean installed = LabEnv.installed(this, item);
        boolean running = anyRunning();
        boolean update = installed && hasNewVersion();
        // 只有"需要安装"的条目才谈安装状态；纯命令条目（终端/二进制/直跑）不显示未安装
        boolean needsInstall = LabEnv.needsInstall(item);
        boolean plainUbuntu = !needsInstall;
        mBinding.btnDownload.setText(item.isUbuntu() && needsInstall ? "安装环境" : (update ? "更新" : "下载安装"));
        mBinding.btnDownload.setVisibility(plainUbuntu || (installed && !update) ? View.GONE : View.VISIBLE);
        mBinding.btnUninstall.setVisibility(installed && needsInstall ? View.VISIBLE : View.GONE);
        if (running) {
            mBinding.status.setText(R.string.lab_running);
            mBinding.status.setBackgroundResource(R.drawable.shape_lab_running_tag);
            mBinding.status.setVisibility(View.VISIBLE);
        } else if (!needsInstall) {
            // 无需安装的条目：状态位对用户没有意义，直接收起
            mBinding.status.setVisibility(View.GONE);
        } else if (installed) {
            mBinding.status.setText(R.string.lab_installed);
            mBinding.status.setBackgroundResource(R.drawable.shape_lab_installed);
            mBinding.status.setVisibility(View.VISIBLE);
        } else {
            mBinding.status.setText(R.string.lab_not_installed);
            mBinding.status.setBackgroundResource(R.drawable.shape_lab_not_installed);
            mBinding.status.setVisibility(View.VISIBLE);
        }
        commandAdapter.notifyDataSetChanged();
    }

    private boolean hasNewVersion() {
        String installed = LabConfig.get().getInstalledVersion(item.name);
        return !installed.isEmpty() && LabEnv.compareVersions(displayVersion(), installed) > 0;
    }

    /**
     * 后台跑 check_command 摸运行状态。只查“带停止命令”的（即常驻服务），
     * 避免一个条目几十条检测命令把手机拖住。
     */
    private void pollCheckState() {
        final LabModels.Item target = item;
        if (target == null || checkBusy) return;
        final java.util.List<LabModels.Command> pending = new java.util.ArrayList<>();
        if (target.commands != null) {
            for (LabModels.Command command : target.commands) {
                if (command != null && command.hasCheck() && command.hasStop()) pending.add(command);
            }
        }
        final boolean wantInstall = target.isUbuntu() && target.hasInstall();
        if (pending.isEmpty() && !wantInstall) return;
        checkBusy = true;
        new Thread(() -> {
            final Map<String, Boolean> result = new HashMap<>();
            for (LabModels.Command command : pending) {
                result.put(command.id, LabRunner.checkRunning(LabDetailActivity.this, target, command));
            }
            final boolean done = wantInstall && LabRunner.installDone(LabDetailActivity.this, target);
            App.post(() -> {
                checkState.clear();
                checkState.putAll(result);
                if (wantInstall) installDone = done;
                checkBusy = false;
                if (item != null) updateButtons();
            });
        }).start();
    }

    private boolean anyRunning() {
        if (item.commands != null) {
            for (LabModels.Command command : item.commands) {
                if (LabRunner.isRunning(item.name + "/" + command.id)) return true;
                if (Boolean.TRUE.equals(checkState.get(command.id))) return true;
            }
        }
        for (LabCustomCommands.CustomCommand custom : LabCustomCommands.list(item.name)) {
            if (custom.id != null && LabRunner.isRunning(item.name + "/" + custom.id)) return true;
        }
        return false;
    }

    private void onDownload() {
        if (item.isUbuntu()) {
            onContainerInstall();
            return;
        }
        if (!item.available) {
            Toast.makeText(this, "该包暂未上线", Toast.LENGTH_SHORT).show();
            return;
        }
        if (LabEnv.installed(this, item) && hasNewVersion()) {
            new MaterialAlertDialogBuilder(this, R.style.Theme_App_Lab_Dialog)
                    .setTitle("确认更新")
                    .setMessage("发现新版本 " + displayVersion() + "，是否立即更新？")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton("更新", (d, w) -> LabActions.installWithDialog(this, item, this::updateButtons))
                    .show();
            return;
        }
        LabActions.installWithDialog(this, item, this::updateButtons);
    }

    private void onUninstall() {
        if (item.isUbuntu()) {
            onContainerUninstall();
            return;
        }
        new MaterialAlertDialogBuilder(this, R.style.Theme_App_Lab_Dialog)
                .setTitle("确认卸载")
                .setMessage("确定要卸载 " + item.name + " 吗？这将停止所有运行中的命令。")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("卸载", (d, w) -> {
                    LabActions.uninstall(this, item, this::updateButtons);
                })
                .show();
    }

    /**
     * 打开终端：ubuntu 条目必须开进 proot 容器（本机 shell 里跑不了 apt/php/python3），
     * android 条目保持原来的本机 shell。
     */
    private void openTerminal() {
        String name = item == null ? itemName : item.name;
        if (item != null && item.isUbuntu()) {
            if (!LabUbuntu.installed(this)) {
                Toast.makeText(this, "请先在实验室设置里装好 Ubuntu 环境", Toast.LENGTH_LONG).show();
                return;
            }
            String shell = LabUbuntu.shellCommand(this);
            if (shell == null || shell.isEmpty()) {
                Toast.makeText(this, "proot 未就绪", Toast.LENGTH_SHORT).show();
                return;
            }
            LabTerminalActivity.start(this, name, name, shell);
            return;
        }
        LabTerminalActivity.start(this, name, name);
    }

    /** 容器条目的环境安装：在容器终端里跑 install.command（apt 输出看得见）。 */
    private void onContainerInstall() {
        if (!item.hasInstall()) {
            // 「终端」这类条目本来就不装环境：直接附着容器终端，别静默无反应
            if (item.isUbuntu() && item.terminal_auto_open) {
                openTerminal();
            } else {
                Toast.makeText(this, "该条目无需安装环境", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (!LabUbuntu.installed(this)) {
            Toast.makeText(this, "请先在实验室设置里装好 Ubuntu 环境", Toast.LENGTH_LONG).show();
            return;
        }
        // install.command 里带 {package_dir} 等占位符，必须先展开再进容器，
        // 否则 touch {package_dir}/.installed 会写成字面路径，安装状态永远点不亮。
        String expanded = LabRunner.expand(this, item, item.install.command, null);
        String cmd = LabUbuntu.prootCommand(this, wrapInstall(item, expanded, LabEnv.packageDir(this, item)));
        if (cmd == null || cmd.isEmpty()) {
            Toast.makeText(this, "proot 未就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        LabTerminalActivity.start(this, item.name + " 安装", item.name, cmd);
    }

    /**
     * 给 install.command 套壳：分三段跑，每段都打进终端（谁在干什么一眼看清）。
     *
     * <p>1/3 清 apt/dpkg 残留锁；2/3 有中断残留就修（先 dpkg -f install，修不动才清**本环境自己**的包）；
     * 3/3 安装。跑完按**真实退出码**决定是否写安装标记，退出码与标记路径都打出来。
     *
     * <p>json 里的 install.command 是 `apt-get ... && touch '{package_dir}/.installed'`
     * 这种长 && 链，中间任何一步非 0 都会让 touch 永不执行、终端里还看不出失败在哪。
     */
    private String wrapInstall(LabModels.Item item, String command, java.io.File packageDir) {
        String dir = packageDir.getAbsolutePath().replace("'", "'\\''");
        String name = item == null || item.name == null ? "env" : item.name;
        String reset = item != null && item.install != null && item.install.hasReset()
                ? LabRunner.expand(this, item, item.install.reset_command, null) : "";
        StringBuilder sb = new StringBuilder();
        sb.append("export DEBIAN_FRONTEND=noninteractive; ");
        sb.append("echo \"[lab] ===== ").append(name).append(" 安装（独立终端） =====\"; ");
        sb.append(LOCK_CLEANUP);
        sb.append("echo \"[lab] 1/3 残留锁已清；2/3 检查上次中断残留\"; ");
        sb.append("if ! apt-get check >/dev/null 2>&1 || [ -n \"$(dpkg --audit 2>/dev/null)\" ]; then ");
        sb.append("echo \"[lab] 检测到 dpkg 残留，先修复（可能顺带处理其它环境遗留的半装包，三个环境共用同一套 apt/dpkg 库，属正常）...\"; ");
        sb.append("dpkg --configure -a; apt-get -f install -y; ");
        if (reset.isEmpty()) {
            sb.append("echo \"[lab] 残留处理完毕\"; ");
        } else {
            sb.append("if [ -n \"$(dpkg --audit 2>/dev/null)\" ]; then ");
            sb.append("echo \"[lab] 残留仍未清干净，先卸载本环境自己的包再重装...\"; { ").append(reset).append(" ; } ; ");
            sb.append("else echo \"[lab] 残留已修复\"; fi; ");
        }
        sb.append("else echo \"[lab] 无残留，直接安装\"; fi; ");
        sb.append("echo \"[lab] 3/3 安装 ").append(name).append("\"; ");
        sb.append("{ ").append(command).append(" ; } ; __lab_ec=$?; ");
        sb.append("echo \"[lab] install exit=$__lab_ec\"; ");
        sb.append("if [ $__lab_ec -eq 0 ]; then mkdir -p '").append(dir).append("' && touch '").append(dir)
                .append("/.installed' && echo \"[lab] 标记已写入: ").append(dir).append("/.installed\"; ");
        sb.append("else echo \"[lab] 安装失败（exit=$__lab_ec），未写标记\"; fi; ");
        sb.append("exit $__lab_ec");
        return sb.toString();
    }

    /** 清掉 apt/dpkg 的几把残留锁（上次装到一半被杀留下的锁会挡住后面所有安装，幂等）。 */
    private static final String LOCK_CLEANUP =
            "for __lab_lock in /var/lib/dpkg/lock-frontend /var/lib/dpkg/lock "
                    + "/var/lib/apt/lists/lock /var/cache/apt/archives/lock; do "
                    + "[ -e \"$__lab_lock\" ] && rm -f \"$__lab_lock\"; done; ";

    private void onContainerUninstall() {
        if (item.install == null || !item.install.hasUninstall()) {
            Toast.makeText(this, "该条目没有配置卸载命令", Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this, R.style.Theme_App_Lab_Dialog)
                .setTitle("卸载 " + item.name)
                .setMessage("将从 Ubuntu 环境里【真实卸载】 " + item.name + " 本体（apt purge 本环境自己的包），"
                        + "并先停掉它所有运行中的命令。\n共用目录（如 wwwroot）与其它环境不受影响。")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("卸载", (d, w) -> runContainerUninstall())
                .show();
    }

    private void runContainerUninstall() {
        // 卸载前先停掉该条目所有运行中的命令（否则 php -S / node 这些还在占用文件与端口）
        LabActions.stopItem(item);
        String expanded = LabRunner.expand(this, item, item.install.uninstall_command, null);
        String cmd = LabUbuntu.prootCommand(this, wrapUninstall(item, expanded, LabEnv.packageDir(this, item)));
        if (cmd == null || cmd.isEmpty()) {
            Toast.makeText(this, "proot 未就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        LabTerminalActivity.start(this, item.name + " 卸载", item.name, cmd);
    }

    /**
     * 给 uninstall_command 套壳：清锁 → 修 dpkg 残留 → **真卸本体** → 打退出码。
     * 退出码为 0 时由引擎兜底再删一次安装标记（json 里删过也无害，幂等）。
     */
    private String wrapUninstall(LabModels.Item item, String command, java.io.File packageDir) {
        String dir = packageDir.getAbsolutePath().replace("'", "'\\''");
        String name = item == null || item.name == null ? "env" : item.name;
        StringBuilder sb = new StringBuilder();
        sb.append("export DEBIAN_FRONTEND=noninteractive; ");
        sb.append("echo \"[lab] ===== ").append(name).append(" 卸载（独立终端） =====\"; ");
        sb.append(LOCK_CLEANUP);
        sb.append("echo \"[lab] 1/3 残留锁已清；2/3 修复 dpkg 残留（有才修）\"; ");
        sb.append("if ! apt-get check >/dev/null 2>&1 || [ -n \"$(dpkg --audit 2>/dev/null)\" ]; then ");
        sb.append("dpkg --configure -a >/dev/null 2>&1; apt-get -f install -y >/dev/null 2>&1; echo \"[lab] 已修复\"; ");
        sb.append("else echo \"[lab] 无残留\"; fi; ");
        sb.append("echo \"[lab] 3/3 卸载 ").append(name).append(" 环境本体\"; ");
        sb.append("{ ").append(command).append(" ; } ; __lab_ec=$?; ");
        sb.append("echo \"[lab] uninstall exit=$__lab_ec\"; ");
        sb.append("if [ $__lab_ec -eq 0 ]; then rm -f '").append(dir)
                .append("/.installed' && echo \"[lab] 标记已移除: ").append(dir).append("/.installed\"; ");
        sb.append("else echo \"[lab] 卸载未完成（exit=$__lab_ec），安装标记保留\"; fi; ");
        sb.append("exit $__lab_ec");
        return sb.toString();
    }

    private void onRefreshCommands() {
        new MaterialAlertDialogBuilder(this, R.style.Theme_App_Lab_Dialog)
                .setTitle("重置命令列表")
                .setMessage("确定要从远程重新加载命令列表吗？这将覆盖本地修改的命令，并停止所有运行中的进程。")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("确定", (d, w) -> {
                    LabActions.stopItem(item);
                    LabConfig.get().clearCommandCache(item.name);
                    LabConfig.get().clearCommandOverrides(item.name);
                    reload();
                    Notify.show(R.string.lab_refresh_success);
                })
                .show();
    }

    @Override
    public void onOpen(LabModels.Item item, LabModels.Command command) {
        LabCustomCommands.CustomCommand custom = customCommand(command);
        if (custom != null) {
            LabActions.runCustom(this, item, custom, this::updateButtons);
            return;
        }
        if (!command.isSupported(LabEnv.appVersionCode(this))) {
            Toast.makeText(this, "需要更新影视+至 v" + command.min_version + " 以上", Toast.LENGTH_SHORT).show();
            return;
        }
        new LabCommandSheet(this, item, command, this::updateButtons).show();
    }

    @Override
    public void onAction(LabModels.Item item, LabModels.Command command) {
        String key = item.name + "/" + command.id;
        boolean running = LabRunner.isRunning(key) || Boolean.TRUE.equals(checkState.get(command.id));
        if (running) {
            if (command.hasStop()) {
                LabRunner.runStop(this, item, command, command.cachedVariableValues, null);
                checkState.put(command.id, false);
            } else {
                LabRunner.stop(key);
            }
            Notify.show(R.string.lab_command_stopped);
        } else {
            LabCommandSheet sheet = new LabCommandSheet(this, item, command, this::updateButtons);
            sheet.show();
            sheet.run();
        }
        updateButtons();
    }

    @Override
    public void onLongPress(LabModels.Item item, LabModels.Command command) {
        LabCommandEditDialog.show(this, item, command, this::reload);
    }

    private LabCustomCommands.CustomCommand customCommand(LabModels.Command command) {
        if (command == null || command.id == null || !command.id.startsWith("custom_")) return null;
        for (LabCustomCommands.CustomCommand custom : LabCustomCommands.list(item.name)) {
            if (command.id.equals(custom.id)) return custom;
        }
        return null;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (item != null && item.hasSettings()) {
            getMenuInflater().inflate(R.menu.menu_lab_detail, menu);
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        if (menuItem.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        if (menuItem.getItemId() == R.id.settings && item != null && item.hasSettings()) {
            showPackageSettings();
            return true;
        }
        if (menuItem.getItemId() == R.id.export) {
            exportPackage();
            return true;
        }
        return super.onOptionsItemSelected(menuItem);
    }

    private void exportPackage() {
        if (LabEnv.needsInstall(item) && !LabEnv.installed(this, item)) {
            Toast.makeText(this, "该包尚未安装", Toast.LENGTH_SHORT).show();
            return;
        }
        TextView progress = new TextView(this);
        progress.setTextColor(Color.WHITE);
        progress.setTextSize(14);
        progress.setPadding(48, 32, 48, 32);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this, R.style.Theme_App_Lab_Dialog)
                .setTitle("打包导出")
                .setView(progress)
                .setCancelable(false)
                .setPositiveButton("关闭", null)
                .create();
        dialog.show();
        LabActions.hideCloseButton(dialog);
        new Thread(() -> {
            try {
                File file = LabEnv.exportPackage(this, item, LabCustomCommands.list(item.name));
                App.post(() -> {
                    dialog.dismiss();
                    Notify.show("已导出到: " + file.getAbsolutePath());
                });
            } catch (Exception e) {
                App.post(() -> {
                    progress.setText("导出失败：\n" + e.getMessage());
                    LabActions.showCloseButton(dialog);
                });
            }
        }).start();
    }

    private void showPackageSettings() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(48, 24, 48, 8);
        Map<String, String> values = LabConfig.get().loadUserSettings(item.name);
        for (LabModels.Setting setting : item.settings) {
            View row = inflateSetting(container, setting, values);
            if (row != null) container.addView(row);
        }
        new MaterialAlertDialogBuilder(this, R.style.Theme_WebHTV_LightDialog)
                .setTitle(item.name + " 设置")
                .setView(container)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("保存", (d, w) -> {
                    LabConfig.get().saveUserSettings(item.name, values);
                    Notify.show("设置已保存");
                })
                .show();
    }

    private View inflateSetting(LinearLayout parent, LabModels.Setting setting, Map<String, String> values) {
        String type = setting.type == null ? "text" : setting.type;
        int layout;
        switch (type) {
            case "number":
                layout = R.layout.item_lab_var_number_dialog;
                break;
            case "select":
                layout = R.layout.item_lab_var_select_dialog;
                break;
            case "switch":
                layout = R.layout.item_lab_var_switch_dialog;
                break;
            case "directory":
                layout = R.layout.item_lab_var_dir_dialog;
                break;
            default:
                layout = R.layout.item_lab_var_text_dialog;
                break;
        }
        View row = getLayoutInflater().inflate(layout, parent, false);
        if ("switch".equals(type)) {
            TextView name = row.findViewById(R.id.name);
            TextView hint = row.findViewById(R.id.hint);
            MaterialSwitch sw = row.findViewById(R.id.switchView);
            name.setText(setting.name);
            if (setting.hint != null) {
                hint.setText(setting.hint);
                hint.setVisibility(View.VISIBLE);
            }
            String value = values.containsKey(setting.key) ? values.get(setting.key) : setting.defaultValue;
            sw.setChecked("true".equals(value) || "1".equals(value));
            values.put(setting.key, sw.isChecked() ? "true" : "false");
            sw.setOnCheckedChangeListener((button, checked) -> values.put(setting.key, checked ? "true" : "false"));
            return row;
        }
        TextInputLayout layoutContainer = row.findViewById(R.id.inputLayout);
        EditText edit = row.findViewById(R.id.input);
        if (edit == null) edit = row.findViewById(R.id.dropdown);
        if (edit == null) return row;
        layoutContainer.setHint(setting.name);
        if (setting.hint != null) layoutContainer.setPlaceholderText(setting.hint);
        String value = values.containsKey(setting.key) ? values.get(setting.key) : setting.defaultValue;
        if (value == null) value = "";
        edit.setText(value);
        values.put(setting.key, value);
        if ("select".equals(type) && edit instanceof MaterialAutoCompleteTextView) {
            MaterialAutoCompleteTextView dropdown = (MaterialAutoCompleteTextView) edit;
            List<LabModels.Option> options = setting.options == null ? new java.util.ArrayList<LabModels.Option>() : setting.options;
            String[] labels = new String[options.size()];
            for (int i = 0; i < labels.length; i++) labels[i] = options.get(i).label;
            dropdown.setSimpleItems(labels);
            for (LabModels.Option option : options) {
                if (value.equals(option.value)) {
                    dropdown.setText(option.label, false);
                    break;
                }
            }
            dropdown.setOnItemClickListener((p, view, position, id) -> values.put(setting.key, options.get(position).value));
        } else {
            edit.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(android.text.Editable s) {
                    values.put(setting.key, s == null ? "" : s.toString());
                }
            });
        }
        return row;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (item != null) updateButtons();
        mRefreshHandler.postDelayed(mRefreshRunnable, 2000);
    }

    @Override
    protected void onPause() {
        mRefreshHandler.removeCallbacks(mRefreshRunnable);
        super.onPause();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        LabFilePicker.onActivityResult(requestCode, resultCode, data);
    }
}
