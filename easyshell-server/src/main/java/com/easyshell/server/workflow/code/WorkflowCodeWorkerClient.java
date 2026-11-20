package com.easyshell.server.workflow.code;

import com.easyshell.server.workflow.properties.WorkflowCodeWorkerProperties;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Internal mTLS JSON client. No retries, local execution or output masking.
 * Callers must apply SensitiveDataFilter before exposing or persisting output.
 * The control plane must authorize the certificate identity, verify request ownership,
 * and reject reuse of request IDs; an unknown POST outcome is never replayed here.
 */
public final class WorkflowCodeWorkerClient implements AutoCloseable {
    static final int MAX_BODY_BYTES = 65_536;
    static final int MAX_REQUEST_BYTES = 61_440;
    static final int MAX_SOURCE_BYTES = 32_768;
    static final int MAX_DEPTH = 64;
    private static final String EXECUTIONS = "/internal/v1/code-executions";
    private static final Set<String> ERRORS = Set.of("TIMEOUT", "RESOURCE_EXHAUSTED",
            "EXECUTION_FAILED", "INVALID_REQUEST", "UNSUPPORTED_LANGUAGE", "SOURCE_TOO_LARGE",
            "INPUT_TOO_LARGE", "OUTPUT_TOO_LARGE", "OUTPUT_NOT_SERIALIZABLE", "ENTRYPOINT_MISSING",
            "RUNTIME_UNAVAILABLE", "POLICY_UNVERIFIED", "RATE_LIMITED", "CANCELLED",
            "CODE_EXECUTION_UNCERTAIN");
    private final URI endpoint;
    private final boolean configured;
    private final int requestTimeoutMs;
    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(MAX_DEPTH).build())
            .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Transport transport;
    // Cancellation must not queue behind the POSTs it is trying to terminate.
    private final ThreadPoolExecutor cancellations = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), runnable -> {
                Thread thread = new Thread(runnable, "workflow-code-worker-cancel");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final ThreadPoolExecutor operations = new ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), runnable -> {
                Thread thread = new Thread(runnable, "workflow-code-worker-http");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public WorkflowCodeWorkerClient(WorkflowCodeWorkerProperties properties,
                                    String clientPassword, String trustPassword) {
        this(properties, clientPassword, trustPassword, null);
    }

    /** Package-only test seam; production always constructs the mTLS transport. */
    WorkflowCodeWorkerClient(WorkflowCodeWorkerProperties properties,
                             String clientPassword, String trustPassword, Transport injected) {
        URI origin = validEndpoint(properties.getEndpoint());
        this.endpoint = origin;
        this.requestTimeoutMs = Math.min(25_000, Math.max(1, properties.getRequestTimeoutMs()));
        this.configured = properties.isEnabled() && origin != null
                && validPath(properties.getClientKeyStore()) && validPath(properties.getTrustStore())
                && clientPassword != null && !clientPassword.isBlank()
                && trustPassword != null && !trustPassword.isBlank()
                && properties.getRequestTimeoutMs() > 0;
        this.transport = injected != null ? injected : new MtlsTransport(
                properties.getClientKeyStore(), properties.getTrustStore(), clientPassword, trustPassword);
    }

    /** Configuration check only: no I/O and no claim that runtime policy is healthy. */
    public boolean isAvailable() {
        return configured && !operations.isShutdown();
    }

    public ExecutionResult execute(String language, String source, Map<String, Object> input, int timeoutMs) {
        return execute(language, source, input, timeoutMs, () -> false);
    }

    public ExecutionResult execute(String language, String source, Map<String, Object> input, int timeoutMs,
                                   BooleanSupplier cancelled) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(requestTimeoutMs + 10_000L);
        UUID requestId = UUID.randomUUID(); // Generated once, never accepted from a workflow/user.
        if (cancelled == null) return failure(requestId, "CODE_INPUT_INVALID", 400);
        if (cancelled.getAsBoolean()) return failure(requestId, "CODE_CANCELLED_CONFIRMED", 422);
        if (!isAvailable()) return failure(requestId, "CODE_RUNTIME_UNAVAILABLE", 503);
        byte[] body;
        try {
            if (!("javascript".equals(language) || "python".equals(language))
                    || source == null || source.length() > MAX_SOURCE_BYTES || source.isBlank()
                    || source.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES
                    || timeoutMs < 1 || timeoutMs > 5000 || input == null) {
                return failure(requestId, "CODE_INPUT_INVALID", 400);
            }
            validateJson(input, 2, Collections.newSetFromMap(new IdentityHashMap<>()), new int[]{0});
            body = boundedJson(Map.of("requestId", requestId.toString(), "language", language,
                    "source", source, "input", input, "timeoutMs", timeoutMs));
        } catch (Exception invalid) {
            return failure(requestId, "CODE_INPUT_INVALID", 400);
        }
        return perform(requestId, "POST", EXECUTIONS, body, deadline, true, cancelled, operations);
    }

    /** Internal recovery only. A result from a different client identity is rejected locally. */
    ExecutionResult queryStatus(ExecutionResult previous) {
        requireOwner(previous);
        return perform(previous.requestId, "GET", EXECUTIONS + "/" + previous.requestId,
                null, deadline(), false);
    }

    /** Cancellation only succeeds when the control plane reports the terminal cancelled status. */
    ExecutionResult cancel(ExecutionResult previous) {
        requireOwner(previous);
        return perform(previous.requestId, "DELETE", EXECUTIONS + "/" + previous.requestId,
                null, deadline(), false);
    }

    private long deadline() {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(requestTimeoutMs);
    }

    private void requireOwner(ExecutionResult result) {
        if (result == null || result.owner != this) throw new IllegalArgumentException("Invalid execution owner");
    }

    private ExecutionResult perform(UUID id, String method, String path, byte[] body,
                                    long deadline, boolean checkHealth) {
        return perform(id, method, path, body, deadline, checkHealth, () -> false, operations);
    }

    private ExecutionResult perform(UUID id, String method, String path, byte[] body,
                                    long deadline, boolean checkHealth, BooleanSupplier cancelled,
                                    ThreadPoolExecutor pool) {
        if (!isAvailable()) return failure(id, "CODE_RUNTIME_UNAVAILABLE", 503);
        AtomicBoolean submitted = new AtomicBoolean();
        AtomicBoolean stopped = new AtomicBoolean();
        java.util.concurrent.atomic.AtomicLong phaseDeadline = new java.util.concurrent.atomic.AtomicLong(deadline);
        AtomicReference<InputStream> activeBody = new AtomicReference<>();
        Future<ExecutionResult> future;
        try {
            future = pool.submit(() -> {
                if (stopped.get() || cancelled.getAsBoolean())
                    return failure(id, "CODE_CANCELLED_CONFIRMED", 422);
                if (checkHealth) {
                    long healthDeadline = Math.min(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
                    phaseDeadline.set(healthDeadline);
                    WireResponse health = exchange("GET", "/internal/v1/code-health", null, healthDeadline, activeBody);
                    JsonNode policy = parse(health.body);
                    if (health.status != 200 || !fields(policy, Set.of("status", "policyVerified"))
                            || !"ready".equals(policy.path("status").asText())
                            || !policy.path("policyVerified").isBoolean()
                            || !policy.path("policyVerified").booleanValue()) {
                        return failure(id, "CODE_RUNTIME_UNAVAILABLE", 503);
                    }
                    phaseDeadline.set(Math.min(deadline,
                            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(requestTimeoutMs)));
                }
                HttpRequest request = request(method, path, body, phaseDeadline.get());
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                remaining(phaseDeadline.get());
                synchronized (submitted) {
                    if (stopped.get() || cancelled.getAsBoolean())
                        return failure(id, "CODE_CANCELLED_CONFIRMED", 422);
                    if ("POST".equals(method)) submitted.set(true);
                }
                WireResponse response = exchange(request, activeBody);
                return decode(id, response, method);
            });
        } catch (RejectedExecutionException rejected) {
            return failure(id, "CODE_RUNTIME_UNAVAILABLE", 503);
        }
        try {
            while (true) {
                if (cancelled.getAsBoolean())
                    return cancelInFlight(id, phaseDeadline.get(), submitted, stopped, future, activeBody);
                try {
                    ExecutionResult result = future.get(Math.min(remaining(phaseDeadline.get()),
                            TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
                    if (cancelled.getAsBoolean())
                        return cancelInFlight(id, phaseDeadline.get(), submitted, stopped, future, activeBody);
                    return result;
                } catch (TimeoutException polling) {
                    remaining(phaseDeadline.get());
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | IOException failed) {
            // Exception messages/causes may contain request data or local paths. Never expose them.
            if (cancelled.getAsBoolean())
                return cancelInFlight(id, phaseDeadline.get(), submitted, stopped, future, activeBody);
        } finally {
            synchronized (submitted) {
                stopped.set(true);
            }
            stopRequest(future, activeBody);
        }
        return failure(id, submitted.get() ? "CODE_EXECUTION_UNCERTAIN" : "CODE_RUNTIME_UNAVAILABLE", 503);
    }

    private ExecutionResult cancelInFlight(UUID id, long deadline, AtomicBoolean submitted,
                                           AtomicBoolean stopped, Future<?> future,
                                           AtomicReference<InputStream> activeBody) {
        boolean posted;
        synchronized (submitted) {
            stopped.set(true);
            posted = submitted.get();
        }
        stopRequest(future, activeBody);
        if (!posted) return failure(id, "CODE_CANCELLED_CONFIRMED", 422);
        long cancelDeadline = Math.min(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(3));
        ExecutionResult result = perform(id, "DELETE", EXECUTIONS + "/" + id, null,
                cancelDeadline, false, () -> false, cancellations);
        return failure(id, "cancelled".equals(result.status())
                ? "CODE_CANCELLED_CONFIRMED" : "CODE_EXECUTION_UNCERTAIN",
                "cancelled".equals(result.status()) ? 422 : 503);
    }

    private static void stopRequest(Future<?> future, AtomicReference<InputStream> activeBody) {
        future.cancel(true);
        InputStream stream = activeBody.getAndSet(null);
        if (stream != null) {
            try { stream.close(); } catch (IOException ignored) { /* Never expose transport details. */ }
        }
    }

    private WireResponse exchange(String method, String path, byte[] body, long deadline,
                                  AtomicReference<InputStream> activeBody) throws Exception {
        return exchange(request(method, path, body, deadline), activeBody);
    }

    private WireResponse exchange(HttpRequest request, AtomicReference<InputStream> activeBody) throws Exception {
        try (Response response = transport.send(request)) {
            InputStream stream = response.body();
            activeBody.set(stream);
            try {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                byte[] bytes = stream.readNBytes(MAX_BODY_BYTES + 1);
                if (bytes.length > MAX_BODY_BYTES) throw new IOException("Worker response exceeds limit");
                return new WireResponse(response.status(), bytes);
            } finally {
                activeBody.compareAndSet(stream, null);
            }
        }
    }

    private HttpRequest request(String method, String path, byte[] body, long deadline) throws IOException {
        return HttpRequest.newBuilder(endpoint.resolve(path))
                .timeout(Duration.ofNanos(remaining(deadline)))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body)).build();
    }

    private static long remaining(long deadline) throws IOException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IOException("Worker deadline exceeded");
        return remaining;
    }

    private ExecutionResult decode(UUID id, WireResponse response, String method) throws IOException {
        JsonNode root = parse(response.body);
        if (!root.isObject() || !root.path("requestId").isTextual()
                || !id.toString().equals(root.path("requestId").textValue())
                || !root.path("status").isTextual()) throw new IOException("Invalid worker response");
        String status = root.path("status").textValue();
        if (response.status == 200 && "succeeded".equals(status)
                && fields(root, Set.of("requestId", "status", "output"))) {
            return new ExecutionResult(this, id, status, json.convertValue(root.get("output"), Object.class), null, 200);
        }
        if (response.status == 200 && "cancelled".equals(status)
                && !"POST".equals(method) && fields(root, Set.of("requestId", "status"))) {
            return new ExecutionResult(this, id, status, null, "CANCELLED", 200);
        }
        if (response.status == 200 && "GET".equals(method)
                && Set.of("queued", "running").contains(status)
                && fields(root, Set.of("requestId", "status"))) {
            return new ExecutionResult(this, id, status, null, null, 200);
        }
        if (Set.of(200, 400, 401, 403, 409, 413, 429, 503).contains(response.status)
                && "failed".equals(status) && fields(root, Set.of("requestId", "status", "errorCode"))
                && root.path("errorCode").isTextual() && ERRORS.contains(root.path("errorCode").textValue())) {
            return new ExecutionResult(this, id, status, null, root.path("errorCode").textValue(), response.status);
        }
        throw new IOException("Invalid worker response");
    }

    private JsonNode parse(byte[] bytes) throws IOException {
        JsonNode root = json.readTree(bytes);
        if (root == null) throw new IOException("Invalid worker response");
        return root;
    }

    private static boolean fields(JsonNode node, Set<String> allowed) {
        if (!node.isObject() || node.size() != allowed.size()) return false;
        var names = node.fieldNames();
        while (names.hasNext()) if (!allowed.contains(names.next())) return false;
        return true;
    }

    private byte[] boundedJson(Object value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var limited = new java.io.FilterOutputStream(bytes) {
            private int count;
            @Override public void write(int value) throws IOException {
                if (++count > MAX_REQUEST_BYTES) throw new IOException("Request exceeds limit");
                out.write(value);
            }
            @Override public void write(byte[] buffer, int offset, int length) throws IOException {
                if (length > MAX_REQUEST_BYTES - count) throw new IOException("Request exceeds limit");
                count += length;
                out.write(buffer, offset, length);
            }
        }) {
            json.writeValue(limited, value);
        }
        return bytes.toByteArray();
    }

    private static void validateJson(Object value, int depth, Set<Object> active, int[] elements) {
        if (depth > MAX_DEPTH || ++elements[0] > MAX_BODY_BYTES) throw new IllegalArgumentException();
        if (value == null || value instanceof Boolean) return;
        if (value instanceof String text) {
            if (text.length() > MAX_BODY_BYTES) throw new IllegalArgumentException();
            return;
        }
        if (value instanceof Number number) {
            if (!(number instanceof Byte || number instanceof Short || number instanceof Integer
                    || number instanceof Long || number instanceof Float || number instanceof Double
                    || number instanceof java.math.BigInteger || number instanceof java.math.BigDecimal)
                    || !Double.isFinite(number.doubleValue())) throw new IllegalArgumentException();
            return;
        }
        if (!active.add(value)) throw new IllegalArgumentException();
        try {
            if (value instanceof Map<?, ?> map) {
                if (map.size() > MAX_BODY_BYTES) throw new IllegalArgumentException();
                for (var entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key) || key.length() > MAX_BODY_BYTES)
                        throw new IllegalArgumentException();
                    validateJson(entry.getValue(), depth + 1, active, elements);
                }
            } else if (value instanceof List<?> list) {
                if (list.size() > MAX_BODY_BYTES) throw new IllegalArgumentException();
                for (Object element : list) validateJson(element, depth + 1, active, elements);
            } else throw new IllegalArgumentException();
        } finally {
            active.remove(value);
        }
    }

    private ExecutionResult failure(UUID id, String code, int httpStatus) {
        return new ExecutionResult(this, id, "failed", null, code, httpStatus);
    }

    private static URI validEndpoint(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && (uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))
                    && (uri.getPort() == -1 || (uri.getPort() > 0 && uri.getPort() <= 65535)) ? uri : null;
        } catch (RuntimeException invalid) { return null; }
    }

    private static boolean validPath(String value) {
        try { return value != null && Path.of(value).isAbsolute() && Path.of(value).normalize().equals(Path.of(value)); }
        catch (RuntimeException invalid) { return false; }
    }

    @Override public void close() {
        operations.shutdownNow();
        cancellations.shutdownNow();
        transport.close();
    }

    public static final class ExecutionResult {
        private final WorkflowCodeWorkerClient owner;
        private final UUID requestId;
        private final String status;
        private final Object output;
        private final String errorCode;
        private final int httpStatus;
        private ExecutionResult(WorkflowCodeWorkerClient owner, UUID requestId, String status,
                                Object output, String errorCode, int httpStatus) {
            this.owner = owner;
            this.requestId = requestId;
            this.status = status;
            this.output = output;
            this.errorCode = errorCode;
            this.httpStatus = httpStatus;
        }
        public UUID requestId() { return requestId; }
        public String status() { return status; }
        public Object output() { return output; }
        public String errorCode() { return errorCode; }
        public int httpStatus() { return httpStatus; }
        public boolean succeeded() { return "succeeded".equals(status); }
    }

    interface Transport extends AutoCloseable {
        Response send(HttpRequest request) throws Exception;
        @Override default void close() { }
    }

    record Response(int status, InputStream body) implements AutoCloseable {
        @Override public void close() throws IOException { body.close(); }
    }
    private record WireResponse(int status, byte[] body) { }

    private static final class MtlsTransport implements Transport {
        private final String keyPath;
        private final String trustPath;
        private final char[] keyPassword;
        private final char[] trustPassword;
        private volatile HttpClient client;
        private MtlsTransport(String keyPath, String trustPath, String keyPassword, String trustPassword) {
            this.keyPath = keyPath;
            this.trustPath = trustPath;
            this.keyPassword = keyPassword == null ? new char[0] : keyPassword.toCharArray();
            this.trustPassword = trustPassword == null ? new char[0] : trustPassword.toCharArray();
        }
        @Override public Response send(HttpRequest request) throws Exception {
            HttpResponse<InputStream> response = client().send(request, HttpResponse.BodyHandlers.ofInputStream());
            return new Response(response.statusCode(), response.body());
        }
        private synchronized HttpClient client() throws Exception {
            if (client != null) return client;
            KeyStore keys = load(keyPath, keyPassword);
            KeyStore trust = load(trustPath, trustPassword);
            boolean hasIdentity = false;
            var aliases = keys.aliases();
            while (aliases.hasMoreElements()) {
                if (keys.entryInstanceOf(aliases.nextElement(), KeyStore.PrivateKeyEntry.class)) hasIdentity = true;
            }
            if (!hasIdentity || trust.size() == 0) throw new IOException("Worker TLS configuration unavailable");
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keys, keyPassword);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trust);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            SSLParameters parameters = new SSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            parameters.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
            client = HttpClient.newBuilder().sslContext(context).sslParameters(parameters)
                    .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5))
                    .proxy(new java.net.ProxySelector() {
                        @Override public List<java.net.Proxy> select(URI uri) { return List.of(java.net.Proxy.NO_PROXY); }
                        @Override public void connectFailed(URI uri, java.net.SocketAddress address, IOException error) { }
                    }).build();
            return client;
        }
        private static KeyStore load(String value, char[] password) throws Exception {
            Path path = Path.of(value);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(path))
                throw new IOException("Worker TLS configuration unavailable");
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (InputStream stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                store.load(stream, password);
            }
            return store;
        }
        @Override public synchronized void close() {
            Arrays.fill(keyPassword, '\0');
            Arrays.fill(trustPassword, '\0');
        }
    }
}
