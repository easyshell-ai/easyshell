package com.easyshell.server.ai.controller;

import com.easyshell.server.ai.model.entity.AiSessionSummary;
import com.easyshell.server.ai.repository.AiSessionSummaryRepository;
import com.easyshell.server.ai.repository.AiChatSessionRepository;
import com.easyshell.server.ai.service.AiChatService;
import com.easyshell.server.common.result.R;
import com.easyshell.server.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/memory")
@RequiredArgsConstructor
public class MemoryController {

    private final AiSessionSummaryRepository sessionSummaryRepository;
    private final AiChatSessionRepository chatSessionRepository;
    private final AiChatService aiChatService;

    @GetMapping
    public R<Page<AiSessionSummary>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, Authentication auth) {
        Long userId = principalId(auth);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        List<AiSessionSummary> summaries = sessionSummaryRepository.findByUserIdOrderByCreatedAtDesc(userId);
        int start = (int) Math.min(pageable.getOffset(), summaries.size());
        int end = (int) Math.min((long) start + pageable.getPageSize(), summaries.size());
        return R.ok(new PageImpl<>(summaries.subList(start, end), pageable, summaries.size()));
    }

    @GetMapping("/{id}")
    public R<AiSessionSummary> getById(@PathVariable Long id, Authentication auth) {
        Long userId = principalId(auth);
        return sessionSummaryRepository.findById(id)
                .map(summary -> { requireOwner(summary, userId); return R.ok(summary); })
                .orElse(R.fail("Memory not found"));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public R<Void> delete(@PathVariable Long id, Authentication auth) {
        Long userId = principalId(auth);
        return sessionSummaryRepository.findById(id).map(summary -> {
            requireOwner(summary, userId);
            String sessionId = summary.getSessionId();
            sessionSummaryRepository.deleteById(id);
            // Reset summaryGenerated so it can be re-triggered
            chatSessionRepository.findById(sessionId).filter(session -> userId.equals(session.getUserId())).ifPresent(session -> {
                session.setSummaryGenerated(false);
                chatSessionRepository.save(session);
            });
            return R.<Void>ok(null);
        }).orElse(R.fail("Memory not found"));
    }

    @DeleteMapping
    @Transactional
    public R<Void> clearAll(Authentication auth) {
        Long userId = principalId(auth);
        sessionSummaryRepository.findByUserIdOrderByCreatedAtDesc(userId).forEach(summary -> {
            requireOwner(summary, userId);
            sessionSummaryRepository.delete(summary);
        });
        // Reset only the caller's sessions so they can be re-triggered.
        chatSessionRepository.findByUserIdOrderByUpdatedAtDesc(userId).forEach(session -> {
            if (!userId.equals(session.getUserId())) {
                throw new BusinessException(403, "MEMORY_FORBIDDEN");
            }
            if (Boolean.TRUE.equals(session.getSummaryGenerated())) {
                session.setSummaryGenerated(false);
                chatSessionRepository.save(session);
            }
        });
        return R.ok(null);
    }

    @PostMapping("/trigger-summarization")
    public R<String> triggerSummarization(Authentication auth) {
        Long userId = principalId(auth);
        if (userId <= 0) {
            throw new BusinessException(403, "MEMORY_FORBIDDEN");
        }
        int count = aiChatService.triggerAllSessionSummarization(userId);
        return R.ok("Triggered summarization for " + count + " sessions");
    }

    private static Long principalId(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Long userId)) {
            throw new BusinessException(403, "MEMORY_FORBIDDEN");
        }
        return userId;
    }

    private static void requireOwner(AiSessionSummary summary, Long userId) {
        if (!userId.equals(summary.getUserId())) {
            throw new BusinessException(403, "MEMORY_FORBIDDEN");
        }
    }
}
