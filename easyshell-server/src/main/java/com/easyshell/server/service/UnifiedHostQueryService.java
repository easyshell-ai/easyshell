package com.easyshell.server.service;

import com.easyshell.server.model.vo.HostCredentialVO;
import com.easyshell.server.repository.UserRepository;
import com.easyshell.server.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

/** Authorized agents only. Historical credentials are never merged by IP or stale agent ID. */
@Service
@RequiredArgsConstructor
public class UnifiedHostQueryService {
    private final AgentService agents;
    private final HostAclService acl;
    private final UserRepository users;

    public List<HostCredentialVO> list(Long actor) {
        var user=users.findById(actor).orElseThrow(()->new BusinessException(403,"Active administrator required"));
        if (!Integer.valueOf(1).equals(user.getStatus()) ||
                !("admin".equals(user.getRole()) || "super_admin".equals(user.getRole())))
            throw new BusinessException(403,"Active administrator required");
        return agents.findAll().stream().filter(a->acl.allowed(actor,a.getId(),HostAclService.Permission.VIEW))
                .map(a->HostCredentialVO.builder().agentId(a.getId()).ip(a.getIp())
                        .hostname(a.getHostname()).hostName(a.getHostname()).os(a.getOs()).arch(a.getArch())
                        .kernel(a.getKernel()).cpuModel(a.getCpuModel()).cpuCores(a.getCpuCores())
                        .memTotal(a.getMemTotal()).agentVersion(a.getAgentVersion()).agentStatus(a.getStatus())
                        .cpuUsage(a.getCpuUsage()).memUsage(a.getMemUsage()).diskUsage(a.getDiskUsage())
                        .lastHeartbeat(a.getLastHeartbeat()==null?null:a.getLastHeartbeat().toString()).build()).toList();
    }
}
