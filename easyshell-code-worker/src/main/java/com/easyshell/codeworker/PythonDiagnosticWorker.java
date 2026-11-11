package com.easyshell.codeworker;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.io.IOAccess;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Trusted fixed probe only. Never reads stdin/source; requires external isolation and deadline. */
public final class PythonDiagnosticWorker {
    private PythonDiagnosticWorker() { }

    static String category(Throwable error) {
        if (error instanceof PolyglotException polyglot) {
            if (polyglot.isResourceExhausted()) return "RESOURCE_EXHAUSTED";
            if (polyglot.isCancelled()) return "CANCELLED";
            if (polyglot.isHostException()) return "HOST_EXCEPTION";
            if (polyglot.isInternalError()) return "INTERNAL_ERROR";
            if (polyglot.isGuestException()) return "GUEST_EXCEPTION";
            return "POLYGLOT_EXCEPTION";
        }
        if (error instanceof SecurityException) return "SECURITY_EXCEPTION";
        if (error instanceof LinkageError) return "LINKAGE_ERROR";
        if (error instanceof IllegalArgumentException) return "CONFIGURATION_ERROR";
        if (error instanceof IllegalStateException) return "STATE_ERROR";
        return "RUNTIME_ERROR";
    }

    /** Only fixed initialization probes may use this; never general user-code diagnostics. */
    static String diagnostic(Throwable error, String stage) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        List<String> types = new ArrayList<>();
        var predicates = new LinkedHashSet<String>();
        Throwable current = error;
        while (current != null && types.size() < 8 && seen.add(current)) {
            String type = current.getClass().getName();
            types.add(type.matches("[A-Za-z0-9_.$]{1,200}") ? type : "unknown");
            if (current instanceof UnsatisfiedLinkError || type.contains("ClassNotFound")
                    || type.contains("NoClassDefFound")) predicates.add("MISSING_LIBRARY");
            String message = current.getMessage();
            if (message != null) {
                // Inspect a bounded message prefix but emit only fixed predicate labels.
                String text = message.substring(0, Math.min(message.length(), 8192)).toLowerCase(Locale.ROOT);
                if ((text.contains("read") || text.contains("file"))
                        && (text.contains("denied") || text.contains("not allowed"))) predicates.add("FILE_READ_DENIED");
                if (text.contains("resource cache") || text.contains("org.graalvm.polyglot")
                        && text.contains("cache")) predicates.add("RESOURCE_CACHE");
                if (text.contains("zipfs") || text.contains("provider")
                        && (text.contains("jar") || text.contains("zip"))) predicates.add("ZIPFS_PROVIDER");
                if (text.contains("user.home") || text.contains("user home")) predicates.add("USER_HOME");
                if (text.contains("thread") && (text.contains("not allowed") || text.contains("denied")))
                    predicates.add("THREAD_DENIED");
                if (text.contains("getpwuid") || text.contains("getpwnam") || text.contains("passwd")
                        || text.contains("unixsystem") || text.contains("user lookup")
                        || text.contains("username")) predicates.add("USER_LOOKUP");
                if (text.contains("locale") || text.contains("charset") || text.contains("encoding"))
                    predicates.add("LOCALE");
                if (text.contains("timezone") || text.contains("time zone") || text.contains("tzdb")
                        || text.contains("zoneinfo")) predicates.add("TIMEZONE");
                if (text.contains("resource")) predicates.add("RESOURCE");
                if (text.contains("unsatisfiedlink") || text.contains("can't load library")
                        || text.contains("cannot open shared object") || text.contains("no such library")
                        || text.contains("classnotfound") || text.contains("noclassdeffound"))
                    predicates.add("MISSING_LIBRARY");
            }
            // Only emit fixed labels from frame matches, never source files, frame text or lines.
            StackTraceElement[] frames = current.getStackTrace();
            for (int i = 0; i < Math.min(frames.length, 64); i++) {
                String name = frames[i].getClassName();
                String method = frames[i].getMethodName();
                if (name.equals("com.sun.security.auth.module.UnixSystem")
                        || method.equals("getpwuid") || method.equals("getpwnam")) predicates.add("USER_LOOKUP");
                if (name.contains("PythonLocale")) predicates.add("LOCALE");
                if (name.startsWith("java.time.zone.")) predicates.add("TIMEZONE");
                if (name.contains("InternalResource")) predicates.add("RESOURCE");
                if (name.startsWith("jdk.nio.zipfs.")) predicates.add("ZIPFS_PROVIDER");
            }
            Throwable next = current.getCause();
            if (next == null && current instanceof PolyglotException polyglot && polyglot.isHostException())
                next = polyglot.asHostException();
            current = next;
        }
        if (predicates.isEmpty()) predicates.add("UNCLASSIFIED");
        String safeStage = List.of("context_create", "literal_eval", "context_close").contains(stage) ? stage : "unknown";
        return "{\"exception_class\":\"" + types.get(0) + "\",\"category\":\"" + category(error)
                + "\",\"stage\":\"" + safeStage + "\",\"cause_classes\":[\""
                + String.join("\",\"", types) + "\"],\"predicates\":[\""
                + String.join("\",\"", predicates) + "\"]}";
    }

    static String boundedMessage(String message, Map<String, String> environment) {
        if (message == null) return "";
        String text = message;
        // Trusted probe only: never serialize environment names/values, replace before truncation.
        for (String value : environment.values().stream().filter(v -> !v.isEmpty())
                .sorted((a, b) -> Integer.compare(b.length(), a.length())).toList()) {
            text = text.replace(value, "[ENV]");
        }
        StringBuilder safe = new StringBuilder();
        text.codePoints().filter(c -> !Character.isISOControl(c) && Character.getType(c) != Character.FORMAT)
                .limit(1000).forEach(safe::appendCodePoint);
        return safe.toString();
    }

    static String detailedDiagnostic(Throwable error, String stage, Map<String, String> environment) {
        try {
            ObjectMapper json = new ObjectMapper();
            var result = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(diagnostic(error, stage));
            var messages = result.putArray("initialization_messages");
            var frames = result.putArray("class_methods");
            var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
            Throwable current = error;
            int remaining = 1000;
            while (current != null && seen.size() < 8 && seen.add(current)) {
                String message = boundedMessage(current.getMessage(), environment);
                int end = message.offsetByCodePoints(0, Math.min(remaining, message.codePointCount(0, message.length())));
                messages.add(message.substring(0, end));
                remaining -= message.codePointCount(0, end);
                for (StackTraceElement frame : current.getStackTrace()) {
                    if (frames.size() == 64) break;
                    String name = frame.getClassName() + "#" + frame.getMethodName();
                    frames.add(name.matches("[A-Za-z0-9_.$#<>]{1,300}") ? name : "unknown");
                }
                Throwable next = current.getCause();
                if (next == null && current instanceof PolyglotException p && p.isHostException()) next = p.asHostException();
                current = next;
            }
            return json.writeValueAsString(result);
        } catch (Exception diagnosticFailure) {
            return "{\"category\":\"DIAGNOSTIC_FORMAT_FAILED\"}";
        }
    }

    public static void main(String[] args) {
        if (args.length != 0) {
            System.out.println("{\"status\":\"failed\",\"error\":\"INVALID_REQUEST\"}");
            return;
        }
        String stage = "context_create";
        try (Context context = Context.newBuilder("python")
                .allowHostAccess(HostAccess.NONE).allowHostClassLookup(name -> false)
                .allowHostClassLoading(false).allowIO(IOAccess.NONE)
                .allowEnvironmentAccess(EnvironmentAccess.NONE).allowCreateProcess(false)
                .allowCreateThread(false).allowNativeAccess(false)
                .option("python.PosixModuleBackend", "java")
                .in(InputStream.nullInputStream()).out(OutputStream.nullOutputStream())
                .err(OutputStream.nullOutputStream()).build()) {
            stage = "literal_eval";
            if (context.eval("python", "1+1").asInt() != 2) throw new IllegalStateException();
            stage = "context_close";
        } catch (Throwable error) {
            System.err.println(detailedDiagnostic(error, stage, System.getenv()));
            System.out.println("{\"status\":\"failed\",\"error\":\"DIAGNOSTIC_FAILED\"}");
            System.exit(1);
            return;
        }
        System.out.println("{\"status\":\"succeeded\",\"probe\":\"python_literal\"}");
    }
}
