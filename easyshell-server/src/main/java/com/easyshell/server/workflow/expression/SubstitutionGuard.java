package com.easyshell.server.workflow.expression;

import com.easyshell.server.workflow.model.ValueRange;

import java.util.List;
import java.util.regex.Pattern;

/**
 * L3 segment 级禁替换（§2.4.4）：派发前最后一道值级防线。
 *
 * <p><b>区间裁决（§2.4.7）</b>：L3 只检查由参数值贡献的字符区间，绝不检查全脚本——
 * 否则用户脚本里合法的 {@code $(hostname)} 会被一并杀掉，过度拦截会把产品变成废纸。
 * 区间由 {@code RenderResult.valueRanges} 提供，且只覆盖替换值本身（N2 修正）。</p>
 *
 * <p><b>对 raw 模式这是唯一有效防线</b>：{@code x; cat /etc/shadow} 作为 raw 值，
 * 风险引擎给 LOW（{@code cat} 在 LOW 白名单、{@code ;} 切分后也是 LOW），
 * 是 L3 把它拦下来的那一层。</p>
 */
public final class SubstitutionGuard {

    private static final Pattern SUBST = Pattern.compile("\\$\\(|`|\\$\\{");

    private static final Pattern RAW_FORBIDDEN = Pattern.compile("[;|&<>()'\"\\\\#~!$]");

    private SubstitutionGuard() {
    }

    /**
     * @return null = 放行；非 null = 拒绝码（{@code SUBSTITUTION_REJECTED} / {@code PARAM_RAW_UNSAFE}）
     */
    public static String rejectSubstitution(String rendered, List<ValueRange> ranges) {
        if (rendered == null || ranges == null) {
            return null;
        }
        for (ValueRange r : ranges) {
            if (r.start() < 0 || r.end() > rendered.length() || r.start() >= r.end()) {
                return "SUBSTITUTION_REJECTED";   // 区间非法：fail-closed
            }
            String seg = rendered.substring(r.start(), r.end());
            if (SUBST.matcher(seg).find()) {
                return "SUBSTITUTION_REJECTED";   // 两种模式都禁命令替换
            }
            if (r.raw()) {
                if (RAW_FORBIDDEN.matcher(seg).find()) {
                    return "PARAM_RAW_UNSAFE";    // raw 禁命令结构字符
                }
                if (seg.startsWith("-")) {
                    return "PARAM_RAW_UNSAFE";    // raw 禁标志注入
                }
            }
        }
        return null;   // null = 放行
    }
}
