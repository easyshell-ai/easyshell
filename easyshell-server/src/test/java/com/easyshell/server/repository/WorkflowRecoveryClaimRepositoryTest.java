package com.easyshell.server.repository;

import com.easyshell.server.repository.WorkflowRecoveryClaimRepository.Claim;
import com.easyshell.server.repository.WorkflowRecoveryClaimRepository.Member;
import com.easyshell.server.repository.WorkflowRecoveryClaimRepository.State;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real Flyway + isolated H2 only; no Spring, executor dispatch or external side effects. */
class WorkflowRecoveryClaimRepositoryTest {
    private final WorkflowRecoveryClaimRepository repository = new WorkflowRecoveryClaimRepository(true);
    private final Member first = new Member("node-run-a", "node-a", 1);
    private final Member second = new Member("node-run-b", "node-b", 2);
    private String url;
    private Connection keeper;

    @BeforeEach
    void migrate() throws Exception {
        url = "jdbc:h2:mem:recovery_claim_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000";
        keeper = DriverManager.getConnection(url, "sa", "");
        Flyway flyway = Flyway.configure().dataSource(url, "sa", "")
                .locations("classpath:db/migration").cleanDisabled(true).load();
        flyway.migrate();
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1.2.20");
        tx(connection -> {
            repository.provision(connection, "run");
            return null;
        });
    }

    @AfterEach
    void close() throws Exception {
        if (keeper != null) keeper.close();
    }

    @Test
    void activeLookupAndMemberLookupPreserveClosedTombstones() throws Exception {
        tx(connection -> {
            repository.provisionIfAbsent(connection, "run");
            assertThat(repository.findActive(connection, "run")).isEmpty();
            assertThat(repository.findByMember(connection, first)).isEmpty();
            return null;
        });
        Claim claim = create(List.of(first));
        tx(connection -> {
            assertThat(repository.findActive(connection, "run")).contains(claim);
            var found = repository.findByMember(connection, first).orElseThrow();
            assertThat(found.claim()).isEqualTo(claim);
            assertThat(found.member()).isEqualTo(first);
            assertThat(found.state()).isEqualTo(State.CLAIMED);
            assertThat(repository.closeProvenUndelivered(connection, claim, first)).isTrue();
            assertThat(repository.finishBatch(connection, claim)).isTrue();
            return null;
        });
        tx(connection -> {
            assertThat(repository.findActive(connection, "run")).isEmpty();
            assertThat(repository.findByMember(connection, first).orElseThrow().state()).isEqualTo(State.CANCELLED);
            return null;
        });
    }

