package com.fongmi.android.tv.server.process;

import android.util.Base64;

import com.fongmi.android.tv.server.impl.Process;
import com.github.catvod.utils.Path;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Method;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;

/**
 * WebDAV 服务端：把 9978 端口挂成局域网可写网盘，供 MT 管理器等 WebDAV 客户端管理电视存储。
 * <p>
 * 根目录 = Path.root()（内部存储 /storage/emulated/0），Ubuntu 虚拟存储在
 * Android/data/&lt;包名&gt;/files/lab-runtime 下面，同一个根里就能一并管理。
 * <p>
 * 接入地址两种都行：http://ip:9978/dav 或 http://ip:9978 （根即网盘根）。
 * 只有 /dav 前缀下的 GET/HEAD 走本类，根路径的 GET 仍然返回原来的网页，不影响原有功能。
 * <p>
 * 认证：本类处理的请求（即 WebDAV 这一层）统一要求 HTTP Basic 认证，用户名 admin，
 * 密码 aB@123456。9978 的其余功能（网页、接口等）不走本类，因此不受影响。
 */
public class WebDav implements Process {

    private static final String PREFIX = "/dav";
    private static final String AUTH_USER = "admin";
    private static final String AUTH_PASS = "aB@123456";
    private static final String AUTH_REALM = "WebHTV";
    private static final String CT_XML = "application/xml; charset=utf-8";
    private static final String CT_TEXT = "text/plain; charset=utf-8";
    private static final String CT_HTML = "text/html; charset=utf-8";
    private static final String DAV_HEADER = "1, 2";
    private static final String ALLOW = "OPTIONS, GET, HEAD, PUT, DELETE, PROPFIND, PROPPATCH, MKCOL, COPY, MOVE, LOCK, UNLOCK";
    private static final String STAMP_ISO = "yyyy-MM-dd'T'HH:mm:ss'Z'";
    private static final String STAMP_HTTP = "EEE, dd MMM yyyy HH:mm:ss 'GMT'";
    private static final String LOCK_TOKEN = "opaquelocktoken:webhtv-dav-1";
    private static final TimeZone GMT = TimeZone.getTimeZone("GMT");
    private static final int BUFFER = 64 * 1024;

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        Method method = session.getMethod();
        if (method == null) return false;
        switch (method) {
            case PROPFIND:
            case PROPPATCH:
            case MKCOL:
            case COPY:
            case MOVE:
            case LOCK:
            case UNLOCK:
            case PUT:
            case DELETE:
                return true;
            case GET:
            case HEAD:
                return isDav(url);
            case OPTIONS:
                return isDav(url) || url.equals("/") || url.isEmpty();
            default:
                return false;
        }
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        if (!authorized(session)) return unauthorized();
        String path = isDav(url) ? url.substring(PREFIX.length()) : url;
        if (path.isEmpty()) path = "/";
        try {
            File target = resolve(path);
            if (target == null) return text(Status.FORBIDDEN, "非法路径");
            switch (session.getMethod()) {
                case OPTIONS:
                    return options();
                case PROPFIND:
                    return propfind(session, url, target);
                case PROPPATCH:
                    drain(session);
                    return ack(url);
                case MKCOL:
                    drain(session);
                    return mkcol(target);
                case PUT:
                    return put(session, target);
                case GET:
                case HEAD:
                    return get(session, target, url);
                case DELETE:
                    drain(session);
                    return delete(target);
                case MOVE:
                    return moveOrCopy(session, target, true);
                case COPY:
                    return moveOrCopy(session, target, false);
                case LOCK:
                    drain(session);
                    return lock();
                case UNLOCK:
                    drain(session);
                    return status(Status.NO_CONTENT);
                default:
                    drain(session);
                    return text(Status.NOT_IMPLEMENTED, "未实现");
            }
        } catch (Exception e) {
            return text(Status.INTERNAL_ERROR, "错误：" + e);
        }
    }

    private boolean isDav(String url) {
        return url.equals(PREFIX) || url.startsWith(PREFIX + "/");
    }

    private Response unauthorized() {
        Response response = NanoHTTPD.newFixedLengthResponse(Status.UNAUTHORIZED, CT_TEXT, "401 Unauthorized");
        response.addHeader("WWW-Authenticate", "Basic realm=\"" + AUTH_REALM + "\", charset=\"UTF-8\"");
        return response;
    }

    private boolean authorized(IHTTPSession session) {
        String header = header(session, "authorization");
        if (header == null) return false;
        String value = header.trim();
        if (value.length() < 6 || !value.substring(0, 6).equalsIgnoreCase("basic ")) return false;
        String plain;
        try {
            plain = new String(Base64.decode(value.substring(6).trim(), Base64.DEFAULT), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return false;
        }
        int index = plain.indexOf(':');
        if (index < 0) return false;
        return AUTH_USER.equals(plain.substring(0, index)) && AUTH_PASS.equals(plain.substring(index + 1));
    }

    private File resolve(String path) throws Exception {
        for (String part : path.split("/")) if (part.equals("..")) return null;
        File root = Path.root().getCanonicalFile();
        String relative = path.replace('\\', '/');
        while (relative.startsWith("/")) relative = relative.substring(1);
        File file = relative.isEmpty() ? root : new File(root, relative);
        String base = root.getPath();
        String full = file.getCanonicalPath();
        if (!full.equals(base) && !full.startsWith(base + File.separator)) return null;
        return file;
    }

    private Response options() {
        Response response = NanoHTTPD.newFixedLengthResponse(Status.OK, CT_TEXT, "");
        response.addHeader("DAV", DAV_HEADER);
        response.addHeader("Allow", ALLOW);
        response.addHeader("MS-Author-Via", "DAV");
        response.addHeader("Accept-Ranges", "bytes");
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Headers", "*");
        response.addHeader("Access-Control-Allow-Methods", ALLOW);
        return response;
    }

    private int depth(IHTTPSession session) {
        String value = session.getHeaders().get("depth");
        if (value == null) return 1;
        return value.trim().equals("0") ? 0 : 1;
    }

    private Response propfind(IHTTPSession session, String url, File target) throws Exception {
        drain(session);
        if (!target.exists()) return text(Status.NOT_FOUND, "不存在");
        int depth = depth(session);
        boolean root = target.getCanonicalPath().equals(Path.root().getCanonicalFile().getPath());
        String self = target.isDirectory() && !url.endsWith("/") ? url + "/" : url;
        StringBuilder builder = new StringBuilder(4096);
        builder.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:multistatus xmlns:D=\"DAV:\">\n");
        entry(builder, self, target, root ? "内部存储" : null);
        if (depth > 0 && target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                Arrays.sort(children, (a, b) -> {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                });
                String base = self.endsWith("/") ? self : self + "/";
                for (File child : children) entry(builder, base + child.getName() + (child.isDirectory() ? "/" : ""), child, null);
            }
        }
        builder.append("</D:multistatus>");
        Response response = NanoHTTPD.newFixedLengthResponse(Status.MULTI_STATUS, CT_XML, builder.toString());
        response.addHeader("DAV", DAV_HEADER);
        return response;
    }

    private void entry(StringBuilder builder, String href, File file, String label) {
        String name = label == null || label.isEmpty() ? file.getName() : label;
        if (name.isEmpty()) name = "内部存储";
        builder.append("<D:response>\n<D:href>").append(encode(href)).append("</D:href>\n<D:propstat>\n<D:prop>\n");
        builder.append("<D:displayname>").append(escape(name)).append("</D:displayname>\n");
        if (file.isDirectory()) {
            builder.append("<D:resourcetype><D:collection/></D:resourcetype>\n");
        } else {
            builder.append("<D:resourcetype/>\n");
            builder.append("<D:getcontentlength>").append(file.length()).append("</D:getcontentlength>\n");
            builder.append("<D:getcontenttype>").append(escape(mime(file.getName()))).append("</D:getcontenttype>\n");
        }
        builder.append("<D:getlastmodified>").append(stamp(file.lastModified(), STAMP_HTTP)).append("</D:getlastmodified>\n");
        builder.append("<D:creationdate>").append(stamp(file.lastModified(), STAMP_ISO)).append("</D:creationdate>\n");
        builder.append("<D:supportedlock><D:lockentry><D:lockscope><D:exclusive/></D:lockscope><D:locktype><D:write/></D:locktype></D:lockentry></D:supportedlock>\n");
        builder.append("</D:prop>\n<D:status>HTTP/1.1 200 OK</D:status>\n</D:propstat>\n</D:response>\n");
    }

    private Response ack(String url) {
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:multistatus xmlns:D=\"DAV:\">\n<D:response>\n<D:href>"
                + encode(url) + "</D:href>\n<D:propstat>\n<D:prop/>\n<D:status>HTTP/1.1 200 OK</D:status>\n</D:propstat>\n</D:response>\n</D:multistatus>";
        return NanoHTTPD.newFixedLengthResponse(Status.MULTI_STATUS, CT_XML, body);
    }

    private Response lock() {
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:prop xmlns:D=\"DAV:\">\n<D:lockdiscovery>\n<D:activelock>\n"
                + "<D:locktype><D:write/></D:locktype>\n<D:lockscope><D:exclusive/></D:lockscope>\n<D:depth>infinity</D:depth>\n"
                + "<D:timeout>Second-3600</D:timeout>\n<D:locktoken><D:href>" + LOCK_TOKEN + "</D:href></D:locktoken>\n"
                + "</D:activelock>\n</D:lockdiscovery>\n</D:prop>";
        Response response = NanoHTTPD.newFixedLengthResponse(Status.OK, CT_XML, body);
        response.addHeader("Lock-Token", "<" + LOCK_TOKEN + ">");
        response.addHeader("DAV", DAV_HEADER);
        return response;
    }

    private Response mkcol(File target) {
        if (target.exists()) return text(Status.METHOD_NOT_ALLOWED, "已存在");
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) return text(Status.CONFLICT, "上级目录不存在");
        return target.mkdir() ? status(Status.CREATED) : text(Status.FORBIDDEN, "创建失败");
    }

    private Response put(IHTTPSession session, File target) throws Exception {
        if (target.isDirectory()) return text(Status.METHOD_NOT_ALLOWED, "目标是目录");
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) return text(Status.CONFLICT, "无法创建上级目录");
        boolean existed = target.exists();
        long length = headerLong(session, "content-length", -1);
        String encoding = header(session, "transfer-encoding");
        try (InputStream in = session.getInputStream(); OutputStream out = new FileOutputStream(target)) {
            if (encoding != null && encoding.toLowerCase(Locale.ROOT).contains("chunked")) copyChunked(in, out);
            else if (length >= 0) copy(in, out, length);
            else copyAll(in, out);
        } catch (Exception e) {
            if (!existed) target.delete();
            return text(Status.INTERNAL_ERROR, "写入失败：" + e);
        }
        return status(existed ? Status.NO_CONTENT : Status.CREATED);
    }

    private Response get(IHTTPSession session, File target, String url) throws Exception {
        if (!target.exists()) return text(Status.NOT_FOUND, "不存在");
        String mime = target.isDirectory() ? CT_HTML : mime(target.getName());
        if (target.isDirectory()) return listing(url, target);
        long length = target.length();
        String range = header(session, "range");
        boolean head = session.getMethod() == Method.HEAD;
        if (range != null && range.startsWith("bytes=") && !range.contains(",")) {
            long[] span = range(range.substring(6).trim(), length);
            if (span != null) {
                long size = span[1] - span[0] + 1;
                if (head) {
                    Response response = NanoHTTPD.newFixedLengthResponse(Status.PARTIAL_CONTENT, mime, "");
                    response.addHeader("Content-Length", String.valueOf(size));
                    response.addHeader("Content-Range", "bytes " + span[0] + "-" + span[1] + "/" + length);
                    response.addHeader("Accept-Ranges", "bytes");
                    return response;
                }
                FileInputStream in = new FileInputStream(target);
                skip(in, span[0]);
                Response response = NanoHTTPD.newFixedLengthResponse(Status.PARTIAL_CONTENT, mime, in, size);
                response.addHeader("Content-Range", "bytes " + span[0] + "-" + span[1] + "/" + length);
                response.addHeader("Accept-Ranges", "bytes");
                return response;
            }
        }
        if (head) {
            Response response = NanoHTTPD.newFixedLengthResponse(Status.OK, mime, "");
            response.addHeader("Content-Length", String.valueOf(length));
            response.addHeader("Accept-Ranges", "bytes");
            return response;
        }
        Response response = NanoHTTPD.newFixedLengthResponse(Status.OK, mime, new FileInputStream(target), length);
        response.addHeader("Accept-Ranges", "bytes");
        return response;
    }

    private Response listing(String url, File dir) {
        StringBuilder builder = new StringBuilder(2048);
        builder.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">");
        builder.append("<title>WebDAV</title></head><body style=\"font-family:sans-serif;padding:16px\">");
        builder.append("<h3>WebDAV 目录：").append(escape(url)).append("</h3><ul>");
        if (!url.equals(PREFIX) && !url.equals(PREFIX + "/")) builder.append("<li><a href=\"..\">..</a></li>");
        File[] children = dir.listFiles();
        if (children != null) {
            Arrays.sort(children, (a, b) -> {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });
            String base = url.endsWith("/") ? url : url + "/";
            for (File child : children) {
                String name = child.getName() + (child.isDirectory() ? "/" : "");
                builder.append("<li><a href=\"").append(encode(base + name)).append("\">").append(escape(name)).append("</a></li>");
            }
        }
        builder.append("</ul></body></html>");
        return NanoHTTPD.newFixedLengthResponse(Status.OK, CT_HTML, builder.toString());
    }

    private Response delete(File target) throws Exception {
        if (!target.exists()) return text(Status.NOT_FOUND, "不存在");
        if (target.getCanonicalPath().equals(Path.root().getCanonicalFile().getPath())) return text(Status.FORBIDDEN, "根目录不可删除");
        return deleteRecursive(target) ? status(Status.NO_CONTENT) : text(Status.FORBIDDEN, "删除失败");
    }

    private Response moveOrCopy(IHTTPSession session, File source, boolean move) throws Exception {
        drain(session);
        String destination = header(session, "destination");
        if (destination == null || destination.isEmpty()) return text(Status.BAD_REQUEST, "缺少 Destination");
        File target = resolve(destinationPath(destination));
        if (target == null) return text(Status.FORBIDDEN, "非法目标");
        if (!source.exists()) return text(Status.NOT_FOUND, "源不存在");
        if (source.getCanonicalPath().equals(target.getCanonicalPath())) return text(Status.FORBIDDEN, "源与目标相同");
        if (source.getCanonicalPath().equals(Path.root().getCanonicalFile().getPath())) return text(Status.FORBIDDEN, "根目录不可移动");
        boolean overwrite = !"F".equalsIgnoreCase(headerOr(session, "overwrite", "T"));
        boolean existed = target.exists();
        if (existed && (!overwrite || !deleteRecursive(target))) return text(Status.PRECONDITION_FAILED, "目标已存在");
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) return text(Status.CONFLICT, "无法创建上级目录");
        boolean done = move ? (source.renameTo(target) || (copyRecursive(source, target) && deleteRecursive(source))) : copyRecursive(source, target);
        if (!done) return text(Status.INTERNAL_ERROR, "操作失败");
        return status(existed ? Status.NO_CONTENT : Status.CREATED);
    }

    private String destinationPath(String destination) {
        String path = destination.trim();
        int scheme = path.indexOf("://");
        if (scheme > 0) {
            path = path.substring(scheme + 3);
            int slash = path.indexOf('/');
            path = slash < 0 ? "/" : path.substring(slash);
        }
        int query = path.indexOf('?');
        if (query > 0) path = path.substring(0, query);
        try {
            path = URLDecoder.decode(path, "UTF-8");
        } catch (Exception ignored) {
        }
        if (isDav(path)) path = path.substring(PREFIX.length());
        return path.isEmpty() ? "/" : path;
    }

    private boolean copyRecursive(File source, File target) {
        if (source.isDirectory()) {
            if (!target.exists() && !target.mkdirs()) return false;
            File[] children = source.listFiles();
            if (children != null) for (File child : children) if (!copyRecursive(child, new File(target, child.getName()))) return false;
            return true;
        }
        try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(target)) {
            copyAll(in, out);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) if (!deleteRecursive(child)) return false;
        }
        return file.delete();
    }

    private void copy(InputStream in, OutputStream out, long length) throws Exception {
        byte[] buffer = new byte[BUFFER];
        long remaining = length;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) break;
            out.write(buffer, 0, read);
            remaining -= read;
        }
        out.flush();
    }

    private void copyAll(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[BUFFER];
        int read;
        while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        out.flush();
    }

    private void copyChunked(InputStream in, OutputStream out) throws Exception {
        while (true) {
            String line = readLine(in);
            if (line == null) break;
            int semicolon = line.indexOf(';');
            if (semicolon >= 0) line = line.substring(0, semicolon);
            line = line.trim();
            if (line.isEmpty()) continue;
            int size;
            try {
                size = Integer.parseInt(line, 16);
            } catch (Exception e) {
                break;
            }
            if (size <= 0) {
                drainChunkEnd(in);
                break;
            }
            copy(in, out, size);
            readLine(in);
        }
        out.flush();
    }

    private void drainChunkEnd(InputStream in) throws Exception {
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            // 尾部 trailer，忽略
        }
    }

    private String readLine(InputStream in) throws Exception {
        StringBuilder builder = new StringBuilder();
        int current;
        while ((current = in.read()) != -1) {
            if (current == '\n') return builder.toString();
            if (current != '\r') builder.append((char) current);
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    private void drain(IHTTPSession session) {
        long length = headerLong(session, "content-length", 0);
        if (length <= 0) return;
        try {
            InputStream in = session.getInputStream();
            byte[] buffer = new byte[8 * 1024];
            long remaining = Math.min(length, 1024L * 1024L);
            while (remaining > 0) {
                int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) break;
                remaining -= read;
            }
        } catch (Exception ignored) {
        }
    }

    private void skip(InputStream in, long count) throws Exception {
        long remaining = count;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) return;
                remaining -= 1;
                continue;
            }
            remaining -= skipped;
        }
    }

    private long[] range(String value, long length) {
        try {
            int dash = value.indexOf('-');
            if (dash < 0 || length <= 0) return null;
            String head = value.substring(0, dash).trim();
            String tail = value.substring(dash + 1).trim();
            if (head.isEmpty()) {
                long size = Long.parseLong(tail);
                if (size <= 0) return null;
                long start = Math.max(0, length - size);
                return new long[]{start, length - 1};
            }
            long start = Long.parseLong(head);
            long end = tail.isEmpty() ? length - 1 : Long.parseLong(tail);
            if (start >= length) return null;
            return new long[]{start, Math.min(end, length - 1)};
        } catch (Exception e) {
            return null;
        }
    }

    private String header(IHTTPSession session, String name) {
        return session.getHeaders().get(name);
    }

    private String headerOr(IHTTPSession session, String name, String fallback) {
        String value = header(session, name);
        return value == null || value.isEmpty() ? fallback : value;
    }

    private long headerLong(IHTTPSession session, String name, long fallback) {
        String value = header(session, name);
        if (value == null || value.isEmpty()) return fallback;
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private String mime(String name) {
        String mime = NanoHTTPD.getMimeTypeForFile(name);
        return mime == null || mime.isEmpty() ? "application/octet-stream" : mime;
    }

    private String stamp(long time, String format) {
        SimpleDateFormat formatter = new SimpleDateFormat(format, Locale.US);
        formatter.setTimeZone(GMT);
        return formatter.format(new Date(time));
    }

    private String encode(String href) {
        StringBuilder builder = new StringBuilder(href.length() + 16);
        for (byte value : href.getBytes(StandardCharsets.UTF_8)) {
            char ch = (char) (value & 0xFF);
            boolean keep = ch == '/' || ch == ':' || ch == '-' || ch == '_' || ch == '.' || ch == '~'
                    || (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9');
            if (keep) builder.append(ch);
            else builder.append('%').append(String.format("%02X", value & 0xFF));
        }
        return builder.toString();
    }

    private String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private Response status(Status status) {
        return NanoHTTPD.newFixedLengthResponse(status, CT_TEXT, "");
    }

    private Response text(Status status, String message) {
        return NanoHTTPD.newFixedLengthResponse(status, CT_TEXT, message);
    }
}
