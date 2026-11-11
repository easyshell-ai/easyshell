package com.easyshell.server.controller;

import com.easyshell.server.security.AuditUsers;
import com.easyshell.server.common.result.R;
import com.easyshell.server.model.dto.ClusterAgentRequest;
import com.easyshell.server.model.dto.ClusterRequest;
import com.easyshell.server.model.vo.ClusterDetailVO;
import com.easyshell.server.model.vo.ClusterVO;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.service.ClusterService;
import com.easyshell.server.service.HostAclService;
import com.easyshell.server.repository.ClusterAgentRepository;
import com.easyshell.server.repository.UserRepository;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.entity.Agent;
import com.easyshell.server.model.entity.ClusterAgent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/cluster")
@RequiredArgsConstructor
public class ClusterController {

    private final ClusterService clusterService;
    private final AuditLogService auditLogService;
    private final HostAclService hostAclService;
    private final UserRepository userRepository;
    private final ClusterAgentRepository clusterAgentRepository;

    @PostMapping
    public R<ClusterVO> create(@Valid @RequestBody ClusterRequest request, Authentication auth,
                               HttpServletRequest httpRequest) {
        Long userId = requireAdmin(auth);
        ClusterVO cluster = clusterService.create(request, userId);
        auditLogService.log(userId, AuditUsers.name(auth), "CREATE_CLUSTER", "cluster",
                String.valueOf(cluster.getId()), request.getName(), httpRequest.getRemoteAddr(), "success");
        return R.ok(cluster);
    }

    @PutMapping("/{id}")
    public R<ClusterVO> update(@PathVariable Long id, @Valid @RequestBody ClusterRequest request,
                               Authentication auth, HttpServletRequest httpRequest) {
        Long userId = requireAdmin(auth);
        int visibleCount = visibleAgents(id, userId).size();
        ClusterVO cluster = clusterService.update(id, request);
        cluster.setAgentCount(visibleCount);
        auditLogService.log(userId, AuditUsers.name(auth), "UPDATE_CLUSTER", "cluster",
                String.valueOf(id), request.getName(), httpRequest.getRemoteAddr(), "success");
        return R.ok(cluster);
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id, Authentication auth, HttpServletRequest httpRequest) {
        Long userId = requireAdmin(auth);
        for (ClusterAgent member : clusterAgentRepository.findByClusterId(id)) {
            requireView(userId, member.getAgentId());
        }
        clusterService.delete(id);
        auditLogService.log(userId, AuditUsers.name(auth), "DELETE_CLUSTER", "cluster",
                String.valueOf(id), null, httpRequest.getRemoteAddr(), "success");
        return R.ok();
    }

    @GetMapping("/list")
    public R<List<ClusterVO>> list(Authentication auth) {
        Long userId = principal(auth);
        List<ClusterVO> clusters = clusterService.findAll();
        for (ClusterVO cluster : clusters) {
            cluster.setAgentCount(visibleAgents(cluster.getId(), userId).size());
        }
        return R.ok(clusters);
    }

    @GetMapping("/{id}")
    public R<ClusterDetailVO> detail(@PathVariable Long id, Authentication auth) {
        Long userId = principal(auth);
        ClusterDetailVO detail = clusterService.getDetail(id);
        detail.setAgents(visibleAgents(id, userId, detail));
        return R.ok(detail);
    }

    @PostMapping("/{id}/agents")
    public R<Void> addAgents(@PathVariable Long id, @Valid @RequestBody ClusterAgentRequest request,
                             Authentication auth, HttpServletRequest httpRequest) {
        Long userId = requireAdmin(auth);
        if (request.getAgentIds() == null || request.getAgentIds().isEmpty()) {
            throw new BusinessException(400, "Agent IDs required");
        }
        for (String agentId : request.getAgentIds()) {
            requireView(userId, agentId);
        }
        clusterService.addAgents(id, request.getAgentIds());
        auditLogService.log(userId, AuditUsers.name(auth), "ADD_CLUSTER_AGENTS", "cluster",
                String.valueOf(id), String.join(",", request.getAgentIds()), httpRequest.getRemoteAddr(), "success");
        return R.ok();
    }

    @DeleteMapping("/{id}/agents/{agentId}")
    public R<Void> removeAgent(@PathVariable Long id, @PathVariable String agentId,
                               Authentication auth, HttpServletRequest httpRequest) {
        Long userId = requireAdmin(auth);
        requireView(userId, agentId);
        clusterService.removeAgent(id, agentId);
        auditLogService.log(userId, AuditUsers.name(auth), "REMOVE_CLUSTER_AGENT", "cluster",
                String.valueOf(id), agentId, httpRequest.getRemoteAddr(), "success");
        return R.ok();
    }

    private Long principal(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Long userId) || userId <= 0) {
            throw new BusinessException(403, "Authenticated user required");
        }
        return userId;
    }

    private Long requireAdmin(Authentication auth) {
        Long userId = principal(auth);
        var user = userRepository.findById(userId).orElse(null);
        if (user == null || !Integer.valueOf(1).equals(user.getStatus()) ||
                !("admin".equals(user.getRole()) || "super_admin".equals(user.getRole()))) {
            throw new BusinessException(403, "Administrator required");
        }
        return userId;
    }

    private void requireView(Long userId, String agentId) {
        if (!hostAclService.allowed(userId, agentId, HostAclService.Permission.VIEW)) {
            throw new BusinessException(403, "Host access denied");
        }
    }

    private List<Agent> visibleAgents(Long clusterId, Long userId) {
        return visibleAgents(clusterId, userId, clusterService.getDetail(clusterId));
    }

    private List<Agent> visibleAgents(Long clusterId, Long userId, ClusterDetailVO detail) {
        List<ClusterAgent> members = clusterAgentRepository.findByClusterId(clusterId);
        List<Agent> agents = detail.getAgents();
        if (members == null || agents == null || members.stream().anyMatch(m -> m == null || m.getAgentId() == null)
                || agents.stream().anyMatch(a -> a == null || a.getId() == null)) {
            throw new BusinessException(503, "Cluster members unavailable");
        }
        Set<String> memberIds = members.stream().map(ClusterAgent::getAgentId).collect(Collectors.toSet());
        Set<String> agentIds = agents.stream().map(Agent::getId).collect(Collectors.toSet());
        if (memberIds.size() != members.size() || agentIds.size() != agents.size() || !memberIds.equals(agentIds)) {
            throw new BusinessException(503, "Cluster members unavailable");
        }
        return agents.stream().filter(a -> hostAclService.allowed(userId, a.getId(), HostAclService.Permission.VIEW)).toList();
    }
}
