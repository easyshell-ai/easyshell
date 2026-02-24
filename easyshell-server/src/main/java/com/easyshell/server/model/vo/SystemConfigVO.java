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
public class SystemConfigVO {

    private Long id;
    private String configKey;
    private String configValue;
    private String description;
    private String configGroup;
    private LocalDateTime updatedAt;
}
