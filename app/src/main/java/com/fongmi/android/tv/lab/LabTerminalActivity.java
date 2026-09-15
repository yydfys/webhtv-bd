package com.fongmi.android.tv.lab;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivityLabTerminalBinding;
import com.fongmi.android.tv.setting.Setting;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;

public class LabTerminalActivity extends AppCompatActivity implements LabTerminalPrefs.Listener {

    /** 开关高亮色 / 置灰色（与顶栏其它图标同一套配色）。 */
    private static final int COLOR_ON = Color.parseColor("#FF8A65");
    private static final int COLOR_OFF = Color.parseColor("#666666");

    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_PACKAGE = "package";
    private static final String EXTRA_CMD = "command";

    private ActivityLabTerminalBinding mBinding;
    private Process process;
    private OutputStream stdin;
    private boolean stopped;
    private boolean ctrlMode;
    private int historyIndex = -1;
    private final LinkedList<String> history = new LinkedList<>();
    private final ArrayList<String> commandHistory = new ArrayList<>();
    private LabModels.Item item;
    private String commandLine;

    public static void start(Context context, String title) {
        start(context, title, null);
    }

    public static void start(Context context, String title, String packageName) {
        start(context, title, packageName, null);
    }

    /**
     * commandLine 非空时用它作为 shell 的启动命令——Ubuntu 子系统靠它把终端
     * 直接开进 proot 容器（/system/bin/sh -c "proot ... /bin/bash -i"），
     * 之后用户输入的命令都在容器内的 bash 里执行。
     */
    public static void start(Context context, String title, String packageName, String commandLine) {
        Intent intent = new Intent(context, LabTerminalActivity.class);
        intent.putExtra(EXTRA_TITLE, title);
        if (packageName != null) intent.putExtra(EXTRA_PACKAGE, packageName);
        if (commandLine != null) intent.putExtra(EXTRA_CMD, commandLine);
        context.startActivity(intent);
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(Setting.wrapDisplay(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mBinding = ActivityLabTerminalBinding.inflate(getLayoutInflater());
        setContentView(mBinding.getRoot());
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String packageName = getIntent().getStringExtra(EXTRA_PACKAGE);
        commandLine = getIntent().getStringExtra(EXTRA_CMD);
        if (!TextUtils.isEmpty(packageName)) {
            LabModels.LabRoot root = LabConfig.get().getLabRoot();
            if (root != null && root.lists != null) {
                for (LabModels.Item candidate : root.lists) {
                    if (packageName.equals(candidate.name)) {
                        item = candidate;
                        break;
                    }
                }
            }
        }
        mBinding.termTitle.setText(TextUtils.isEmpty(title) ? "terminal" : title);
        mBinding.btnBack.setOnClickListener(v -> finish());
        mBinding.btnClear.setOnClickListener(v -> {
            history.clear();
            commandHistory.clear();
            historyIndex = -1;
            mBinding.termOutput.setText("");
            renderHistory();
            Toast.makeText(this, "已清除终端日志", Toast.LENGTH_SHORT).show();
        });
        // 两个显示开关（照 VodPlus 终端）：自动滚动 / 自动换行。状态全局共享，多窗口实时同步。
        mBinding.btnAutoScroll.setOnClickListener(v -> {
            boolean next = !LabTerminalPrefs.autoScroll();
            LabTerminalPrefs.setAutoScroll(next);
            Toast.makeText(this, next ? "已开启自动滚动" : "已关闭自动滚动", Toast.LENGTH_SHORT).show();
        });
        mBinding.btnAutoWrap.setOnClickListener(v -> {
            boolean next = !LabTerminalPrefs.autoWrap();
            LabTerminalPrefs.setAutoWrap(next);
            Toast.makeText(this, next ? "已开启自动换行" : "已关闭自动换行", Toast.LENGTH_SHORT).show();
        });
        LabTerminalPrefs.addListener(this);
        applyTerminalPrefs();
        mBinding.btnSend.setOnClickListener(v -> runInput());
        mBinding.termInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        mBinding.termInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_GO) {
                runInput();
                return true;
            }
            return false;
        });
        mBinding.termInput.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                navigateHistory(-1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                navigateHistory(1);
                return true;
            }
            return false;
        });
        buildShortcuts();
        append("实验室终端（交互式 shell）\n输入命令后回车执行，例如: ls、echo hello、apt update\n\n");
        // 自动执行模式（安装/卸载等）：把要跑的命令先回显出来，
        // 否则跑完只剩一句"shell 已退出"，用户根本看不出到底跑没跑、跑了什么。
        if (!TextUtils.isEmpty(commandLine)) {
            append("$ " + summarize(commandLine) + "\n\n");
        }
        startShell();
    }

    /** 回显用：命令太长（proot 一整套参数）就截断，避免刷满整屏。 */
    private String summarize(String command) {
        String one = command.replace('\n', ' ');
        return one.length() > 400 ? one.substring(0, 400) + " …" : one;
    }

    private void buildShortcuts() {
        String[] keys = {"ESC", "TAB", "CTRL", "↑", "↓", "←", "→", "/", "-", "|"};
        float density = getResources().getDisplayMetrics().density;
        for (String key : keys) {
            TextView button = new TextView(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (int) (30 * density));
            params.setMarginEnd((int) (2 * density));
            button.setLayoutParams(params);
            button.setGravity(android.view.Gravity.CENTER);
            button.setPadding((int) (10 * density), 0, (int) (10 * density), 0);
            button.setText(key);
            button.setTextColor(Color.parseColor("#CCCCCC"));
            button.setTextSize(11);
            button.setMaxLines(1);
            button.setBackground(null);
            button.setOnClickListener(v -> shortcut(key));
            mBinding.shortcutContainer.addView(button);
        }
    }

    private void shortcut(String key) {
        switch (key) {
            case "ESC":
                writeRaw(new byte[]{0x1b});
                break;
            case "TAB":
                writeRaw(new byte[]{0x09});
                break;
            case "CTRL":
                ctrlMode = !ctrlMode;
                mBinding.termPrompt.setTextColor(ctrlMode ? Color.parseColor("#FF8A65") : Color.parseColor("#AAFFAA"));
                break;
            case "↑":
                navigateHistory(-1);
                break;
            case "↓":
                navigateHistory(1);
                break;
            case "←":
                writeRaw(new byte[]{0x1b, 0x5b, 0x44});
                break;
            case "→":
                writeRaw(new byte[]{0x1b, 0x5b, 0x43});
                break;
            default:
                insertText(key);
                break;
        }
    }

    private void runInput() {
        String command = mBinding.termInput.getText() == null ? "" : mBinding.termInput.getText().toString();
        if (command.isEmpty()) return;
        if (ctrlMode && command.length() > 0) {
            char c = Character.toLowerCase(command.charAt(0));
            if (c >= 'a' && c <= 'z') {
                writeRaw(new byte[]{(byte) (c - '`')});
                append("^" + Character.toUpperCase(c) + "\n");
            }
            mBinding.termInput.setText("");
            ctrlMode = false;
            mBinding.termPrompt.setTextColor(Color.parseColor("#AAFFAA"));
            return;
        }
        mBinding.termInput.setText("");
        if (commandHistory.isEmpty() || !commandHistory.get(commandHistory.size() - 1).equals(command)) {
            commandHistory.add(command);
            if (commandHistory.size() > 50) commandHistory.remove(0);
        }
        historyIndex = commandHistory.size();
        renderHistory();
        append("$ " + command + "\n");
        try {
            if (process == null || !process.isAlive()) startShell();
            if (stdin != null) {
                stdin.write((command + "\n").getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }
        } catch (Exception e) {
            append("[写入失败: " + e.getMessage() + "]\n");
        }
    }

    private void insertText(String text) {
        int start = mBinding.termInput.getSelectionStart();
        int end = mBinding.termInput.getSelectionEnd();
        if (start < 0) start = 0;
        if (end < 0) end = 0;
        mBinding.termInput.getText().replace(Math.min(start, end), Math.max(start, end), text, 0, text.length());
    }

    private void writeRaw(byte[] bytes) {
        try {
            if (process == null || !process.isAlive()) startShell();
            if (stdin != null) {
                stdin.write(bytes);
                stdin.flush();
            }
        } catch (Exception ignored) {
        }
    }

    private void navigateHistory(int direction) {
        if (commandHistory.isEmpty()) return;
        historyIndex += direction;
        if (historyIndex < 0) historyIndex = 0;
        if (historyIndex >= commandHistory.size()) {
            historyIndex = commandHistory.size();
            mBinding.termInput.setText("");
        } else {
            String text = commandHistory.get(historyIndex);
            mBinding.termInput.setText(text);
            mBinding.termInput.setSelection(text.length());
        }
    }

    private void renderHistory() {
        mBinding.historyContainer.removeAllViews();
        if (commandHistory.isEmpty()) {
            mBinding.historyScroll.setVisibility(View.GONE);
            return;
        }
        mBinding.historyScroll.setVisibility(View.VISIBLE);
        float density = getResources().getDisplayMetrics().density;
        int max = Math.max(0, commandHistory.size() - 10);
        for (int i = commandHistory.size() - 1; i >= max; i--) {
            String text = commandHistory.get(i);
            TextView chip = new TextView(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (int) (28 * density));
            params.setMarginEnd((int) (6 * density));
            chip.setLayoutParams(params);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setPadding((int) (12 * density), 0, (int) (12 * density), 0);
            chip.setText(text.length() > 30 ? text.substring(0, 30) + "…" : text);
            chip.setTextColor(Color.parseColor("#FF8A65"));
            chip.setTextSize(11);
            chip.setBackgroundResource(R.drawable.shape_lab_output_bg);
            chip.setOnClickListener(v -> {
                mBinding.termInput.setText(text);
                mBinding.termInput.setSelection(text.length());
            });
            mBinding.historyContainer.addView(chip);
        }
    }

    private void startShell() {
        if (process != null && process.isAlive()) return;
        try {
            boolean ubuntu = !TextUtils.isEmpty(commandLine);
            ProcessBuilder builder = ubuntu
                    ? new ProcessBuilder("/system/bin/sh", "-c", commandLine)
                    : new ProcessBuilder("/system/bin/sh");
            builder.redirectErrorStream(true);
            if (item != null) {
                File cwd = LabEnv.packageRoot(this, item);
                // 包目录可能还不存在（首次安装）：先把工作目录建出来，
                // 否则 ProcessBuilder.directory() 指向不存在的目录会让 shell 直接起不来。
                if (!cwd.exists()) cwd.mkdirs();
                builder.directory(cwd);
                LabRunner.applyEnv(builder.environment(), this, item);
                builder.environment().put("HOME", cwd.getAbsolutePath());
                builder.environment().put("TERM", "dumb");
            } else if (ubuntu) {
                // Ubuntu 终端：proot 是动态链接的，必须带上 loader 与依赖 .so 的搜索路径
                builder.directory(getFilesDir());
                builder.environment().putAll(LabEnv.prootEnv(this));
                String existing = builder.environment().get("LD_LIBRARY_PATH");
                String prootDir = LabEnv.prootRoot(this).getAbsolutePath();
                builder.environment().put("LD_LIBRARY_PATH", TextUtils.isEmpty(existing) ? prootDir : existing + ":" + prootDir);
                builder.environment().put("PATH", LabEnv.sharedBin(this).getAbsolutePath() + ":/system/bin:/system/xbin");
                builder.environment().put("HOME", getFilesDir().getAbsolutePath());
                builder.environment().put("TERM", "dumb");
            }
            process = builder.start();
            stdin = process.getOutputStream();
            stopped = false;
            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!stopped) append(line + "\n");
                    }
                } catch (Exception ignored) {
                }
                if (!stopped) {
                    int code = -1;
                    try {
                        if (process != null) code = process.exitValue();
                    } catch (Exception ignored) {
                    }
                    append("\n[已退出 code=" + code + "，输入命令可重启]\n");
                }
            }).start();
        } catch (Exception e) {
            append("启动 shell 失败: " + e.getMessage() + "\n");
        }
    }

    private void stopProcess() {
        stopped = true;
        try {
            if (stdin != null) stdin.close();
        } catch (Exception ignored) {
        }
        if (process != null) {
            process.destroy();
            process = null;
        }
        stdin = null;
    }

    private void append(String text) {
        App.post(() -> {
            mBinding.termOutput.append(text);
            if (LabTerminalPrefs.autoScroll()) scrollToBottom();
        });
    }

    /** 自动滚动开关变化（含其它终端窗口触发的变更）→ 同步按钮外观并立即套用显示效果。 */
    @Override
    public void onTerminalPrefsChanged() {
        App.post(this::applyTerminalPrefs);
    }

    private void applyTerminalPrefs() {
        boolean scroll = LabTerminalPrefs.autoScroll();
        boolean wrap = LabTerminalPrefs.autoWrap();
        mBinding.btnAutoScroll.setImageTintList(ColorStateList.valueOf(scroll ? COLOR_ON : COLOR_OFF));
        mBinding.btnAutoScroll.setAlpha(scroll ? 1f : 0.7f);
        mBinding.btnAutoScroll.setContentDescription(scroll ? "自动滚动已开启" : "自动滚动已关闭");
        mBinding.btnAutoWrap.setImageTintList(ColorStateList.valueOf(wrap ? COLOR_ON : COLOR_OFF));
        mBinding.btnAutoWrap.setAlpha(wrap ? 1f : 0.7f);
        mBinding.btnAutoWrap.setContentDescription(wrap ? "自动换行已开启" : "自动换行已关闭");
        // 换行开 → 文本按屏宽折行；换行关 → 文本按最长行撑开，横向拖动查看
        // 关键：HorizontalScrollView 默认用 UNSPECIFIED 量孩子，TextView 会按最长行撑开、永不折行，
        // 所以必须让容器在"换行开"时按父宽 EXACTLY 量子视图（见 LabTerminalScrollView）。
        mBinding.termHScroll.setWrapEnabled(wrap);
        ViewGroup.LayoutParams params = mBinding.termOutput.getLayoutParams();
        int width = wrap ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT;
        if (params.width != width) {
            params.width = width;
            mBinding.termOutput.setLayoutParams(params);
        }
        mBinding.termOutput.setHorizontallyScrolling(!wrap);
        if (scroll) scrollToBottom();
    }

    private void scrollToBottom() {
        mBinding.termScroll.post(() -> mBinding.termScroll.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    protected void onDestroy() {
        LabTerminalPrefs.removeListener(this);
        stopProcess();
        super.onDestroy();
    }
}
