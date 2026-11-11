package com.easyshell.server.workflow.gate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Pure signing primitive, not an approval verifier or dispatch authority. */
public final class DispatchIntentHmac {
    private final byte[] key;

    /** Explicit opt-in: never load the secret from database-backed settings. */
    public static DispatchIntentHmac fromEnvironment() {
        return new DispatchIntentHmac(System.getenv("EASYSHELL_DISPATCH_HMAC_KEY"));
    }

    /** A base64-encoded, independently generated secret of at least 32 bytes. */
    public DispatchIntentHmac(String base64Key) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key == null ? "" : base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid dispatch HMAC key encoding");
        }
        if (decoded.length < 32) {
            throw new IllegalArgumentException("Dispatch HMAC key must contain at least 32 bytes");
        }
        this.key = decoded.clone();
    }

    /** Exact host identity and its durable generation, not an agent name or display label. */
    public record Target(String agentId, long generation) {
        public Target {
            requireText(agentId);
            if (generation <= 0) throw new IllegalArgumentException("Invalid target generation");
        }
    }

    /** Script bytes are UTF-8 exactly as dispatched, without normalization. */
    public String scriptHmac(String script) {
        if (script == null) throw new IllegalArgumentException("Missing script");
        return sign(encoded(out -> {
            text(out, "easyshell.dispatch.script.v1");
            text(out, script);
        }));
    }

    /** Targets are an unordered set; duplicate IDs are rejected rather than collapsed. */
    public String intentHmac(String runId, String nodeRunId, int attempt, long userId,
                             String scriptHmac, List<Target> targets, int timeoutSec) {
        requireText(runId);
        requireText(nodeRunId);
        if (attempt <= 0 || userId <= 0 || timeoutSec <= 0 || !validDigest(scriptHmac)
                || targets == null || targets.isEmpty() || targets.stream().anyMatch(t -> t == null)) {
            throw new IllegalArgumentException("Invalid dispatch intent");
        }
        List<Target> sorted = targets.stream().sorted(Comparator.comparing(Target::agentId)).toList();
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i - 1).agentId().equals(sorted.get(i).agentId())) {
                throw new IllegalArgumentException("Duplicate dispatch target");
            }
        }
        return sign(encoded(out -> {
            text(out, "easyshell.dispatch.intent.v1");
            text(out, runId);
            text(out, nodeRunId);
            out.writeInt(attempt);
            out.writeLong(userId);
            text(out, scriptHmac);
            out.writeInt(sorted.size());
            for (Target target : sorted) {
                text(out, target.agentId());
                out.writeLong(target.generation());
            }
            out.writeInt(timeoutSec);
        }));
    }

    /** Reject missing, malformed and non-canonical digests before constant-time comparison. */
    public static boolean matches(String expected, String actual) {
        return validDigest(expected) && validDigest(actual)
                && MessageDigest.isEqual(HexFormat.of().parseHex(expected), HexFormat.of().parseHex(actual));
    }

    private static boolean validDigest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static void requireText(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing intent field");
    }

    private String sign(byte[] bytes) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(bytes));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Dispatch HMAC unavailable", e);
        }
    }

    private static void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static byte[] encoded(Encoder encoder) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                encoder.write(out);
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot encode dispatch intent", e);
        }
    }

    @FunctionalInterface
    private interface Encoder {
        void write(DataOutputStream out) throws IOException;
    }
}
