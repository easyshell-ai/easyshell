package com.easyshell.server.repository;

import com.easyshell.server.model.entity.Script;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

@Repository
public interface ScriptRepository extends JpaRepository<Script, Long> {

    List<Script> findByIsTemplateTrue();

    List<Script> findByIsTemplateFalse();

    Optional<Script> findByName(String name);

    boolean existsByName(String name);

    @Query("select count(s) from Script s where s.isPublic = true or s.createdBy = :actor or :admin = true")
    long countReadable(@Param("actor") Long actor, @Param("admin") boolean admin);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Script s where s.id = :id")
    Optional<Script> findByIdForSnapshot(@Param("id") Long id);
}
