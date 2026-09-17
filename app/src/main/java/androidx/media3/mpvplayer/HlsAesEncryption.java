package androidx.media3.mpvplayer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * HLS AES-128（{@code #EXT-X-KEY:METHOD=AES-128}）服务端解密支持。
 *
 * <p>IJK 内核的 ffmpeg 拿到 AES-128 加密清单时会直接抛
 * {@code AVERROR_INVALIDDATA}（native extra=-1094995529、UI 上表现为
 * {@code ERROR_CODE_PARSING_CONTAINER_MALFORMED}），加密源在 IJK 里必然黑屏。
 * 所以由本地 HLS 代理在服务端完成解密：清单里的 {@code #EXT-X-KEY} 行剥掉、
 * 分片以明文 TS 下发，播放器侧完全不需要认识加密。
 *
 * <p>本类刻意不引用任何 Android API，只做纯计算（解析清单、AES-CBC 解密、
 * TS 同步字校验），便于脱离设备用 ecj + 真实样本验证。
 */
final class HlsAesEncryption {

    private static final String AES_128 = "AES-128";
    private static final Pattern KEY_TAG = Pattern.compile("#EXT-X-KEY:([^\\r\\n]*)");
    private static final Pattern URI_ATTRIBUTE = Pattern.compile("URI=\"([^\"]*)\"");
    private static final Pattern METHOD_ATTRIBUTE = Pattern.compile("METHOD=([A-Za-z0-9._-]+)");
    private static final Pattern IV_ATTRIBUTE = Pattern.compile("IV=0[xX]([0-9A-Fa-f]{32})");
    private static final Pattern MEDIA_SEQUENCE = Pattern.compile("#EXT-X-MEDIA-SEQUENCE:\\s*(\\d+)");
    private static final int BLOCK_BYTES = 16;

    private HlsAesEncryption() {
    }

    /** 清单里解析出的加密参数。 */
    static final class KeyInfo {

        final String keyUri;
        final String method;
        /** 清单显式声明的 IV（{@code IV=0x...}），没有则为 null。 */
        final byte[] declaredIv;
        final long mediaSequence;
        private final boolean singleKey;

        KeyInfo(String keyUri, String method, byte[] declaredIv, long mediaSequence, boolean singleKey) {
            this.keyUri = keyUri;
            this.method = method;
            this.declaredIv = declaredIv;
            this.mediaSequence = mediaSequence;
            this.singleKey = singleKey;
        }

        /** 是否属于本类能处理的「单密钥 AES-128」清单。 */
        boolean usable() {
            return singleKey
                    && AES_128.equalsIgnoreCase(method)
                    && keyUri != null && !keyUri.isEmpty();
        }

        boolean hasDeclaredIv() {
            return declaredIv != null && declaredIv.length == BLOCK_BYTES;
        }
    }

    /**
     * 解析清单里的 {@code #EXT-X-KEY} 与 {@code #EXT-X-MEDIA-SEQUENCE}。
     *
     * @return 没有任何 KEY 标签时返回 null；密钥轮换（多个不同密钥/IV）时返回
     *         {@link KeyInfo#usable()} 为 false 的对象。
     */
    static KeyInfo parse(String playlistText) {
        if (playlistText == null || playlistText.isEmpty()) return null;
        Matcher matcher = KEY_TAG.matcher(playlistText);
        String method = null;
        byte[] iv = null;
        Set<String> uris = new LinkedHashSet<>();
        Set<String> ivs = new LinkedHashSet<>();
        int tags = 0;
        while (matcher.find()) {
            tags++;
            String attributes = matcher.group(1);
            Matcher methodMatcher = METHOD_ATTRIBUTE.matcher(attributes);
            if (methodMatcher.find()) method = methodMatcher.group(1);
            Matcher uriMatcher = URI_ATTRIBUTE.matcher(attributes);
            if (uriMatcher.find()) uris.add(uriMatcher.group(1).trim());
            Matcher ivMatcher = IV_ATTRIBUTE.matcher(attributes);
            if (ivMatcher.find()) {
                ivs.add(ivMatcher.group(1).toLowerCase(java.util.Locale.US));
            }
        }
        if (tags == 0) return null;
        if (ivs.size() == 1) iv = hexToBytes(ivs.iterator().next());
        long mediaSequence = 0;
        Matcher sequenceMatcher = MEDIA_SEQUENCE.matcher(playlistText);
        if (sequenceMatcher.find()) {
            try {
                mediaSequence = Long.parseLong(sequenceMatcher.group(1));
            } catch (NumberFormatException ignored) {
                mediaSequence = 0;
            }
        }
        boolean singleKey = uris.size() <= 1 && ivs.size() <= 1;
        return new KeyInfo(uris.isEmpty() ? null : uris.iterator().next(), method, iv, mediaSequence, singleKey);
    }

    /** 序号驱动的 IV：HLS AES-128 在未声明 IV 时用媒体序号做 16 字节大端 IV。 */
    static byte[] ivForSequence(long sequence) {
        byte[] iv = new byte[BLOCK_BYTES];
        long value = sequence;
        for (int i = BLOCK_BYTES - 1; i >= 0; i--) {
            iv[i] = (byte) (value & 0xFF);
            value >>= 8;
        }
        return iv;
    }

    /**
     * AES-128-CBC 解密（去除 PKCS7 补齐）。
     *
     * @return 明文；输入不足一个 AES 块或解密过程出错时返回 null。
     */
    static byte[] decrypt(byte[] data, byte[] key, byte[] iv) {
        if (data == null || key == null || key.length != BLOCK_BYTES || iv == null || iv.length != BLOCK_BYTES) {
            return null;
        }
        int blockLength = data.length - (data.length % BLOCK_BYTES);
        if (blockLength <= 0) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            byte[] plain = cipher.doFinal(data, 0, blockLength);
            int length = unpad(plain, blockLength);
            int trailing = data.length - blockLength;
            if (trailing <= 0) return length == plain.length ? plain : Arrays.copyOf(plain, length);
            byte[] result = Arrays.copyOf(plain, length + trailing);
            System.arraycopy(data, blockLength, result, length, trailing);
            return result;
        } catch (Throwable e) {
            return null;
        }
    }

    /** PKCS7 去补齐：只在补齐字节确实合法时才裁掉，避免误砍真实数据。 */
    private static int unpad(byte[] plain, int length) {
        if (length <= 0) return length;
        int padding = plain[length - 1] & 0xFF;
        if (padding < 1 || padding > BLOCK_BYTES || padding > length) return length;
        for (int i = length - padding; i < length; i++) {
            if ((plain[i] & 0xFF) != padding) return length;
        }
        return length - padding;
    }

    /** 明文是否为 MPEG-TS（0x47 同步字按 188 字节周期对齐）。 */
    static boolean looksLikeTransportStream(byte[] data) {
        if (data == null || data.length < BLOCK_BYTES * 24) return false;
        return data[0] == 0x47 && data[188] == 0x47 && data[376] == 0x47;
    }

    /** 剥掉 AES-128 的 {@code #EXT-X-KEY} 行（其它加密方式的标签原样保留）。 */
    static String stripKeyTags(String playlistText) {
        if (playlistText == null || playlistText.isEmpty()) return playlistText;
        boolean changed = false;
        StringBuilder builder = new StringBuilder(playlistText.length());
        String[] lines = playlistText.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.startsWith("#EXT-X-KEY:") && isAes128Tag(trimmed)) {
                changed = true;
                continue;
            }
            builder.append(line);
            if (i < lines.length - 1) builder.append('\n');
        }
        return changed ? builder.toString() : playlistText;
    }

    private static boolean isAes128Tag(String line) {
        Matcher matcher = METHOD_ATTRIBUTE.matcher(line);
        return matcher.find() && AES_128.equalsIgnoreCase(matcher.group(1));
    }

    /** 候选 IV 列表：先清单声明的 IV，再序号驱动的 IV（含邻近序号容错）。 */
    static List<byte[]> candidateIvs(KeyInfo info, long sequence) {
        List<byte[]> candidates = new ArrayList<>(4);
        if (info != null && info.hasDeclaredIv()) candidates.add(info.declaredIv);
        candidates.add(ivForSequence(sequence));
        if (sequence > 0) candidates.add(ivForSequence(sequence - 1));
        candidates.add(ivForSequence(sequence + 1));
        return candidates;
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.isEmpty() || hex.length() % 2 != 0) return null;
        int length = hex.length() / 2;
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            int high = Character.digit(hex.charAt(i * 2), 16);
            int low = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) return null;
            out[i] = (byte) ((high << 4) | low);
        }
        return out;
    }
}
