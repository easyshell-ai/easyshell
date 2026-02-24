package com.easyshell.server.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobResultRequest {

    private String jobId;
    private String agentId;
    private Integer status;
    private Integer exitCode;
    private String output;
}
