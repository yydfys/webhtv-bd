package com.fongmi.android.tv.lab;

import android.content.Context;
import android.text.TextUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LabRunner {

    public interface OutputListener {
        void onOutput(String text);

        void onExit(int code);
    }

    private static final Map<String, Process> RUNNING = new ConcurrentHashMap<>();
    private static final Map<String, StringBuilder> LOGS = new ConcurrentHashMap<>();
    private static final Map<String, java.io.OutputStream> STDIN = new ConcurrentHashMap<>();

    /**
     * 同一个 key 的输出可以有**多个监听者**：该命令自己的终端窗 + 命令面板。
     * 这样多个终端窗可以同时开着看不同命令的日志，互不抢流。
     */
    private static final Map<String, java.util.concurrent.CopyOnWriteArrayList<OutputListener>> LISTENERS = new ConcurrentHashMap<>();

    private LabRunner() {
    }

    public static void addListener(String key, OutputListener listener) {
        if (key == null || listener == null) return;
        LISTENERS.computeIfAbsent(key, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).addIfAbsent(listener);
    }

    public static void removeListener(String key, OutputListener listener) {
        if (key == null || listener == null) return;
        java.util.concurrent.CopyOnWriteArrayList<OutputListener> list = LISTENERS.get(key);
        if (list != null) list.remove(listener);
    }

    private static void broadcastOutput(String key, String text) {
        java.util.concurrent.CopyOnWriteArrayList<OutputListener> list = LISTENERS.get(key);
        if (list == null) return;
        for (OutputListener listener : list) {
            try {
                listener.onOutput(text);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void broadcastExit(String key, int code) {
        java.util.concurrent.CopyOnWriteArrayList<OutputListener> list = LISTENERS.get(key);
        if (list == null) return;
        for (OutputListener listener : list) {
            try {
                listener.onExit(code);
            } catch (Throwable ignored) {
            }
        }
    }

    public static boolean isRunning(String key) {
        Process process = RUNNING.get(key);
        if (process != null && process.isAlive()) return true;
        return LabProcManager.recoveredAlive(key);
    }

    public static int runningCount() {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, Process> entry : RUNNING.entrySet()) {
            if (entry.getValue() != null && entry.getValue().isAlive()) keys.add(entry.getKey());
        }
        for (String key : LabProcManager.recoveredKeys()) {
            keys.add(key);
        }
        return keys.size();
    }

    public static java.util.List<String> runningKeys() {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, Process> entry : RUNNING.entrySet()) {
            if (entry.getValue() != null && entry.getValue().isAlive()) keys.add(entry.getKey());
        }
        keys.addAll(LabProcManager.recoveredKeys());
        return new java.util.ArrayList<>(keys);
    }

    public static void stop(String key) {
        Process process = RUNNING.remove(key);
        java.io.OutputStream stdin = STDIN.remove(key);
        if (stdin != null) {
            try {
                stdin.close();
            } catch (Throwable ignored) {
            }
        }
        if (process != null) {
            try {
                process.destroy();
            } catch (Throwable ignored) {
            }
            try {
                process.destroyForcibly();
            } catch (Throwable ignored) {
            }
            try {
                java.lang.reflect.Field field = process.getClass().getDeclaredField("pid");
                field.setAccessible(true);
                int pid = field.getInt(process);
                LabProcManager.killTree(pid);
            } catch (Throwable ignored) {
            }
            LabProcManager.untrack(key);
        } else {
            LabProcManager.stop(key);
        }
        LabProcManager.updateService();
    }

    public static void stopAll() {
        for (String key : new java.util.ArrayList<>(RUNNING.keySet())) stop(key);
        LabProcManager.stopAll();
        LabProcManager.updateService();
    }

    public static String getLog(String key) {
        StringBuilder sb = LOGS.get(key);
        if (sb != null && sb.length() > 0) return sb.toString();
        File file = LabProcManager.logFor(key);
        if (file != null && file.exists()) {
            try {
                return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    /**
     * 清除某条命令的日志：内存缓冲 + 落盘日志文件一起清，
     * 否则窗口重开时会从日志文件回放出刚被清掉的旧日志。
     */
    public static synchronized void clearLog(String key) {
        // 内存缓冲必须在**原位**清空：pump 线程持有的是这个对象的引用，
        // 直接 LOGS.remove(key) 会让之后的输出写进一个没人引用的 builder（新日志"丢"）。
        StringBuilder log = LOGS.get(key);
        if (log != null) {
            synchronized (log) {
                log.setLength(0);
            }
        }
        // 落盘文件也不能只 truncate：正在跑的命令，writer 的写入偏移还停在截断前的位置，
        // 继续写会在文件开头留一大段 \0 空洞（重开窗口回放就是一片乱码）。
        // 正确做法：关掉旧 writer → 截断 → 重开（append 模式，文件已经是空的）。
        closeLogWriter(key);
        File file = LabProcManager.logFor(key);
        if (file != null && file.exists()) {
            try (java.io.FileOutputStream ignored = new java.io.FileOutputStream(file, false)) {
                // 截断为 0 字节
            } catch (Exception ignored) {
            }
            openLogWriter(key, file, null);
        }
    }

    /** 每条命令一个落盘 writer（窗口重开时能回放）。 */
    private static final Map<String, java.io.FileWriter> LOG_WRITERS = new ConcurrentHashMap<>();

    private static synchronized void openLogWriter(String key, File file, String header) {
        closeLogWriter(key);
        if (file == null) return;
        try {
            file.getParentFile().mkdirs();
            java.io.FileWriter writer = new java.io.FileWriter(file, true);
            if (!TextUtils.isEmpty(header)) {
                writer.write(header);
                writer.flush();
            }
            LOG_WRITERS.put(key, writer);
        } catch (Exception ignored) {
        }
    }

    private static synchronized void writeLogWriter(String key, String text) {
        java.io.FileWriter writer = LOG_WRITERS.get(key);
        if (writer == null) return;
        try {
            writer.write(text);
            writer.flush();
        } catch (Exception ignored) {
        }
    }

    private static synchronized void closeLogWriter(String key) {
        java.io.FileWriter writer = LOG_WRITERS.remove(key);
        if (writer == null) return;
        try {
            writer.close();
        } catch (Exception ignored) {
        }
    }

    public static Process getProcess(String key) {
        return RUNNING.get(key);
    }

    public static boolean writeInput(String key, String line) {
        java.io.OutputStream stdin = STDIN.get(key);
        if (stdin == null) return false;
        try {
            stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static Process run(Context context, LabModels.Item item, LabModels.Command command,
                              Map<String, String> vars, OutputListener listener) {
        String key = item.name + "/" + command.id;
        String expanded = expand(context, item, command.command, vars);
        return runShellCommand(context, item, key, expanded, vars, listener);
    }

    public static Process runCustom(Context context, LabModels.Item item, String commandText,
                                    Map<String, String> vars, String key, OutputListener listener) {
        String expanded = expand(context, item, commandText, vars);
        return runShellCommand(context, item, key, expanded, vars, listener);
    }

    /** runtime=ubuntu 的命令包进 proot 容器；包装不可用时退回原命令（不静默丢弃）。 */
    public static String shellOf(Context context, LabModels.Item item, String command) {
        return shellOf(context, item, command, null);
    }

    /**
     * runtime=ubuntu 的命令包进 proot 容器，并把"生效代理"作为容器内环境变量注入
     * （env -i 会清空宿主环境，所以必须在 proot 的 env 参数里带上）。
     */
    public static String shellOf(Context context, LabModels.Item item, String command, Map<String, String> vars) {
        if (item == null || !item.isUbuntu() || TextUtils.isEmpty(command)) return command;
        String wrapped = LabUbuntu.prootCommand(context, command, vars);
        return TextUtils.isEmpty(wrapped) ? command : wrapped;
    }

    /**
     * 跑 check_command 判断条目是否运行中：有输出 = 运行中。
     * 容器条目在 proot 内执行（pgrep 之类需要容器内进程视图）。
     */
    public static boolean checkRunning(Context context, LabModels.Item item, LabModels.Command command) {
        if (item == null || command == null || !command.hasCheck()) return false;
        return runCheck(context, item, command.check_command, command.cachedVariableValues);
    }

    /** 条目级安装检测：跑 install.check_command，通过即为已装好。 */
    public static boolean installDone(Context context, LabModels.Item item) {
        if (item == null || item.install == null) return false;
        String check = item.install.check_command;
        if (TextUtils.isEmpty(check)) return false;
        return runCheck(context, item, check, null);
    }

    /**
     * 通用状态检测：以命令自身的退出码为准（0 = 真）。
     *
     * <p>不能用"有输出即真"——ubuntu 条目的检测命令要包进 proot，proot 一启动就
     * 自己往 stdout 打东西，会把没装好的条目误判成已装好（状态灯乱跳）。
     * proot 会透传容器内命令的退出码，所以 pgrep / test -f / command -v 这类
     * 检测都靠退出码判断即可。
     */
    public static boolean runCheck(Context context, LabModels.Item item, String checkCommand, Map<String, String> vars) {
        if (item == null || TextUtils.isEmpty(checkCommand)) return false;
        String cmd = expand(context, item, checkCommand, vars);
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder("/system/bin/sh", "-c", shellOf(context, item, cmd, vars));
            builder.redirectErrorStream(true);
            applyEnv(builder.environment(), context, item, vars);
            process = builder.start();
            java.io.InputStream in = process.getInputStream();
            byte[] buf = new byte[512];
            long deadline = System.currentTimeMillis() + 8000;
            // 必须持续排空输出，否则管道塞满后子进程卡住不退出
            while (!process.waitFor(150, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                while (in.available() > 0) in.read(buf);
                if (System.currentTimeMillis() > deadline) {
                    process.destroy();
                    return false;
                }
            }
            while (in.available() > 0) in.read(buf);
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (process != null) process.destroy();
        }
    }

    /** 执行 stop_command（容器条目在 proot 内跑），执行完让它自然退出。 */
    public static Process runStop(Context context, LabModels.Item item, LabModels.Command command,
                                  Map<String, String> vars, OutputListener listener) {
        if (item == null || command == null || !command.hasStop()) return null;
        return runCustom(context, item, command.stop_command, vars, item.name + "/" + command.id + "#stop", listener);
    }

    public static Process runShellCommand(Context context, LabModels.Item item, String key,
                                          String command, OutputListener listener) {
        return runShellCommand(context, item, key, command, null, listener);
    }

    public static Process runShellCommand(Context context, LabModels.Item item, String key,
                                          String command, Map<String, String> vars, OutputListener listener) {
        stop(key);
        try {
            if (TextUtils.isEmpty(command)) throw new IOException("命令为空");
            File cwd = LabEnv.packageRoot(context, item);
            String cmdline = shellOf(context, item, command, vars);
            ProcessBuilder builder = new ProcessBuilder("/system/bin/setsid", "/system/bin/sh", "-c", cmdline);
            builder.directory(cwd);
            builder.redirectErrorStream(true);
            applyEnv(builder.environment(), context, item, vars);
            Process process = builder.start();
            RUNNING.put(key, process);
            try {
                STDIN.put(key, process.getOutputStream());
            } catch (Throwable ignored) {
            }
            String cmdId = key.contains("/") ? key.substring(key.indexOf('/') + 1) : key;
            LabProcManager.track(key, pidOf(process), item.name, cmdId);
            LabProcManager.updateService();
            addListener(key, listener);
            pump(process, key, command);
            return process;
        } catch (Exception e) {
            if (listener != null) {
                listener.onOutput("错误: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + "\n");
                listener.onExit(-1);
            }
            return null;
        }
    }

    public static Process runShell(Context context, String shell, String key, OutputListener listener) {
        stop(key);
        try {
            File cwd = LabEnv.localRoot().exists() ? LabEnv.localRoot() : context.getFilesDir();
            ProcessBuilder builder = new ProcessBuilder("/system/bin/sh", "-c", shell);
            builder.directory(cwd);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            RUNNING.put(key, process);
            LabProcManager.updateService();
            addListener(key, listener);
            pump(process, key, shell);
            return process;
        } catch (Exception e) {
            if (listener != null) {
                listener.onOutput("错误: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + "\n");
                listener.onExit(-1);
            }
            return null;
        }
    }

    public static void applyEnv(Map<String, String> env, Context context, LabModels.Item item) {
        applyEnv(env, context, item, null);
    }

    public static void applyEnv(Map<String, String> env, Context context, LabModels.Item item, Map<String, String> vars) {
        LabEnv.ensure7z(context);
        LabEnv.ensureProot(context);
        File packageDir = LabEnv.packageRoot(context, item);
        Set<String> pathSet = new LinkedHashSet<>();
        pathSet.add(LabEnv.sharedBin(context).getAbsolutePath());
        pathSet.add(LabEnv.prootRoot(context).getAbsolutePath());
        pathSet.add(new File(packageDir, "bin").getAbsolutePath());
        File base = LabEnv.baseRoot(context);
        File[] dirs = base.listFiles();
        if (dirs != null) {
            for (File dir : dirs) {
                if (!dir.isDirectory() || "bin".equals(dir.getName()) || dir.equals(packageDir)) continue;
                File bin = new File(dir, "bin");
                if (bin.exists() && bin.isDirectory()) pathSet.add(bin.getAbsolutePath());
            }
        }
        String original = env.get("PATH");
        if (original != null) {
            for (String part : original.split(":")) {
                if (!part.contains("com.termux")) pathSet.add(part);
            }
        }
        pathSet.add("/system/bin");
        pathSet.add("/system/xbin");
        pathSet.add("/vendor/bin");
        pathSet.add("/sbin");
        if (item.var_path != null && item.var_path.containsKey("PATH")) {
            String value = item.var_path.get("PATH");
            if (!TextUtils.isEmpty(value)) {
                for (String part : value.split(":")) {
                    if (!part.contains("com.termux")) {
                        pathSet.add(part.startsWith("/") ? part : new File(packageDir, part).getAbsolutePath());
                    }
                }
            }
        }
        env.put("PATH", join(pathSet, ":"));
        String libDir = LabEnv.sharedBin(context).getAbsolutePath();
        String oldLd = env.get("LD_LIBRARY_PATH");
        if (TextUtils.isEmpty(oldLd)) env.put("LD_LIBRARY_PATH", libDir);
        else if (!oldLd.contains(libDir)) env.put("LD_LIBRARY_PATH", libDir + ":" + oldLd);
        // proot 是动态链接的，它的依赖 .so 放在 sharedBin/proot/ 下，得单独进搜索路径
        File prootDir = LabEnv.prootRoot(context);
        if (prootDir.isDirectory()) {
            String prootPath = prootDir.getAbsolutePath();
            String ld = env.get("LD_LIBRARY_PATH");
            if (!ld.contains(prootPath)) env.put("LD_LIBRARY_PATH", ld + ":" + prootPath);
            env.putAll(LabEnv.prootEnv(context));
        }
        if (item.var_path != null) {
            for (Map.Entry<String, String> e : item.var_path.entrySet()) {
                String key = e.getKey();
                if ("PATH".equals(key)) continue;
                String value = e.getValue();
                if (TextUtils.isEmpty(value)) continue;
                if (!value.startsWith("/") && !value.startsWith("$")) {
                    value = new File(packageDir, value).getAbsolutePath();
                }
                value = replacePlaceholders(context, item, value);
                if ("LD_LIBRARY_PATH".equals(key)) {
                    String old = env.get("LD_LIBRARY_PATH");
                    if (TextUtils.isEmpty(old)) env.put(key, value);
                    else env.put(key, value + ":" + old);
                } else {
                    env.put(key, value);
                }
            }
        }
        Map<String, String> userSettings = LabConfig.get().loadUserSettings(item.name);
        if (item.settings != null && !userSettings.isEmpty()) {
            for (LabModels.Setting setting : item.settings) {
                String value = userSettings.get(setting.key);
                if (value == null || value.isEmpty()) value = setting.defaultValue;
                if (value != null && !value.isEmpty()) {
                    env.put(setting.key, replacePlaceholders(context, item, value));
                }
            }
        }
        applyBinEnvVars(env, context);
        LabProxy.applyToProcessEnv(env, item, vars);
        if (item.name != null && "nodejs".equalsIgnoreCase(item.name)) {
            putIfAbsent(env, "HOME", packageDir.getAbsolutePath());
            String nodeOptions = env.get("NODE_OPTIONS");
            if (nodeOptions == null || !nodeOptions.contains("--max-old-space-size")) {
                env.put("NODE_OPTIONS", nodeOptions == null ? "--max-old-space-size=512" : nodeOptions + " --max-old-space-size=512");
            }
        }
    }

    private static void applyBinEnvVars(Map<String, String> env, Context context) {
        LabModels.LabRoot root = LabConfig.get().getLabRoot();
        if (root == null || root.lists == null) return;
        for (LabModels.Item other : root.lists) {
            if (other == null || other.name == null) continue;
            if (!LabEnv.installed(context, other)) continue;
            String rel = other.binary_path;
            if (TextUtils.isEmpty(rel)) rel = other.cmd_name;
            if (TextUtils.isEmpty(rel)) continue;
            File bin = new File(LabEnv.packageRoot(context, other), rel);
            if (bin.exists()) {
                env.put(other.name.toUpperCase(Locale.ROOT) + "_BIN", bin.getAbsolutePath());
            }
        }
    }

    private static void putIfAbsent(Map<String, String> env, String key, String value) {
        if (!env.containsKey(key) || TextUtils.isEmpty(env.get(key))) {
            env.put(key, value);
        }
    }

    private static String join(Set<String> set, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : set) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(s);
        }
        return sb.toString();
    }

    private static int pidOf(Process process) {
        try {
            java.lang.reflect.Field field = process.getClass().getDeclaredField("pid");
            field.setAccessible(true);
            return field.getInt(process);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** 收到一段输出：广播给所有订阅者（各命令自己的终端窗）+ 落盘 + 进内存缓冲。 */
    private static void emitOutput(String key, StringBuilder log, String text) {
        if (text == null || text.isEmpty()) return;
        broadcastOutput(key, text);
        writeLogWriter(key, text);
        synchronized (log) {
            log.append(text);
            if (log.length() > 200000) log.delete(0, log.length() / 2);
        }
    }

    private static void pump(Process process, String key, String command) {
        StringBuilder log = LOGS.computeIfAbsent(key, k -> new StringBuilder());
        openLogWriter(key, LabProcManager.logFor(key), "$ " + command + "\n\n");
        Thread out = new Thread(() -> {
            // 字节级读取：不能用 readLine()——cfst 这类 pb 进度条在管道模式下
            // 既不写 \r 也不写 \n，"行"是切不开的，会被一直攒到最后才一次性吐出来。
            LabTerminalWriter.pump(process.getInputStream(), text -> emitOutput(key, log, text));
            // writer 跟着输出流一起收（不能跟 process.waitFor() 走：后台命令的子进程
            // 还在往管道里写时，提前关掉会让日志文件停更）
            closeLogWriter(key);
        });
        out.start();
        new Thread(() -> {
            try {
                int code = process.waitFor();
                try {
                    out.join(500);
                } catch (InterruptedException ignored) {
                }
                RUNNING.remove(key, process);
                STDIN.remove(key);
                int pid = pidOf(process);
                boolean daemon = false;
                long deadline = System.currentTimeMillis() + 2000;
                while (System.currentTimeMillis() < deadline) {
                    if (LabProcManager.trackGroup(key, pid)) {
                        daemon = true;
                        break;
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                if (daemon) {
                    broadcastOutput(key, "\n[命令已转入后台运行]\n");
                    LabProcManager.updateService();
                } else {
                    LabProcManager.untrack(key);
                    LabProcManager.updateService();
                    broadcastExit(key, code);
                }
            } catch (InterruptedException ignored) {
            }
        }).start();
    }

    public static String expand(Context context, LabModels.Item item, String template, Map<String, String> vars) {
        if (template == null) return "";
        String result = expandNested(context, item, template, vars, 0);
        return adaptPackageLookup(replacePlaceholders(context, item, result), context);
    }

    private static String expandNested(Context context, LabModels.Item item, String text, Map<String, String> vars, int depth) {
        if (text == null || depth > 5) return text;
        Matcher matcher = Pattern.compile("\\{([a-zA-Z0-9_]+)\\}").matcher(text);
        StringBuffer sb = new StringBuffer();
        boolean changed = false;
        while (matcher.find()) {
            if (matcher.start() > 0 && text.charAt(matcher.start() - 1) == '$') {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }
            String value = vars == null ? null : vars.get(matcher.group(1));
            if (value == null) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacePlaceholders(context, item, value)));
            changed = true;
        }
        matcher.appendTail(sb);
        if (!changed) return sb.toString();
        return expandNested(context, item, sb.toString(), vars, depth + 1);
    }

    private static String adaptPackageLookup(String command, Context context) {
        if (command == null || !command.contains("pm list packages")) return command;
        String pkg = context.getPackageName();
        return Pattern.compile("\\$\\(\\s*pm list packages.*?\\)", Pattern.DOTALL)
                .matcher(command)
                .replaceAll(Matcher.quoteReplacement(pkg));
    }

    private static String replacePlaceholders(Context context, LabModels.Item item, String value) {
        File packageDir = LabEnv.packageRoot(context, item);
        return value
                .replace("{serverPort}", LabConfig.serverPort())
                .replace("{tvPath}", new File(LabEnv.localRoot().getParentFile(), "TV").getAbsolutePath())
                .replace("{dataPath}", new File(packageDir, "data").getAbsolutePath())
                .replace("{cachePath}", context.getCacheDir().getAbsolutePath())
                .replace("{envRootPath}", packageDir.getAbsolutePath())
                .replace("{package_dir}", LabEnv.packageDir(context, item).getAbsolutePath())
                .replace("{wwwroot}", new File(LabEnv.localRoot(), "wwwroot").getAbsolutePath())
                .replace("{sdcard}", "/storage/emulated/0");
    }

    public static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
            } else if (c == '\\' && !inSingle) {
                escaped = true;
            } else if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (Character.isWhitespace(c) && !inSingle && !inDouble) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (escaped) current.append('\\');
        if (current.length() > 0) tokens.add(current.toString());
        return tokens;
    }
}
