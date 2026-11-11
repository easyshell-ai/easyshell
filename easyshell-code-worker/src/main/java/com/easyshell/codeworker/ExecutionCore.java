package com.easyshell.codeworker;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.io.IOAccess;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Experimental mechanism; default Worker never calls it. External isolation is required. */
final class ExecutionCore {
    static final int MAX_REQUEST = 65536, MAX_SOURCE = 32768, MAX_RESPONSE = 65536;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> FIELDS = Set.of("language", "source", "input", "timeoutMs");

    static byte[] run(InputStream in) {
        return run(in, false);
    }

    /** Only the fixed external-deployment entry point and mechanism tests call this. */
    static byte[] runIsolated(InputStream in) {
        return run(in, true);
    }

    private static byte[] run(InputStream in, boolean isolated) {
        try {
            byte[] bytes = in.readNBytes(MAX_REQUEST + 1);
            if (bytes.length > MAX_REQUEST) return error("REQUEST_TOO_LARGE");
            JsonNode root = JSON.readTree(bytes);
            if (root == null || !root.isObject() || root.size() != 4
                    || !FIELDS.containsAll(toFields(root)) || !root.path("source").isTextual()
                    || !root.path("language").isTextual() || !root.path("timeoutMs").isIntegralNumber()
                    || !root.path("timeoutMs").canConvertToInt() || !root.has("input")) return error("INVALID_REQUEST");
            String source = root.path("source").textValue();
            String lang = root.path("language").textValue();
            int timeout = root.path("timeoutMs").intValue();
            if (source.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE || timeout < 1 || timeout > 5000
                    || !(lang.equals("javascript") || lang.equals("python"))) return error("INVALID_REQUEST");
            if (lang.equals("python") && !isolated) return error("ISOLATION_UNVERIFIED");
            String input = JSON.writeValueAsString(root.get("input"));
            // Request validation ends here. Context/build/cleanup exceptions are execution failures.
            return executionBoundary(() -> evaluate(lang, source, input, timeout));
        } catch (Exception e) {
            return error("INVALID_REQUEST");
        }
    }

    /** Package-private so fixed tests can exercise initialization failures without relaxing permissions. */
    static byte[] executionBoundary(Callable<byte[]> execution) {
        try {
            return execution.call();
        } catch (Exception | LinkageError failure) {
            return error("EXECUTION_FAILED");
        }
    }

    private static Set<String> toFields(JsonNode root) {
        var fields = new java.util.HashSet<String>();
        root.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private static byte[] evaluate(String lang, String source, String input, int timeout) {
        String id = lang.equals("python") ? "python" : "js";
        var builder = Context.newBuilder(id).allowHostAccess(HostAccess.NONE).allowHostClassLookup(s -> false)
                .allowHostClassLoading(false)
                .allowIO(IOAccess.NONE).allowEnvironmentAccess(EnvironmentAccess.NONE).allowCreateProcess(false)
                .allowCreateThread(false).allowNativeAccess(false).in(InputStream.nullInputStream())
                .out(OutputStream.nullOutputStream()).err(OutputStream.nullOutputStream());
        if (id.equals("python")) builder.option("python.PosixModuleBackend", "java");
        Context context = builder.build();
        // Trusted startup allowance is enforced externally. Warm up before timing user source.
        try {
            context.initialize(id);
        } catch (Exception | LinkageError failure) {
            context.close(true);
            throw failure;
        }
        var executor = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; });
        try {
            var future = executor.submit(() -> {
                context.eval(id, source);
                // Copy input through language JSON parsing, never expose a host object.
                String quoted = JSON.writeValueAsString(input);
                String expression;
                if (id.equals("python")) {
                    context.eval(id, "import json");
                    expression = "json.dumps(main(json.loads(" + quoted + ")), allow_nan=False)";
                } else {
                    expression = "JSON.stringify(main(JSON.parse(" + quoted + ")))";
                }
                String result = context.eval(id, expression).asString();
                byte[] resultBytes = result.getBytes(StandardCharsets.UTF_8);
                if (resultBytes.length > MAX_RESPONSE - 64) return error("RESPONSE_TOO_LARGE");
                JsonNode output = JSON.readTree(resultBytes);
                if (output == null) return error("EXECUTION_FAILED");
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                buffer.write("{\"status\":\"succeeded\",\"output\":".getBytes(StandardCharsets.UTF_8));
                buffer.write(JSON.writeValueAsBytes(output));
                buffer.write('}');
                return buffer.size() <= MAX_RESPONSE ? buffer.toByteArray() : error("RESPONSE_TOO_LARGE");
            });
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            context.close(true);
            return error("TIMEOUT");
        } catch (Exception e) {
            return error("EXECUTION_FAILED");
        } finally {
            context.close(true);
            executor.shutdownNow();
        }
    }

    private static byte[] error(String code) {
        return ("{\"status\":\"failed\",\"error\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8);
    }
}
