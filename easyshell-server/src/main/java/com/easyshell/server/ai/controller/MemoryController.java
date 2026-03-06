package com.easyshell.server.ai.controller;

import com.easyshell.server.ai.model.entity.AiSessionSummary;
import com.easyshell.server.ai.repository.AiSessionSummaryRepository;
import com.easyshell.server.ai.repository.AiChatSessionRepository;
import com.easyshell.server.ai.service.AiChatService;
import com.easyshell.server.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

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
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return R.ok(sessionSummaryRepository.findAll(pageable));
    }

    @GetMapping("/{id}")
    public R<AiSessionSummary> getById(@PathVariable Long id) {
        return sessionSummaryRepository.findById(id)
                .map(R::ok)
                .orElse(R.fail("Memory not found"));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        return sessionSummaryRepository.findById(id).map(summary -> {
            String sessionId = summary.getSessionId();
            sessionSummaryRepository.deleteById(id);
            // Reset summaryGenerated so it can be re-triggered
            chatSessionRepository.findById(sessionId).ifPresent(session -> {
                session.setSummaryGenerated(false);
                chatSessionRepository.save(session);
            });
            return R.<Void>ok(null);
        }).orElse(R.fail("Memory not found"));
    }

    @DeleteMapping
    public R<Void> clearAll() {
        sessionSummaryRepository.deleteAll();
        // Reset summaryGenerated on all sessions so they can be re-triggered
        chatSessionRepository.findAll().forEach(session -> {
            if (Boolean.TRUE.equals(session.getSummaryGenerated())) {
                session.setSummaryGenerated(false);
                chatSessionRepository.save(session);
            }
        });
        return R.ok(null);
    }

    @PostMapping("/trigger-summarization")
    public R<String> triggerSummarization() {
        int count = aiChatService.triggerAllSessionSummarization();
        return R.ok("Triggered summarization for " + count + " sessions");
    }
}
