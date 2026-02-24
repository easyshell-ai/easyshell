package com.easyshell.server.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "sys_user")
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(nullable = false, length = 128)
    private String password;

    @Column(length = 128)
    private String email;

    @Column(nullable = false, length = 32)
    private String role = "viewer";

    @Column(nullable = false)
    private Integer status = 1;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;
}
