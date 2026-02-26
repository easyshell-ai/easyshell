package com.easyshell.server.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class HostProvisionRequest {

    @NotBlank(message = "IP address is required")
    private String ip;

    @Min(value = 1, message = "SSH port must be between 1 and 65535")
    @Max(value = 65535, message = "SSH port must be between 1 and 65535")
    private Integer sshPort = 22;

    @NotBlank(message = "SSH username is required")
    private String sshUsername;

    private String sshPassword;

    /**
     * Authentication type: password or key (default: password)
     */
    private String authType = "password";

    /**
     * SSH private key content (PEM format), used when authType=key
     */
    private String sshPrivateKey;

    /**
     * Display name / alias for the host
     */
    private String hostName;

    /**
     * Whether to deploy immediately after adding (default: true for backward compatibility)
     */
    private Boolean deployNow = true;
}
