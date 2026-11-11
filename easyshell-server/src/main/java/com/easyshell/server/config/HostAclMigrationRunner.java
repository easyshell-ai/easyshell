package com.easyshell.server.config;

import com.easyshell.server.service.HostAclService;
import com.easyshell.server.ai.service.AiSchedulerService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Failure propagates and prevents successful application startup. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HostAclMigrationRunner implements ApplicationRunner {
    private final HostAclService acl;
    private final DataInitializer initializer;
    private final HostAclStartupBarrier barrier;
    private final AiSchedulerService scheduler;

    public HostAclMigrationRunner(HostAclService acl, DataInitializer initializer,
                                  HostAclStartupBarrier barrier, AiSchedulerService scheduler) {
        this.acl = acl;
        this.initializer = initializer;
        this.barrier = barrier;
        this.scheduler = scheduler;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            // Both calls return only after their respective transactions commit.
            acl.migrateExisting();
            initializer.initialize();
            scheduler.init();
            barrier.open();
        } catch (RuntimeException | Error failure) {
            barrier.abort();
            throw failure;
        }
    }
}
