package com.easyshell.server.security;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.entity.Script;
import com.easyshell.server.model.entity.User;
import com.easyshell.server.repository.ScriptRepository;
import com.easyshell.server.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Shared script read policy. The caller supplies a trusted user ID, never a claimed role. */
@Component
@RequiredArgsConstructor
public class ScriptAccess {
    private final ScriptRepository scripts;
    private final UserRepository users;

    public User requireActiveUser(Long userId) {
        if (userId == null) {
            throw notFound();
        }
        return users.findById(userId)
                .filter(user -> Integer.valueOf(1).equals(user.getStatus()))
                .orElseThrow(ScriptAccess::notFound);
    }

    public boolean canRead(User user, Script script) {
        return user != null && user.getId() != null && Integer.valueOf(1).equals(user.getStatus())
                && script != null && (Boolean.TRUE.equals(script.getIsPublic())
                || user.getId().equals(script.getCreatedBy())
                || "admin".equals(user.getRole()) || "super_admin".equals(user.getRole()));
    }

    public Script requireRead(Long userId, Long scriptId) {
        User user = requireActiveUser(userId);
        if (scriptId == null) {
            throw notFound();
        }
        return scripts.findById(scriptId).filter(script -> canRead(user, script))
                .orElseThrow(ScriptAccess::notFound);
    }

    /** Must be called inside the transaction which creates the workflow snapshot. */
    public Script requireReadForSnapshot(Long userId, Long scriptId) {
        User user = requireActiveUser(userId);
        if (scriptId == null) {
            throw notFound();
        }
        return scripts.findByIdForSnapshot(scriptId).filter(script -> canRead(user, script))
                .orElseThrow(ScriptAccess::notFound);
    }

    private static BusinessException notFound() {
        return new BusinessException(404, "Script not found");
    }
}
