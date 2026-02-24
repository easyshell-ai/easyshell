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
@Table(name = "ai_iteration_message", indexes = {
        @Index(name = "idx_iter_msg_session", columnList = "session_id"),
        @Index(name = "idx_iter_msg_agent", columnList = "agent_name")
})
public class AiIterationMessage extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(nullable = false)
    private Integer iteration;

    @Column(nullable = false, length = 20)
    private String role;

    @Lob
    @Column(columnDefinition = "MEDIUMTEXT")
    private String content;

    @Column(name = "tool_name", length = 64)
    private String toolName;

    @Lob
    @Column(name = "tool_args", columnDefinition = "MEDIUMTEXT")
    private String toolArgs;

    @Lob
    @Column(name = "tool_result", columnDefinition = "MEDIUMTEXT")
    private String toolResult;

    @Column(name = "agent_name", length = 50)
    private String agentName;

    @Column(name = "duration_ms")
    private Long durationMs;
}