    @Test
    void disabledByDefaultAndExplicitTransactionRequired() throws Exception {
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            assertThatThrownBy(() -> new WorkflowRecoveryClaimRepository().provision(connection, "other"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("disabled");
            assertThatThrownBy(() -> repository.provision(connection, "other"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("transaction");
        }
    }

    @Test
    void nodeKeysMustFitThePersistedWorkflowIdentity() {
        assertThatThrownBy(() -> new Member("node", "n".repeat(65), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentClaimsAndConcurrentEntryHaveOneWinner() throws Exception {
        List<Claim> winners = compete(8, () -> tx(connection -> repository.claim(connection, "run", "owner",
                Duration.ofHours(1), List.of(first, second)).orElse(null))).stream()
                .filter(claim -> claim != null).toList();
        assertThat(winners).hasSize(1);
        Claim claim = winners.get(0);
        assertThat(count("workflow_recovery_batch")).isEqualTo(1);
        assertThat(count("workflow_recovery_member")).isEqualTo(2);
        List<Boolean> entered = compete(8, () -> tx(connection -> repository.enter(connection, claim, first)));
        assertThat(entered.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        assertThat(state(first)).isEqualTo("ENTERED");
        assertThat(state(second)).isEqualTo("CLAIMED");
    }

    @Test
    void cancellationAndEntryRaceCannotBothWin() throws Exception {
        Claim claim = create(List.of(first));
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> entered = executor.submit(() -> {
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return tx(connection -> repository.enter(connection, claim, first));
            });
            Future<Integer> cancelled = executor.submit(() -> {
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return tx(connection -> repository.cancelUnentered(connection, claim, List.of(first)));
            });
            start.countDown();
            boolean didEnter = entered.get(20, TimeUnit.SECONDS);
            int didCancel = cancelled.get(20, TimeUnit.SECONDS);
            assertThat((didEnter ? 1 : 0) + didCancel).isEqualTo(1);
            assertThat(state(first)).isEqualTo(didEnter ? "ENTERED" : "CANCELLED");
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void roundOverflowFailsWithoutWrappingOrCreatingRows() throws Exception {
        tx(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE workflow_recovery_run SET last_round = ? WHERE run_id = 'run'")) {
                statement.setLong(1, Long.MAX_VALUE);
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }
            return null;
        });
        assertThatThrownBy(() -> create(List.of(first))).isInstanceOf(ArithmeticException.class);
        assertThat(count("workflow_recovery_batch")).isZero();
        assertThat(count("workflow_recovery_member")).isZero();
    }

    @Test
    void ownerTokenAndExactMemberAreAllRequired() throws Exception {
        Claim claim = create(List.of(first));
        Claim wrongOwner = new Claim("run", claim.round(), "other-owner", claim.token());
        Claim wrongToken = new Claim("run", claim.round(), claim.owner(), UUID.randomUUID().toString());
        assertThat(this.<Boolean>tx(connection -> repository.enter(connection, wrongOwner, first))).isFalse();
        assertThat(this.<Boolean>tx(connection -> repository.enter(connection, wrongToken, first))).isFalse();
        assertThat(this.<Boolean>tx(connection -> repository.enter(connection, claim,
                new Member(first.nodeRunId(), first.nodeKey(), 2)))).isFalse();
        assertThat(this.<Boolean>tx(connection -> repository.enter(connection, claim,
                new Member(first.nodeRunId(), "other-key", first.attempt())))).isFalse();
        assertThat(state(first)).isEqualTo("CLAIMED");
        assertThat(this.<Boolean>tx(connection -> repository.enter(connection, claim, first))).isTrue();
        assertThat(this.<Boolean>tx(connection -> repository.complete(connection, wrongToken, first, State.SUCCEEDED))).isFalse();
        assertThat(this.<Boolean>tx(connection -> repository.complete(connection, claim, first, State.SUCCEEDED))).isTrue();
        assertThat(this.<Boolean>tx(connection -> repository.complete(connection, claim, first, State.FAILED))).isFalse();
        assertThat(state(first)).isEqualTo("SUCCEEDED");
    }

    @Test
    void expiredClaimsRejectNormalWritesAndUnknownCannotBeReclaimed() throws Exception {
        Claim claim = create(List.of(first, second));
        assertThat(this.<Boolean>tx(connection -> repository.enter(connection, claim, first))).isTrue();
        expireFixture(claim);
        assertThat(this.<Boolean>tx(connection -> repository.enter(connection, claim, second))).isFalse();
        assertThat(this.<Boolean>tx(connection -> repository.verifyEntered(connection, claim, first))).isFalse();
        assertThat(this.<Integer>tx(connection -> repository.cancelUnentered(connection, claim, List.of(second)))).isZero();
        Claim wrongToken = new Claim("run", claim.round(), claim.owner(), UUID.randomUUID().toString());
        assertThat(this.<Boolean>tx(connection -> repository.expire(connection, wrongToken))).isFalse();
        assertThat(this.<Boolean>tx(connection -> repository.expire(connection, claim))).isTrue();
        assertThat(state(first)).isEqualTo("UNCERTAIN");
        assertThat(state(second)).isEqualTo("CANCELLED");
        assertThat(this.<Boolean>tx(connection -> repository.finishBatch(connection, claim))).isFalse();
        assertThat(this.<java.util.Optional<Claim>>tx(connection -> repository.claim(connection, "run", "replacement", Duration.ofHours(1),
                List.of(new Member("new-node", "node", 1))))).isEmpty();
        assertThat(this.<Boolean>tx(connection -> repository.enter(connection, claim, first))).isFalse();
        assertThat(this.<Boolean>tx(connection -> repository.complete(connection, claim, first, State.SUCCEEDED))).isFalse();
    }

    @Test
    void explicitUnknownAlsoBlocksBatchAndDoesNotReturnToClaimed() throws Exception {
        Claim claim = create(List.of(first));
        assertThat(this.<Boolean>tx(connection -> repository.expire(connection, claim))).isFalse();
        assertThat(this.<Boolean>tx(connection -> repository.enter(connection, claim, first))).isTrue();
        assertThat(this.<Boolean>tx(connection -> repository.complete(connection, claim, first, State.UNCERTAIN))).isTrue();
        assertThat(this.<Boolean>tx(connection -> repository.enter(connection, claim, first))).isFalse();
        assertThat(this.<Integer>tx(connection -> repository.cancelUnentered(connection, claim, List.of(first)))).isZero();
        assertThat(this.<Boolean>tx(connection -> repository.finishBatch(connection, claim))).isFalse();
        assertThat(this.<java.util.Optional<Claim>>tx(connection -> repository.claim(connection, "run", "new-owner", Duration.ofHours(1),
                List.of(second)))).isEmpty();
    }

    @Test
    void cancellationTouchesOnlyNamedUnenteredAttempts() throws Exception {
        Member untouched = new Member("node-run-c", "node-c", 1);
        Claim claim = create(List.of(first, second, untouched));
        assertThat(this.<Boolean>tx(connection -> repository.enter(connection, claim, first))).isTrue();
        assertThat(this.<Integer>tx(connection -> repository.cancelUnentered(connection, claim, List.of(first,
                new Member(second.nodeRunId(), second.nodeKey(), 1), second, second)))).isEqualTo(1);
        assertThat(state(first)).isEqualTo("ENTERED");
        assertThat(state(second)).isEqualTo("CANCELLED");
        assertThat(state(untouched)).isEqualTo("CLAIMED");
        assertThat(this.<Boolean>tx(connection -> repository.finishBatch(connection, claim))).isFalse();
    }

    @Test
    void roundsIncreaseAndCommittedAttemptTombstonesCannotBeReused() throws Exception {
        Claim old = create(List.of(first));
        assertThat(this.<Integer>tx(connection -> repository.cancelUnentered(connection, old, List.of(first)))).isEqualTo(1);
        assertThat(this.<Boolean>tx(connection -> repository.finishBatch(connection, old))).isTrue();
        assertThat(this.<Boolean>tx(connection -> repository.finishBatch(connection, old))).isFalse();
        // The new batch header is inserted before this old member violates permanent uniqueness.
        assertThatThrownBy(() -> create(List.of(second, first))).isInstanceOf(SQLException.class);
        assertThat(count("workflow_recovery_batch")).isEqualTo(1);
        assertThat(count("workflow_recovery_member")).isEqualTo(1);
        Claim current = create(List.of(second));
        assertThat(current.round()).isEqualTo(old.round() + 1);
        assertThat(current.token()).isNotEqualTo(old.token());
        assertThat(this.<Boolean>tx(connection -> repository.enter(connection, old, first))).isFalse();
        assertThat(this.<Integer>tx(connection -> repository.cancelUnentered(connection, old, List.of(second)))).isZero();
        assertThat(this.<Boolean>tx(connection -> repository.expire(connection, old))).isFalse();
        assertThat(state(first)).isEqualTo("CANCELLED");
        assertThat(state(second)).isEqualTo("CLAIMED");
    }

    @Test
    void rollbackRestoresAllocationAndEntryWithoutPublishingIdentity() throws Exception {
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.setAutoCommit(false);
            repository.claim(connection, "run", "owner", Duration.ofHours(1), List.of(first)).orElseThrow();
            connection.rollback();
        }
        assertThat(count("workflow_recovery_batch")).isZero();
        assertThat(count("workflow_recovery_member")).isZero();
        Claim claim = create(List.of(first));
        assertThat(claim.round()).isEqualTo(1);
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.setAutoCommit(false);
            assertThat(repository.enter(connection, claim, first)).isTrue();
            connection.rollback();
        }
        assertThat(state(first)).isEqualTo("CLAIMED");
        assertThat(this.<Boolean>tx(connection -> repository.enter(connection, claim, first))).isTrue();
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.setAutoCommit(false);
            assertThat(repository.complete(connection, claim, first, State.SUCCEEDED)).isTrue();
            assertThat(repository.finishBatch(connection, claim)).isTrue();
            connection.rollback();
        }
        assertThat(state(first)).isEqualTo("ENTERED");
        assertThat(this.<java.util.Optional<Claim>>tx(connection -> repository.claim(connection, "run", "owner", Duration.ofHours(1),
                List.of(second)))).isEmpty();
    }

    @Test
    void expiredUnenteredBatchLeavesTombstonesAndCanCloseWithoutReclaim() throws Exception {
        Claim claim = create(List.of(first));
        expireFixture(claim);
        assertThat(this.<Boolean>tx(connection -> repository.expire(connection, claim))).isTrue();
        assertThat(state(first)).isEqualTo("CANCELLED");
        assertThat(this.<Boolean>tx(connection -> repository.finishBatch(connection, claim))).isTrue();
        assertThatThrownBy(() -> create(List.of(first))).isInstanceOf(SQLException.class);
        assertThat(create(List.of(second)).round()).isEqualTo(claim.round() + 1);
    }

    private Claim create(List<Member> members) throws Exception {
        return tx(connection -> repository.claim(connection, "run", "owner", Duration.ofHours(1), members)
                .orElseThrow());
    }

    /** Deterministic test fixture only; production exposes no clock or lease mutation API. */
    private void expireFixture(Claim claim) throws Exception {
        tx(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE workflow_recovery_batch SET expires_at = TIMESTAMP '2000-01-01 00:00:00' "
                            + "WHERE run_id = ? AND claim_round = ?")) {
                statement.setString(1, claim.runId());
                statement.setLong(2, claim.round());
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }
            return null;
        });
    }

    private String state(Member member) throws SQLException {
        try (PreparedStatement statement = keeper.prepareStatement(
                "SELECT claim_state FROM workflow_recovery_member WHERE node_run_id = ? AND attempt = ?")) {
            statement.setString(1, member.nodeRunId());
            statement.setInt(2, member.attempt());
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                String state = rows.getString(1);
                assertThat(rows.next()).isFalse();
                return state;
            }
        }
    }

    private long count(String table) throws SQLException {
        // Table names are compile-time test constants, never application input.
        try (PreparedStatement statement = keeper.prepareStatement("SELECT COUNT(*) FROM " + table);
             ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private <T> T tx(SqlWork<T> work) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (Exception | AssertionError failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static <T> List<T> compete(int threads, Callable<T> work) throws Exception {
        var executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timed out");
                    return work.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            return results;
        } finally {
            start.countDown();
            for (Future<T> future : futures) future.cancel(true);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws Exception;
    }
}
