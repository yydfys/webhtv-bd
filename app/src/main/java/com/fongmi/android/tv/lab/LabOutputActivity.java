package com.fongmi.android.tv.lab;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivityLabOutputBinding;

import java.util.HashMap;

public class LabOutputActivity extends AppCompatActivity implements LabTerminalPrefs.Listener {

    /** 开关高亮色 / 置灰色（与终端窗口同一套配色）。 */
    private static final int COLOR_ON = Color.parseColor("#FF8A65");
    private static final int COLOR_OFF = Color.parseColor("#666666");

    private static LabOutputActivity sInstance;

    private ActivityLabOutputBinding mBinding;
    private LabModels.Item item;
    private LabModels.Command command;
    private String itemName;
    private String commandId;
    private HashMap<String, String> vars;

    public static void start(Context context, String itemName, String commandId, HashMap<String, String> vars) {
        Intent intent = new Intent(context, LabOutputActivity.class);
        intent.putExtra("item", itemName);
        intent.putExtra("command", commandId);
        intent.putExtra("vars", vars);
        context.startActivity(intent);
    }

    public static void appendGlobal(String text) {
        if (sInstance != null) sInstance.append(text);
    }

    public static void onExitGlobal(int code) {
        if (sInstance != null) sInstance.onExit(code);
    }

    private String key() {
        return itemName + "/" + commandId;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mBinding = ActivityLabOutputBinding.inflate(getLayoutInflater());
        setContentView(mBinding.getRoot());
        sInstance = this;

        itemName = getIntent().getStringExtra("item");
        commandId = getIntent().getStringExtra("command");
        //noinspection unchecked
        vars = (HashMap<String, String>) getIntent().getSerializableExtra("vars");
        if (vars == null) vars = new HashMap<>();

        item = findItem(itemName);
        command = findCommand(item, commandId);

        mBinding.btnClose.setOnClickListener(v -> finish());
        mBinding.btnStop.setOnClickListener(v -> stop());
        mBinding.btnSend.setOnClickListener(v -> sendInput());
        // 两个显示开关（照 VodPlus 终端）：自动滚动 / 自动换行；状态全局共享，多窗口实时同步
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
        mBinding.inputEdit.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_GO) {
                sendInput();
                return true;
            }
            return false;
        });

        mBinding.outputTitle.setText(command == null ? (commandId == null ? "输出" : commandId)
                : (command.name == null ? command.description : command.name));

        String log = LabRunner.getLog(key());
        if (!TextUtils.isEmpty(log)) mBinding.outputText.setText(log);

        boolean running = LabRunner.isRunning(key());
        showRunning(running);
    }

    private LabModels.Item findItem(String name) {
        if (name == null) return null;
        LabModels.LabRoot root = LabConfig.get().getLabRoot();
        if (root != null && root.lists != null) {
            for (LabModels.Item candidate : root.lists) {
                if (name.equals(candidate.name)) return candidate;
            }
        }
        return null;
    }

    private LabModels.Command findCommand(LabModels.Item item, String id) {
        if (item == null || id == null || item.commands == null) return null;
        for (LabModels.Command c : item.commands) {
            if (id.equals(c.id)) return c;
        }
        return null;
    }

    private void showRunning(boolean running) {
        mBinding.btnStop.setVisibility(running ? View.VISIBLE : View.GONE);
        boolean interactive = running && command != null && command.isShowOutput() && !command.isBackground();
        mBinding.inputContainer.setVisibility(interactive ? View.VISIBLE : View.GONE);
    }

    private void append(String text) {
        App.post(() -> {
            mBinding.outputText.append(text);
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
        // 换行开 → 按屏宽折行；换行关 → 按最长行撑开，横向拖动查看
        ViewGroup.LayoutParams params = mBinding.outputText.getLayoutParams();
        int width = wrap ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT;
        if (params.width != width) {
            params.width = width;
            mBinding.outputText.setLayoutParams(params);
        }
        mBinding.outputText.setHorizontallyScrolling(!wrap);
        if (scroll) scrollToBottom();
    }

    private void scrollToBottom() {
        mBinding.outputScroll.post(() -> mBinding.outputScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void onExit(int code) {
        App.post(() -> {
            append("\n[进程结束，退出码 " + code + "]\n");
            showRunning(false);
        });
    }

    private void stop() {
        LabRunner.stop(key());
        showRunning(false);
        append("\n=== 进程已停止 ===\n");
    }

    private void sendInput() {
        String text = mBinding.inputEdit.getText() == null ? "" : mBinding.inputEdit.getText().toString();
        if (text.isEmpty()) return;
        mBinding.inputEdit.setText("");
        append("$ " + text + "\n");
        if (!LabRunner.writeInput(key(), text)) {
            append("[发送失败：进程可能不支持交互输入]\n");
        }
    }

    @Override
    protected void onDestroy() {
        LabTerminalPrefs.removeListener(this);
        if (sInstance == this) sInstance = null;
        super.onDestroy();
    }
}
