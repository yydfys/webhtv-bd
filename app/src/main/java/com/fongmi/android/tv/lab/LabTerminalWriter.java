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
 * 终端文本处理器：**字节级读取** + **终端语义落屏**。
 *
 * <p><b>为什么不能用 readLine()</b>：lab 的命令跑在管道里（不是 PTY），而很多程序只在
 * 终端里才画进度条。以 CloudflareSpeedTest 用的 pb 进度条为例，管道模式下它
 * <b>既不写 \r 也不写 \n</b>——实测一帧帧纯空格拼接连着吐（CR 字节数 = 0）。
 * readLine() 找不到行结束符就只能一直攒着，直到后面某个换行才一次性吐出来，
 * 用户看到的就是"测速跑完才刷出一大坨"。所以这里改成收到多少发多少。
 *
 * <p><b>落屏语义</b>（对齐真 PTY 的观感）：
 * <ol>
 *   <li>剥离 ANSI 转义序列（颜色/光标控制），否则文本框里会显示 [32m 这类乱码；</li>
 *   <li>{@code \r} = 回行首 → 删掉当前行已写内容再继续写，进度条原地刷新而不是每帧一行；</li>
 *   <li>{@code \b} = 退格 → 删一个字符；</li>
 *   <li>末尾体积保护，防止长跑命令把 TextView 撑爆。</li>
 * </ol>
 */
public final class LabTerminalWriter {

    /** 超过这个字符数就砍掉最前面一段，只保留最近的输出。 */
    private static final int MAX_CHARS = 300000;
    private static final int TRIM_TO = 200000;

    /**
     * OSC / CSI / 双字符转义 / 其余控制字符（保留 \t \n \r）。
     * 注意别写成 {@code [@-Z\\-_]} 这种范围简写——末尾的 {@code \\-} 会被当成范围，
     * 把 {@code ]} 也一起剥掉。
     */
    private static final Pattern ANSI = Pattern.compile(
            "\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)"
                    + "|\u001B\\[[0-9;?]*[ -/]*[@-~]"
                    + "|\u001B[@-Z]"
                    + "|\u001B\\\\"
                    + "|\u001B_"
                    + "|[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]");

    /** 字节级泵的回调：每读到一段完整字符就回调一次（可能不是完整的一行）。 */
    public interface ChunkListener {
        void onChunk(String text);
    }

    private final TextView view;

    public LabTerminalWriter(TextView view) {
        this.view = view;
    }

    public TextView view() {
        return view;
    }

    public void clear() {
        view.setText("");
    }

    /**
     * 字节级读取：收到多少发多少，不按行切。
     *
     * <p>用流式 {@link CharsetDecoder} 做增量 UTF-8 解码——一个中文字被拆在两次 read
     * 之间时，半个字符的字节会留在解码器/缓冲里等下一批，不会解出乱码。
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

    /** 写一段原始输出（可能是不完整的行，也可能是 \r 拼出来的进度帧）。 */
    public void write(String raw) {
        if (raw == null || raw.isEmpty()) return;
        String text = ANSI.matcher(raw).replaceAll("");
        if (text.isEmpty()) return;
        Editable editable = editable();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\r' && c != '\b') continue;
            if (i > start) editable.append(text.subSequence(start, i));
            if (c == '\r') deleteCurrentLine(editable);
            else deleteLastChar(editable);
            start = i + 1;
        }
        if (start < text.length()) editable.append(text.subSequence(start, text.length()));
        trim(editable);
    }

    /** TextView 默认不是 Editable，先切成 EDITABLE 才能做"回行首覆盖"。 */
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

    /** \r：回行首 = 删掉最后一个换行之后的全部内容（即当前行）。 */
    private static void deleteCurrentLine(Editable editable) {
        int length = editable.length();
        int lineStart = 0;
        for (int i = length - 1; i >= 0; i--) {
            if (editable.charAt(i) == '\n') {
                lineStart = i + 1;
                break;
            }
        }
        if (length > lineStart) editable.delete(lineStart, length);
    }

    private static void deleteLastChar(Editable editable) {
        int length = editable.length();
        if (length > 0) editable.delete(length - 1, length);
    }

    private static void trim(Editable editable) {
        int length = editable.length();
        if (length > MAX_CHARS) editable.delete(0, length - TRIM_TO);
    }
}
