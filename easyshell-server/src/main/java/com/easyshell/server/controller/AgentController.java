package com.easyshell.server.controller;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.common.result.R;
import com.easyshell.server.model.dto.AgentHeartbeatRequest;
import com.easyshell.server.model.dto.AgentRegisterRequest;
import com.easyshell.server.model.dto.JobResultRequest;
import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.model.entity.Job;
import com.easyshell.server.model.vo.AgentRegisterVO;
import com.easyshell.server.repository.JobRepository;
import com.easyshell.server.repository.TaskRepository;
import com.easyshell.server.repository.TaskTargetSnapshotRepository;
import com.easyshell.server.security.JwtUtils;
import com.easyshell.server.security.JwtAuthenticationFilter;
import com.easyshell.server.service.AgentService;
import com.easyshell.server.service.SystemConfigService;
import com.easyshell.server.service.TaskService;
import com.easyshell.server.service.HostAclService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * Agent-facing API.
 *
 * <p>Every endpoint requires agent credentials: a signed agent token whose subject is the
 * agentId being acted on. Registration additionally accepts an administrator token or the
 * configured registration secret so that a brand new agent can bootstrap its credential.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
public class AgentController {

    private static final String REGISTER_SECRET_HEADER = "X-Agent-Register-Secret";

    private final AgentService agentService;
    private final TaskService taskService;
    private final JobRepository jobRepository;
    private final SystemConfigService systemConfigService;
    private final JwtUtils jwtUtils;
    private final TaskRepository taskRepository;
    private final TaskTargetSnapshotRepository taskTargetSnapshotRepository;
    private final HostAclService hostAclService;

    @Value("${easyshell.agent.register-secret:}")
    private String registerSecret;

    @Value("${easyshell.agent.trust-proxy-headers:false}")
    private boolean trustProxyHeaders;

    @PostMapping("/register")
    @Transactional
    public R<AgentRegisterVO> register(@Valid @RequestBody AgentRegisterRequest request,
                                       Authentication auth,
                                       HttpServletRequest httpRequest) {
        if (Boolean.TRUE.equals(httpRequest.getAttribute(JwtAuthenticationFilter.ATTR_BOUND_AGENT_TOKEN))) {
            throw new BusinessException(403, "Bound agent credentials cannot register");
        }
        // An invalid or expired bearer must not be exchanged via the bootstrap secret either.
        String authorization = httpRequest.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")
                && (auth == null || !auth.isAuthenticated()
                    || auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)) {
            throw new BusinessException(401, "Invalid bearer credential");
        }
        String principalAgentId = authenticatedAgentId(auth);
        boolean isAgentCredential = principalAgentId != null;

        if (!isAgentCredential && !isAdministrator(auth) && !hasValidRegisterSecret(httpRequest)) {
            throw new BusinessException(401, "Agent credentials required");
        }
        if (isAgentCredential && !principalAgentId.equals(request.getAgentId())) {
            throw new BusinessException(403, "Agent token does not match agentId");
        }

