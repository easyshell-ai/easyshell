package com.easyshell.server.workflow.model;

/**
 * L2 渲染结果：渲染后全文 + 参数值贡献的字符区间（§2.4.4 N2 修正：区间只覆盖替换值本身）。
 *
 * @param rendered    渲染后的脚本全文
 * @param valueRanges 每个 {{NAME}} 替换值在 rendered 中的字符区间（按出现顺序）
 */
public record RenderResult(String rendered, java.util.List<ValueRange> valueRanges) {

    public RenderResult {
        valueRanges = valueRanges == null ? java.util.List.of() : java.util.List.copyOf(valueRanges);
    }
}
