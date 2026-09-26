package com.fongmi.android.tv.lab;

import android.content.Context;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;

import java.util.ArrayList;
import java.util.List;

public final class LabCommandAdapter extends RecyclerView.Adapter<LabCommandAdapter.Holder> {

    public interface Listener {
        void onOpen(LabModels.Item item, LabModels.Command command);

        void onAction(LabModels.Item item, LabModels.Command command);

        void onLongPress(LabModels.Item item, LabModels.Command command);
    }

    private final List<LabModels.Command> rows = new ArrayList<>();
    private final Listener listener;
    private final Context context;
    private LabModels.Item item;
    /** 列表本身的引用：用于「就地刷新运行状态」，不必为了刷新状态而重建整个列表。 */
    private RecyclerView recycler;

    public LabCommandAdapter(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView view) {
        super.onAttachedToRecyclerView(view);
        recycler = view;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView view) {
        super.onDetachedFromRecyclerView(view);
        if (recycler == view) recycler = null;
    }

    /**
     * 就地刷新每一行的「运行中」视觉（状态点 / 运行中标签 / 运行↔停止按钮）。
     *
     * <p>这是给 2 秒一次的 check_command 轮询用的：绝对不能走 notifyDataSetChanged ——
     * 重建列表会把正在被遥控器选中的那一行拆掉，焦点随即被框架丢回窗口里第一个可聚焦控件
     * （也就是工具栏的返回箭头），表现为「条目不稳、自动跳到返回箭头上」。
     */
    public void refreshRunningStates() {
        RecyclerView list = recycler;
        if (list == null) return;
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            RecyclerView.ViewHolder holder = list.getChildViewHolder(child);
            if (!(holder instanceof Holder)) continue;
            int position = list.getChildAdapterPosition(child);
            if (position == RecyclerView.NO_POSITION || position >= rows.size()) continue;
            applyRunning((Holder) holder, rows.get(position));
        }
    }

    /** 把某个命令行的运行状态刷到控件上；状态与上一轮一致就直接返回，不做无谓的重新布局。 */
    private void applyRunning(Holder holder, LabModels.Command command) {
        boolean running = item != null && command != null && LabRunner.isRunning(item.name + "/" + command.id);
        if (holder.bound && holder.running == running) return;
        holder.bound = true;
        holder.running = running;
        holder.statusDot.setBackgroundResource(running ? R.drawable.shape_lab_status_dot_running : R.drawable.shape_lab_status_dot_idle);
        holder.runningTag.setVisibility(running ? View.VISIBLE : View.GONE);
        holder.btnAction.setText(running ? R.string.lab_stop : R.string.lab_run);
        holder.btnAction.setBackgroundResource(running ? R.drawable.shape_lab_stop_btn : R.drawable.shape_lab_run_btn);
    }

    /** 让相邻行的「运行/停止」按钮拿到焦点（按钮列内上下切换用）。 */
    private boolean focusActionColumn(int position) {
        RecyclerView list = recycler;
        if (list == null || position < 0 || position >= rows.size()) return false;
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(position);
        if (!(holder instanceof Holder)) return false;
        return ((Holder) holder).btnAction.requestFocus();
    }

    public void setItem(LabModels.Item item) {
        this.item = item;
        rows.clear();
        if (item != null) {
            if (item.commands != null) rows.addAll(item.commands);
            List<LabCustomCommands.CustomCommand> customs = LabCustomCommands.list(item.name);
            for (int i = 0; i < customs.size(); i++) {
                LabCustomCommands.CustomCommand custom = customs.get(i);
                LabModels.Command command = new LabModels.Command();
                command.id = custom.id != null ? custom.id : ("custom_" + i);
                command.name = custom.name;
                command.description = custom.description;
                command.command = custom.command;
                command.auto_execute = custom.autoExecute;
                rows.add(command);
            }
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.adapter_lab_command_compact, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        LabModels.Command command = rows.get(position);
        holder.name.setText(command.name == null ? command.description : command.name);
        applyRunning(holder, command);
        LabFocus.commandCard(holder.itemView);
        LabFocus.enable(holder.btnAction);
        if (LabFocus.tv()) {
            // 卡片与「运行」按钮都要能被遥控选中：卡片默认焦点，右键进本行的按钮，左键回到本行卡片。
            // 这里刻意不用 nextFocus*Id —— 同一 id 在每行都出现，框架按 root.findViewById 找会命中
            // 第一行（列表顶部）的同名控件，导致焦点乱跳；键监听是行内绑定的，不会串行。
            if (holder.itemView.getId() == View.NO_ID) holder.itemView.setId(View.generateViewId());
            holder.btnAction.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != MotionEvent.ACTION_DOWN) return false;
                // 左键回本行卡片；上下在本列的按钮之间走
                //（交给框架的几何查找会跑到别的行的卡片上，落点飘）
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return holder.itemView.requestFocus();
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (position == 0) return false; // 第一行交给 nextFocusUpId，落到工具栏「终端」
                    return focusActionColumn(position - 1);
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return focusActionColumn(position + 1);
                return false;
            });
            // 第一行的按钮继续往上就是工具栏；其余行的上下由上面的键监听在列内接管
            holder.btnAction.setNextFocusUpId(position == 0 ? R.id.btnTerminal : View.NO_ID);
            // TV：第一条命令往上切能落到工具栏的「终端 / 加号 / 重置」三个按钮上
            holder.itemView.setNextFocusUpId(position == 0 ? R.id.btnTerminal : View.NO_ID);
            holder.itemView.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == MotionEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    return holder.btnAction.requestFocus();
                }
                return false;
            });
        }
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onOpen(item, command);
        });
        holder.btnAction.setOnClickListener(v -> {
            if (listener != null) listener.onAction(item, command);
        });
        holder.itemView.setOnLongClickListener(v -> {
            if (listener != null) listener.onLongPress(item, command);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final View statusDot;
        final TextView name;
        final TextView runningTag;
        final TextView btnAction;
        /** 上一次刷进控件的运行状态与是否刷过：一致就跳过，避免 2 秒一次的轮询反复重新布局。 */
        boolean bound;
        boolean running;

        Holder(View itemView) {
            super(itemView);
            statusDot = itemView.findViewById(R.id.statusDot);
            name = itemView.findViewById(R.id.name);
            runningTag = itemView.findViewById(R.id.runningTag);
            btnAction = itemView.findViewById(R.id.btnAction);
        }
    }
}
