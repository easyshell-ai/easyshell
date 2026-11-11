package com.easyshell.server.config;

import com.easyshell.server.ai.service.AiSchedulerService;
import com.easyshell.server.service.HostAclService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HostAclStartupBarrierTest {
    @Test
    void runnerOpensOnlyAfterMigrationDataAndSchedulerInitialization() {
        var acl = mock(HostAclService.class);
        var initializer = mock(DataInitializer.class);
        var scheduler = mock(AiSchedulerService.class);
        var barrier = new HostAclStartupBarrier();
        doAnswer(invocation -> { assertFalse(barrier.isOpen()); return null; }).when(acl).migrateExisting();
        doAnswer(invocation -> { assertFalse(barrier.isOpen()); return null; }).when(scheduler).init();
        new HostAclMigrationRunner(acl, initializer, barrier, scheduler).run(null);
        var order = inOrder(initializer, acl, scheduler);
        order.verify(acl).migrateExisting();
        order.verify(initializer).initialize();
        order.verify(scheduler).init();
        assertTrue(barrier.isOpen());
    }

    @Test
    void migrationFailureKeepsGateClosed() {
        var acl = mock(HostAclService.class);
        var scheduler = mock(AiSchedulerService.class);
        var barrier = new HostAclStartupBarrier();
        doThrow(new IllegalStateException("database unavailable")).when(acl).migrateExisting();
        var runner = new HostAclMigrationRunner(acl, mock(DataInitializer.class), barrier, scheduler);
        assertThrows(IllegalStateException.class, () -> runner.run(null));
        assertFalse(barrier.isOpen());
        verifyNoInteractions(scheduler);
    }

    @Test
    void filterBlocksHttpAndWebSocketUpgradeUntilOpen() throws Exception {
        var barrier = new HostAclStartupBarrier();
        var filter = new HostAclStartupFilter(barrier);
        var request = new MockHttpServletRequest("GET", "/ws/terminal/host");
        request.addHeader("Upgrade", "websocket");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertEquals(503, response.getStatus());
        assertNull(chain.getRequest());
        barrier.open();
        chain = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/host"), new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest());
    }

    @Test
    void schedulerWaitsAndPreservesOneShotFixedDelayAndCronRescheduling() throws Exception {
        var barrier = new HostAclStartupBarrier();
        var processor = HostAclSchedulerBarrierConfiguration.hostAclSchedulerBarrier(barrier);
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(3);
        processor.postProcessBeforeInitialization(scheduler, "workflowScheduler");
        scheduler.initialize();
        try {
            var oneShot = new CountDownLatch(1);
            var fixedDelay = new CountDownLatch(2);
            var cron = new CountDownLatch(2);
            var onceFuture = scheduler.schedule(oneShot::countDown, Instant.now());
            var fixedFuture = scheduler.scheduleWithFixedDelay(fixedDelay::countDown, Duration.ofMillis(50));
            var cronFuture = scheduler.schedule(cron::countDown, new CronTrigger("* * * * * *"));
            assertThrows(TimeoutException.class, () -> onceFuture.get(1200, TimeUnit.MILLISECONDS));
            assertEquals(1, oneShot.getCount());
            assertEquals(2, fixedDelay.getCount());
            assertEquals(2, cron.getCount());
            barrier.open();
            assertTrue(oneShot.await(2, TimeUnit.SECONDS));
            assertTrue(fixedDelay.await(2, TimeUnit.SECONDS));
            assertTrue(cron.await(4, TimeUnit.SECONDS));
            fixedFuture.cancel(true);
            cronFuture.cancel(true);
        } finally {
            barrier.abort();
            scheduler.shutdown();
        }
    }

    @Test
    void destructionAbortsWaitingTasksAndCannotReopen() throws Exception {
        var barrier = new HostAclStartupBarrier();
        var scheduler = new ThreadPoolTaskScheduler();
        HostAclSchedulerBarrierConfiguration.hostAclSchedulerBarrier(barrier)
                .postProcessBeforeInitialization(scheduler, "scheduler");
        scheduler.initialize();
        try {
            var ran = new CountDownLatch(1);
            var future = scheduler.schedule(ran::countDown, Instant.now());
            assertThrows(TimeoutException.class, () -> future.get(200, TimeUnit.MILLISECONDS));
            barrier.destroy();
            assertThrows(java.util.concurrent.CancellationException.class, () -> future.get(2, TimeUnit.SECONDS));
            barrier.open();
            assertFalse(barrier.isOpen());
            assertEquals(1, ran.getCount());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void interruptedWaitIsFailClosedAndPreservesInterrupt() throws Exception {
        var barrier = new HostAclStartupBarrier();
        var started = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var result = new java.util.concurrent.atomic.AtomicBoolean(true);
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        var thread = new Thread(() -> {
            started.countDown();
            result.set(barrier.awaitOpen());
            interrupted.set(Thread.currentThread().isInterrupted());
            finished.countDown();
        });
        thread.start();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        thread.interrupt();
        assertTrue(finished.await(2, TimeUnit.SECONDS));
        assertFalse(result.get());
        assertTrue(interrupted.get());
    }
}
