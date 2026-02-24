package com.easyshell.server.ai.agent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentDefinitionRepository extends JpaRepository<AgentDefinition, Long> {

    Optional<AgentDefinition> findByNameAndEnabledTrue(String name);

    List<AgentDefinition> findByModeAndEnabledTrue(String mode);

    List<AgentDefinition> findByEnabledTrue();

    Optional<AgentDefinition> findByName(String name);

    List<AgentDefinition> findAllByOrderByIdAsc();

    boolean existsByName(String name);
}
