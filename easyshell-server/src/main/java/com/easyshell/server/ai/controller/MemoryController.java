package com.easyshell.server.ai.controller;

import com.easyshell.server.ai.model.entity.AiSessionSummary;
import com.easyshell.server.ai.repository.AiSessionSummaryRepository;
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
        if (!sessionSummaryRepository.existsById(id)) {
            return R.fail("Memory not found");
        }
        sessionSummaryRepository.deleteById(id);
        return R.ok(null);
    }

    @DeleteMapping
    public R<Void> clearAll() {
        sessionSummaryRepository.deleteAll();
        return R.ok(null);
    }
}
