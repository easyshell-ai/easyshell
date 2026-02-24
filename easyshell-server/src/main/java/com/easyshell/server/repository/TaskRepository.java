package com.easyshell.server.repository;

import com.easyshell.server.model.entity.Task;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TaskRepository extends JpaRepository<Task, String> {

    List<Task> findByCreatedByOrderByCreatedAtDesc(Long createdBy);

    List<Task> findAllByOrderByCreatedAtDesc();

    List<Task> findByStatus(Integer status);

    List<Task> findTop10ByOrderByCreatedAtDesc();

    Page<Task> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<Task> findByStatusOrderByCreatedAtDesc(Integer status, Pageable pageable);

    long countByCreatedAtAfter(LocalDateTime time);

    long countByStatusAndCreatedAtAfter(Integer status, LocalDateTime time);

    long countByStatus(Integer status);

    List<Task> findByApprovalStatusOrderByCreatedAtDesc(String approvalStatus);
}
