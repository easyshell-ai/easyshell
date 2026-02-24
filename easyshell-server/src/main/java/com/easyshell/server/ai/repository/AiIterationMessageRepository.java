package com.easyshell.server.ai.repository;

import com.easyshell.server.ai.model.entity.AiIterationMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AiIterationMessageRepository extends JpaRepository<AiIterationMessage, Long> {

    List<AiIterationMessage> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    void deleteBySessionId(String sessionId);

    long countBySessionId(String sessionId);
}
