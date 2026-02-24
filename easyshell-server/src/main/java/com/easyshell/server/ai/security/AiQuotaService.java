package com.easyshell.server.ai.security;

import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.repository.SystemConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiQuotaService {

    private final SystemConfigRepository systemConfigRepository;

    private final Map<String, AtomicInteger> dailyUsage = new ConcurrentHashMap<>();
    private volatile LocalDate currentDate = LocalDate.now();

    public void checkAndIncrement(Long userId, String action) {
        resetIfNewDay();

        int limit = getDailyLimit();
        if (limit <= 0) {
            return;
        }

        String key = userId + ":" + currentDate;
        AtomicInteger counter = dailyUsage.computeIfAbsent(key, k -> new AtomicInteger(0));
        int current = counter.incrementAndGet();

        if (current > limit) {
            counter.decrementAndGet();
            log.warn("AI quota exceeded for user {} (action={}): {}/{}", userId, action, current, limit);
            throw new BusinessException(429, "AI 调用已达今日上限 (" + limit + " 次/天)，请明日再试");
        }

        log.debug("AI quota usage for user {}: {}/{} (action={})", userId, current, limit, action);
    }

    public int getUsage(Long userId) {
        resetIfNewDay();
        String key = userId + ":" + currentDate;
        AtomicInteger counter = dailyUsage.get(key);
        return counter != null ? counter.get() : 0;
    }

    public int getDailyLimit() {
        try {
            return systemConfigRepository.findByConfigKey("ai.quota.daily-limit")
                    .map(config -> Integer.parseInt(config.getConfigValue().trim()))
                    .orElse(100);
        } catch (NumberFormatException e) {
            log.warn("Invalid ai.quota.daily-limit config value");
            return 100;
        }
    }

    private void resetIfNewDay() {
        LocalDate today = LocalDate.now();
        if (!today.equals(currentDate)) {
            synchronized (this) {
                if (!today.equals(currentDate)) {
                    dailyUsage.clear();
                    currentDate = today;
                    log.info("AI quota counters reset for new day: {}", today);
                }
            }
        }
    }
}
