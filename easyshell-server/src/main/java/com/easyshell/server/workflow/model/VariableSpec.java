package com.easyshell.server.workflow.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 声明式变量规格（definition.variables，触发入参的校验依据，§2.7 类型系统）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = false)
public class VariableSpec {

    /** string / number / boolean / json（M2 才有 file / host-list / secret） */
    private String type;

    /** 展示名（可选；UI 用它作为触发表单标签，缺省回退到 name） */
    private String label;

    private Boolean required;

    /** definition_json 里的键名是 default（JsonProperty 对齐，避免被当成未知字段拒绝） */
    @JsonProperty("default")
    private Object defaultValue;

    /**
     * 字符串枚举候选（可选，仅 type=string）。
     *
     * <p>只影响触发入参校验与 UI 选择器；缺省时保持旧的自由文本行为。</p>
     */
    private java.util.List<String> options;

    private String description;
}
