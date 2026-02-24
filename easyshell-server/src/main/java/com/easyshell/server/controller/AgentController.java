package com.easyshell.server.controller;

import com.easyshell.server.common.result.R;
import com.easyshell.server.model.dto.AgentHeartbeatRequest;
import com.easyshell.server.model.dto.AgentRegisterRequest;
import com.easyshell.server.model.dto.JobResultRequest;
import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.model.entity.Job;
import com.easyshell.server.repository.JobRepository;
import com.easyshell.server.service.AgentService;
import com.easyshell.server.service.SystemConfigService;
import com.easyshell.server.service.TaskService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;
    private final TaskService taskService;
    private final JobRepository jobRepository;
    private final SystemConfigService systemConfigService;

    @PostMapping("/register")
    public R<Agent> register(@Valid @RequestBody AgentRegisterRequest request,
                             HttpServletRequest httpRequest) {
        String clientIp = extractClientIp(httpRequest);
        return R.ok(agentService.register(request, clientIp));
    }

    @PostMapping("/heartbeat")
    public R<Void> heartbeat(@Valid @RequestBody AgentHeartbeatRequest request,
                             HttpServletRequest httpRequest) {
        String clientIp = extractClientIp(httpRequest);
        agentService.heartbeat(request, clientIp);
        return R.ok();
    }

    @GetMapping("/jobs/pending/{agentId}")
    public R<List<Job>> getPendingJobs(@PathVariable String agentId) {
        return R.ok(jobRepository.findByAgentIdAndStatus(agentId, 0));
    }

    @PostMapping("/jobs/result")
    public R<Void> reportJobResult(@RequestBody JobResultRequest request) {
        taskService.reportJobResult(request);
        return R.ok();
    }

    @PostMapping("/jobs/log")
    public R<Void> reportJobLog(@RequestBody java.util.Map<String, String> body) {
        String jobId = body.get("jobId");
        String log = body.get("log");
        if (jobId != null && log != null) {
            taskService.appendJobLog(jobId, log);
        }
        return R.ok();
    }

    @GetMapping("/config")
    public R<Map<String, String>> getAgentConfig() {
        return R.ok(systemConfigService.getAgentConfig());
    }

    private String extractClientIp(HttpServletRequest request) {
        String[] headers = {
            "X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP"
        };
        for (String header : headers) {
            String ip = request.getHeader(header);
            if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                int commaIdx = ip.indexOf(',');
                return commaIdx > 0 ? ip.substring(0, commaIdx).trim() : ip.trim();
            }
        }
        String remoteAddr = request.getRemoteAddr();
        if ("0:0:0:0:0:0:0:1".equals(remoteAddr)) {
            return "127.0.0.1";
        }
        return remoteAddr;
    }
}
