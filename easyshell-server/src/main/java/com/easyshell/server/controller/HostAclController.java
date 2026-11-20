package com.easyshell.server.controller;

import com.easyshell.server.common.result.R;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.repository.HostAclRepository;
import com.easyshell.server.service.HostAclService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/host-acl")
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
public class HostAclController {
    private final HostAclService acl;
    private final HostAclRepository repository;

    public HostAclController(HostAclService acl, HostAclRepository repository) { this.acl = acl; this.repository = repository; }

    @GetMapping("/catalog")
    public R<List<HostAclRepository.CatalogHost>> catalog(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size, Authentication auth) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > 1_000_000)
            throw new BusinessException(400, "Invalid pagination");
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Long actor) || actor <= 0)
            throw new BusinessException(403, "Administrator required");
        return R.ok(repository.catalog(actor, size, (long) page * size));
    }

    @GetMapping("/{agentId}")
    public R<List<HostAclRepository.Grant>> list(@PathVariable String agentId, Authentication auth) {
        return R.ok(acl.list((Long) auth.getPrincipal(), agentId));
    }

    @GetMapping("/{agentId}/preset")
    public R<HostAclService.SubjectSnapshot> snapshot(@PathVariable String agentId,
            @RequestParam String subjectType, @RequestParam String subjectId, Authentication auth) {
        return R.ok(acl.snapshot((Long) auth.getPrincipal(), agentId, subjectType, subjectId));
    }

    @PutMapping("/{agentId}/preset")
    public R<Void> preset(@PathVariable String agentId, @RequestBody PresetRequest request, Authentication auth) {
        acl.applyPreset((Long) auth.getPrincipal(), agentId, request.subjectType(), request.subjectId(), request.preset(),
                request.expectedGeneration(), request.expectedVersion(), request.expectedPermissions(),
                Boolean.TRUE.equals(request.terminalRiskConfirmed()));
        return R.ok();
    }

    public record PresetRequest(String subjectType, String subjectId, HostAclService.Preset preset,
                                Long expectedGeneration, Long expectedVersion, Set<String> expectedPermissions,
                                Boolean terminalRiskConfirmed) { }

    @PutMapping("/{agentId}")
    public R<Void> grant(@PathVariable String agentId, @RequestBody GrantRequest request, Authentication auth) {
        acl.change((Long) auth.getPrincipal(), agentId, request.subjectType(), request.subjectId(), request.permission(), true,
                request.expectedGeneration());
        return R.ok();
    }

    @DeleteMapping("/{agentId}")
    public R<Void> revoke(@PathVariable String agentId, @RequestBody GrantRequest request, Authentication auth) {
        acl.change((Long) auth.getPrincipal(), agentId, request.subjectType(), request.subjectId(), request.permission(), false,
                request.expectedGeneration());
        return R.ok();
    }

    public record GrantRequest(String subjectType, String subjectId, HostAclService.Permission permission,
                               Long expectedGeneration) { }
}
