package com.easyshell.server.workflow.graph;

import java.util.ArrayList;
import java.util.List;

/**
 * 校验问题（发布期 issues 面板的条目，§11.1）。
 *
 * @param severity ERROR = 阻塞发布 / 运行期拒绝执行；WARNING = 可发布，UI 黄标
 * @param code     稳定错误码（如 GRAPH_CYCLE / NODE_KEY_DUPLICATE / EDGE_TYPE_INCOMPATIBLE）
 * @param message  人读信息（不含脚本原文与参数值）
 * @param nodeKey  关联节点（definition 级问题为 null）
 */
public record ValidationIssue(Severity severity, String code, String message, String nodeKey) {

    public enum Severity {
        ERROR, WARNING
    }

    public static ValidationIssue error(String code, String message, String nodeKey) {
        return new ValidationIssue(Severity.ERROR, code, message, nodeKey);
    }

    public static ValidationIssue warning(String code, String message, String nodeKey) {
        return new ValidationIssue(Severity.WARNING, code, message, nodeKey);
    }

    /** 收集器（{@code NodeExecutor.validate} 与 DagValidator 共用） */
    public static class Sink {
        private final List<ValidationIssue> issues = new ArrayList<>();

        public void error(String code, String message, String nodeKey) {
            issues.add(ValidationIssue.error(code, message, nodeKey));
        }

        public void warning(String code, String message, String nodeKey) {
            issues.add(ValidationIssue.warning(code, message, nodeKey));
        }

        public void add(ValidationIssue issue) {
            issues.add(issue);
        }

        public List<ValidationIssue> issues() {
            return issues;
        }

        public boolean hasError() {
            return issues.stream().anyMatch(i -> i.severity() == Severity.ERROR);
        }
    }
}
