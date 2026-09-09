package com.fongmi.android.tv.ui.dialog;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterNodeManageBinding;
import com.fongmi.android.tv.databinding.DialogNodeManageBinding;
import com.fongmi.android.tv.lab.SystemVpnService;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 节点管理（2026-09-09）：经 mihomo RESTful API(127.0.0.1:9090) 读取
 * 订阅节点并手动切换 select 组。config 模板需带 external-controller+secret。
 *
 * 交互（mobile 触摸 / leanback 遥控通用）：
 *   组行（Select/URLTest/Fallback…）→ 点击展开/收起组内节点
 *   节点行 → 点击 PUT /proxies/{组} 切换（仅 select 组可切）
 *   行右侧：组行=当前选中；节点行=最近延迟(ms)
 */
public class NodeManageDialog extends BaseAlertDialog {

    private static final String API_BASE = "http://127.0.0.1:9090";
    private static final String SECRET = "WEBHTV_MIHOMO_2026";
    private static final int COLOR_ACCENT = 0xFF1976D2;
    private static final int COLOR_PRIMARY = 0xFF1C1B1F;
    private static final int COLOR_SECONDARY = 0xFF5C5C66;

    private DialogNodeManageBinding binding;
    private NodeAdapter adapter;
    private List<Row> rows = new ArrayList<>();
    private final java.util.Map<String, NodeInfo> nodes = new java.util.HashMap<>();
    private volatile boolean loading;

    public static void show(BaseAlertDialog parent) {
        new NodeManageDialog().show(parent.getChildFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogNodeManageBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.node_manage_title)
                .setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        binding.recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recycler.setItemAnimator(null);
        binding.recycler.setAdapter(adapter = new NodeAdapter());
    }