        String clientIp = extractClientIp(httpRequest);
        Agent agent = agentService.register(request, clientIp);
        String agentToken = jwtUtils.generateAgentToken(request.getAgentId());
        log.info("Issued agent token for {}", request.getAgentId());
        return R.ok(new AgentRegisterVO(agent, agentToken));
    }

    @PostMapping("/heartbeat")
    public R<Void> heartbeat(@Valid @RequestBody AgentHeartbeatRequest request,
                             Authentication auth,
                             HttpServletRequest httpRequest) {
        String agentId = requireAgent(auth);
        if (!agentId.equals(request.getAgentId())) {
            throw new BusinessException(403, "Agent token does not match agentId");
        }
        agentService.heartbeat(request, extractClientIp(httpRequest));
        return R.ok();
    }

    @GetMapping("/jobs/pending/{agentId}")
    public R<List<Job>> getPendingJobs(@PathVariable String agentId, Authentication auth) {
        requireMatchingAgent(auth, agentId);
        return R.ok(jobRepository.findByAgentIdAndStatus(agentId, 0).stream().filter(job -> {
            try {
                var task = taskRepository.findById(job.getTaskId()).orElse(null);
                if (task == null || task.getCreatedBy() == null || task.getTotalCount() == null
                        || task.getTotalCount() <= 0) return false;
                var snapshot = taskTargetSnapshotRepository.findComplete(job.getTaskId()).orElse(null);
                if (snapshot == null || !job.getTaskId().equals(snapshot.taskId())
                        || snapshot.targets().size() != task.getTotalCount()) return false;
                var taskJobs = jobRepository.findByTaskId(job.getTaskId());
                if (taskJobs == null || taskJobs.size() != task.getTotalCount()) return false;
                Set<String> targetIds = new HashSet<>();
                for (var target : snapshot.targets()) {
                    if (!targetIds.add(target.agentId())) return false;
                }
                Set<String> jobIds = new HashSet<>();
                Set<String> jobAgentIds = new HashSet<>();
                boolean currentJobFound = false;
                for (var taskJob : taskJobs) {
                    if (taskJob == null || taskJob.getId() == null || taskJob.getId().isBlank()
                            || !job.getTaskId().equals(taskJob.getTaskId())
                            || !targetIds.contains(taskJob.getAgentId())
                            || !jobIds.add(taskJob.getId()) || !jobAgentIds.add(taskJob.getAgentId())) return false;
                    if (taskJob.getId().equals(job.getId()) && agentId.equals(taskJob.getAgentId())) {
                        currentJobFound = true;
                    }
                }
                if (!currentJobFound || !targetIds.equals(jobAgentIds) || !agentId.equals(job.getAgentId())) return false;
                var targets = snapshot.targets().stream()
                        .filter(target -> agentId.equals(target.agentId())).toList();
                return targets.size() == 1 && hostAclService.allowedForGeneration(
                        task.getCreatedBy(), agentId, targets.get(0).generation(), HostAclService.Permission.EXECUTE);
            } catch (Exception e) {
                log.warn("Pending job validation failed");
                return false;
            }
        }).toList());
    }

    @PostMapping("/jobs/result")
    public R<Void> reportJobResult(@RequestBody JobResultRequest request, Authentication auth) {
        requireMatchingAgent(auth, request.getAgentId());
        taskService.reportJobResult(request, request.getAgentId());
        return R.ok();
    }

    @PostMapping("/jobs/log")
    public R<Void> reportJobLog(@RequestBody Map<String, String> body, Authentication auth) {
        String jobId = body.get("jobId");
        String log = body.get("log");
        if (jobId == null || log == null) {
            throw new BusinessException(400, "jobId and log are required");
        }
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new BusinessException(404, "Job not found"));
        requireMatchingAgent(auth, job.getAgentId());
        taskService.appendJobLog(jobId, log, job.getAgentId());
        return R.ok();
    }

    @GetMapping("/config")
    public R<Map<String, String>> getAgentConfig(Authentication auth) {
        requireAgent(auth);
        return R.ok(systemConfigService.getAgentConfig());
    }

    // ---------- helpers ----------

    private String authenticatedAgentId(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()
                || !(auth.getPrincipal() instanceof String principal) || principal.isBlank()
                || auth.getAuthorities().stream().noneMatch(a -> "ROLE_AGENT".equals(a.getAuthority()))) {
            return null;
        }
        return principal;
    }

    private boolean isAdministrator(Authentication auth) {
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_SUPER_ADMIN")
                        || a.getAuthority().equals("ROLE_ADMIN"));
    }

    private boolean hasValidRegisterSecret(HttpServletRequest request) {
        if (registerSecret == null || registerSecret.isBlank()) {
            return false;
        }
        String provided = request.getHeader(REGISTER_SECRET_HEADER);
        if (provided == null || provided.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                registerSecret.getBytes(StandardCharsets.UTF_8));
    }

    private String requireAgent(Authentication auth) {
        String agentId = authenticatedAgentId(auth);
        if (agentId == null) {
            throw new BusinessException(401, "Agent credentials required");
        }
        return agentId;
    }

    private void requireMatchingAgent(Authentication auth, String agentId) {
        String principal = requireAgent(auth);
        if (agentId == null || !principal.equals(agentId)) {
            throw new BusinessException(403, "Agent token does not match agentId");
        }
    }

    /**
     * X-Forwarded-For is only honoured when the deployment explicitly trusts the proxy in
     * front of the server; otherwise any client could forge the IP recorded in audit logs.
     */
    private String extractClientIp(HttpServletRequest request) {
        if (trustProxyHeaders) {
            String[] headers = {"X-Forwarded-For", "X-Real-IP"};
            for (String header : headers) {
                String ip = request.getHeader(header);
                if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                    int commaIdx = ip.indexOf(',');
                    return commaIdx > 0 ? ip.substring(0, commaIdx).trim() : ip.trim();
                }
            }
        }
        String remoteAddr = request.getRemoteAddr();
        if ("0:0:0:0:0:0:0:1".equals(remoteAddr)) {
            return "127.0.0.1";
        }
        return remoteAddr;
    }
}
