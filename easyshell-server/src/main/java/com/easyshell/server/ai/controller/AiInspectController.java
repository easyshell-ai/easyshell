package com.easyshell.server.ai.controller;

import com.easyshell.server.ai.model.entity.AiInspectReport;
import com.easyshell.server.ai.model.vo.AiInspectReportVO;
import com.easyshell.server.ai.repository.AiInspectReportRepository;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/ai/inspect")
@RequiredArgsConstructor
public class AiInspectController {

    private final AiInspectReportRepository reportRepository;

    @GetMapping("/reports")
    public R<Map<String, Object>> listReports(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<AiInspectReport> reports = reportRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size));
        return R.ok(Map.of(
                "content", reports.getContent().stream().map(this::toVO).toList(),
                "totalElements", reports.getTotalElements(),
                "totalPages", reports.getTotalPages(),
                "number", reports.getNumber(),
                "size", reports.getSize()
        ));
    }

    @GetMapping("/reports/{id}")
    public R<AiInspectReportVO> getReport(@PathVariable Long id) {
        AiInspectReport report = reportRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "巡检报告不存在"));
        return R.ok(toVO(report));
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
