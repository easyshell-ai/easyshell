package com.easyshell.server.controller;

import com.easyshell.server.common.result.R;
import com.easyshell.server.model.dto.HostProvisionRequest;
import com.easyshell.server.model.vo.HostCredentialVO;
import com.easyshell.server.service.HostProvisionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/host/provision")
@RequiredArgsConstructor
public class HostProvisionController {

    private final HostProvisionService hostProvisionService;

    @PostMapping
    public R<HostCredentialVO> provision(@Valid @RequestBody HostProvisionRequest request) {
        HostCredentialVO vo = hostProvisionService.provision(request);
        // Only start async deploy if deployNow is true (default)
        if (request.getDeployNow() == null || request.getDeployNow()) {
            hostProvisionService.startProvisionAsync(vo.getId());
        }
        return R.ok(vo);
    }

    @GetMapping("/list")
    public R<List<HostCredentialVO>> list() {
        return R.ok(hostProvisionService.listAll());
    }

    @GetMapping("/{id}")
    public R<HostCredentialVO> getById(@PathVariable Long id) {
        return R.ok(hostProvisionService.getById(id));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        hostProvisionService.deleteById(id);
        return R.ok();
    }

    @PostMapping("/{id}/retry")
    public R<HostCredentialVO> retry(@PathVariable Long id) {
        HostCredentialVO vo = hostProvisionService.retry(id);
        hostProvisionService.startRetryAsync(vo.getId());
        return R.ok(vo);
    }

    @PostMapping("/reinstall/{agentId}")
    public R<HostCredentialVO> reinstall(@PathVariable String agentId) {
        HostCredentialVO vo = hostProvisionService.reinstall(agentId);
        hostProvisionService.startReinstallAsync(vo.getId());
        return R.ok(vo);
    }

    @PostMapping("/reinstall/credential/{credentialId}")
    public R<HostCredentialVO> reinstallByCredential(@PathVariable Long credentialId) {
        HostCredentialVO vo = hostProvisionService.reinstallByCredentialId(credentialId);
        hostProvisionService.startProvisionAsync(vo.getId());
        return R.ok(vo);
    }

    @PostMapping("/reinstall/batch")
    public R<List<HostCredentialVO>> batchReinstall(@RequestBody List<String> agentIds) {
        return R.ok(hostProvisionService.batchReinstall(agentIds));
    }

    @PostMapping("/uninstall/{agentId}")
    public R<HostCredentialVO> uninstall(@PathVariable String agentId) {
        HostCredentialVO vo = hostProvisionService.uninstall(agentId);
        hostProvisionService.startUninstallAsync(vo.getId());
        return R.ok(vo);
    }

    @PostMapping("/deploy/batch")
    public R<List<HostCredentialVO>> batchDeploy(@RequestBody List<Long> credentialIds) {
        return R.ok(hostProvisionService.batchDeploy(credentialIds));
    }

    @PostMapping("/import/csv")
    public R<List<HostCredentialVO>> importCsv(@RequestParam("file") MultipartFile file) throws Exception {
        return R.ok(hostProvisionService.importFromCsv(file.getInputStream()));
    }

    @GetMapping("/import/template")
    public ResponseEntity<byte[]> downloadTemplate() {
        byte[] content = hostProvisionService.generateCsvTemplate();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=host_import_template.csv")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(content);
    }

    @GetMapping("/unified-list")
    public R<List<HostCredentialVO>> unifiedList() {
        return R.ok(hostProvisionService.listUnified());
    }
}