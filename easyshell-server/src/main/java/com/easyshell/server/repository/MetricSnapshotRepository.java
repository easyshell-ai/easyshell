package com.easyshell.server.repository;

import com.easyshell.server.model.entity.MetricSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MetricSnapshotRepository extends JpaRepository<MetricSnapshot, Long> {

    List<MetricSnapshot> findByAgentIdAndRecordedAtBetweenOrderByRecordedAtAsc(
        String agentId, LocalDateTime start, LocalDateTime end);

    @Modifying
    @Query("DELETE FROM MetricSnapshot m WHERE m.recordedAt < :threshold")
    int deleteByRecordedAtBefore(@Param("threshold") LocalDateTime threshold);

    @Modifying
    @Query("DELETE FROM MetricSnapshot m WHERE m.agentId = :agentId")
    int deleteByAgentId(@Param("agentId") String agentId);
}
