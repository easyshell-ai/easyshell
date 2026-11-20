package com.easyshell.server.workflow.model;

/**
 * 参数值在渲染后脚本中的字符区间（L3 只检查这些区间，不动用户脚本自带的
 * {@code $(hostname)} 等——过度拦截会把产品变成废纸，§2.4.7）。
 *
 * @param start 区间起始（含）
 * @param end   区间结束（不含）
 * @param raw   该参数是否以裸值模式渲染（{@code {{NAME:raw}}}）
 */
public record ValueRange(int start, int end, boolean raw) {
}
