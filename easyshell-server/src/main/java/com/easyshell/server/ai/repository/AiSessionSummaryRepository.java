package com.easyshell.server.ai.repository;

import com.easyshell.server.ai.model.entity.AiSessionSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AiSessionSummaryRepository extends JpaRepository<AiSessionSummary, Long> {

    Optional<AiSessionSummary> findBySessionId(String sessionId);

    boolean existsBySessionId(String sessionId);

    List<AiSessionSummary> findByUserIdOrderByCreatedAtDesc(Long userId);
}
