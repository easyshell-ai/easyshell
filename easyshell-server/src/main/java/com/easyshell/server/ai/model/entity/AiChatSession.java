package com.easyshell.server.ai.model.entity;

import com.easyshell.server.model.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "ai_chat_session")
public class AiChatSession extends BaseEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 128)
    private String title;

    @Column(length = 32)
    private String provider;

    @Column(name = "message_count", nullable = false)
    private Integer messageCount = 0;

    @Column(name = "parent_session_id", length = 64)
    private String parentSessionId;

    @Column(name = "agent_name", length = 50)
    private String agentName;

    @Column(name = "summary_generated")
    private Boolean summaryGenerated = false;
}
