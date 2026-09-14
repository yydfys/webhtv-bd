package com.fongmi.android.tv.lab;

import android.content.Context;
import android.os.Environment;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.google.gson.Gson;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 内置 Ubuntu（PRoot）虚拟系统。
 *
 * <p>与 lab.json 里那些"下载可执行文件"的包不同，Ubuntu 是一套独立子系统：
 * 下载官方 ubuntu-base 根文件系统 → 解压成完整 rootfs → 补齐 DNS/hosts/hostname/apt 源 →
 * 之后所有命令通过 proot 进容器执行（非 root 也能拿到 uid0 的发行版环境）。
 *
 * <p>目录布局（应用私有目录下，卸载 App 即清除）：
 * <pre>
 * files/lab-runtime/ubuntu            rootfs 根（容器内的 /）
 * files/lab-runtime/.lab-ubuntu.json  安装元数据
 * files/lab-runtime/rootfs.installing 安装中标记
 * files/lab-runtime/rootfs.backup     重装时的旧系统备份
 * </pre>
 */
public final class LabUbuntu {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private static final String RUNTIME_DIR = "lab-runtime";
    private static final String ROOTFS_NAME = "ubuntu";
    private static final String META_NAME = ".lab-ubuntu.json";
    private static final String INSTALLING_NAME = "rootfs.installing";
    private static final String BACKUP_NAME = "rootfs.backup";
    private static final String HOST_NAME = "webhtv-ubuntu";

    /** 设置项键名（存 SharedPreferences，与 lab 共用一份）。 */
    public static final String KEY_RELEASE = "ubuntu_release";
    public static final String KEY_ROOTFS_SOURCE = "ubuntu_rootfs_source";
    public static final String KEY_ROOTFS_URL = "ubuntu_rootfs_custom_url";
    public static final String KEY_APT_SOURCE = "ubuntu_apt_source";
    public static final String KEY_APT_URL = "ubuntu_apt_custom_url";
    public static final String KEY_SHARED_STORAGE = "ubuntu_shared_storage";

    /** 镜像源档位（rootfs 与 apt 共用同一套档位常量）。 */
    public static final int SRC_OFFICIAL = 0;
    public static final int SRC_ALIYUN = 1;
    public static final int SRC_TUNA = 2;
    public static final int SRC_USTC = 3;
    public static final int SRC_HUAWEI = 4;
    public static final int SRC_CUSTOM = 5;

    /** 可选发行版：展示名、apt 兜底代号、镜像文件名版本候选（首个优先）。 */
    private static final String[] RELEASE_LABELS = {"Ubuntu Base 24.04", "Ubuntu Base 26.04"};
    private static final String[] RELEASE_DIRS = {"24.04", "26.04"};
    private static final String[] RELEASE_CODENAMES = {"noble", "resolute"};
    private static final String[][] RELEASE_FILES = {
            {"24.04.5", "24.04.4", "24.04.3", "24.04.2"},
            {"26.04.1", "26.04"},
    };

    private static final String[] ROOTFS_MIRRORS = {
            "https://cdimage.ubuntu.com/ubuntu-base/releases/",
            "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/",
            "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/",
            "https://mirrors.ustc.edu.cn/ubuntu-cdimage/ubuntu-base/releases/",
            "https://repo.huaweicloud.com/ubuntu-cdimage/ubuntu-base/releases/",
    };

    /** apt 源只认 ports（arm64/armhf 都在 ports 仓库，archive 只服务 amd64）。 */
    private static final String[] APT_MIRRORS = {
            "http://ports.ubuntu.com/ubuntu-ports/",
            "https://mirrors.aliyun.com/ubuntu-ports/",
            "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/",
            "https://mirrors.ustc.edu.cn/ubuntu-ports/",
            "https://repo.huaweicloud.com/ubuntu-ports/",
    };

    private static final Gson GSON = new Gson();

    private LabUbuntu() {
    }

    /* ===================== 路径 ===================== */