    @Override
    protected void initEvent() {
        binding.refresh.setOnClickListener(view -> load());
        binding.close.setOnClickListener(view -> dismiss());
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() == null || getDialog().getWindow() == null) return;
        Window window = getDialog().getWindow();
        WindowManager.LayoutParams params = window.getAttributes();
        params.width = (int) (ResUtil.getScreenWidth(requireContext()) * 0.92f);
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        window.setAttributes(params);
        // RecyclerView 内容超高时截断为屏幕 55%，避免 dialog 顶出屏幕
        binding.recycler.post(() -> {
            int cap = (int) (ResUtil.getScreenHeight(requireContext()) * 0.55f);
            if (binding.recycler.getHeight() > cap) {
                ViewGroup.LayoutParams lp = binding.recycler.getLayoutParams();
                lp.height = cap;
                binding.recycler.setLayoutParams(lp);
            }
        });
        load();
    }

    // ---------------- 数据加载 ----------------

    private void load() {
        if (loading) return;
        loading = true;
        binding.status.setText(R.string.node_manage_loading);
        if (!SystemVpnService.isProxyRunning()) {
            loading = false;
            binding.status.setText(R.string.node_manage_not_running);
            rows.clear();
            adapter.notifyDataSetChanged();
            return;
        }
        Task.execute(() -> {
            String body = null;
            String error = null;
            try {
                body = httpGet(API_BASE + "/proxies");
            } catch (Exception e) {
                error = e.getMessage();
            }
            String out = body;
            String err = error;
            App.post(() -> {
                loading = false;
                if (err != null || out == null || out.isEmpty()) {
                    binding.status.setText(err == null ? R.string.node_manage_empty : err);
                    return;
                }
                try {
                    parse(out);
                    render();
                } catch (Exception e) {
                    binding.status.setText(e.getMessage());
                }
            });
        });
    }

    private void parse(String body) throws Exception {
        JSONObject root = new JSONObject(body);
        JSONObject proxies = root.optJSONObject("proxies");
        if (proxies == null) throw new Exception("no proxies field");
        nodes.clear();
        Iterator<String> keys = proxies.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject obj = proxies.optJSONObject(key);
            if (obj == null) continue;
            NodeInfo info = new NodeInfo();
            info.type = obj.optString("type", "");
            info.now = obj.optString("now", "");
            JSONArray all = obj.optJSONArray("all");
            if (all != null) {
                info.all = new ArrayList<>();
                for (int i = 0; i < all.length(); i++) info.all.add(all.optString(i));
            }
            JSONArray history = obj.optJSONArray("history");
            if (history != null && history.length() > 0) {
                JSONObject last = history.optJSONObject(history.length() - 1);
                if (last != null) info.delay = last.optLong("delay", -1);
            }
            nodes.put(key, info);
        }
    }

    private void render() {
        rows.clear();
        List<String> groups = new ArrayList<>();
        for (String key : nodes.keySet()) {
            NodeInfo info = nodes.get(key);
            if (info == null) continue;
            if (isGroup(info.type)) groups.add(key);
        }
        // select 组优先，其余按名称稳定排序
        groups.sort((a, b) -> {
            boolean sa = "Select".equalsIgnoreCase(nodes.get(a).type);
            boolean sb = "Select".equalsIgnoreCase(nodes.get(b).type);
            if (sa != sb) return sa ? -1 : 1;
            return a.compareTo(b);
        });
        boolean firstSelectExpanded = false;
        for (String group : groups) {
            NodeInfo info = nodes.get(group);
            Row row = new Row();
            row.isGroup = true;
            row.group = group;
            row.type = info.type;
            row.now = info.now;
            // 默认只展开第一个 select 组（最常用：手动切换），其余收起
            boolean expand = "Select".equalsIgnoreCase(info.type) && !firstSelectExpanded;
            if (expand) firstSelectExpanded = true;
            row.expanded = expand;
            rows.add(row);
            if (expand && info.all != null) {
                for (String node : info.all) {
                    Row child = new Row();
                    child.isGroup = false;
                    child.group = group;
                    child.name = node;
                    child.delay = delayOf(node);
                    child.current = node.equals(info.now);
                    rows.add(child);
                }
            }
        }
        binding.status.setText(groups.size() + " groups");
        adapter.notifyDataSetChanged();
    }

    private long delayOf(String name) {
        NodeInfo info = nodes.get(name);
        return info == null ? -1 : info.delay;
    }

    private static boolean isGroup(String type) {
        String t = type.toLowerCase();
        return t.equals("select") || t.equals("urltest") || t.equals("url-test") || t.equals("fallback") || t.equals("loadbalance");
    }

    private static String typeText(String type) {
        String t = type.toLowerCase();
        if (t.equals("select")) return "Select";
        if (t.equals("urltest") || t.equals("url-test")) return "URLTest";
        if (t.equals("fallback")) return "Fallback";
        if (t.equals("loadbalance")) return "LoadBalance";
        return type;
    }

    // ---------------- 切换节点 ----------------

    private void switchNode(String group, String node) {
        NodeInfo info = nodes.get(group);
        boolean selectable = info != null && "Select".equalsIgnoreCase(info.type);
        if (!selectable) {
            Notify.show(ResUtil.getString(R.string.node_manage_type_not_select, info == null ? "" : typeText(info.type)));
            return;
        }
        Task.execute(() -> {
            String err = null;
            try {
                httpPutSwitch(group, node);
            } catch (Exception e) {
                err = e.getMessage();
            }
            String error = err;
            App.post(() -> {
                if (error == null) {
                    Notify.show(ResUtil.getString(R.string.node_manage_switched, node));
                    load();
                } else {
                    Notify.show(ResUtil.getString(R.string.node_manage_switch_fail, error));
                }
            });
        });
    }

    private void httpPutSwitch(String group, String node) throws Exception {
        HttpUrl url = HttpUrl.get(API_BASE + "/proxies").newBuilder().addPathSegment(group).build();
        String json = "{\"name\":" + JSONObject.quote(node) + "}";
        RequestBody body = RequestBody.create(json, MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url(url)
                .put(body)
                .header("Authorization", "Bearer " + SECRET)
                .build();
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() && response.code() != 204) {
                throw new Exception("HTTP " + response.code());
            }
        }
    }

    private String httpGet(String url) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + SECRET)
                .build();
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 401) throw new Exception("secret mismatch");
            if (!response.isSuccessful()) throw new Exception("HTTP " + response.code());
            String text = response.body() == null ? "" : response.body().string();
            if (text.length() > 2_000_000) throw new Exception("response too large");
            return text;
        }
    }

    // ---------------- adapter ----------------

    private static class Row {
        boolean isGroup;
        String group;
        String type;
        String now;
        boolean expanded;
        String name;
        long delay = -1;
        boolean current;
    }

    private static class NodeInfo {
        String type = "";
        String now = "";
        List<String> all;
        long delay = -1;
    }

    private class NodeAdapter extends RecyclerView.Adapter<NodeAdapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            AdapterNodeManageBinding binding = AdapterNodeManageBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
            return new VH(binding);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            Row row = rows.get(position);
            TextView title = holder.binding.title;
            TextView badge = holder.binding.badge;
            if (row.isGroup) {
                title.setText((row.expanded ? "▾ " : "▸ ") + row.group + "   [" + typeText(row.type) + "]");
                title.setTextColor(COLOR_PRIMARY);
                title.setTextSize(15);
                String now = row.now == null ? "" : row.now;
                badge.setText(now.isEmpty() ? "" : now);
                badge.setTextColor(COLOR_SECONDARY);
                holder.binding.getRoot().setOnClickListener(v -> toggleGroup(row.group));
            } else {
                boolean isCur = row.current;
                title.setText((isCur ? "● " : "    ") + row.name);
                title.setTextColor(isCur ? COLOR_ACCENT : COLOR_PRIMARY);
                title.setTextSize(14);
                badge.setText(row.delay >= 0 ? row.delay + " ms" : "-");
                badge.setTextColor(isCur ? COLOR_ACCENT : COLOR_SECONDARY);
                holder.binding.getRoot().setOnClickListener(v -> switchNode(row.group, row.name));
            }
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        private void toggleGroup(String group) {
            for (Row row : rows) {
                if (row.isGroup && row.group.equals(group)) {
                    row.expanded = !row.expanded;
                }
            }
            rebuildExpanded();
        }

        private void rebuildExpanded() {
            List<Row> newRows = new ArrayList<>();
            for (Row row : rows) {
                if (!row.isGroup) continue;
                newRows.add(row);
                if (row.expanded) {
                    NodeInfo info = nodes.get(row.group);
                    if (info != null && info.all != null) {
                        for (String node : info.all) {
                            Row child = new Row();
                            child.isGroup = false;
                            child.group = row.group;
                            child.name = node;
                            child.delay = delayOf(node);
                            child.current = node.equals(info.now);
                            newRows.add(child);
                        }
                    }
                }
            }
            rows = newRows;
            notifyDataSetChanged();
        }
    }

    private static class VH extends RecyclerView.ViewHolder {
        final AdapterNodeManageBinding binding;

        VH(@NonNull AdapterNodeManageBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
