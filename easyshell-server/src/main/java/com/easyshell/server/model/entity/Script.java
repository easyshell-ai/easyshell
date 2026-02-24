package com.easyshell.server.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "script")
public class Script extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 512)
    private String description;

    @Lob
    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String content;

    @Column(name = "script_type", nullable = false, length = 32)
    private String scriptType = "shell";

    @Column(name = "is_public", nullable = false)
    private Boolean isPublic = true;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(nullable = false)
    private Integer version = 1;

    @Column(name = "is_template", nullable = false)
    private Boolean isTemplate = false;
}
