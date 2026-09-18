package com.fongmi.android.tv.player.engine;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 本地中转（MpvHlsProxy）启用范围策略。
 *
 * <p>壳内本地中转只服务两个站点：哔哩so 的 DASH 清单要转成 HLS、华人so 的 AES-128
 * 加密清单要服务端解密。但原来的判定（{@code shouldProxyHls}）是「所有 HLS 一律进中转」，
 * 普通 HLS 流被无差别接管后，IJK 起播超时、MPV 播放失败（同一个流 Exo 直连正常）。
 *
 * <p>这里把中转范围按站点收口：只有 {@link #DIRECT_ONLY} 里显式列出的站点被排除，
 * 其余站点返回 true，也就是判定结果与改动前完全一致（其他站点行为不变）。
 */
public final class RelayScopePolicy {

    /** 明确不走本地中转、普通 HLS 原样交给播放器的站点。 */
    private static final Set<String> DIRECT_ONLY = new HashSet<>(Arrays.asList("油管so"));

    private RelayScopePolicy() {
    }

    /**
     * 调用点必须把原来的 {@code shouldProxyHls(...)} 作为前置条件，本方法只做站点收口。
     *
     * @param sourceKey 当前播放站点 key（{@code mediaItem.mediaId}）
     * @return true = 允许进本地中转（除 DIRECT_ONLY 外的所有站点，保持原行为）
     */
    public static boolean allowsRelay(String sourceKey) {
        return !DIRECT_ONLY.contains(normalize(sourceKey));
    }

    private static String normalize(String sourceKey) {
        return sourceKey == null ? "" : sourceKey.trim().toLowerCase(Locale.US);
    }
}
