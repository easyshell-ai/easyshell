package com.easyshell.server.ai.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiChatSessionVO {
    private String id;
    private String title;
    private String provider;
    private Integer messageCount;
    private String createdAt;
    private String updatedAt;
}
