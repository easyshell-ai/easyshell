package com.easyshell.server.ai.model.entity;

import com.easyshell.server.model.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * Phase 3 — Standard Operating Procedure template extracted from successful operations.
 * High-confidence SOPs can directly generate execution plans, skipping LLM planning.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "ai_sop_template", indexes = {
    @Index(name = "idx_sop_category", columnList = "category"),
    @Index(name = "idx_sop_user", columnList = "user_id")
})
public class AiSopTemplate extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SOP title, e.g. "Nginx configuration check and reload" */
    @Column(nullable = false, length = 200)
    private String title;

    /** Natural language description of the SOP */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String description;

    /** JSON-structured steps: {"steps":[{"index":1,"description":"...","agent":"execute","tools":[]},...], "estimated_risk":"LOW"} */
    @Lob
    @Column(name = "steps_json", columnDefinition = "MEDIUMTEXT", nullable = false)
    private String stepsJson;

    /** Trigger keywords/pattern — comma-separated keywords that match this SOP */
    @Column(name = "trigger_pattern", length = 500)
    private String triggerPattern;

    /** Category: nginx, docker, monitoring, security, deployment, etc. */
    @Column(name = "category", length = 50)
    private String category;

    /** Number of successful executions */
    @Column(name = "success_count", nullable = false)
    @Builder.Default
    private Integer successCount = 0;

    /** Total execution attempts */
    @Column(name = "total_count", nullable = false)
    @Builder.Default
    private Integer totalCount = 0;

    /** Confidence score: successCount / totalCount */
    @Column(name = "confidence", nullable = false)
    @Builder.Default
    private Double confidence = 0.0;

    /** Comma-separated session IDs that contributed to this SOP */
    @Column(name = "source_session_ids", length = 2000)
    private String sourceSessionIds;

    /** null = global SOP, non-null = user-private SOP */
    @Column(name = "user_id")
    private Long userId;

    /** Whether this SOP is active */
    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    /** Vector store document ID for similarity search */
    @Column(name = "embedding_id", length = 64)
    private String embeddingId;
}
