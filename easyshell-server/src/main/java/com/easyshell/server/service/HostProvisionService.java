package com.easyshell.server.service;

import com.easyshell.server.model.dto.HostProvisionRequest;
import com.easyshell.server.model.vo.HostCredentialVO;

import java.util.List;
import java.io.InputStream;

public interface HostProvisionService {
    HostCredentialVO provision(HostProvisionRequest request);
    List<HostCredentialVO> listAll();
    HostCredentialVO getById(Long id);
    void deleteById(Long id);
    HostCredentialVO retry(Long id);
    void startProvisionAsync(Long credentialId);
    void startRetryAsync(Long credentialId);
    HostCredentialVO reinstall(String agentId);
    void startReinstallAsync(Long credentialId);
    List<HostCredentialVO> batchReinstall(List<String> agentIds);
    HostCredentialVO uninstall(String agentId);
    void startUninstallAsync(Long credentialId);

    // New methods for host deployment enhancement
    List<HostCredentialVO> listUnified();
    List<HostCredentialVO> batchDeploy(List<Long> credentialIds);
    List<HostCredentialVO> importFromCsv(InputStream csvInputStream);
    byte[] generateCsvTemplate();
}
