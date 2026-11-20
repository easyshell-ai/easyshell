package com.easyshell.server.ai.controller;

import com.easyshell.server.ai.model.entity.AiInspectReport;
import com.easyshell.server.ai.model.vo.AiInspectReportVO;
import com.easyshell.server.ai.service.InspectReportAccessService;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.common.result.R;
import com.easyshell.server.security.AuditUsers;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import org.springframework.data.domain.Page;

@RestController
@RequestMapping("/api/v1/ai/inspect")
@RequiredArgsConstructor
public class AiInspectController {

    private final InspectReportAccessService access;

    @GetMapping("/reports")
    @PreAuthorize("hasAnyRole('VIEWER','OPERATOR','ADMIN','SUPER_ADMIN')")
    public R<Map<String, Object>> listReports(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, Authentication auth) {
        Long userId = requireUserId(auth);
        if (page < 0 || size < 1 || size > 100) throw new BusinessException(400, "Invalid pagination");
        Page<AiInspectReportVO> result = access.page(userId, isAdmin(auth), page, size).map(this::toVO);
        return R.ok(Map.of("content", result.getContent(), "totalElements", result.getTotalElements(),
                "totalPages", result.getTotalPages(), "number", result.getNumber(), "size", result.getSize()));
    }

    @GetMapping("/reports/{id}")
    @PreAuthorize("hasAnyRole('VIEWER','OPERATOR','ADMIN','SUPER_ADMIN')")
    public R<AiInspectReportVO> getReport(@PathVariable Long id, Authentication auth) {
        Long userId = requireUserId(auth);
        AiInspectReport report = access.detail(id, userId, isAdmin(auth));
        return R.ok(toVO(report));
    }

    private Long requireUserId(Authentication auth) {
        Long userId = AuditUsers.id(auth);
        if (userId == null || !auth.isAuthenticated()) {
            throw new BusinessException(403, "Access denied");
        }
        return userId;
    }

    private boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())
                || "ROLE_SUPER_ADMIN".equals(a.getAuthority()));
    }

    private AiInspectReportVO toVO(AiInspectReport report) {
        return AiInspectReportVO.builder()
                .id(report.getId())
                .scheduledTaskId(report.getScheduledTaskId())
                .taskType(report.getTaskType())
                .taskName(report.getTaskName())
                .targetSummary(report.getTargetSummary())
                .scriptOutput(report.getScriptOutput())
                .aiAnalysis(report.getAiAnalysis())
                .status(report.getStatus())
                .createdBy(report.getCreatedBy())
                .createdAt(report.getCreatedAt())
                .build();
    }
}
