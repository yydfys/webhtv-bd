package com.fongmi.android.tv.lab;

import android.text.Editable;
import android.widget.TextView;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 终端文本处理器：**字节级读取** + **终端落屏语义**。
 *
 * <p><b>为什么不能用 readLine()</b>：lab 的命令跑在管道里（不是 PTY），而很多程序只在
 * 终端里才画进度条。以 CloudflareSpeedTest 用的 pb 进度条为例，管道模式下它
 * <b>既不写 \r 也不写 \n</b>——实测一帧帧纯空格拼接连着吐（raw 字节里 CR = 0）。
 * readLine() 找不到行结束符只能一直攒着，直到后面某个换行才一次性吐出来，
 * 用户看到的就是"测速跑完才刷出一大坨"。所以这里改成收到多少发多少。
 *
 * <p><b>落屏按真终端语义实现</b>（不是简单地"遇 \r 清行"——那样会把 CRLF 输出的正文吃掉）：
 * <ul>
 *   <li>{@code \r} = 光标回行首；{@code \n} = 换行；{@code \b} = 光标左移一格；</li>
 *   <li>普通字符写到光标处：行尾就追加，行中间就<b>覆盖</b>该位置已有的字符
 *       （跨行时不吞换行符）——进度条因此原地刷新，CRLF 文本也照常成行；</li>
 *   <li>剥离 ANSI 转义序列（颜色/光标控制），否则文本框里会显示 [32m 这类乱码；</li>
 *   <li>末尾体积保护，防止长跑命令把 TextView 撑爆。</li>
 * </ul>
 */
public final class LabTerminalWriter {

    /** 超过这个字符数就砍掉最前面一段，只保留最近的输出。 */
    private static final int MAX_CHARS = 300000;
    private static final int TRIM_TO = 200000;

    /**
     * OSC / CSI / 双字符转义 / 其余控制字符（保留 {@code \t \n \r \b}——退格要留给
     * 光标逻辑处理，剥掉就成了"删字符"的另一层语义，会串味）。
     * 注意别写成 {@code [@-Z\\-_]} 这种范围简写——末尾的 {@code \\-} 会被当成范围，
     * 把 {@code ]} 也一起剥掉。
     */
    private static final Pattern ANSI = Pattern.compile(
            "\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)"
                    + "|\u001B\\[[0-9;?]*[ -/]*[@-~]"
                    + "|\u001B[@-Z]"
                    + "|\u001B\\\\"
                    + "|\u001B_"
                    + "|[\u0000-\u0007\u000B\u000C\u000E-\u001F\u007F]");

    /** 字节级泵的回调：每读到一段完整字符就回调一次（可能不是完整的一行）。 */
    public interface ChunkListener {
        void onChunk(String text);
    }

    private final TextView view;
    /** 光标所在下标（写入位置）。 */
    private int cursor;
    /** 当前行行首在文档中的下标（\r 回到这里）。 */
    private int lineStart;

    public LabTerminalWriter(TextView view) {
        this.view = view;
        this.cursor = 0;
        this.lineStart = 0;
    }

    public TextView view() {
        return view;
    }

    /**
     * 字节级读取：收到多少发多少，不按行切。
     *
     * <p>用流式 {@link CharsetDecoder} 做增量 UTF-8 解码——一个中文字被拆在两次 read
     * 之间时，半个字符的字节会留到下一批，不会解出乱码。
     */
    public static void pump(InputStream in, ChunkListener listener) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        byte[] buf = new byte[8192];
        ByteBuffer carry = ByteBuffer.allocate(0);
        try (InputStream input = in) {
            int count;
            while ((count = input.read(buf)) != -1) {
                if (count == 0) continue;
                ByteBuffer bytes;
                if (carry.hasRemaining()) {
                    bytes = ByteBuffer.allocate(carry.remaining() + count);
                    bytes.put(carry);
                    bytes.put(buf, 0, count);
                    bytes.flip();
                } else {
                    bytes = ByteBuffer.wrap(buf, 0, count);
                }
                CharBuffer chars = CharBuffer.allocate(bytes.remaining() + 8);
                decoder.decode(bytes, chars, false);
                chars.flip();
                if (bytes.hasRemaining()) {
                    // 半个多字节字符：留给下一批，凑齐了再解
                    carry = ByteBuffer.allocate(bytes.remaining());
                    carry.put(bytes);
                    carry.flip();
                } else {
                    carry = ByteBuffer.allocate(0);
                }
                if (chars.hasRemaining() && listener != null) listener.onChunk(chars.toString());
            }
            CharBuffer tail = CharBuffer.allocate(16);
            decoder.decode(ByteBuffer.allocate(0), tail, true);
            decoder.flush(tail);
            tail.flip();
            if (tail.hasRemaining() && listener != null) listener.onChunk(tail.toString());
        } catch (Exception ignored) {
            // 流被关掉/进程被杀都会走到这里，属正常结束路径
        }
    }

    /** 直接设整段文本（回放历史日志用），同时把光标状态重置到末尾。 */
    public void setText(String text) {
        view.setText(text == null ? "" : text);
        Editable editable = editable();
        cursor = editable.length();
        lineStart = lastLineStart(editable, cursor);
    }

    public void clear() {
        setText("");
    }

    /** 写一段原始输出（可能是不完整的行，也可能是 \r 拼出来的进度帧）。 */
    public void write(String raw) {
        if (raw == null || raw.isEmpty()) return;
        String text = ANSI.matcher(raw).replaceAll("");
        if (text.isEmpty()) return;
        Editable editable = editable();
        syncState(editable);
        int index = 0;
        int length = text.length();
        while (index < length) {
            char c = text.charAt(index);
            if (c == '\r') {
                cursor = lineStart;          // 回行首（不删内容：后写的字符会覆盖）
                index++;
            } else if (c == '\n') {
                editable.append('\n');
                cursor = editable.length();
                lineStart = cursor;
                index++;
            } else if (c == '\b') {
                if (cursor > lineStart) cursor--;
                index++;
            } else {
                int end = index;
                while (end < length) {
                    char t = text.charAt(end);
                    if (t == '\r' || t == '\n' || t == '\b') break;
                    end++;
                }
                cursor = put(editable, text.substring(index, end), cursor);
                index = end;
            }
        }
        trim(editable);
    }

    /**
     * 把一段普通文本写到 {@code at} 处：行尾就追加，行中间就覆盖（真终端的覆盖语义）。
     * 覆盖范围只到本行末——绝不跨过换行符吞掉后面的内容。
     */
    private int put(Editable editable, String run, int at) {
        int length = editable.length();
        if (at >= length) {
            editable.append(run);
            return at + run.length();
        }
        int lineEnd = at;
        while (lineEnd < length && editable.charAt(lineEnd) != '\n') lineEnd++;
        int to = Math.min(lineEnd, at + run.length());
        editable.replace(at, to, run);
        return at + run.length();
    }

    /** TextView 默认不是 Editable，先切成 EDITABLE 才能做覆盖写。 */
    private Editable editable() {
        CharSequence text = view.getText();
        if (text == null) text = "";
        if (!(text instanceof Editable)) {
            view.setText(text, TextView.BufferType.EDITABLE);
        }
        CharSequence now = view.getText();
        return now instanceof Editable ? (Editable) now
                : new android.text.SpannableStringBuilder(now == null ? "" : now);
    }

    /** 文本被外部改过（或首次写入）时校准光标位置，避免写到越界下标。 */
    private void syncState(Editable editable) {
        int length = editable.length();
        if (cursor < 0 || cursor > length) cursor = length;
        if (lineStart < 0 || lineStart > cursor) lineStart = lastLineStart(editable, cursor);
    }

    private static int lastLineStart(Editable editable, int from) {
        int start = Math.min(from, editable.length());
        for (int i = start - 1; i >= 0; i--) {
            if (editable.charAt(i) == '\n') return i + 1;
        }
        return 0;
    }

    private void trim(Editable editable) {
        int length = editable.length();
        if (length <= MAX_CHARS) return;
        int cut = length - TRIM_TO;
        editable.delete(0, cut);
        cursor = Math.max(0, cursor - cut);
        lineStart = Math.max(0, lineStart - cut);
    }
}
