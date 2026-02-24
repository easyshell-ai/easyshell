package com.easyshell.server.repository;

import com.easyshell.server.model.entity.Script;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ScriptRepository extends JpaRepository<Script, Long> {

    List<Script> findByIsTemplateTrue();

    List<Script> findByIsTemplateFalse();

    Optional<Script> findByName(String name);

    boolean existsByName(String name);
}
