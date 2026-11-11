package com.easyshell.server.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Unwired JDBC foundation. Callers own the transaction and must roll back on any exception. */
public final class WorkflowRecoveryClaimRepository {
    private final boolean enabled;

    public WorkflowRecoveryClaimRepository() {
        this(false);
    }

    public WorkflowRecoveryClaimRepository(boolean enabled) {
        this.enabled = enabled;
    }

    public record Member(String nodeRunId, String nodeKey, int attempt) {
        public Member {
            text(nodeRunId, 64);
            text(nodeKey, 64);
            if (attempt < 1) throw new IllegalArgumentException("attempt must be positive");
        }
    }

    public record Claim(String runId, long round, String owner, String token) {
        public Claim {
            text(runId, 64);
            text(owner, 128);
            text(token, 36);
            if (round < 1) throw new IllegalArgumentException("round must be positive");
        }
    }

    public enum State { CLAIMED, ENTERED, UNCERTAIN, SUCCEEDED, FAILED, CANCELLED }

    public record MemberClaim(Claim claim, Member member, State state) { }

    /** Bounded discovery hints only. Caller must lock workflow_run before rechecking any candidate. */
    public List<String> findExpiredRunIds(Connection connection, String afterRunId, int limit) throws SQLException {
        transaction(connection);
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("candidate limit must be 1..100");
        if (afterRunId != null) text(afterRunId, 64);
        String sql = "SELECT r.run_id FROM workflow_recovery_run r JOIN workflow_recovery_batch b "
                + "ON b.run_id = r.run_id AND b.claim_round = r.active_round "
                + "WHERE b.closed_at IS NULL AND b.expires_at <= CURRENT_TIMESTAMP(6) "
                + "AND (EXISTS (SELECT 1 FROM workflow_recovery_member m WHERE m.run_id = b.run_id "
                + "AND m.claim_round = b.claim_round AND m.claim_state IN ('CLAIMED', 'ENTERED')) "
                + "OR NOT EXISTS (SELECT 1 FROM workflow_recovery_member u WHERE u.run_id = b.run_id "
                + "AND u.claim_round = b.claim_round AND u.claim_state = 'UNCERTAIN')) "
                + (afterRunId == null ? "" : "AND r.run_id > ? ") + "ORDER BY r.run_id LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            if (afterRunId != null) statement.setString(index++, afterRunId);
            statement.setInt(index, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<String> ids = new ArrayList<>();
                while (rows.next()) ids.add(rows.getString(1));
                return List.copyOf(ids);
            }
        }
    }

    /** Current, locked snapshot of an expired active batch; empty means no eligible members/batch. */
    public List<MemberClaim> findExpiredMembers(Connection connection, Claim claim) throws SQLException {
        transaction(connection);
        if (!owns(connection, claim, true)) return List.of();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT node_run_id, node_key, attempt, claim_state FROM workflow_recovery_member "
                        + "WHERE run_id = ? AND claim_round = ? ORDER BY node_run_id, attempt FOR UPDATE")) {
            statement.setString(1, claim.runId());
            statement.setLong(2, claim.round());
            try (ResultSet rows = statement.executeQuery()) {
                List<MemberClaim> members = new ArrayList<>();
                while (rows.next()) members.add(new MemberClaim(claim,
                        new Member(rows.getString(1), rows.getString(2), rows.getInt(3)),
                        State.valueOf(rows.getString(4))));
                return List.copyOf(members);
            }
        }
    }

    /** Caller must first lock the business workflow_run; never suppress a duplicate insert error. */
    public void provisionIfAbsent(Connection connection, String runId) throws SQLException {
        transaction(connection);
        text(runId, 64);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT run_id FROM workflow_recovery_run WHERE run_id = ? FOR UPDATE")) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) return;
            }
        }
        provision(connection, runId);
    }

    /** Current read, locking the permanent slot before its batch. Does not confer dispatch authority. */
    public Optional<Claim> findActive(Connection connection, String runId) throws SQLException {
        transaction(connection);
        text(runId, 64);
        long round;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT active_round FROM workflow_recovery_run WHERE run_id = ? FOR UPDATE")) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.getObject(1) == null) return Optional.empty();
                round = rows.getLong(1);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT owner_id, fencing_token FROM workflow_recovery_batch "
                        + "WHERE run_id = ? AND claim_round = ? AND closed_at IS NULL FOR UPDATE")) {
            statement.setString(1, runId);
            statement.setLong(2, round);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(new Claim(runId, round, rows.getString(1), rows.getString(2)))
                        : Optional.empty();
            }
        }
    }

    /** Observational snapshot only, including closed tombstones; all writes revalidate identity under locks. */
    public Optional<MemberClaim> findByMember(Connection connection, Member member) throws SQLException {
        transaction(connection);
        Objects.requireNonNull(member);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT m.run_id, m.claim_round, b.owner_id, b.fencing_token, m.claim_state "
                        + "FROM workflow_recovery_member m JOIN workflow_recovery_batch b "
                        + "ON b.run_id = m.run_id AND b.claim_round = m.claim_round "
                        + "WHERE m.node_run_id = ? AND m.attempt = ? AND m.node_key = ?")) {
            statement.setString(1, member.nodeRunId());
            statement.setInt(2, member.attempt());
            statement.setString(3, member.nodeKey());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                Claim claim = new Claim(rows.getString(1), rows.getLong(2), rows.getString(3), rows.getString(4));
                return Optional.of(new MemberClaim(claim, member, State.valueOf(rows.getString(5))));
            }
        }
    }

    /** Provision in a transaction before claiming; duplicate-key errors require rollback, not suppression. */
    public void provision(Connection connection, String runId) throws SQLException {
        transaction(connection);
        text(runId, 64);
        update(connection, "INSERT INTO workflow_recovery_run (run_id) VALUES (?)", runId);
    }

    /** Returned identity is usable only after the caller successfully commits. */
    public Optional<Claim> claim(Connection connection, String runId, String owner,
                                 Duration lease, List<Member> members) throws SQLException {
        transaction(connection);
        text(runId, 64);
        text(owner, 128);
        if (lease == null || lease.isNegative() || lease.isZero()
                || lease.compareTo(Duration.ofDays(1)) > 0 || lease.toMillis() < 1) {
            throw new IllegalArgumentException("lease must be between 1ms and one day");
        }
        members = List.copyOf(members);
        if (members.isEmpty()) throw new IllegalArgumentException("empty batch");
        var identities = new HashSet<List<Object>>();
        for (Member member : members) {
            if (!identities.add(List.of(member.nodeRunId(), member.attempt()))) {
                throw new IllegalArgumentException("duplicate member");
            }
        }
        long previous;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT last_round, active_round FROM workflow_recovery_run WHERE run_id = ? FOR UPDATE")) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("run must be provisioned");
                previous = rows.getLong(1);
                if (rows.getObject(2) != null) return Optional.empty();
            }
        }
        long round = Math.addExact(previous, 1);
        Claim claim = new Claim(runId, round, owner, UUID.randomUUID().toString());
        Instant expires = databaseNow(connection).plusMillis(lease.toMillis());
        update(connection, "INSERT INTO workflow_recovery_batch "
                        + "(run_id, claim_round, owner_id, fencing_token, expires_at) VALUES (?, ?, ?, ?, ?)",
                runId, round, owner, claim.token(), Timestamp.from(expires));
        for (Member member : members) {
            update(connection, "INSERT INTO workflow_recovery_member "
                            + "(run_id, claim_round, node_run_id, node_key, attempt, claim_state) "
                            + "VALUES (?, ?, ?, ?, ?, 'CLAIMED')",
                    runId, round, member.nodeRunId(), member.nodeKey(), member.attempt());
        }
        update(connection, "UPDATE workflow_recovery_run SET last_round = ?, active_round = ? WHERE run_id = ?",
                round, round, runId);
        return Optional.of(claim);
    }

    /** Commit this barrier BEFORE submitting to any executor. There is deliberately no reverse transition. */
    public boolean enter(Connection connection, Claim claim, Member member) throws SQLException {
        return transition(connection, claim, member, State.CLAIMED, State.ENTERED);
    }

    /** Non-consuming delivery check. Expired identities must not begin execution. */
    public boolean verifyEntered(Connection connection, Claim claim, Member member) throws SQLException {
        transaction(connection);
        Objects.requireNonNull(member);
        if (!owns(connection, claim, false)) return false;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT claim_state FROM workflow_recovery_member WHERE run_id = ? AND claim_round = ? "
                        + "AND node_run_id = ? AND node_key = ? AND attempt = ? FOR UPDATE")) {
            statement.setString(1, claim.runId());
            statement.setLong(2, claim.round());
            statement.setString(3, member.nodeRunId());
            statement.setString(4, member.nodeKey());
            statement.setInt(5, member.attempt());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && "ENTERED".equals(rows.getString(1));
            }
        }
    }

    public boolean complete(Connection connection, Claim claim, Member member, State outcome) throws SQLException {
        if (outcome != State.SUCCEEDED && outcome != State.FAILED && outcome != State.CANCELLED
                && outcome != State.UNCERTAIN) {
            throw new IllegalArgumentException("invalid entered outcome");
        }
        transaction(connection);
        Objects.requireNonNull(member);
        // Lease gates delivery, not a known result from the same immutable execution identity.
        // UNCERTAIN can be recorded, but can never be resolved through ordinary completion.
        return identity(connection, claim) && cas(connection, claim, member, State.ENTERED, outcome) == 1;
    }

    /** Persistent CLAIMED proves the delivery barrier was never crossed; no lease requirement. */
    public boolean closeProvenUndelivered(Connection connection, Claim claim, Member member) throws SQLException {
        transaction(connection);
        Objects.requireNonNull(member);
        return identity(connection, claim) && cas(connection, claim, member, State.CLAIMED, State.CANCELLED) == 1;
    }

    /**
     * Caller must hold proof that a standard abort-on-rejection executor did not accept this exact submission.
     * This API cannot establish that proof; never call it for timeout, ambiguous exceptions or accepted work.
     */
    public boolean rejectEntered(Connection connection, Claim claim, Member member) throws SQLException {
        transaction(connection);
        Objects.requireNonNull(member);
        return identity(connection, claim) && cas(connection, claim, member, State.ENTERED, State.CANCELLED) == 1;
    }

    /** Only these exact, still unentered members can become cancellation tombstones. */
    public int cancelUnentered(Connection connection, Claim claim, List<Member> members) throws SQLException {
        transaction(connection);
        members = List.copyOf(members);
        if (!owns(connection, claim, false)) return 0;
        int changed = 0;
        for (Member member : members) changed += cas(connection, claim, member, State.CLAIMED, State.CANCELLED);
        return changed;
    }

    /** Expiration records uncertainty; it never grants a new owner or permission to dispatch. */
    public boolean expire(Connection connection, Claim claim) throws SQLException {
        transaction(connection);
        if (!owns(connection, claim, true)) return false;
        update(connection, "UPDATE workflow_recovery_member SET claim_state = CASE "
                        + "WHEN claim_state = 'CLAIMED' THEN 'CANCELLED' ELSE 'UNCERTAIN' END, "
                        + "updated_at = CURRENT_TIMESTAMP(6) WHERE run_id = ? AND claim_round = ? "
                        + "AND claim_state IN ('CLAIMED', 'ENTERED')", claim.runId(), claim.round());
        return true;
    }

    /** A fully terminal batch may release its slot even after expiration. UNCERTAIN blocks forever. */
    public boolean finishBatch(Connection connection, Claim claim) throws SQLException {
        transaction(connection);
        if (!identity(connection, claim)) return false;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT claim_state FROM workflow_recovery_member WHERE run_id = ? AND claim_round = ? FOR UPDATE")) {
            statement.setString(1, claim.runId());
            statement.setLong(2, claim.round());
            try (ResultSet rows = statement.executeQuery()) {
                boolean found = false;
                while (rows.next()) {
                    found = true;
                    if (!List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(rows.getString(1))) return false;
                }
                if (!found) return false;
            }
        }
        update(connection, "UPDATE workflow_recovery_batch SET closed_at = CURRENT_TIMESTAMP(6) "
                + "WHERE run_id = ? AND claim_round = ? AND closed_at IS NULL", claim.runId(), claim.round());
        return update(connection, "UPDATE workflow_recovery_run SET active_round = NULL "
                + "WHERE run_id = ? AND active_round = ?", claim.runId(), claim.round()) == 1;
    }

    private boolean transition(Connection connection, Claim claim, Member member, State before, State after)
            throws SQLException {
        transaction(connection);
        Objects.requireNonNull(member);
        return owns(connection, claim, false) && cas(connection, claim, member, before, after) == 1;
    }

    private int cas(Connection connection, Claim claim, Member member, State before, State after)
            throws SQLException {
        return update(connection, "UPDATE workflow_recovery_member SET claim_state = ?, "
                        + "updated_at = CURRENT_TIMESTAMP(6) WHERE run_id = ? AND claim_round = ? "
                        + "AND node_run_id = ? AND node_key = ? AND attempt = ? AND claim_state = ?",
                after.name(), claim.runId(), claim.round(), member.nodeRunId(), member.nodeKey(),
                member.attempt(), before.name());
    }

    private boolean owns(Connection connection, Claim claim, boolean expired) throws SQLException {
        if (!identity(connection, claim)) return false;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT expires_at FROM workflow_recovery_batch WHERE run_id = ? AND claim_round = ?")) {
            statement.setString(1, claim.runId());
            statement.setLong(2, claim.round());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return false;
                boolean isExpired = !rows.getTimestamp(1).toInstant().isAfter(databaseNow(connection));
                return expired == isExpired;
            }
        }
    }

    private boolean identity(Connection connection, Claim claim) throws SQLException {
        Objects.requireNonNull(claim);
        // Always acquire the permanent run slot first, including on stale-token paths.
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT active_round FROM workflow_recovery_run WHERE run_id = ? FOR UPDATE")) {
            statement.setString(1, claim.runId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.getObject(1) == null || rows.getLong(1) != claim.round()) return false;
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT owner_id, fencing_token, closed_at FROM workflow_recovery_batch "
                        + "WHERE run_id = ? AND claim_round = ? FOR UPDATE")) {
            statement.setString(1, claim.runId());
            statement.setLong(2, claim.round());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && claim.owner().equals(rows.getString(1))
                        && claim.token().equals(rows.getString(2)) && rows.getObject(3) == null;
            }
        }
    }

    private void transaction(Connection connection) throws SQLException {
        if (!enabled) throw new IllegalStateException("recovery claims are disabled");
        if (connection.getAutoCommit()) throw new IllegalStateException("explicit transaction required");
    }

    private static Instant databaseNow(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT CURRENT_TIMESTAMP(6)");
             ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getTimestamp(1).toInstant();
        }
    }

    private static int update(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement.executeUpdate();
        }
    }

    private static void text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException("invalid claim identifier");
        }
    }
}
