package com.easyshell.server.workflow.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.Map;

/**
 * 工作流节点（definition_json 的 nodes[] 元素，schema 冻结见设计 §4.1）。
 *
 * <p>服务端 schema 权威：未知字段一律拒绝（{@code ignoreUnknown = false}），
 * 前端镜像仅供编辑体验。</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = false)
public class WorkflowNode {

    /** ^[a-z][a-z0-9_]{0,63}$，图内稳定 ID */
    private String key;

    /** start / script-exec / transform / condition / ai-analyze / llm / classifier / code / approval / notify / wait / end */
    private String type;

    private String name;

    private Map<String, Object> position;

    /** 节点类型私有配置（各类型 schema 见设计 §11.2） */
    private Map<String, Object> config;

    /** 重试次数（默认 0，不含首次尝试） */
    private Integer retryMax;

    /** 重试基础间隔秒（默认 5），退避 = interval * 2^(attempt-1)，上限 300s，加 ±20% jitter */
    private Integer retryIntervalSec;

    /** 节点级超时秒（null = 用 engine.default-node-timeout-sec） */
    private Integer timeoutSec;

    /** 本节点自身失败的策略：abort(默认) / skip / partial / compensate(M2) */
    private String onFailure;

    /** 输出别名：= nodes.<key>.output，与 nodeKey 不得相同（发布期 ERROR） */
    private String outputVar;

    /** 值越界处置：reject(默认) / raw / truncate（§2.4.5） */
    private String onUnsafeValue;

    /** block(默认) / allow_medium / strict（§6.5.3） */
    private String riskPolicy;

    // ------------------------------------------------------------------
    // 便捷读取（非 bean 属性，不进 JSON）
    // ------------------------------------------------------------------

    public NodeType nodeType() {
        return NodeType.fromCode(type);
    }

    public int retryMaxOrDefault(int fallback) {
        return retryMax != null ? Math.max(0, retryMax) : fallback;
    }

    public int retryIntervalSecOrDefault(int fallback) {
        return retryIntervalSec != null && retryIntervalSec > 0 ? retryIntervalSec : fallback;
    }

    public int timeoutSecOrDefault(int fallback) {
        return timeoutSec != null && timeoutSec > 0 ? timeoutSec : fallback;
    }

    public String configString(String key) {
        return configString(key, null);
    }

    /** config 取值并转字符串：number/boolean 走 ValueType 的渲染规则，null → null */
    public String configString(String key, String fallback) {
        Object value = config == null ? null : config.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof String s) {
            return s;
        }
        return ValueType.renderToString(value, null);
    }

    public int configInt(String key, int fallback) {
        Object value = config == null ? null : config.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /** config 取 double；缺失/非法返回 null（便于区分"未配置"与 0） */
    public Double configDouble(String key) {
        Object value = config == null ? null : config.get(key);
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public boolean configBoolean(String key, boolean fallback) {
        Object value = config == null ? null : config.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s.trim());
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> configMap(String key) {
        Object value = config == null ? null : config.get(key);
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    public java.util.List<Object> configList(String key) {
        Object value = config == null ? null : config.get(key);
        return value instanceof java.util.List<?> l ? (java.util.List<Object>) l : null;
    }
}
