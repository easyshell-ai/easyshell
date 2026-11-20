package com.easyshell.server.workflow.model;

import java.util.Arrays;

/**
 * 失败策略（§2.8 三策略）。多上游策略不一致时取最严格：ABORT &gt; SKIP &gt; PARTIAL。
 */
public enum FailurePolicy {

    /** fail-fast：本节点及全部未开始节点取消，run = failed */
    ABORT("abort", 3),

    /** skip（默认）：本节点标记 skipped 不执行，其下游按各自策略判断 */
    SKIP("skip", 2),

    /** continue-with-partial：上游失败也执行本节点，失败上游的 output 视为不存在 */
    PARTIAL("partial", 1);

    private final String code;
    private final int strictness;

    FailurePolicy(String code, int strictness) {
        this.code = code;
        this.strictness = strictness;
    }

    public String code() {
        return code;
    }

    public int strictness() {
        return strictness;
    }

    /** definition_json 里的字符串 → 枚举；未知值返回 null（调用方 fail-closed 取 ABORT） */
    public static FailurePolicy fromCode(String code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values()).filter(p -> p.code.equals(code)).findFirst().orElse(null);
    }

    /** 多个策略里取最严格的一个（§2.8） */
    public static FailurePolicy strictest(FailurePolicy a, FailurePolicy b) {
        FailurePolicy left = a == null ? SKIP : a;
        FailurePolicy right = b == null ? SKIP : b;
        return left.strictness >= right.strictness ? left : right;
    }
}
