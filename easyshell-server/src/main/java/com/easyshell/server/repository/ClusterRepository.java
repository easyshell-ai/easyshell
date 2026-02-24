package com.easyshell.server.repository;

import com.easyshell.server.model.entity.Cluster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ClusterRepository extends JpaRepository<Cluster, Long> {

    Optional<Cluster> findByName(String name);

    boolean existsByName(String name);
}
