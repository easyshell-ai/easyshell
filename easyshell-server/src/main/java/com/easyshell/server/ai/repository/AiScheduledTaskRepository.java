package com.easyshell.server.ai.repository;

import com.easyshell.server.ai.model.entity.AiScheduledTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AiScheduledTaskRepository extends JpaRepository<AiScheduledTask, Long> {

    List<AiScheduledTask> findByEnabledTrueOrderByCreatedAtDesc();

    List<AiScheduledTask> findByCreatedByOrderByCreatedAtDesc(Long createdBy);

    List<AiScheduledTask> findAllByOrderByCreatedAtDesc();
}
