package com.easyshell.server.model.dto;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Complete set of generation-bound task targets, not a live host lookup. */
public record TaskTargetSnapshot(String taskId, List<Target> targets) {
    public TaskTargetSnapshot {
        if (taskId == null || taskId.isBlank() || taskId.length() > 64)
            throw new IllegalArgumentException("Invalid task id");
        targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
        if (targets.isEmpty()) throw new IllegalArgumentException("Targets must not be empty");
        var ids = new HashSet<String>();
        for (Target target : targets) {
            if (!ids.add(target.agentId())) throw new IllegalArgumentException("Duplicate target agent id");
        }
    }

    public record Target(String agentId, long generation) {
        public Target {
            if (agentId == null || agentId.isBlank() || agentId.length() > 64 || generation <= 0)
                throw new IllegalArgumentException("Invalid target identity");
        }
    }
}
