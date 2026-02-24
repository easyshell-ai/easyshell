package com.easyshell.server.ai.controller;

import com.easyshell.server.ai.learning.SopExtractor;
import com.easyshell.server.ai.model.entity.AiSopTemplate;
import com.easyshell.server.ai.repository.AiSopTemplateRepository;
import com.easyshell.server.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sop")
@RequiredArgsConstructor
public class SopController {

    private final AiSopTemplateRepository sopTemplateRepository;
    private final SopExtractor sopExtractor;

    @GetMapping
    public R<Page<AiSopTemplate>> list(
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "confidence"));
        Page<AiSopTemplate> result = (category != null && !category.isBlank())
                ? sopTemplateRepository.findByCategory(category, pageable)
                : sopTemplateRepository.findAll(pageable);
        return R.ok(result);
    }

    @GetMapping("/{id}")
    public R<AiSopTemplate> getById(@PathVariable Long id) {
        return sopTemplateRepository.findById(id)
                .map(R::ok)
                .orElse(R.fail("SOP not found"));
    }

    @PutMapping("/{id}")
    public R<AiSopTemplate> update(@PathVariable Long id, @RequestBody AiSopTemplate request) {
        return sopTemplateRepository.findById(id).map(sop -> {
            if (request.getTitle() != null) sop.setTitle(request.getTitle());
            if (request.getDescription() != null) sop.setDescription(request.getDescription());
            if (request.getStepsJson() != null) sop.setStepsJson(request.getStepsJson());
            if (request.getTriggerPattern() != null) sop.setTriggerPattern(request.getTriggerPattern());
            if (request.getCategory() != null) sop.setCategory(request.getCategory());
            if (request.getEnabled() != null) sop.setEnabled(request.getEnabled());
            return R.ok(sopTemplateRepository.save(sop));
        }).orElse(R.fail("SOP not found"));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        if (!sopTemplateRepository.existsById(id)) {
            return R.fail("SOP not found");
        }
        sopTemplateRepository.deleteById(id);
        return R.ok(null);
    }

    @PostMapping("/extract")
    public R<String> triggerExtraction() {
        sopExtractor.extractSopPatterns();
        return R.ok("SOP extraction triggered");
    }
}
