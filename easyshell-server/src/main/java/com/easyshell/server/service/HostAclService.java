package com.easyshell.server.service;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.entity.User;
import com.easyshell.server.repository.AgentRepository;
import com.easyshell.server.repository.HostAclRepository;
import com.easyshell.server.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataAccessException;

import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.HashSet;

@Service
public class HostAclService {
    public enum Permission { VIEW, EXECUTE, TERMINAL }
    private static final Set<String> ROLES = Set.of("viewer", "operator", "admin", "super_admin");
    private static final Set<String> TERMINAL_ROLES = Set.of("operator", "admin", "super_admin");
    private final HostAclRepository acl;
    private final HostIdentityService identities;
    private final AgentRepository agents;
    private final UserRepository users;
    private final AuditLogService audit;

    public HostAclService(HostAclRepository acl, HostIdentityService identities, AgentRepository agents, UserRepository users,
                          AuditLogService audit) {
        this.acl = acl; this.identities = identities; this.agents = agents; this.users = users;
        this.audit = audit;
    }

    /** Transactional snapshot. State lock serializes competing startup instances. */
    @Transactional
    public void migrateExisting() {
        if (acl.lockCompleted()) return;
        var ids = agents.findAll().stream().map(a -> a.getId()).sorted().toList();
        identities.initializeExisting(ids);
        var existingUsers = users.findAll();
        for (String id : ids) {
            var identity = identities.lock(id);
            if (!identity.active() || !agents.existsById(id)) continue;
            for (User user : existingUsers) {
                if (user.getRole() == null || !ROLES.contains(user.getRole())) continue;
                grantLegacy(id, identity.generation(), user.getId(), Permission.VIEW);
                grantLegacy(id, identity.generation(), user.getId(), Permission.EXECUTE);
                if (!"viewer".equals(user.getRole()))
                    grantLegacy(id, identity.generation(), user.getId(), Permission.TERMINAL);
            }
        }
        acl.complete();
    }

    private void grantLegacy(String id, long generation, Long userId, Permission permission) {
        boolean existed = acl.hasExactGrant(id, generation, "USER", userId.toString(), permission.name());
        acl.grant(id, generation, "USER", userId.toString(), permission.name());
        if (!existed) acl.advanceVersion(id);
    }

    public boolean allowed(Long userId, String agentId, Permission permission) {
        return authorizedGeneration(userId, agentId, permission).isPresent();
    }

    /** A point-in-time authorization, not a lock or a lease. Administrators require grants too. */
    public OptionalLong authorizedGeneration(Long userId, String agentId, Permission permission) {
        if (userId == null || userId <= 0 || agentId == null || agentId.isBlank() || permission == null)
            return OptionalLong.empty();
        try {
            return acl.authorizedGeneration(userId, agentId, permission.name());
        } catch (DataAccessException ex) {
            return OptionalLong.empty();
        }
    }

    /** Rechecks authorization and generation together; does not lock subsequent host operations. */
    public boolean allowedForGeneration(Long userId, String agentId, long expectedGeneration, Permission permission) {
        var generation = authorizedGeneration(userId, agentId, permission);
        return generation.isPresent() && generation.getAsLong() == expectedGeneration;
    }

    @Transactional
    public List<HostAclRepository.Grant> list(Long actor, String agentId) {
        requireAdmin(actor);
        var identity = requireHost(agentId);
        return acl.list(agentId, identity.generation());
    }

    public enum Preset { READ_ONLY, OPERATE }
    public record SubjectSnapshot(long generation, long aclVersion, Set<String> permissions) { }

    /** Consistent only against writers that obey the identity lock; mixed versions are prohibited. */
    @Transactional
    public SubjectSnapshot snapshot(Long actor, String agentId, String type, String subject) {
        requireAdmin(actor);
        validateSubject(type, subject, false);
        var identity = requireHost(agentId);
        long version = acl.version(agentId);
        return new SubjectSnapshot(identity.generation(), version,
                Set.copyOf(acl.subjectPermissions(agentId, identity.generation(), type, subject)));
    }

