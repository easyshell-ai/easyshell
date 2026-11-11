package com.easyshell.server.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import java.util.concurrent.Future;

/**
 * Covers both the default scheduler and the separately named workflow scheduler.
 * Waiting occupies scheduler threads and can saturate a small pool during startup;
 * this in-memory gate does not provide durable scheduling or misfire recovery.
 */
@Configuration
public class HostAclSchedulerBarrierConfiguration {
    @Bean
    static BeanPostProcessor hostAclSchedulerBarrier(HostAclStartupBarrier barrier) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String name) throws BeansException {
                if (bean instanceof ThreadPoolTaskScheduler scheduler) {
                    scheduler.setTaskDecorator(task -> () -> {
                        if (barrier.awaitOpen()) {
                            task.run();
                        } else if (task instanceof Future<?> future) {
                            // The decorated Runnable is also the scheduler's future. Merely
                            // skipping run() leaves its callers waiting forever on shutdown.
                            future.cancel(false);
                        }
                    });
                }
                return bean;
            }
        };
    }
}
