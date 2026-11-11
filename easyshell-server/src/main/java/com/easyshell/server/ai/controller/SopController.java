package com.easyshell.server.ai.controller;

import com.easyshell.server.ai.learning.SopExtractor;
import com.easyshell.server.ai.model.entity.AiSopTemplate;
import com.easyshell.server.ai.repository.AiSopTemplateRepository;
import com.easyshell.server.common.result.R;
import com.easyshell.server.common.exception.BusinessException;
import com.easyshell.server.model.entity.User;
import com.easyshell.server.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.List;

@RestController
@RequestMapping("/api/sop")
@RequiredArgsConstructor
public class SopController {

    private final AiSopTemplateRepository sopTemplateRepository;
    private final SopExtractor sopExtractor;
    private final UserRepository userRepository;

    @GetMapping
    public R<Page<AiSopTemplate>> list(
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, Authentication auth) {
        User user = currentUser(auth);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "confidence"));
        List<AiSopTemplate> visible = sopTemplateRepository.findByUserIdIsNullOrUserId(user.getId()).stream()
                .filter(sop -> sop.getUserId() == null || user.getId().equals(sop.getUserId()))
                .filter(sop -> category == null || category.isBlank() || category.equals(sop.getCategory()))
                .sorted(Comparator.comparing(AiSopTemplate::getConfidence,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        int start = (int) Math.min(pageable.getOffset(), visible.size());
        int end = (int) Math.min((long) start + size, visible.size());
        return R.ok(new PageImpl<>(visible.subList(start, end), pageable, visible.size()));
    }

    @GetMapping("/{id}")
    public R<AiSopTemplate> getById(@PathVariable Long id, Authentication auth) {
        User user = currentUser(auth);
        return sopTemplateRepository.findById(id)
                .map(sop -> { requireAccess(sop, user, false); return R.ok(sop); })
                .orElse(R.fail("SOP not found"));
    }

    @PutMapping("/{id}")
    public R<AiSopTemplate> update(@PathVariable Long id, @RequestBody AiSopTemplate request, Authentication auth) {
        User user = currentUser(auth);
        return sopTemplateRepository.findById(id).map(sop -> {
            requireAccess(sop, user, true);
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
    public R<Void> delete(@PathVariable Long id, Authentication auth) {
        User user = currentUser(auth);
        return sopTemplateRepository.findById(id).map(sop -> {
            requireAccess(sop, user, true);
            sopTemplateRepository.deleteById(id);
            return R.<Void>ok(null);
        }).orElse(R.fail("SOP not found"));
    }

    @PostMapping("/extract")
    public R<String> triggerExtraction(Authentication auth) {
        if (!"super_admin".equals(currentUser(auth).getRole())) {
            throw new BusinessException(403, "SOP_FORBIDDEN");
        }
        sopExtractor.extractSopPatterns();
        return R.ok("SOP extraction triggered");
    }

    private User currentUser(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken
                || !(auth.getPrincipal() instanceof Long id) || id <= 0) {
            throw new BusinessException(403, "SOP_FORBIDDEN");
        }
        User user = userRepository.findById(id).orElseThrow(() -> new BusinessException(403, "SOP_FORBIDDEN"));
        if (!id.equals(user.getId()) || !Integer.valueOf(1).equals(user.getStatus())) {
            throw new BusinessException(403, "SOP_FORBIDDEN");
        }
        return user;
    }

    private static void requireAccess(AiSopTemplate sop, User user, boolean write) {
        if (sop.getUserId() == null ? write && !"super_admin".equals(user.getRole())
                : !sop.getUserId().equals(user.getId())) {
            throw new BusinessException(403, "SOP_FORBIDDEN");
        }
    }
}
