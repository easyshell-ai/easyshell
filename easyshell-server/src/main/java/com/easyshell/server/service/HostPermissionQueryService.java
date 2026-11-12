package com.easyshell.server.service;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.vo.HostPermissionQueryVO;
import com.easyshell.server.repository.HostPermissionQueryRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class HostPermissionQueryService {
    private final HostPermissionQueryRepository repository;

    public HostPermissionQueryService(HostPermissionQueryRepository repository) { this.repository = repository; }

    public record ExecuteTargetsPage(List<String> agentIds,
            @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor) { }

    public ExecuteTargetsPage executeTargets(long userId, String cursor, int limit) {
        if (limit < 1 || limit > 100 || (cursor != null && (cursor.isBlank() || cursor.length() > 64)))
            throw new BusinessException(400, "Invalid pagination");
        try {
            List<String> ids = repository.executeTargets(userId, cursor, limit + 1);
            boolean hasMore = ids.size() > limit;
            List<String> page = hasMore ? List.copyOf(ids.subList(0, limit)) : ids;
            return new ExecuteTargetsPage(page, hasMore ? page.get(page.size() - 1) : null);
        } catch (DataAccessException ex) {
            throw new BusinessException(503, "Host permissions unavailable");
        }
    }

    public List<HostPermissionQueryVO> query(long userId, List<String> agentIds) {
        try {
            return repository.query(userId, agentIds);
        } catch (DataAccessException ex) {
            throw new BusinessException(503, "Host permissions unavailable");
        }
    }
}
