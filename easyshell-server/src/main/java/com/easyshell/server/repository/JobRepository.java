package com.easyshell.server.repository;

import com.easyshell.server.model.entity.Job;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface JobRepository extends JpaRepository<Job, String> {

    List<Job> findByTaskId(String taskId);

    List<Job> findByAgentId(String agentId);

    List<Job> findByAgentIdAndStatus(String agentId, Integer status);

    List<Job> findByTaskIdAndStatus(String taskId, Integer status);

    long countByTaskIdAndStatus(String taskId, Integer status);

    List<Job> findByAgentIdOrderByCreatedAtDesc(String agentId);

    Page<Job> findByAgentId(String agentId, Pageable pageable);
}
