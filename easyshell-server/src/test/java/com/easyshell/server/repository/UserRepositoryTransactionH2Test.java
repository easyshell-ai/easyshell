package com.easyshell.server.repository;

import com.easyshell.server.model.entity.User;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** H2-only evidence; H2 locking and isolation do not establish MySQL semantics. */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:user_repository_tx_test;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=500",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = UserRepositoryTransactionH2Test.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserRepositoryTransactionH2Test {
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = User.class)
    @EnableJpaRepositories(basePackageClasses = UserRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = UserRepository.class))
    static class Config { }

    @Autowired UserRepository users;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private long userId;
    private String username;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.executeWithoutResult(status -> {
            User user = new User();
            username = "tx-" + UUID.randomUUID();
            user.setUsername(username);
            user.setPassword("test-only");
            user.setEmail("before@example.test");
            userId = users.saveAndFlush(user).getId();
        });
    }

    @Test
    void repositoryLockAndJdbcShareConnectionAndRollbackTogether() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(status -> {
                User locked = users.findByIdForUpdate(userId).orElseThrow();
                assertEquals(username, locked.getUsername());
                Integer jpaSession = entityManager.unwrap(Session.class).doReturningWork(connection -> {
                    try (var statement = connection.createStatement();
                         var result = statement.executeQuery("SELECT SESSION_ID()")) {
                        result.next();
                        return result.getInt(1);
                    }
                });
                assertEquals(jpaSession, jdbc.queryForObject("SELECT SESSION_ID()", Integer.class));
                // The repository method has returned, but the outer transaction is still open.
                var competing = executor.submit(() -> assertThrows(RuntimeException.class, () ->
                        tx.executeWithoutResult(other -> jdbc.update(
                                "UPDATE sys_user SET email = ? WHERE id = ?", "competitor@example.test", userId))));
                try {
                    Throwable failure = competing.get(10, TimeUnit.SECONDS);
                    assertTrue(hasH2LockTimeout(failure), "Expected H2 lock timeout, got: " + failure);
                } catch (Exception e) {
                    throw new AssertionError("Competing transaction did not finish", e);
                }
                jdbc.update("UPDATE sys_user SET email = ? WHERE id = ?", "jdbc@example.test", userId);
                assertEquals("jdbc@example.test", jdbc.queryForObject(
                        "SELECT email FROM sys_user WHERE id = ?", String.class, userId));
                status.setRollbackOnly();
            });
            assertEquals("before@example.test", jdbc.queryForObject(
                    "SELECT email FROM sys_user WHERE id = ?", String.class, userId));
            assertEquals(1, jdbc.update("UPDATE sys_user SET email = ? WHERE id = ?", "after@example.test", userId));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void preloadedEntityRemainsManagedWhileJdbcReadsCommittedDatabaseValue() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(status -> {
                User preloaded = users.findById(userId).orElseThrow();
                assertEquals("before@example.test", preloaded.getEmail());
                try {
                    executor.submit(() -> tx.executeWithoutResult(other -> jdbc.update(
                            "UPDATE sys_user SET email = ? WHERE id = ?", "committed@example.test", userId)))
                            .get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new AssertionError("Concurrent update did not commit", e);
                }
                User locked = users.findByUsernameForUpdate(username).orElseThrow();
                assertSame(preloaded, locked);
                // A lock query is not a refresh of an already managed entity.
                assertEquals("before@example.test", locked.getEmail());
                assertEquals("committed@example.test", jdbc.queryForObject(
                        "SELECT email FROM sys_user WHERE id = ?", String.class, userId));
            });
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static boolean hasH2LockTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == 50200) {
                return true;
            }
        }
        return false;
    }
}
