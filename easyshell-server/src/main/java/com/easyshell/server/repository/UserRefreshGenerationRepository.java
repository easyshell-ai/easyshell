package com.easyshell.server.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Objects;

/**
 * Storage only; not connected to token issuance or validation.
 * Call within the transaction that owns the sys_user row lock. Each operation
 * also obtains that lock on the JDBC connection before touching the generation.
 * ID reuse and restoring an older database remain blockers to secure use.
 */
@Repository
public class UserRefreshGenerationRepository {
    private final JdbcTemplate jdbc;

    public UserRefreshGenerationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Initialize a new user once; an existing generation is never reset. */
    public long initialize(long userId) {
        lockUser(userId);
        jdbc.update("INSERT INTO user_refresh_generation (user_id, generation) VALUES (?, 0)", userId);
        return 0L;
    }

    /** Missing or invalid state is an error, never an implicit initial generation. */
    public long read(long userId) {
        lockUser(userId);
        return readLocked(userId);
    }

    public long increment(long userId) {
        lockUser(userId);
        long current = readLocked(userId);
        if (current == Long.MAX_VALUE) {
            throw new IllegalStateException("Refresh generation exhausted");
        }
        if (jdbc.update("UPDATE user_refresh_generation SET generation = ? WHERE user_id = ? AND generation = ?",
                current + 1, userId, current) != 1) {
            throw new IllegalStateException("Refresh generation changed unexpectedly");
        }
        return current + 1;
    }

    private void lockUser(long userId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(Objects.requireNonNull(jdbc.getDataSource()))) {
            throw new IllegalStateException("An existing database transaction is required");
        }
        List<Long> users = jdbc.query("SELECT id FROM sys_user WHERE id = ? FOR UPDATE",
                (rs, row) -> rs.getLong(1), userId);
        if (users.size() != 1) {
            throw new IllegalStateException("User does not exist");
        }
    }

    private long readLocked(long userId) {
        // A locking current read is required under MySQL REPEATABLE READ: a plain
        // SELECT can observe an earlier transaction snapshot even after lockUser.
        List<Long> values = jdbc.query("SELECT generation FROM user_refresh_generation WHERE user_id = ? FOR UPDATE",
                (rs, row) -> (Long) rs.getObject(1, Long.class), userId);
        if (values.size() != 1 || values.get(0) == null || values.get(0) < 0) {
            throw new IllegalStateException("Invalid or missing refresh generation");
        }
        return values.get(0);
    }
}
