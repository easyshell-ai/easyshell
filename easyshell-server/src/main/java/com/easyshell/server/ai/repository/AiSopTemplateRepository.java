package com.easyshell.server.ai.repository;

import com.easyshell.server.ai.model.entity.AiSopTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AiSopTemplateRepository extends JpaRepository<AiSopTemplate, Long> {

    List<AiSopTemplate> findByEnabledTrueOrderByConfidenceDesc();

    List<AiSopTemplate> findByCategoryAndEnabledTrue(String category);

    /** Global SOPs (userId is null) + user-private SOPs */
    List<AiSopTemplate> findByUserIdIsNullOrUserId(Long userId);

    Optional<AiSopTemplate> findByTitle(String title);

    Page<AiSopTemplate> findByEnabledTrue(Pageable pageable);

    Page<AiSopTemplate> findByCategoryAndEnabledTrue(String category, Pageable pageable);

    Page<AiSopTemplate> findAll(Pageable pageable);

    Page<AiSopTemplate> findByCategory(String category, Pageable pageable);
}
