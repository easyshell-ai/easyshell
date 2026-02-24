package com.easyshell.server.ai.model.entity;

import com.easyshell.server.model.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Phase 3 — Stores summarized session data for long-term memory retrieval.
 * Each completed AI chat session gets one summary record + a vector embedding.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "ai_session_summary", indexes = {
    @Index(name = "idx_session_summary_user", columnList = "user_id"),
    @Index(name = "idx_session_summary_session", columnList = "session_id")
})
public class AiSessionSummary extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, unique = true, length = 64)
    private String sessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** LLM-generated natural language summary of the session */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String summary;

    /** JSON array of key operations performed (e.g. ["deployed nginx", "restarted mysql"]) */
    @Column(name = "key_operations", columnDefinition = "TEXT")
    private String keyOperations;

    /** Comma-separated hostnames involved in the session */
    @Column(name = "hosts_involved", length = 1000)
    private String hostsInvolved;

    /** Comma-separated service names involved */
    @Column(name = "services_involved", length = 500)
    private String servicesInvolved;

    /** Session outcome: SUCCESS, PARTIAL, FAILED */
    @Column(name = "outcome", length = 20)
    private String outcome;

    /** Comma-separated tags for categorization */
    @Column(name = "tags", length = 500)
    private String tags;

    /** Vector store document ID for similarity search */
    @Column(name = "embedding_id", length = 64)
    private String embeddingId;
}