    public static File runtimeDir(Context context) {
        File dir = new File(context.getFilesDir(), RUNTIME_DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** rootfs 根目录——容器内的 /。 */
    public static File rootfsDir(Context context) {
        return new File(runtimeDir(context), ROOTFS_NAME);
    }

    private static File metaFile(Context context) {
        return new File(runtimeDir(context), META_NAME);
    }

    private static File installingFlag(Context context) {
        return new File(runtimeDir(context), INSTALLING_NAME);
    }

    private static File backupDir(Context context) {
        return new File(runtimeDir(context), BACKUP_NAME);
    }

    /* ===================== 元数据 ===================== */

    public static class Meta {
        public String version = "";
        public String release = "";
        public int source;
        public String url = "";
        public int aptSource;
        public String aptUrl = "";
        public long time;
    }

    public static Meta readMeta(Context context) {
        File file = metaFile(context);
        if (!file.exists()) return null;
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int read = in.read(buf);
            if (read <= 0) return null;
            Meta meta = GSON.fromJson(new String(buf, 0, read, StandardCharsets.UTF_8), Meta.class);
            return meta == null ? null : meta;
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeMeta(Context context, Meta meta) {
        try {
            writeText(metaFile(context), GSON.toJson(meta));
        } catch (Exception ignored) {
        }
    }

    /* ===================== 状态 ===================== */

    /** rootfs 是否已就位（判定标准是发行版自身的 /etc/os-release，而非目录非空）。 */
    public static boolean installed(Context context) {
        return new File(rootfsDir(context), "etc/os-release").exists();
    }

    public static boolean isInstalling(Context context) {
        return installingFlag(context).exists();
    }

    /** 供界面显示的版本文案，如 "Ubuntu Base 24.04.4"。 */
    public static String installedVersion(Context context) {
        Meta meta = readMeta(context);
        if (meta != null && !TextUtils.isEmpty(meta.version)) return "Ubuntu Base " + meta.version;
        return installed(context) ? "Ubuntu Base" : "";
    }

    /* ===================== 设置读写 ===================== */

    private static android.content.SharedPreferences sp() {
        return App.get().getSharedPreferences("lab", Context.MODE_PRIVATE);
    }

    public static int getRelease(Context context) {
        return clampIndex(sp().getInt(KEY_RELEASE, 0), RELEASE_LABELS.length);
    }

    public static void setRelease(Context context, int index) {
        sp().edit().putInt(KEY_RELEASE, clampIndex(index, RELEASE_LABELS.length)).apply();
    }

    public static String[] releaseLabels() {
        return RELEASE_LABELS.clone();
    }

    public static String releaseLabel(int index) {
        return RELEASE_LABELS[clampIndex(index, RELEASE_LABELS.length)];
    }

    public static int getRootfsSource(Context context) {
        return clampIndex(sp().getInt(KEY_ROOTFS_SOURCE, SRC_OFFICIAL), SRC_CUSTOM + 1);
    }

    public static void setRootfsSource(Context context, int source) {
        sp().edit().putInt(KEY_ROOTFS_SOURCE, clampIndex(source, SRC_CUSTOM + 1)).apply();
    }

    public static String getRootfsCustomUrl(Context context) {
        return sp().getString(KEY_ROOTFS_URL, "");
    }

    public static void setRootfsCustomUrl(Context context, String url) {
        sp().edit().putString(KEY_ROOTFS_URL, url == null ? "" : url.trim()).apply();
    }

    public static int getAptSource(Context context) {
        return clampIndex(sp().getInt(KEY_APT_SOURCE, SRC_ALIYUN), SRC_CUSTOM + 1);
    }

    public static void setAptSource(Context context, int source) {
        sp().edit().putInt(KEY_APT_SOURCE, clampIndex(source, SRC_CUSTOM + 1)).apply();
    }

    public static String getAptCustomUrl(Context context) {
        return sp().getString(KEY_APT_URL, "");
    }

    public static void setAptCustomUrl(Context context, String url) {
        sp().edit().putString(KEY_APT_URL, url == null ? "" : url.trim()).apply();
    }

    public static boolean getSharedStorage(Context context) {
        return sp().getBoolean(KEY_SHARED_STORAGE, true);
    }

    public static void setSharedStorage(Context context, boolean enabled) {
        sp().edit().putBoolean(KEY_SHARED_STORAGE, enabled).apply();
    }

    public static String[] sourceLabels() {
        return new String[]{"官方源", "阿里云", "清华 TUNA", "中科大 USTC", "华为云", "自定义"};
    }

    private static int clampIndex(int value, int size) {
        return value < 0 || value >= size ? 0 : value;
    }

    /* ===================== 架构与 URL ===================== */

    /** ubuntu-base 镜像里的架构标签；不支持的设备返回 null。 */
    public static String archTag() {
        String arch = LabEnv.arch();
        if ("arm64-v8a".equals(arch)) return "arm64";
        if ("armeabi-v7a".equals(arch)) return "armhf";
        return null;
    }

    /** rootfs 下载信息（地址 / 文件名 / 版本 / 校验和）。 */
    public static class Artifact {
        public String url = "";
        public String fileName = "";
        public String version = "";
        public String sha256 = "";
    }

    /**
     * 读发行版目录的 SHA256SUMS，挑出当前架构可用的最新镜像。
     * 比"猜文件名"可靠：各镜像站点版本同步进度不一（USTC 有 24.04.5 却对 HEAD 回 403），
     * 只有清单里列出的文件名才是真实存在的。
     */
    private static Artifact parseIndex(String base, String tag) {
        String text = fetchText(base + "SHA256SUMS");
        if (TextUtils.isEmpty(text)) return null;
        String suffix = "-base-" + tag + ".tar.gz";
        Artifact best = null;
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            int star = trimmed.indexOf('*');
            if (star <= 0) continue;
            String hash = trimmed.substring(0, star).trim();
            String name = trimmed.substring(star + 1).trim();
            if (!name.startsWith("ubuntu-base-") || !name.endsWith(suffix)) continue;
            String version = name.substring("ubuntu-base-".length(), name.length() - suffix.length());
            if (!version.matches("[0-9]+(\\.[0-9]+)*")) continue;
            if (best != null && LabEnv.compareVersions(version, best.version) <= 0) continue;
            Artifact artifact = new Artifact();
            artifact.url = base + name;
            artifact.fileName = name;
            artifact.version = version;
            artifact.sha256 = hash;
            best = artifact;
        }
        return best;
    }

    /** 候选地址探测：按候选版本顺序找到第一个真实可下载的。 */
    private static Artifact probe(Context context, String tag) {
        int source = getRootfsSource(context);
        if (source == SRC_CUSTOM) {
            Artifact artifact = new Artifact();
            artifact.url = getRootfsCustomUrl(context);
            artifact.fileName = fileName(artifact.url);
            artifact.version = versionFromUrl(artifact.url);
            return TextUtils.isEmpty(artifact.url) ? null : artifact;
        }
        int release = getRelease(context);
        String base = ROOTFS_MIRRORS[source] + RELEASE_DIRS[release] + "/release/";
        for (String version : RELEASE_FILES[release]) {
            String url = base + "ubuntu-base-" + version + "-base-" + tag + ".tar.gz";
            if (reachable(url)) {
                Artifact artifact = new Artifact();
                artifact.url = url;
                artifact.fileName = fileName(url);
                artifact.version = version;
                return artifact;
            }
        }
        return null;
    }

    /** 解析下载信息：先看目录清单（顺带拿到最新点版本与校验和），失败退回候选探测。 */
    private static Artifact resolveArtifact(Context context, String tag) {
        int source = getRootfsSource(context);
        int release = getRelease(context);
        if (source != SRC_CUSTOM) {
            Artifact fromIndex = parseIndex(ROOTFS_MIRRORS[source] + RELEASE_DIRS[release] + "/release/", tag);
            if (fromIndex != null) return fromIndex;
        }
        Artifact probed = probe(context, tag);
        if (probed != null) return probed;
        Artifact fallback = new Artifact();
        fallback.version = RELEASE_FILES[release][0];
        fallback.fileName = "ubuntu-base-" + fallback.version + "-base-" + tag + ".tar.gz";
        fallback.url = (source == SRC_CUSTOM
                ? getRootfsCustomUrl(context)
                : ROOTFS_MIRRORS[source] + RELEASE_DIRS[release] + "/release/") + fallback.fileName;
        return fallback;
    }

    /** 取小文本（限长，防止异常大文件拖垮内存）。 */
    private static String fetchText(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "WebHTV-Lab");
            if (conn.getResponseCode() != 200) return "";
            StringBuilder sb = new StringBuilder();
            try (InputStream in = conn.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    sb.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    if (sb.length() > 262144) break;
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** apt 源地址（自定义源时用用户填的 URL）。 */
    public static String aptUrl(Context context) {
        int source = getAptSource(context);
        if (source == SRC_CUSTOM) {
            String custom = getAptCustomUrl(context);
            return TextUtils.isEmpty(custom) ? "" : custom;
        }
        return APT_MIRRORS[source];
    }

    /**
     * 轻量可达性探测：Range 只取 1 字节。
     * 不用 HEAD——部分镜像站（如 USTC）对 HEAD 直接回 403，会把存在的文件误判成缺失。
     */
    private static boolean reachable(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "WebHTV-Lab");
            conn.setRequestProperty("Range", "bytes=0-0");
            int code = conn.getResponseCode();
            return code == 200 || code == 206;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 从下载地址里解析出版本号，用于元数据展示（24.04.4 等）。 */
    private static String versionFromUrl(String url) {
        if (TextUtils.isEmpty(url)) return "";
        String name = url.substring(url.lastIndexOf('/') + 1);
        if (!name.startsWith("ubuntu-base-")) return "";
        String rest = name.substring("ubuntu-base-".length());
        int index = rest.indexOf("-base-");
        return index > 0 ? rest.substring(0, index) : "";
    }

    /* ===================== 安装 ===================== */

    public static void install(Context context, LabEnv.InstallCallback callback) {
        final Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            File rootfs = rootfsDir(app);
            File backup = backupDir(app);
            File flag = installingFlag(app);
            try {
                String tag = archTag();
                if (tag == null) throw new IOException("当前设备架构不支持 Ubuntu 运行环境");
                if (callback != null) App.post(() -> callback.onProgress("正在准备 ..."));
                Artifact artifact = resolveArtifact(app, tag);
                if (artifact == null || TextUtils.isEmpty(artifact.url)) throw new IOException("没有可用的 rootfs 下载地址");
                String url = artifact.url;

                deleteQuietly(backup);
                if (rootfs.exists() && !rootfs.renameTo(backup)) {
                    throw new IOException("无法备份旧系统，请先卸载");
                }
                runtimeDir(app).mkdirs();
                writeText(flag, String.valueOf(System.currentTimeMillis()));
                try {
                    rootfs.mkdirs();
                    if (callback != null) App.post(() -> callback.onProgress("正在下载 Ubuntu Base ..."));
                    File archive = new File(app.getCacheDir(), "ubuntu-base-" + tag + ".tar.gz");
                    deleteQuietly(archive);
                    LabEnv.download(url, archive, callback, artifact.fileName);
                    if (!TextUtils.isEmpty(artifact.sha256) && !artifact.sha256.equalsIgnoreCase(sha256(archive))) {
                        if (callback != null) App.post(() -> callback.onProgress("校验值不一致（镜像可能尚未同步），继续安装 ..."));
                    }

                    if (callback != null) App.post(() -> callback.onProgress("正在解压 ..."));
                    LabEnv.extract(archive, rootfs, null, (done, total) -> {
                        if (callback != null) callback.onUnzipProgress(done, total);
                    });
                    deleteQuietly(archive);

                    if (callback != null) App.post(callback::onFinalizing);
                    flattenRootfs(rootfs);
                    initRootfs(app, rootfs);
                    LabEnv.ensureProot(app);

                    Meta meta = new Meta();
                    meta.release = RELEASE_DIRS[getRelease(app)];
                    meta.version = artifact.version;
                    meta.source = getRootfsSource(app);
                    meta.url = url;
                    meta.aptSource = getAptSource(app);
                    meta.aptUrl = aptUrl(app);
                    meta.time = System.currentTimeMillis();
                    writeMeta(app, meta);

                    deleteQuietly(backup);
                    deleteQuietly(flag);
                    if (callback != null) App.post(callback::onDone);
                } catch (Exception e) {
                    deleteQuietly(rootfs);
                    if (backup.exists()) backup.renameTo(rootfs);
                    deleteQuietly(flag);
                    throw e;
                }
            } catch (Exception e) {
                deleteQuietly(flag);
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                if (callback != null) App.post(() -> callback.onError(message));
            }
        });
    }

    /** 卸载：只删 rootfs 与元数据，手机共享存储与 lab.json 不受影响。 */
    public static void uninstall(Context context) {
        deleteQuietly(rootfsDir(context));
        deleteQuietly(metaFile(context));
        deleteQuietly(installingFlag(context));
        deleteQuietly(backupDir(context));
    }

    /**
     * ubuntu-base 压缩包顶层是单个目录（ubuntu-base-24.04.4-base-arm64/），
     * 直接解到 rootfs 会让容器根变成嵌套结构，这里把它摊平。
     */
    private static void flattenRootfs(File rootfs) throws IOException {
        if (new File(rootfs, "etc/os-release").exists()) return;
        File[] files = rootfs.listFiles();
        if (files == null || files.length != 1 || !files[0].isDirectory()) return;
        File wrapper = files[0];
        File staging = new File(rootfs.getParentFile(), "rootfs.unwrap");
        deleteQuietly(staging);
        if (!wrapper.renameTo(staging)) return;
        File[] inner = staging.listFiles();
        if (inner == null) return;
        for (File file : inner) {
            if (!file.renameTo(new File(rootfs, file.getName()))) {
                throw new IOException("无法展开 rootfs：" + file.getName());
            }
        }
        deleteQuietly(staging);
    }

    /** 补齐新解出来 rootfs 的 DNS / hosts / hostname / apt 源。 */
    private static void initRootfs(Context context, File rootfs) throws IOException {
        LabEnv.prepareRootfs(rootfs);
        File etc = new File(rootfs, "etc");
        etc.mkdirs();
        writeText(new File(etc, "resolv.conf"), "nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 8.8.8.8\n");
        writeText(new File(etc, "hostname"), HOST_NAME + "\n");
        writeText(new File(etc, "hosts"),
                "127.0.0.1 localhost\n"
                        + "::1 localhost ip6-localhost ip6-loopback\n"
                        + "127.0.1.1 " + HOST_NAME + "\n");
        writeAptSource(context, rootfs);
    }

    private static void writeAptSource(Context context, File rootfs) throws IOException {
        String uri = aptUrl(context);
        if (TextUtils.isEmpty(uri)) return;
        String codename = codename(rootfs);
        if (TextUtils.isEmpty(codename)) codename = RELEASE_CODENAMES[getRelease(context)];
        if (TextUtils.isEmpty(codename)) return;
        String suites = codename + " " + codename + "-updates " + codename + "-backports " + codename + "-security";
        String content = "Types: deb\n"
                + "URIs: " + uri + "\n"
                + "Suites: " + suites + "\n"
                + "Components: main restricted universe multiverse\n"
                + "Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n";
        File dir = new File(rootfs, "etc/apt/sources.list.d");
        dir.mkdirs();
        writeText(new File(dir, "ubuntu.sources"), content);
        File legacy = new File(rootfs, "etc/apt/sources.list");
        if (legacy.exists()) writeText(legacy, "# managed by WebHTV lab\n");
    }

    /** 从 rootfs 的 /etc/os-release 读发行代号（VERSION_CODENAME），不硬编码。 */
    private static String codename(File rootfs) {
        File osRelease = new File(rootfs, "etc/os-release");
        if (!osRelease.exists()) return "";
        try {
            String text = new String(readAll(osRelease), StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("VERSION_CODENAME=")) {
                    return trimmed.substring("VERSION_CODENAME=".length()).replace("\"", "").trim();
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /* ===================== proot 包装 ===================== */

    /**
     * 把一条命令包进 proot 容器。
     * {@code -0} 伪造 root、{@code --link2symlink} 兼容 rootfs 里的硬链接、
     * {@code --kill-on-exit} 退出时清掉容器内子进程。
     */
    public static String prootCommand(Context context, String inner) {
        File proot = LabEnv.ensureProot(context);
        if (proot == null) return null;
        if (TextUtils.isEmpty(inner)) inner = "/bin/bash -l";
        File rootfs = rootfsDir(context);
        // bind 目标必须在 rootfs 内已存在，否则 proot 直接起不来 → 命令全失败
        LabEnv.ensureBindTargets(context, rootfs);
        StringBuilder sb = new StringBuilder();
        sb.append(quote(proot.getAbsolutePath()));
        sb.append(" -0 --link2symlink --kill-on-exit");
        sb.append(" -r ").append(quote(rootfs.getAbsolutePath()));
        // 内核挂载点用宿主原样的 /dev /proc /sys。
        // 注意：**不绑 /tmp**——Android 上没有宿主 /tmp，绑一个不存在的目录会让 proot 起不来，
        // 容器内 /tmp 用 rootfs 自己的（init 时建好并置 1777），临时目录由 PROOT_TMP_DIR/TMPDIR 指到私目录。
        sb.append(" -b /dev -b /proc -b /sys -b /dev/urandom:/dev/random");
        // 私目录同路径绑定：json 里的 {files_dir} / {package_dir} 这类宿主绝对路径，容器内外指向同一份数据
        // （VodPlus 同款做法：filesDir 与 lab 目录都按原路径挂进去，安装标记两边都看得见）
        for (String dir : new String[]{context.getFilesDir().getAbsolutePath(),
                context.getCacheDir().getAbsolutePath(),
                LabEnv.localRoot().getAbsolutePath()}) {
            if (!TextUtils.isEmpty(dir)) {
                sb.append(" -b ").append(quote(dir)).append(":").append(quote(dir));
            }
        }
        String external = externalStorage();
        if (external != null) {
            // 同路径绑定：lab.json 里写死的 /storage/emulated/0/... 在容器内按原路径可直接用
            sb.append(" -b ").append(quote(external)).append(":").append(quote(external));
            // lab 根目录（存放 lab.json / 各条目目录）在容器内同时可见为 /lab，兼容 json 里的 /lab/... 写法
            sb.append(" -b ").append(quote(LabEnv.localRoot().getAbsolutePath())).append(":/lab");
            if (getSharedStorage(context)) {
                sb.append(" -b ").append(quote(external)).append(":/sdcard");
            }
        }
        sb.append(" -w /root");
        sb.append(" /usr/bin/env -i HOME=/root");
        sb.append(" PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        sb.append(" TERM=xterm-256color LANG=C.UTF-8 LC_ALL=C.UTF-8");
        sb.append(" /bin/bash -lc ").append(quote(inner));
        return sb.toString();
    }

    /** 打开交互式 shell（-i 让容器内 bash 打印提示符并逐行读 stdin）。 */
    public static String shellCommand(Context context) {
        return prootCommand(context, "/bin/bash -i");
    }

    public static String externalStorage() {
        try {
            File dir = Environment.getExternalStorageDirectory();
            return dir == null ? null : dir.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    /** 单引号安全包裹，供 shell 消费。 */
    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /* ===================== 工具 ===================== */

    private static String fileName(String url) {
        int index = url == null ? -1 : url.lastIndexOf('/');
        return index < 0 ? "ubuntu-base.tar.gz" : url.substring(index + 1);
    }

    static void deleteQuietly(File file) {
        if (file == null || !file.exists()) return;
        try {
            if (file.isDirectory()) {
                File[] files = file.listFiles();
                if (files != null) for (File child : files) deleteQuietly(child);
            }
            file.delete();
        } catch (Exception ignored) {
        }
    }

    private static String sha256(File file) {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            StringBuilder sb = new StringBuilder();
            for (byte value : digest.digest()) sb.append(String.format(Locale.US, "%02x", value));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] readAll(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) file.length()];
            int offset = 0;
            while (offset < buffer.length) {
                int read = in.read(buffer, offset, buffer.length - offset);
                if (read <= 0) break;
                offset += read;
            }
            if (offset == buffer.length) return buffer;
            byte[] result = new byte[offset];
            System.arraycopy(buffer, 0, result, 0, offset);
            return result;
        }
    }

    static void writeText(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        file.setReadable(true, false);
    }

    /** 供界面显示的概况，如 "Ubuntu Base 24.04.4 · 阿里云 · arm64"。 */
    public static String summary(Context context) {
        if (!installed(context)) return "未安装";
        String source = sourceLabels()[getRootfsSource(context)];
        return installedVersion(context) + " · " + source + String.format(Locale.US, " · %s", archTag());
    }
}
