package com.easyshell.server.ai.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiChatMessageVO {
    private Long id;
    private String sessionId;
    private String role;
    private String content;
    private String toolName;
    private String processData;
    private String createdAt;
}
