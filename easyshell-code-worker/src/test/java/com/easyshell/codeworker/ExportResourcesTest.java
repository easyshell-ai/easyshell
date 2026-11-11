package com.easyshell.codeworker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ExportResourcesTest {
    @TempDir Path directory;

    @Test void reservesNewDirectoryAndNeverOverwrites() throws Exception {
        Path output = directory.resolve("resources");
        assertEquals(output, ExportResources.reserve(new String[] {output.toString()}));
        Files.writeString(output.resolve("sentinel"), "synthetic");
        assertThrows(IOException.class, () -> ExportResources.reserve(new String[] {output.toString()}));
        assertEquals("synthetic", Files.readString(output.resolve("sentinel")));
    }

    @Test void rejectsArgumentsMissingParentsAndSymlinks() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> ExportResources.reserve(new String[0]));
        assertThrows(IllegalArgumentException.class, () -> ExportResources.reserve(new String[] {"a", "b"}));
        assertThrows(IOException.class, () -> ExportResources.reserve(new String[] {directory.resolve("missing/out").toString()}));
        Path link = directory.resolve("link");
        Files.createSymbolicLink(link, directory);
        assertThrows(IOException.class, () -> ExportResources.reserve(new String[] {link.resolve("out").toString()}));
    }
}
