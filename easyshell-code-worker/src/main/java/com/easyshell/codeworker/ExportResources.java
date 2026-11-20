package com.easyshell.codeworker;

import org.graalvm.polyglot.Engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Trusted build-only export. No Context, guest execution, environment copying or overwrite. */
public final class ExportResources {
    private ExportResources() { }

    static Path reserve(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("Exactly one new output directory required");
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        Path parent = output.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || !parent.toRealPath().equals(parent)) {
            throw new IOException("Output parent must exist without symbolic links");
        }
        // Atomic reservation: reject every existing target, including symlinks and partial exports.
        Files.createDirectory(output);
        return output;
    }

    public static void main(String[] args) throws Exception {
        Path output = reserve(args);
        // Fixed language set; dependencies pin the matching 25.2.4 resource providers.
        // Leave partial output on failure for inspection, never recursively delete it.
        if (!Engine.copyResources(output, "js", "python")) {
            throw new IOException("No language resources exported");
        }
        Files.writeString(output.resolve("easyshell-resource-version.txt"), "25.2.4\njs\npython\n",
                java.nio.file.StandardOpenOption.CREATE_NEW);
    }
}
