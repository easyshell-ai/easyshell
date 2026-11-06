package com.easyshell.server.ai.channel.support;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.function.BiFunction;

/**
 * Shared webhook signature verification policy.
 *
 * <p>All provider webhooks must present their signature headers. Missing headers, stale
 * timestamps (replay) or an invalid signature are hard failures - never a bypass.
 */
public final class WebhookVerification {

    private WebhookVerification() {
    }

    public enum Result {
        OK(200, "ok"),
        MISSING_HEADERS(401, "missing signature headers"),
        STALE_TIMESTAMP(401, "stale timestamp"),
        INVALID_SIGNATURE(401, "invalid signature");

        private final int httpStatus;
        private final String message;

        Result(int httpStatus, String message) {
            this.httpStatus = httpStatus;
            this.message = message;
        }

        public int httpStatus() {
            return httpStatus;
        }

        public String message() {
            return message;
        }
    }

    /**
     * @param verifier (nonce, signature) -> valid; must return false when no signing secret is configured
     */
    public static Result verify(String timestamp, String nonce, String signature,
                                long maxSkewMs, BiFunction<String, String, Boolean> verifier) {
        if (timestamp == null || timestamp.isBlank() || signature == null || signature.isBlank()) {
            return Result.MISSING_HEADERS;
        }
        if (!isFresh(timestamp, maxSkewMs)) {
            return Result.STALE_TIMESTAMP;
        }
        boolean valid;
        try {
            valid = Boolean.TRUE.equals(verifier.apply(nonce == null ? "" : nonce, signature));
        } catch (Exception e) {
            return Result.INVALID_SIGNATURE;
        }
        return valid ? Result.OK : Result.INVALID_SIGNATURE;
    }

    private static boolean isFresh(String timestamp, long maxSkewMs) {
        long seconds;
        try {
            seconds = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            try {
                seconds = Instant.parse(timestamp.trim()).getEpochSecond();
            } catch (DateTimeParseException e2) {
                return false;
            }
        }
        long skew = Math.abs(Instant.now().getEpochSecond() - seconds) * 1000L;
        return skew <= maxSkewMs;
    }
}
