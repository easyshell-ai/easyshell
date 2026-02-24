package com.easyshell.server.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterVO {

    private Long id;
    private String name;
    private String description;
    private int agentCount;
    private Long createdBy;
    private LocalDateTime createdAt;
}
