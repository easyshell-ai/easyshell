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
@Table(name = "ai_chat_message", indexes = {
        @Index(name = "idx_session_id", columnList = "session_id")
})
public class AiChatMessage extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    /**
     * user, assistant, system, tool
     */
    @Column(nullable = false, length = 16)
    private String role;

    @Lob
    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String content;

    @Column(name = "tool_call_id", length = 128)
    private String toolCallId;

    @Column(name = "tool_name", length = 64)
    private String toolName;

    /**
     * JSON string storing execution process data (plan, thinkingLog, toolCalls).
     * Only populated for assistant messages after streaming completes.
     */
    @Lob
    @Column(name = "process_data", columnDefinition = "MEDIUMTEXT")
    private String processData;
}
