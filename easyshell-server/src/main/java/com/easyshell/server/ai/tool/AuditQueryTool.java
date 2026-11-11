package com.easyshell.server.ai.tool;

import com.easyshell.server.model.entity.AuditLog;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.repository.UserRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class AuditQueryTool {

    private final AuditLogService auditLogService;
    private final UserRepository users;
    private final Long currentUserId;

    @Autowired
    public AuditQueryTool(AuditLogService auditLogService, UserRepository users) {
        this(auditLogService, users, null);
    }

    private AuditQueryTool(AuditLogService auditLogService, UserRepository users, Long currentUserId) {
        this.auditLogService = auditLogService;
        this.users = users;
        this.currentUserId = currentUserId;
    }

    public AuditQueryTool forUser(Long userId, String sourceIp) {
        return new AuditQueryTool(auditLogService, users, userId);
    }

    @Tool(description = "查询操作审计日志，可按操作类型过滤。操作类型包括: LOGIN, SCRIPT_EXECUTE, AI_CHAT, SCHEDULED_TASK_EXECUTE 等")
    public String queryAuditLogs(
            @ToolParam(description = "操作类型过滤，为空则查询全部。例如: LOGIN, SCRIPT_EXECUTE, AI_CHAT") String action,
            @ToolParam(description = "返回条数，默认 20") int limit) {
        try {
            if (currentUserId == null || currentUserId <= 0 || users.findById(currentUserId)
                    .filter(user -> Integer.valueOf(1).equals(user.getStatus()) && "super_admin".equals(user.getRole()))
                    .isEmpty()) return "错误：无权查询审计日志。";
        } catch (RuntimeException ex) {
            return "错误：无权查询审计日志。";
        }
        if (limit <= 0) limit = 20;
        if (limit > 100) limit = 100;

        PageRequest pageable = PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<AuditLog> page;
        if (action != null && !action.isBlank()) {
            page = auditLogService.findByAction(action.trim(), pageable);
        } else {
            page = auditLogService.findAll(pageable);
        }

        if (page.isEmpty()) {
            return action != null && !action.isBlank()
                    ? "未找到操作类型为 \"" + action + "\" 的审计日志"
                    : "暂无审计日志记录";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("审计日志 (共 %d 条，显示 %d 条):\n\n",
                page.getTotalElements(), page.getContent().size()));

        for (AuditLog log : page.getContent()) {
            sb.append(String.format("[%s] %s | 用户: %s | 资源: %s/%s | 结果: %s\n",
                    log.getCreatedAt(),
                    log.getAction(),
                    log.getUsername() != null ? log.getUsername() : "ID:" + log.getUserId(),
                    log.getResourceType() != null ? log.getResourceType() : "-",
                    log.getResourceId() != null ? log.getResourceId() : "-",
                    log.getResult()));
            if (log.getDetail() != null && !log.getDetail().isBlank()) {
                String detail = log.getDetail().length() > 100
                        ? log.getDetail().substring(0, 100) + "..."
                        : log.getDetail();
                sb.append("  详情: ").append(detail).append("\n");
            }
        }
        return sb.toString();
    }
}
