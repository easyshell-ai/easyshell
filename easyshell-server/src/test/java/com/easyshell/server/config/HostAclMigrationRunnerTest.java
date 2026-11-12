package com.easyshell.server.config;

import com.easyshell.server.ai.service.AiSchedulerService;
import com.easyshell.server.service.HostAclService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class HostAclMigrationRunnerTest {
    @Test
    void eachFailureAbortsAndPropagatesWithoutOpening() {
        for (int stage = 0; stage < 3; stage++) {
            var acl = mock(HostAclService.class);
            var initializer = mock(DataInitializer.class);
            var scheduler = mock(AiSchedulerService.class);
            var barrier = mock(HostAclStartupBarrier.class);
            var failure = new IllegalStateException("startup failure");
            if (stage == 0) doThrow(failure).when(acl).migrateExisting();
            if (stage == 1) doThrow(failure).when(initializer).initialize();
            if (stage == 2) doThrow(failure).when(scheduler).init();

            var runner = new HostAclMigrationRunner(acl, initializer, barrier, scheduler);
            assertSame(failure, assertThrows(IllegalStateException.class, () -> runner.run(null)));
            verify(barrier).abort();
            verify(barrier, never()).open();
            if (stage == 0) verifyNoInteractions(initializer, scheduler);
            if (stage == 1) verifyNoInteractions(scheduler);
        }
    }
}
