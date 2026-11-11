package com.easyshell.server.workflow.code;

import com.easyshell.server.workflow.properties.WorkflowCodeWorkerProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowCodeWorkerClientTest {
    private static final String HEALTH = "{\"status\":\"ready\",\"policyVerified\":true}";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void cancellationBeforeAdmissionMakesNoTransportCalls() {
        try (var client = client(properties(), request -> { throw new AssertionError("Cancelled request admitted"); })) {
            var result = client.execute("python", "x", Map.of(), 1, () -> true);
            assertEquals("failed", result.status());
            assertEquals("CODE_CANCELLED_CONFIRMED", result.errorCode());
            assertNull(result.output());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 25_000})
    void cancellationBudgetIsThreeSecondsCappedByTaskRemaining(int taskBudgetMs) {
        var properties = properties();
        properties.setRequestTimeoutMs(taskBudgetMs);
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        AtomicInteger deletes = new AtomicInteger();
        var id = new java.util.concurrent.atomic.AtomicReference<String>();
        try (var client = client(properties, request -> {
            if (isHealth(request)) return response(200, HEALTH);
            if (request.method().equals("POST")) {
                id.set(requestId(request));
                cancelled.set(true);
                return response(200, success(id.get(), "null"));
            }
            deletes.incrementAndGet();
            long budget = request.timeout().orElseThrow().toNanos();
            assertTrue(budget <= TimeUnit.MILLISECONDS.toNanos(Math.min(3000, taskBudgetMs)));
            if (taskBudgetMs == 25_000) assertTrue(budget > TimeUnit.SECONDS.toNanos(1));
            return response(200, "{\"requestId\":\"" + id.get() + "\",\"status\":\"cancelled\"}");
        })) {
            var result = client.execute("python", "x", Map.of(), 1, cancelled::get);
            assertEquals("CODE_CANCELLED_CONFIRMED", result.errorCode());
            assertEquals(1, deletes.get());
        }
    }

    @Test
    void cancellationDuringHealthNeverPosts() {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        try (var client = client(properties(), request -> {
            calls.incrementAndGet();
            assertTrue(isHealth(request));
            cancelled.set(true);
            return response(200, HEALTH);
        })) {
            var result = client.execute("python", "x", Map.of(), 1, cancelled::get);
            assertEquals("CODE_CANCELLED_CONFIRMED", result.errorCode());
            assertNull(result.output());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void inFlightCancellationClosesPostAndSendsOneDeleteWithSameId() throws Exception {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        AtomicInteger posts = new AtomicInteger();
        AtomicInteger deletes = new AtomicInteger();
        CountDownLatch closed = new CountDownLatch(1);
        var id = new java.util.concurrent.atomic.AtomicReference<String>();
        try (var client = client(properties(), request -> {
            if (isHealth(request)) return response(200, HEALTH);
            if (request.method().equals("DELETE")) {
                deletes.incrementAndGet();
                assertEquals("/internal/v1/code-executions/" + id.get(), request.uri().getPath());
                assertTrue(request.timeout().orElseThrow().toNanos() <= TimeUnit.SECONDS.toNanos(3));
                return response(200, "{\"requestId\":\"" + id.get() + "\",\"status\":\"cancelled\"}");
            }
            posts.incrementAndGet();
            id.set(requestId(request));
            return new WorkflowCodeWorkerClient.Response(200, new InputStream() {
                @Override public int read() throws IOException {
                    cancelled.set(true);
                    try { closed.await(3, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    throw new IOException("cancelled POST");
                }
                @Override public void close() { closed.countDown(); }
            });
        })) {
            var result = client.execute("python", "x", Map.of(), 1, cancelled::get);
            assertEquals("CODE_CANCELLED_CONFIRMED", result.errorCode());
            assertEquals("failed", result.status());
            assertNull(result.output());
            assertEquals(id.get(), result.requestId().toString());
            assertEquals(1, posts.get());
            assertEquals(1, deletes.get());
            assertTrue(closed.await(1, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"running", "succeeded", "malformed", "timeout", "cancelled", "cancel_pending"})
    void cancellationRacingWithResultDiscardsOutputAndRequiresVerifiedDelete(String reply) {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        AtomicInteger posts = new AtomicInteger();
        AtomicInteger deletes = new AtomicInteger();
        var id = new java.util.concurrent.atomic.AtomicReference<String>();
        try (var client = client(properties(), request -> {
            if (isHealth(request)) return response(200, HEALTH);
            if (request.method().equals("POST")) {
                posts.incrementAndGet();
                id.set(requestId(request));
                cancelled.set(true); // Cancellation wins even if the POST response is already available.
                return response(200, success(id.get(), "{\"stale\":true}"));
            }
            assertEquals("DELETE", request.method());
            deletes.incrementAndGet();
            assertEquals("/internal/v1/code-executions/" + id.get(), request.uri().getPath());
            if (reply.equals("timeout")) throw new HttpTimeoutException("private transport detail");
            if (reply.equals("malformed")) return response(200, "{");
            if (reply.equals("cancel_pending")) return response(202,
                    "{\"requestId\":\"" + id.get() + "\",\"status\":\"cancel_pending\"}");
            if (reply.equals("succeeded")) return response(200, success(id.get(), "{\"stale\":true}"));
            return response(200, "{\"requestId\":\"" + id.get() + "\",\"status\":\"" + reply + "\"}");
        })) {
            var result = client.execute("python", "x", Map.of(), 1, cancelled::get);
            assertEquals(reply.equals("cancelled") ? "CODE_CANCELLED_CONFIRMED" : "CODE_EXECUTION_UNCERTAIN",
                    result.errorCode());
            assertEquals("failed", result.status());
            assertNull(result.output());
            assertEquals(1, posts.get());
            assertEquals(1, deletes.get());
        }
    }

    @Test
    void controlPlaneUncertaintyIsPreservedWithoutRetryOrOutput() {
        AtomicInteger posts = new AtomicInteger();
        try (var client = client(properties(), request -> {
            if (isHealth(request)) return response(200, HEALTH);
            posts.incrementAndGet();
            return response(503, "{\"requestId\":\"" + requestId(request)
                    + "\",\"status\":\"failed\",\"errorCode\":\"CODE_EXECUTION_UNCERTAIN\"}");
        })) {
            var result = client.execute("python", "x", Map.of(), 1);
            assertEquals("CODE_EXECUTION_UNCERTAIN", result.errorCode());
            assertEquals(503, result.httpStatus());
            assertNull(result.output());
            assertEquals(1, posts.get());
        }
    }

    @Test
    void statusQueryReturnsCompleteTerminalOutputOrPreservesUncertainty() {
        for (boolean known : List.of(false, true)) {
            AtomicInteger posts = new AtomicInteger();
            try (var client = client(properties(), request -> {
                if (isHealth(request)) return response(200, HEALTH);
                if (request.method().equals("POST")) {
                    posts.incrementAndGet();
                    throw new IOException("POST outcome unknown");
                }
                assertEquals("GET", request.method());
                String id = request.uri().getPath().substring(request.uri().getPath().lastIndexOf('/') + 1);
                return known ? response(200, success(id, "{\"complete\":[1,2,3]}"))
                        : response(503, "{\"requestId\":\"" + id
                        + "\",\"status\":\"failed\",\"errorCode\":\"CODE_EXECUTION_UNCERTAIN\"}");
            })) {
                var initial = client.execute("python", "x", Map.of(), 1);
                var status = client.queryStatus(initial);
                assertEquals(initial.requestId(), status.requestId());
                if (known) {
                    assertTrue(status.succeeded());
                    assertEquals(Map.of("complete", List.of(1, 2, 3)), status.output());
                } else {
                    assertEquals("CODE_EXECUTION_UNCERTAIN", status.errorCode());
                    assertNull(status.output());
                }
                assertEquals(1, posts.get());
            }
        }
    }

    @Test
    void requestEnvelopeHasSixtyKiBLimitWhileResponseRetainsSixtyFourKiB() throws Exception {
        String placeholderId = "00000000-0000-0000-0000-000000000000";
        int overhead = JSON.writeValueAsBytes(Map.of("requestId", placeholderId, "language", "python",
                "source", "x", "input", Map.of("value", ""), "timeoutMs", 1)).length;
        String text = "x".repeat(61_440 - overhead);
        AtomicInteger posts = new AtomicInteger();
        try (var client = client(properties(), request -> {
            if (isHealth(request)) return response(200, HEALTH);
            posts.incrementAndGet();
            byte[] bytes = body(request);
            assertEquals(61_440, bytes.length);
            String id = JSON.readTree(bytes).path("requestId").asText();
            return response(200, success(id, "null"));
        })) {
            assertTrue(client.execute("python", "x", Map.of("value", text), 1).succeeded());
            assertEquals("CODE_INPUT_INVALID",
                    client.execute("python", "x", Map.of("value", text + "x"), 1).errorCode());
            assertEquals(1, posts.get());
        }
    }

    @Test
    void healthHasIndependentTenSecondBudgetAndTaskUsesConfiguredBudget() {
        var properties = properties();
        properties.setRequestTimeoutMs(500);
        try (var client = client(properties, request -> {
            long budget = request.timeout().orElseThrow().toNanos();
            if (isHealth(request)) {
                assertTrue(budget <= TimeUnit.SECONDS.toNanos(10));
                assertTrue(budget > TimeUnit.MILLISECONDS.toNanos(500));
                return response(200, HEALTH);
            }
            assertTrue(budget <= TimeUnit.MILLISECONDS.toNanos(500));
            return response(200, success(requestId(request), "null"));
        })) {
            assertTrue(client.execute("python", "x", Map.of(), 1).succeeded());
        }
    }

    @Test
    void disabledByDefaultAndConfigurationCheckDoesNotContactWorker() {
        WorkflowCodeWorkerProperties properties = properties();
        properties.setEnabled(false);
        AtomicInteger calls = new AtomicInteger();
        try (var client = client(properties, request -> {
            calls.incrementAndGet();
            throw new AssertionError("Disabled client must not use transport");
        })) {
            assertFalse(client.isAvailable());
            assertEquals("CODE_RUNTIME_UNAVAILABLE", client.execute("python", "secret-source", Map.of(), 1).errorCode());
            assertEquals(0, calls.get());
        }
        try (var client = client(properties(), request -> { throw new AssertionError("Configuration check did I/O"); })) {
            assertTrue(client.isAvailable());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://worker", "https://user@worker", "https://worker?x=1",
            "https://worker#x", "https://worker/base", "https://worker:0", "file:///worker", "https://worker?"})
    void rejectsUnsafeEndpoints(String endpoint) {
        var properties = properties();
        properties.setEndpoint(endpoint);
        try (var client = client(properties, request -> { throw new AssertionError(); })) {
            assertFalse(client.isAvailable());
        }
    }

    @Test
    void rejectsMissingPasswordsAndUntrustedPathShapeWithoutIo() {
        var properties = properties();
        properties.setClientKeyStore("relative.p12");
        try (var client = client(properties, request -> { throw new AssertionError(); })) {
            assertFalse(client.isAvailable());
        }
        try (var client = new WorkflowCodeWorkerClient(properties(), "", "test", request -> { throw new AssertionError(); })) {
            assertFalse(client.isAvailable());
        }
    }

    @Test
    void invalidRequestsNeverReachTransport() {
        try (var client = client(properties(), request -> { throw new AssertionError("Invalid request reached transport"); })) {
            assertInvalid(client.execute("js", "x", Map.of(), 1));
            assertInvalid(client.execute("python", " ", Map.of(), 1));
            assertInvalid(client.execute("python", "x", Map.of(), 0));
            assertInvalid(client.execute("python", "x", Map.of(), 5001));
            assertInvalid(client.execute("python", "x", null, 1));
            assertInvalid(client.execute("python", "界".repeat(10_923), Map.of(), 1));
            assertInvalid(client.execute("python", "x", Map.of("x", "x".repeat(65_536)), 1));
            assertInvalid(client.execute("python", "x", Map.of("x", Double.NaN), 1));
            assertInvalid(client.execute("python", "x", Map.of("x", new Object()), 1));
            Map<String, Object> cycle = new HashMap<>();
            cycle.put("cycle", cycle);
            assertInvalid(client.execute("python", "x", cycle, 1));
            Object nested = "value";
            for (int i = 0; i < 64; i++) nested = Map.of("x", nested);
            assertInvalid(client.execute("python", "x", Map.of("nested", nested), 1));
        }
    }

    @Test
    void postsExactFieldsFreshUuidAndMaximumGuestAndSourceLimits() throws Exception {
        List<Map<?, ?>> bodies = new ArrayList<>();
        try (var client = client(properties(), request -> {
            if (isHealth(request)) return response(200, HEALTH);
            assertEquals("POST", request.method());
            assertEquals("/internal/v1/code-executions", request.uri().getPath());
            assertTrue(request.timeout().orElseThrow().toMillis() <= 25_000);
            Map<?, ?> body = JSON.readValue(body(request), Map.class);
            assertEquals(java.util.Set.of("requestId", "language", "source", "input", "timeoutMs"), body.keySet());
            assertEquals("javascript", body.get("language"));
            assertEquals(32_768, ((String) body.get("source")).getBytes(StandardCharsets.UTF_8).length);
            assertEquals(5000, body.get("timeoutMs"));
            bodies.add(body);
            return response(200, success(body.get("requestId").toString(), "{\"value\":7}"));
        })) {
            var first = client.execute("javascript", "x".repeat(32_768), Map.of("a", 1), 5000);
            var second = client.execute("javascript", "x".repeat(32_768), Map.of("a", 1), 5000);
            assertTrue(first.succeeded());
            assertEquals(Map.of("value", 7), first.output());
            assertTrue(second.succeeded());
            assertNotEquals(first.requestId(), second.requestId());
            assertEquals(first.requestId().toString(), bodies.get(0).get("requestId"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"status\":\"ready\",\"policyVerified\":false}",
            "{\"status\":\"ready\",\"policyVerified\":\"true\"}",
            "{\"status\":\"ready\",\"policyVerified\":true,\"extra\":1}",
            "{\"status\":\"ready\",\"policyVerified\":true} {}",
            "{\"status\":\"ready\",\"status\":\"ready\",\"policyVerified\":true}"})
    void unverifiedOrMalformedHealthFailsClosedAndNeverPosts(String health) {
        AtomicInteger calls = new AtomicInteger();
        try (var client = client(properties(), request -> {
            calls.incrementAndGet();
            assertTrue(isHealth(request));
            return response(200, health);
        })) {
            var result = client.execute("python", "secret-source", Map.of("secret-input", "value"), 1);
            assertEquals("CODE_RUNTIME_UNAVAILABLE", result.errorCode());
            assertEquals(503, result.httpStatus());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void healthIsRecheckedOnEveryExecution() {
        AtomicInteger healthCalls = new AtomicInteger();
        AtomicInteger posts = new AtomicInteger();
        try (var client = client(properties(), request -> {
            if (isHealth(request)) return response(200, healthCalls.incrementAndGet() == 1
                    ? HEALTH : "{\"status\":\"ready\",\"policyVerified\":false}");
            posts.incrementAndGet();
            return response(200, success(requestId(request), "null"));
        })) {
            assertTrue(client.execute("python", "x", Map.of(), 1).succeeded());
            assertEquals("CODE_RUNTIME_UNAVAILABLE", client.execute("python", "x", Map.of(), 1).errorCode());
            assertEquals(1, posts.get());
            assertEquals(2, healthCalls.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"requestId\":\"wrong\",\"status\":\"succeeded\",\"output\":1}",
            "{\"requestId\":\"$ID\",\"status\":\"succeeded\",\"output\":1,\"output\":2}",
            "{\"requestId\":\"$ID\",\"status\":\"succeeded\",\"output\":1} {}",
            "{\"requestId\":\"$ID\",\"status\":\"succeeded\"}",
            "{\"requestId\":\"$ID\",\"status\":\"succeeded\",\"output\":1,\"trace\":\"secret-source\"}",
            "{\"requestId\":\"$ID\",\"status\":\"failed\",\"errorCode\":\"secret-source\"}",
            "{\"requestId\":\"$ID\",\"status\":\"running\"}"})
    void malformedOrUnrecognizedPostResponseIsUncertainWithoutRetry(String reply) {
        AtomicInteger posts = new AtomicInteger();
        try (var client = client(properties(), request -> {
            if (isHealth(request)) return response(200, HEALTH);
            posts.incrementAndGet();
            return response(200, reply.replace("$ID", requestId(request)));
        })) {
            var result = client.execute("python", "secret-source", Map.of(), 1);
            assertEquals("CODE_EXECUTION_UNCERTAIN", result.errorCode());
            assertNull(result.output());
            assertEquals(1, posts.get());
        }
    }

    @Test
    void readsAtMostLimitPlusOneFromActualStreamAndClosesIt() {
        AtomicInteger read = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        try (var client = client(properties(), request -> {
            if (isHealth(request)) return response(200, HEALTH);
            return new WorkflowCodeWorkerClient.Response(200, new InputStream() {
                @Override public int read() { read.incrementAndGet(); return 'x'; }
                @Override public int read(byte[] bytes, int offset, int length) {
                    java.util.Arrays.fill(bytes, offset, offset + length, (byte) 'x');
                    read.addAndGet(length);
                    return length;
                }
                @Override public void close() { closed.incrementAndGet(); }
            });
        })) {
            assertEquals("CODE_EXECUTION_UNCERTAIN", client.execute("python", "x", Map.of(), 1).errorCode());
            assertEquals(65_537, read.get());
            assertTrue(closed.get() > 0);
        }
    }

    @Test
    void acceptsExactResponseByteLimitAndRejectsExcessiveResponseDepth() {
        for (boolean deep : List.of(false, true)) {
            try (var client = client(properties(), request -> {
                if (isHealth(request)) return response(200, HEALTH);
                String id = requestId(request);
                String output;
                if (deep) {
                    output = "[".repeat(65) + "0" + "]".repeat(65);
                } else {
                    int overhead = success(id, "\"\"").getBytes(StandardCharsets.UTF_8).length;
                    output = "\"" + "x".repeat(65_536 - overhead) + "\"";
                }
                String reply = success(id, output);
                if (!deep) assertEquals(65_536, reply.getBytes(StandardCharsets.UTF_8).length);
                return response(200, reply);
            })) {
                var result = client.execute("python", "x", Map.of(), 1);
                if (deep) assertEquals("CODE_EXECUTION_UNCERTAIN", result.errorCode());
                else assertTrue(result.succeeded());
            }
        }
    }

    @Test
    void clampsConfiguredBudgetToTwentyFiveSeconds() {
        var properties = properties();
        properties.setRequestTimeoutMs(100_000);
        try (var client = client(properties, request -> {
            assertTrue(request.timeout().orElseThrow().toNanos() <= TimeUnit.SECONDS.toNanos(25));
            if (isHealth(request)) return response(200, HEALTH);
            return response(200, success(requestId(request), "null"));
        })) {
            assertTrue(client.execute("python", "x", Map.of(), 5000).succeeded());
        }
    }

    @Test
    void truncatedResponseAndTimeoutAreUncertainAndNeverRetried() {
        for (boolean timeout : List.of(false, true)) {
            AtomicInteger posts = new AtomicInteger();
            try (var client = client(properties(), request -> {
                if (isHealth(request)) return response(200, HEALTH);
                posts.incrementAndGet();
                if (timeout) throw new HttpTimeoutException("secret-source secret-input");
                return new WorkflowCodeWorkerClient.Response(200, new InputStream() {
                    @Override public int read() throws IOException { throw new IOException("secret-source"); }
                });
            })) {
                assertEquals("CODE_EXECUTION_UNCERTAIN", client.execute("python", "secret-source", Map.of(), 1).errorCode());
                assertEquals(1, posts.get());
            }
        }
    }

    @Test
    void wholeOperationDeadlineIncludesBodyReadAndClosesBlockedStream() throws Exception {
        var properties = properties();
        properties.setRequestTimeoutMs(500);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicInteger posts = new AtomicInteger();
        try (var client = client(properties, request -> {
            if (isHealth(request)) return response(200, HEALTH);
            posts.incrementAndGet();
            return new WorkflowCodeWorkerClient.Response(200, new InputStream() {
                @Override public int read() throws IOException {
                    try { closed.await(3, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    throw new IOException("closed");
                }
                @Override public void close() { closed.countDown(); }
            });
        })) {
            long start = System.nanoTime();
            var result = client.execute("python", "x", Map.of(), 1);
            assertEquals("CODE_EXECUTION_UNCERTAIN", result.errorCode());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2500);
            assertTrue(closed.await(1, TimeUnit.SECONDS));
            assertEquals(1, posts.get());
        }
    }

    @Test
    void startupConsumesSameDeadlineAndNoPostFollowsFailedHealth() {
        AtomicInteger calls = new AtomicInteger();
        try (var client = client(properties(), request -> {
            calls.incrementAndGet();
            assertTrue(isHealth(request));
            throw new HttpTimeoutException("startup unavailable");
        })) {
            assertEquals("CODE_RUNTIME_UNAVAILABLE", client.execute("python", "x", Map.of(), 1).errorCode());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void knownErrorCodeIsReturnedWithoutWorkerText() {
        try (var client = client(properties(), request -> {
            if (isHealth(request)) return response(200, HEALTH);
            return response(200, "{\"requestId\":\"" + requestId(request)
                    + "\",\"status\":\"failed\",\"errorCode\":\"TIMEOUT\"}");
        })) {
            var result = client.execute("python", "secret-source", Map.of(), 1);
            assertEquals("TIMEOUT", result.errorCode());
            assertFalse(result.succeeded());
        }
    }

    @Test
    void internalStatusAndCancelPreserveRequestIdAndRejectOtherClientOwner() {
        List<String> methods = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        try (var client = client(properties(), request -> {
            if (isHealth(request)) return response(200, HEALTH);
            methods.add(request.method());
            paths.add(request.uri().getPath());
            if (request.method().equals("POST")) throw new IOException("unknown outcome");
            String id = request.uri().getPath().substring(request.uri().getPath().lastIndexOf('/') + 1);
            return response(200, "{\"requestId\":\"" + id + "\",\"status\":\""
                    + (request.method().equals("GET") ? "running" : "cancelled") + "\"}");
        }); var other = client(properties(), request -> { throw new AssertionError(); })) {
            var original = client.execute("python", "x", Map.of(), 1);
            var status = client.queryStatus(original);
            var cancelled = client.cancel(status);
            assertEquals(original.requestId(), status.requestId());
            assertEquals(original.requestId(), cancelled.requestId());
            assertEquals("running", status.status());
            assertEquals("cancelled", cancelled.status());
            assertEquals(List.of("POST", "GET", "DELETE"), methods);
            assertEquals(paths.get(0) + "/" + original.requestId(), paths.get(1));
            assertEquals(paths.get(1), paths.get(2));
            assertThrows(IllegalArgumentException.class, () -> other.queryStatus(original));
            assertThrows(IllegalArgumentException.class, () -> other.cancel(original));
        }
    }

    private static WorkflowCodeWorkerProperties properties() {
        var properties = new WorkflowCodeWorkerProperties();
        properties.setEnabled(true);
        properties.setEndpoint("https://worker.internal:9443");
        properties.setClientKeyStore("/trusted/client.p12");
        properties.setTrustStore("/trusted/trust.p12");
        return properties;
    }

    private static WorkflowCodeWorkerClient client(WorkflowCodeWorkerProperties properties,
                                                   WorkflowCodeWorkerClient.Transport transport) {
        return new WorkflowCodeWorkerClient(properties, "test-only", "test-only", transport);
    }

    private static boolean isHealth(HttpRequest request) {
        return request.method().equals("GET") && request.uri().getPath().equals("/internal/v1/code-health");
    }

    private static WorkflowCodeWorkerClient.Response response(int status, String body) {
        return new WorkflowCodeWorkerClient.Response(status,
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static String success(String id, String output) {
        return "{\"requestId\":\"" + id + "\",\"status\":\"succeeded\",\"output\":" + output + "}";
    }

    private static String requestId(HttpRequest request) throws Exception {
        return JSON.readTree(body(request)).path("requestId").asText();
    }

    private static byte[] body(HttpRequest request) throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer buffer) {
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            @Override public void onError(Throwable error) { result.completeExceptionally(error); }
            @Override public void onComplete() { result.complete(bytes.toByteArray()); }
        });
        return result.get(1, TimeUnit.SECONDS);
    }

    private static void assertInvalid(WorkflowCodeWorkerClient.ExecutionResult result) {
        assertEquals("CODE_INPUT_INVALID", result.errorCode());
        assertEquals(400, result.httpStatus());
        assertNull(result.output());
    }
}
