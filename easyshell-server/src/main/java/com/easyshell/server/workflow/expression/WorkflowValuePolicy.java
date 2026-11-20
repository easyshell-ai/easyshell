package com.easyshell.server.workflow.expression;

import java.util.regex.Pattern;

/**
 * L1 值策略（§2.4.4）：渲染前逐值检查，越界即拒绝（节点失败，不派发）。
 *
 * <p><b>raw 的字符集为什么放在 L3 而不是这里（v3 关键设计）</b>：v2 把 raw 的字符集
 * 检查放在 L1，导致 L3 对 raw 永远不可达——一个"设计了但跑不到"的防线等于没有防线。
 * v3 把 raw 的字符集执法下沉到 L3，于是 L1 对 raw 只做"空串/换行/控制字符"的基础检查，
 * L3 对 raw 做完整的结构字符检查，两层各自可达、各自有测试。实测证明 L3 对 raw 是
 * load-bearing 的：{@code echo {{MSG:raw}}} + {@code MSG=x; cat /etc/shadow} 渲染成
 * {@code echo x; cat /etc/shadow}，风险引擎判 LOW——没有 L3，这条值就真的会把 shadow 读出来。</p>
 */
public final class WorkflowValuePolicy {

    /** 值策略字符上限（§2.4.8 N7-j：与 8192B 通道阈值是两个概念） */
    public static final int MAX_CHARS = 256;

    private static final Pattern SAFE_VALUE =
            Pattern.compile("^[\\p{L}\\p{N}\\p{M}._:@%/=,+'\\- ]{1,256}$");

    private WorkflowValuePolicy() {
    }

    public enum ValueDisposition {
        ACCEPT, REJECT
    }

    public static ValueDisposition check(String name, String value, boolean raw) {
        if (value == null || value.isEmpty()) {
            return ValueDisposition.REJECT;   // 空串永拒（fail-closed）
        }
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            return ValueDisposition.REJECT;   // 换行永拒
        }
        for (int i = 0; i < value.length(); i++) {                               // 控制字符永拒
            if (value.charAt(i) < 0x20 || value.charAt(i) == 0x7F) {
                return ValueDisposition.REJECT;
            }
        }
        if (raw) {
            return ValueDisposition.ACCEPT;          // raw 的字符集由 L3 执法（保证 L3 可达）
        }
        return SAFE_VALUE.matcher(value).matches() ? ValueDisposition.ACCEPT : ValueDisposition.REJECT;
    }

    /** 首个越界字符（用于错误信息指出参数名与越界位置）；全部合规返回 -1 */
    public static int firstOffendingChar(String value) {
        if (value == null || value.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F || c == '\n' || c == '\r' || !SAFE_VALUE.matcher(String.valueOf(c)).matches()) {
                return i;
            }
        }
        return -1;
    }
}