    @Transactional
    public void applyPreset(Long actor, String agentId, String type, String subject, Preset preset,
                            Long expectedGeneration, Long expectedVersion, Set<String> expectedPermissions,
                            boolean terminalRiskConfirmed) {
        User admin = requireAdmin(actor);
        if (preset == null || expectedGeneration == null || expectedGeneration <= 0 || expectedVersion == null
                || expectedVersion < 0 || expectedPermissions == null || expectedPermissions.stream().anyMatch(java.util.Objects::isNull))
            throw new BusinessException(400, "Invalid preset precondition");
        validateSubject(type, subject, true);
        if (preset == Preset.OPERATE && (!terminalRiskConfirmed || !terminalAllowed(type, subject)))
            throw new BusinessException(400, "Terminal risk confirmation and eligible role required");
        var identity = requireHost(agentId);
        long version = acl.version(agentId);
        Set<String> current = new HashSet<>(acl.subjectPermissions(agentId, identity.generation(), type, subject));
        if (identity.generation() != expectedGeneration || version != expectedVersion || !current.equals(expectedPermissions))
            throw new BusinessException(409, "Host ACL changed; reload and confirm");
        Set<String> target = preset == Preset.READ_ONLY ? Set.of("VIEW") : Set.of("VIEW", "EXECUTE", "TERMINAL");
        Set<String> known = Set.of("VIEW", "EXECUTE", "TERMINAL");
        boolean changed = false;
        for (String permission : known) {
            if (current.contains(permission) && !target.contains(permission)) {
                acl.revoke(agentId, identity.generation(), type, subject, permission);
                changed = true;
            } else if (!current.contains(permission) && target.contains(permission)) {
                acl.grant(agentId, identity.generation(), type, subject, permission);
                changed = true;
            }
        }
        if (changed) acl.advanceVersion(agentId);
        var knownBefore = new java.util.TreeSet<String>();
        for (String permission : known) {
            if (current.contains(permission)) knownBefore.add(permission);
        }
        audit.logSync(actor, admin.getUsername(), "HOST_ACL_PRESET", "agent", agentId,
                "generation=" + identity.generation() + ", version=" + version + ", subjectType=" + type
                        + ", subjectId=" + subject + ", preset=" + preset + ", before=" + knownBefore
                        + ", unknownCount=" + (current.size() - knownBefore.size())
                        + ", riskConfirmed=" + terminalRiskConfirmed, null, "SUCCESS");
    }

    private void validateSubject(String type, String subject, boolean requireExisting) {
        if (type == null || subject == null) throw new BusinessException(400, "Invalid subject");
        if ("ROLE".equals(type) && ROLES.contains(subject)) return;
        if ("USER".equals(type)) {
            try {
                long id = Long.parseLong(subject);
                if (id > 0 && Long.toString(id).equals(subject) && (!requireExisting || users.existsById(id))) return;
            } catch (NumberFormatException ignored) { }
        }
        throw new BusinessException(400, "Invalid subject");
    }

    private boolean terminalAllowed(String type, String subject) {
        if ("ROLE".equals(type)) return TERMINAL_ROLES.contains(subject);
        return users.findById(Long.parseLong(subject)).map(User::getRole).filter(TERMINAL_ROLES::contains).isPresent();
    }

    /** Legacy callers cannot mutate ACLs without a generation precondition. */
    public void change(Long actor, String agentId, String type, String subject, Permission permission, boolean grant) {
        throw new BusinessException(409, "Expected generation required");
    }

    @Transactional
    public void change(Long actor, String agentId, String type, String subject, Permission permission, boolean grant,
                       Long expectedGeneration) {
        User admin = requireAdmin(actor);
        if (expectedGeneration == null || expectedGeneration <= 0)
            throw new BusinessException(400, "Expected generation required");
        if (permission == null || subject == null || type == null) throw new BusinessException(400, "Invalid grant");
        if ("USER".equals(type)) {
            try {
                long id = Long.parseLong(subject);
                if (id <= 0 || !Long.toString(id).equals(subject) || (grant && !users.existsById(id)))
                    throw new BusinessException(400, "Invalid user");
                if (grant && permission == Permission.TERMINAL && users.findById(id)
                        .map(User::getRole).filter(TERMINAL_ROLES::contains).isEmpty())
                    throw new BusinessException(400, "Terminal requires operator, admin, or super_admin role");
            } catch (NumberFormatException e) { throw new BusinessException(400, "Invalid user"); }
        } else if (!"ROLE".equals(type) || !ROLES.contains(subject)) {
            throw new BusinessException(400, "Invalid subject");
        } else if (grant && permission == Permission.TERMINAL && !TERMINAL_ROLES.contains(subject)) {
            throw new BusinessException(400, "Terminal requires operator, admin, or super_admin role");
        }
        var identity = requireHost(agentId);
        if (identity.generation() != expectedGeneration)
            throw new BusinessException(409, "Host generation changed");
        boolean existed = acl.hasExactGrant(agentId, identity.generation(), type, subject, permission.name());
        if (grant) acl.grant(agentId, identity.generation(), type, subject, permission.name());
        else acl.revoke(agentId, identity.generation(), type, subject, permission.name());
        if (grant != existed) acl.advanceVersion(agentId);
        audit.logSync(actor, admin.getUsername(), grant ? "HOST_ACL_GRANT" : "HOST_ACL_REVOKE", "agent", agentId,
                "generation=" + identity.generation() + ", subjectType=" + type + ", subjectId=" + subject
                        + ", permission=" + permission.name(), null, "SUCCESS");
    }

    private User requireAdmin(Long actor) {
        if (!acl.completed()) throw new BusinessException(503, "Host ACL migration incomplete");
        User user = actor == null ? null : users.findById(actor).orElse(null);
        if (user == null || !Integer.valueOf(1).equals(user.getStatus()) ||
                !("admin".equals(user.getRole()) || "super_admin".equals(user.getRole())))
            throw new BusinessException(403, "Administrator required");
        return user;
    }

    private HostIdentityService.Identity requireHost(String agentId) {
        var identity = identities.lock(agentId);
        if (!identity.active() || !agents.existsById(agentId)) throw new BusinessException(404, "Host not found");
        return identity;
    }
}
