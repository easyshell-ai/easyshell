package com.easyshell.server.controller;

import com.easyshell.server.common.result.R;
import com.easyshell.server.model.dto.ClusterAgentRequest;
import com.easyshell.server.model.dto.ClusterRequest;
import com.easyshell.server.model.vo.ClusterDetailVO;
import com.easyshell.server.model.vo.ClusterVO;
import com.easyshell.server.service.AuditLogService;
import com.easyshell.server.service.ClusterService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/cluster")
@RequiredArgsConstructor
public class ClusterController {

    private final ClusterService clusterService;
    private final AuditLogService auditLogService;

    @PostMapping
    public R<ClusterVO> create(@Valid @RequestBody ClusterRequest request, Authentication auth,
                               HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();
        ClusterVO cluster = clusterService.create(request, userId);
        auditLogService.log(userId, auth.getName(), "CREATE_CLUSTER", "cluster",
                String.valueOf(cluster.getId()), request.getName(), httpRequest.getRemoteAddr(), "success");
        return R.ok(cluster);
    }

    @PutMapping("/{id}")
    public R<ClusterVO> update(@PathVariable Long id, @Valid @RequestBody ClusterRequest request,
                               Authentication auth, HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();
        ClusterVO cluster = clusterService.update(id, request);
        auditLogService.log(userId, auth.getName(), "UPDATE_CLUSTER", "cluster",
                String.valueOf(id), request.getName(), httpRequest.getRemoteAddr(), "success");
        return R.ok(cluster);
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id, Authentication auth, HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();
        clusterService.delete(id);
        auditLogService.log(userId, auth.getName(), "DELETE_CLUSTER", "cluster",
                String.valueOf(id), null, httpRequest.getRemoteAddr(), "success");
        return R.ok();
    }

    @GetMapping("/list")
    public R<List<ClusterVO>> list() {
        return R.ok(clusterService.findAll());
    }

    @GetMapping("/{id}")
    public R<ClusterDetailVO> detail(@PathVariable Long id) {
        return R.ok(clusterService.getDetail(id));
    }

    @PostMapping("/{id}/agents")
    public R<Void> addAgents(@PathVariable Long id, @Valid @RequestBody ClusterAgentRequest request,
                             Authentication auth, HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();
        clusterService.addAgents(id, request.getAgentIds());
        auditLogService.log(userId, auth.getName(), "ADD_CLUSTER_AGENTS", "cluster",
                String.valueOf(id), String.join(",", request.getAgentIds()), httpRequest.getRemoteAddr(), "success");
        return R.ok();
    }

    @DeleteMapping("/{id}/agents/{agentId}")
    public R<Void> removeAgent(@PathVariable Long id, @PathVariable String agentId,
                               Authentication auth, HttpServletRequest httpRequest) {
        Long userId = (Long) auth.getPrincipal();
        clusterService.removeAgent(id, agentId);
        auditLogService.log(userId, auth.getName(), "REMOVE_CLUSTER_AGENT", "cluster",
                String.valueOf(id), agentId, httpRequest.getRemoteAddr(), "success");
        return R.ok();
    }
}
