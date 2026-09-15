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

public class LabOutputActivity extends AppCompatActivity implements LabTerminalPrefs.Listener, LabRunner.OutputListener {

    /** 开关高亮色 / 置灰色（与终端窗口同一套配色）。 */
    private static final int COLOR_ON = Color.parseColor("#FF8A65");
    private static final int COLOR_OFF = Color.parseColor("#666666");

    /**
     * 一命令一窗口（照 VodPlus 的多终端模型）：每个命令的日志窗各自独立、可同时开着，
     * 各自只收自己命令的输出流；同一命令重复打开时复用已有窗口（SINGLE_TOP）。
     */
    private static final java.util.Map<String, LabOutputActivity> INSTANCES = new java.util.concurrent.ConcurrentHashMap<>();

    private ActivityLabOutputBinding mBinding;
    private LabModels.Item item;
    private LabModels.Command command;
    private String itemName;
    private String commandId;
    private HashMap<String, String> vars;

    /** 输出落屏：合帧缓冲 + 终端语义写入器（\r 回行首覆盖 / 剥离 ANSI）。 */
    private final StringBuilder pendingOut = new StringBuilder();
    private final Runnable flushRunnable = this::flushOutput;
    private boolean flushScheduled;
    private LabTerminalWriter writer;

    public static void start(Context context, String itemName, String commandId, HashMap<String, String> vars) {
        Intent intent = new Intent(context, LabOutputActivity.class);
        intent.putExtra("item", itemName);
        intent.putExtra("command", commandId);
        intent.putExtra("vars", vars);
        // 已有该命令的窗口 → 直接拿到前台，不再叠新实例
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        context.startActivity(intent);
    }

    private String key() {
        return itemName + "/" + commandId;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mBinding = ActivityLabOutputBinding.inflate(getLayoutInflater());
        setContentView(mBinding.getRoot());

        itemName = getIntent().getStringExtra("item");
        commandId = getIntent().getStringExtra("command");
        //noinspection unchecked
        vars = (HashMap<String, String>) getIntent().getSerializableExtra("vars");
        if (vars == null) vars = new HashMap<>();
        writer = new LabTerminalWriter(mBinding.outputText);
        INSTANCES.put(key(), this);
        // 自己订阅该命令的输出流：窗口开着就实时刷，关掉即退订（不影响命令继续跑）
        LabRunner.addListener(key(), this);

        item = findItem(itemName);
        command = findCommand(item, commandId);

        mBinding.btnClose.setOnClickListener(v -> finish());
        mBinding.btnStop.setOnClickListener(v -> stop());
        mBinding.btnSend.setOnClickListener(v -> sendInput());
        // 「清除当前日志」：清屏 + 清掉本命令的内存/落盘日志，窗口重开不再回放旧日志
        mBinding.btnClear.setOnClickListener(v -> {
            LabRunner.clearLog(key());
            pendingOut.setLength(0);
            writer.clear();
            updateTitle();
            Toast.makeText(this, "已清除当前日志", Toast.LENGTH_SHORT).show();
        });
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

        String log = LabRunner.getLog(key());
        if (!TextUtils.isEmpty(log)) mBinding.outputText.setText(log);

        boolean running = LabRunner.isRunning(key());
        showRunning(running);
        updateTitle();
    }

    /** 标题带状态：命令名 · 运行中 / 已结束，一眼看出这条终端还活着没。 */
    private void updateTitle() {
        String base = command == null ? (commandId == null ? "输出" : commandId)
                : (command.name == null ? command.description : command.name);
        boolean running = LabRunner.isRunning(key());
        String suffix = running ? " · 运行中" : (TextUtils.isEmpty(LabRunner.getLog(key())) ? "" : " · 已结束");
        mBinding.outputTitle.setText(base + suffix);
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
        updateTitle();
    }

    /**
     * 输出落屏（高频路径）：先攒进缓冲，50ms 内合并成一批再写。
     *
     * <p>字节级泵是"收到多少发多少"，一帧进度条会被拆成很多小段；逐段 append
     * 会让 TextView 反复重排。合帧后既保持实时（最迟 50ms 上屏），又不会卡。
     */
    private void queue(String text) {
        if (text == null || text.isEmpty() || writer == null) return;
        synchronized (pendingOut) {
            pendingOut.append(text);
        }
        if (flushScheduled) return;
        flushScheduled = true;
        App.post(flushRunnable, 50);
    }

    /** 立即落屏（结束/停止/发送回显这类一次性文案，不参与合帧）；非主线程自动转主线程。 */
    private void append(String text) {
        if (text == null || text.isEmpty() || writer == null) return;
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            final String chunk = text;
            App.post(() -> append(chunk));
            return;
        }
        synchronized (pendingOut) {
            pendingOut.append(text);
        }
        flushOutput();
    }

    /** 主线程：把攒下的输出一次写进终端窗（\r 回行首 / ANSI 剥离都在 writer 里做）。 */
    private void flushOutput() {
        flushScheduled = false;
        String chunk;
        synchronized (pendingOut) {
            if (pendingOut.length() == 0) return;
            chunk = pendingOut.toString();
            pendingOut.setLength(0);
        }
        writer.write(chunk);
        if (LabTerminalPrefs.autoScroll()) scrollToBottom();
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
        // 关键：HorizontalScrollView 默认用 UNSPECIFIED 量孩子，TextView 会按最长行撑开、永不折行，
        // 所以必须让容器在"换行开"时按父宽 EXACTLY 量子视图（见 LabTerminalScrollView）。
        mBinding.outputHScroll.setWrapEnabled(wrap);
        ViewGroup.LayoutParams params = mBinding.outputText.getLayoutParams();
        int width = wrap ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT;
        if (params.width != width) {
            params.width = width;
            mBinding.outputText.setLayoutParams(params);
        }
        mBinding.outputText.setHorizontallyScrolling(!wrap);
        // 切回"换行开"时把横向滚动归零，否则内容会停在右边看不见行首
        if (wrap) mBinding.outputHScroll.scrollTo(0, 0);
        if (scroll) scrollToBottom();
    }

    private void scrollToBottom() {
        mBinding.outputScroll.post(() -> mBinding.outputScroll.fullScroll(View.FOCUS_DOWN));
    }

    /** 命令输出流回调（本窗自己订阅）：高频小段 → 合帧上屏。 */
    @Override
    public void onOutput(String text) {
        queue(text);
    }

    /** 命令结束回调（本窗自己订阅）。 */
    @Override
    public void onExit(int code) {
        App.post(() -> {
            append("\n[进程结束，退出码 " + code + "]\n");
            showRunning(false);
            updateTitle();
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
        LabRunner.removeListener(key(), this);
        INSTANCES.remove(key(), this);
        // -1 = 只 removeCallbacks：窗口关了就别再往这刷了
        App.post(flushRunnable, -1);
        super.onDestroy();
    }
}
