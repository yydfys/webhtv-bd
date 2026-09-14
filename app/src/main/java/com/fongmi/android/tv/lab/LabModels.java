package com.fongmi.android.tv.lab;

import com.google.gson.annotations.SerializedName;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class LabModels {

    public static class LabRoot {
        public String version;
        public List<Group> groups;
        public List<Item> lists;
        public String root;
    }

    public static class Group {
        public String id;
        public String name;
        public int order;
    }

    public static class Item {
        public String name;
        public String group;
        public String version;
        public int min_version;
        public String icon;
        public String info;
        public boolean auto_install;
        public boolean available;
        public boolean show;
        /** 运行环境：ubuntu = 命令包进 proot 容器执行；缺省/android = 直接跑 /system/bin/sh。 */
        public String runtime;
        /** 需要先装（install 成功后才允许跑命令）。 */
        public boolean install_required;
        /** 点击条目直接进容器终端，不展示命令卡片。 */
        public boolean terminal_auto_open;
        /** 条目级安装/卸载脚本（apt 装环境）。 */
        public Install install;
        public String binary_path;
        public String cmd_name;
        /** 显式声明这是 Linux rootfs 包（解压到 rootfs/ 而非包根目录）；不填则按包名推断。 */
        public Boolean rootfs;
        public Object mkdir;
        public Map<String, String> var_path;
        public List<Download> downloads;
        public List<Command> commands;
        public List<Setting> settings;

        /** 是否容器（Ubuntu）运行时。 */
        public boolean isUbuntu() {
            return "ubuntu".equalsIgnoreCase(runtime);
        }

        public boolean hasInstall() {
            return install != null && install.command != null && !install.command.isEmpty();
        }

        public boolean hasSettings() {
            return settings != null && !settings.isEmpty();
        }

        public boolean isSupported(int versionCode) {
            return min_version <= 0 || versionCode >= min_version;
        }

        public List<String> mkdirList() {
            List<String> list = new java.util.ArrayList<>();
            if (mkdir == null) return list;
            if (mkdir instanceof String) {
                list.add((String) mkdir);
            } else if (mkdir instanceof List) {
                for (Object value : (List<?>) mkdir) {
                    if (value instanceof String) list.add((String) value);
                }
            }
            return list;
        }
    }

    /** 条目级安装脚本（在容器内执行）。 */
    public static class Install {
        public String check_command;
        public String command;
        public String uninstall_command;
        /** terminal = 在容器终端里跑（能看 apt 输出）。 */
        public String mode;

        public boolean isTerminalMode() {
            return "terminal".equalsIgnoreCase(mode);
        }

        public boolean hasUninstall() {
            return uninstall_command != null && !uninstall_command.isEmpty();
        }
    }

    public static class Setting {
        public String key;
        public String name;
        public String type;
        @SerializedName("default")
        public String defaultValue;
        public String hint;
        public Boolean editable;
        public List<Option> options;
    }

    public static class Download {
        public String arch;
        public String version;
        public String url;
        public String liburl;
        public String size;
        public Object mirrors;

        public List<String> getMirrorNames() {
            List<String> names = new java.util.ArrayList<>();
            names.add("默认");
            if (mirrors instanceof Map) {
                names.addAll(((Map<String, String>) mirrors).keySet());
            }
            return names;
        }

        public String getUrlByMirror(String name) {
            if (name == null || "默认".equals(name) || !(mirrors instanceof Map)) return url;
            String mirror = ((Map<String, String>) mirrors).get(name);
            return mirror == null ? url : mirror;
        }
    }

    public static class Command {
        public String id;
        public String name;
        public String description;
        public String command;
        public boolean auto_execute;
        public boolean background;
        public Boolean show_output;
        /** 状态检测命令（容器内执行，有输出 = 运行中）。 */
        public String check_command;
        /** 停止命令（容器内执行）。 */
        public String stop_command;
        public int min_version;
        public List<Variable> variables;
        public List<Click> clicks;
        public CommandDownload download;
        public Map<String, String> cachedVariableValues;
        public String cachedCommand;

        public boolean hasVariables() {
            return variables != null && !variables.isEmpty();
        }

        public List<Variable> getVariables() {
            return variables != null ? variables : Collections.emptyList();
        }

        public List<Click> getClicks() {
            return clicks != null ? clicks : Collections.emptyList();
        }

        public boolean isSupported(int versionCode) {
            return min_version <= 0 || versionCode >= min_version;
        }

        public boolean hasMinVersion() {
            return min_version > 0;
        }

        public boolean isBackground() {
            return background;
        }

        public boolean isShowOutput() {
            return show_output == null || show_output;
        }

        public boolean hasCheck() {
            return check_command != null && !check_command.isEmpty();
        }

        public boolean hasStop() {
            return stop_command != null && !stop_command.isEmpty();
        }
    }

    public static class CommandDownload {
        public String title;
        public String url;
        public String size;
        public String check_file;
        public String save_to;
        public String extract_to;
        public boolean required;
        public Object mirrors;

        public List<String> getMirrorNames() {
            List<String> names = new java.util.ArrayList<>();
            names.add("默认");
            if (mirrors instanceof Map) {
                names.addAll(((Map<String, String>) mirrors).keySet());
            }
            return names;
        }

        public String getUrlByMirror(String name) {
            if (name == null || "默认".equals(name) || !(mirrors instanceof Map)) return url;
            String mirror = ((Map<String, String>) mirrors).get(name);
            return mirror == null ? url : mirror;
        }
    }

    public static class Variable {
        public String key;
        public String name;
        public String type;
        @SerializedName("default")
        public String defaultValue;
        public String hint;
        public String filter;
        public boolean required;
        public String min;
        public String max;
        public List<Option> options;
    }

    public static class Option {
        public String label;
        public String value;
    }

    public static class Click {
        public String title;
        public String action;
        public String value;
        public String style;
        public String content;
        public int width;
        public int height;
    }
}
