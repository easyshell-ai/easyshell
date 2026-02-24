package com.easyshell.server.ai.repository;

import com.easyshell.server.ai.model.entity.AiChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AiChatSessionRepository extends JpaRepository<AiChatSession, String> {

    List<AiChatSession> findByUserIdOrderByUpdatedAtDesc(Long userId);
}
