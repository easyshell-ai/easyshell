package com.easyshell.server.repository;

import com.easyshell.server.model.entity.HostCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HostCredentialRepository extends JpaRepository<HostCredential, Long> {
    Optional<HostCredential> findByIp(String ip);
    List<HostCredential> findAllByOrderByCreatedAtDesc();
    List<HostCredential> findAllByProvisionStatusIn(List<String> statuses);
    List<HostCredential> findAllByIdIn(List<Long> ids);
}
