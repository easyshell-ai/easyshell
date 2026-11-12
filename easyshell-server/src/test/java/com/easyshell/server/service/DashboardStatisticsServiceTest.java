package com.easyshell.server.service;

import com.easyshell.server.model.entity.User;
import com.easyshell.server.model.vo.DashboardStatsVO;
import com.easyshell.server.repository.ScriptRepository;
import com.easyshell.server.repository.TaskAccessQueryRepository;
import com.easyshell.server.security.ScriptAccess;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DashboardStatisticsServiceTest {
    @Test
    void fillsOnlyCurrentUserScopedStatisticsAndReturnsActualZero() {
        var tasks=mock(TaskAccessQueryRepository.class);var scripts=mock(ScriptRepository.class);
        var access=mock(ScriptAccess.class);var user=new User();user.setId(7L);user.setRole("operator");
        when(access.requireActiveUser(7L)).thenReturn(user);
        when(scripts.countReadable(7L,false)).thenReturn(2L);
        when(tasks.dashboard(eq(7L),any())).thenReturn(new TaskAccessQueryRepository.Dashboard(0,0,0,0,0,List.of()));
        var stats=new DashboardStatsVO();new DashboardStatisticsService(tasks,scripts,access).populate(7L,stats);
        assertTrue(stats.isTasksAvailable());assertTrue(stats.isScriptsAvailable());
        assertEquals(0L,stats.getTotalTasks());assertEquals(2L,stats.getTotalScripts());assertEquals(List.of(),stats.getRecentTasks());
        verify(scripts).countReadable(7L,false);
    }
}
