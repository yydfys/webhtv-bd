package com.fongmi.android.tv.lab;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import com.fongmi.android.tv.R;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 把 Ubuntu 私有目录（files/lab-runtime）通过 SAF 暴露给系统文件管理器
 * （MT 管理器「添加本地存储」里显示为「Ubuntu 虚拟存储」），用于浏览 / 上传 /
 * 下载 / 新建 / 重命名 / 移动 / 删除 Rootfs 内的文件。
 *
 * <p>安全边界：只映射 lab-runtime 一棵树，解析出的真实路径必须落在该目录内
 * （canonical 校验），rootfs 里指向宿主绝对路径的符号链接一律拒绝访问。
 */
public class UbuntuDocumentsProvider extends DocumentsProvider {

    private static final String ROOT_ID = "ubuntu";
    private static final String ROOT_DOC_ID = "root";
    private static final String DIR_MIME = Document.MIME_TYPE_DIR;
    private static final String SEPARATOR = "/";

    private static final String[] DEFAULT_ROOT_PROJECTION = {
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES, Root.COLUMN_ICON, Root.COLUMN_AVAILABLE_BYTES,
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
    };

    @Override
    public boolean onCreate() {
        return getContext() != null;
    }

    /* ===================== roots ===================== */

    @Override
    public Cursor queryRoots(String[] projection) {
        String[] columns = projection == null ? DEFAULT_ROOT_PROJECTION : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        File base = LabUbuntu.runtimeDir(getContext());
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            switch (column) {
                case Root.COLUMN_ROOT_ID:
                    row.add(column, ROOT_ID);
                    break;
                case Root.COLUMN_DOCUMENT_ID:
                    row.add(column, ROOT_DOC_ID);
                    break;
                case Root.COLUMN_TITLE:
                    row.add(column, "Ubuntu 虚拟存储");
                    break;
                case Root.COLUMN_SUMMARY:
                    row.add(column, LabUbuntu.installed(getContext())
                            ? LabUbuntu.installedVersion(getContext()) + " · Rootfs 文件管理"
                            : "Rootfs 文件管理");
                    break;
                case Root.COLUMN_FLAGS:
                    row.add(column, Root.FLAG_LOCAL_ONLY | Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_IS_CHILD);
                    break;
                case Root.COLUMN_MIME_TYPES:
                    row.add(column, "*/*");
                    break;
                case Root.COLUMN_ICON:
                    row.add(column, R.drawable.ic_setting_folder);
                    break;
                case Root.COLUMN_AVAILABLE_BYTES:
                    row.add(column, base.getFreeSpace());
                    break;
                default:
                    row.add(column, null);
                    break;
            }
        }
        return cursor;
    }

    /* ===================== documents ===================== */

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        String[] columns = projection == null ? DEFAULT_DOCUMENT_PROJECTION : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        File file = resolve(documentId);
        includeFile(cursor, columns, file, documentId);
        return cursor;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        String[] columns = projection == null ? DEFAULT_DOCUMENT_PROJECTION : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        File parent = resolve(parentDocumentId);
        File[] children = parent.listFiles();
        List<File> files = new ArrayList<>();
        if (children != null) {
            for (File child : children) {
                if (isInside(child)) files.add(child);
            }
        }
        Collections.sort(files, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        for (File child : files) {
            try {
                includeFile(cursor, columns, child, documentIdOf(child));
            } catch (Exception ignored) {
                // 个别条目（权限/竞态）跳过，不影响整目录列举
            }
        }
        return cursor;
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        File file = resolve(documentId);
        return file.isDirectory() ? DIR_MIME : mimeOf(file);
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentDocumentId) {
        try {
            File parent = resolve(parentDocumentId);
            File child = resolve(documentDocumentId);
            String parentPath = parent.getCanonicalPath();
            String childPath = child.getCanonicalPath();
            return childPath.equals(parentPath) || childPath.startsWith(parentPath + File.separator);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        File file = resolve(documentId);
        boolean write = mode != null && (mode.contains("w") || mode.contains("a") || mode.contains("t"));
        if (!write) {
            if (!file.exists()) throw new FileNotFoundException("文件不存在：" + documentId);
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        // SAF 契约：以「写」方式打开时必须自己把文件建出来（AOSP 的 ExternalStorageProvider 同样如此），
        // 不能因为文件还不存在就抛 FileNotFoundException。
        // MT 管理器「复制到虚拟存储」正是先取 openOutputStream，再由 provider 负责创建：
        // 旧实现直接抛「文件不存在：root/ubuntu/...」→ 复制全部失败。
        if (file.isDirectory()) throw new FileNotFoundException("目标是一个目录，无法写入：" + documentId);
        if (!file.exists()) {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new FileNotFoundException("无法创建父目录：" + documentId);
            }
            try {
                if (!file.createNewFile() && !file.exists()) {
                    throw new FileNotFoundException("无法创建文件：" + documentId);
                }
            } catch (IOException e) {
                throw new FileNotFoundException("无法创建文件：" + documentId);
            }
        }
        int flags = ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE;
        // "wt"/"w" 都是从头写（截断）；只有纯 "a" 追加才保留原内容
        boolean append = "a".equals(mode) || "wa".equals(mode);
        if (!append) flags |= ParcelFileDescriptor.MODE_TRUNCATE;
        return ParcelFileDescriptor.open(file, flags);
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        File parent = resolve(parentDocumentId);
        if (!parent.isDirectory()) throw new FileNotFoundException("父节点不是目录");
        boolean directory = DIR_MIME.equals(mimeType);
        String name = sanitize(displayName);
        // SAF 契约：同名时生成唯一名字（AOSP 也是这样），不能静默返回已存在的条目 ——
        // 否则 MT 新建文件夹会提示「文件已存在」，复制粘贴同名文件也会互相覆盖。
        File target = unique(parent, name, directory);
        if (directory) {
            if (!target.mkdirs() && !target.isDirectory()) throw new FileNotFoundException("无法创建目录：" + target.getName());
        } else {
            try {
                if (!target.createNewFile() && !target.isFile()) {
                    throw new FileNotFoundException("无法创建文件：" + target.getName());
                }
            } catch (IOException e) {
                throw new FileNotFoundException("无法创建文件：" + target.getName());
            }
        }
        return documentIdOf(target);
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        if (ROOT_DOC_ID.equals(documentId)) throw new FileNotFoundException("根目录不可删除");
        File file = resolve(documentId);
        deleteRecursively(file);
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        if (ROOT_DOC_ID.equals(documentId)) throw new FileNotFoundException("根目录不可重命名");
        File file = resolve(documentId);
        File parent = file.getParentFile();
        if (parent == null) throw new FileNotFoundException("无法定位父目录");
        String name = sanitize(displayName);
        if (name.equals(file.getName())) return documentId;
        // SAF 契约：重命名撞名时生成唯一名字（MT 的新建/重命名流程会重试，直接报错会表现为「文件已存在」）
        File target = unique(parent, name, file.isDirectory());
        if (!file.renameTo(target)) throw new FileNotFoundException("重命名失败：" + target.getName());
        return documentIdOf(target);
    }

    @Override
    public String moveDocument(String sourceDocumentId, String sourceParentDocumentId, String targetParentDocumentId)
            throws FileNotFoundException {
        File source = resolve(sourceDocumentId);
        File targetParent = resolve(targetParentDocumentId);
        if (!targetParent.isDirectory()) throw new FileNotFoundException("目标不是目录");
        File target = unique(targetParent, source.getName(), source.isDirectory());
        if (!source.renameTo(target)) throw new FileNotFoundException("移动失败：" + target.getName());
        return documentIdOf(target);
    }

    /* ===================== 内部工具 ===================== */

    private void includeFile(MatrixCursor cursor, String[] columns, File file, String documentId) {
        boolean directory = file.isDirectory();
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            switch (column) {
                case Document.COLUMN_DOCUMENT_ID:
                    row.add(column, documentId);
                    break;
                case Document.COLUMN_DISPLAY_NAME:
                    row.add(column, file.getName());
                    break;
                case Document.COLUMN_MIME_TYPE:
                    row.add(column, directory ? DIR_MIME : mimeOf(file));
                    break;
                case Document.COLUMN_SIZE:
                    row.add(column, directory ? null : file.length());
                    break;
                case Document.COLUMN_LAST_MODIFIED:
                    row.add(column, file.lastModified());
                    break;
                case Document.COLUMN_FLAGS: {
                    int flags = 0;
                    if (directory) {
                        flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
                    } else {
                        flags |= Document.FLAG_SUPPORTS_WRITE;
                    }
                    if (!ROOT_DOC_ID.equals(documentId)) {
                        flags |= Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME | Document.FLAG_SUPPORTS_MOVE;
                    }
                    row.add(column, flags);
                    break;
                }
                case Document.COLUMN_SUMMARY:
                    row.add(column, directory ? "目录" : LabEnv.formatSize(file.length()));
                    break;
                default:
                    row.add(column, null);
                    break;
            }
        }
    }

    /** 文档 ID → 真实文件；越界（含符号链接逃逸）一律拒绝。 */
    private File resolve(String documentId) throws FileNotFoundException {
        File base = LabUbuntu.runtimeDir(getContext());
        File file;
        if (TextUtils.isEmpty(documentId) || ROOT_DOC_ID.equals(documentId)) {
            file = base;
        } else if (documentId.startsWith(ROOT_DOC_ID + SEPARATOR)) {
            String relative = documentId.substring(ROOT_DOC_ID.length() + 1);
            if (relative.contains("..")) throw new FileNotFoundException("非法路径");
            file = new File(base, relative);
        } else {
            throw new FileNotFoundException("未知的文档 ID：" + documentId);
        }
        if (!isInside(file)) throw new FileNotFoundException("路径越界，已拒绝访问");
        return file;
    }

    private String documentIdOf(File file) {
        File base = LabUbuntu.runtimeDir(getContext());
        String basePath = base.getAbsolutePath();
        String path = file.getAbsolutePath();
        if (path.equals(basePath)) return ROOT_DOC_ID;
        if (path.startsWith(basePath + SEPARATOR)) return ROOT_DOC_ID + SEPARATOR + path.substring(basePath.length() + 1);
        throw new IllegalArgumentException("文件不在 Ubuntu 目录内：" + path);
    }

    /** canonical 校验：解析掉符号链接后仍须落在 lab-runtime 内。 */
    private boolean isInside(File file) {
        try {
            File base = LabUbuntu.runtimeDir(getContext());
            String basePath = base.getCanonicalPath();
            String path = file.getCanonicalPath();
            return path.equals(basePath) || path.startsWith(basePath + File.separator);
        } catch (Exception e) {
            return false;
        }
    }

    private static String mimeOf(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (!TextUtils.isEmpty(type)) return type;
        }
        return "application/octet-stream";
    }

    /**
     * 同名时按 AOSP 规则生成唯一名字：{@code name.ext} → {@code name (1).ext}。
     * 目录不加扩展名后缀。最多试 1000 次，仍撞则退回「带时间戳」的名字。
     */
    private static File unique(File parent, String name, boolean directory) {
        File target = new File(parent, name);
        if (!target.exists()) return target;
        String base = name;
        String extension = "";
        int dot = name.lastIndexOf('.');
        if (!directory && dot > 0 && dot < name.length() - 1) {
            base = name.substring(0, dot);
            extension = name.substring(dot);
        }
        for (int i = 1; i < 1000; i++) {
            target = new File(parent, base + " (" + i + ")" + extension);
            if (!target.exists()) return target;
        }
        return new File(parent, base + " (" + System.currentTimeMillis() + ")" + extension);
    }

    private static String sanitize(String name) {
        if (TextUtils.isEmpty(name)) return "untitled";
        String value = name.replace("/", "_").trim();
        if (value.equals(".") || value.equals("..")) return "untitled";
        return value;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }
}
