package com.easyshell.server.ai.repository;

import com.easyshell.server.ai.model.entity.AiInspectReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AiInspectReportRepository extends JpaRepository<AiInspectReport, Long> {

    List<AiInspectReport> findByScheduledTaskIdOrderByCreatedAtDesc(Long scheduledTaskId);

    Page<AiInspectReport> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
