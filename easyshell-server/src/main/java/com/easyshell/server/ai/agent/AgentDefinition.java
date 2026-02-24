package com.easyshell.server.ai.agent;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@Entity
@Table(name = "agent_definitions")
public class AgentDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String name;

    @Column(length = 100)
    private String displayName;

    @Column(nullable = false, length = 20)
    private String mode;  // "primary" | "subagent"

    @Column(columnDefinition = "TEXT", nullable = false)
    private String permissions;  // JSON array: [{"tool":"*","action":"allow"}]

    @Column(length = 50)
    private String modelProvider;

    @Column(length = 100)
    private String modelName;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String systemPrompt;

    @Column(nullable = false)
    private Integer maxIterations = 5;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(columnDefinition = "TEXT")
    private String description;
}
