package com.easyshell.server.ai.repository;

import com.easyshell.server.ai.model.entity.HostSoftwareInventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HostSoftwareInventoryRepository extends JpaRepository<HostSoftwareInventory, Long> {

    List<HostSoftwareInventory> findByAgentIdOrderByDetectedAtDesc(String agentId);

    List<HostSoftwareInventory> findByAgentIdAndIsDockerContainerFalseOrderByDetectedAtDesc(String agentId);

    List<HostSoftwareInventory> findByAgentIdAndIsDockerContainerTrueOrderByDetectedAtDesc(String agentId);

    void deleteByAgentId(String agentId);
}
