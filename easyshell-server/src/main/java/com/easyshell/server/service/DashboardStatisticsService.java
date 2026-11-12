package com.easyshell.server.service;

import com.easyshell.server.model.vo.DashboardStatsVO;
import com.easyshell.server.repository.ScriptRepository;
import com.easyshell.server.repository.TaskAccessQueryRepository;
import com.easyshell.server.security.ScriptAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/** Uses the same current principal and visibility policy as the resource lists. */
@Service
@RequiredArgsConstructor
public class DashboardStatisticsService {
    private final TaskAccessQueryRepository tasks;
    private final ScriptRepository scripts;
    private final ScriptAccess scriptAccess;

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public void populate(Long actor, DashboardStatsVO stats) {
        var user = scriptAccess.requireActiveUser(actor);
        long scriptCount = scripts.countReadable(actor,
                "admin".equals(user.getRole()) || "super_admin".equals(user.getRole()));
        var summary = tasks.dashboard(actor, LocalDate.now().atStartOfDay());
        stats.setScriptsAvailable(true);
        stats.setTotalScripts(scriptCount);
        stats.setTasksAvailable(true);
        stats.setTotalTasks(summary.total());
        stats.setTodayTasks(summary.today());
        stats.setTodaySuccessTasks(summary.todaySucceeded());
        stats.setTodayFailedTasks(summary.todayFailed());
        stats.setTaskSuccessRate(summary.successRate());
        stats.setRecentTasks(summary.recent());
    }
}
