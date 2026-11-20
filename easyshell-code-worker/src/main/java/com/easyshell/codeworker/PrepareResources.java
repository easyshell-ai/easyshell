package com.easyshell.codeworker;

import org.graalvm.polyglot.Engine;

import java.nio.file.Path;

/** Trusted build-time tool only; never a task entry point. Regenerate with dependency updates. */
public final class PrepareResources {
    private PrepareResources() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("No arguments accepted");
        Engine.copyResources(Path.of("build/graalvm-resources"), "js", "python");
    }
}
