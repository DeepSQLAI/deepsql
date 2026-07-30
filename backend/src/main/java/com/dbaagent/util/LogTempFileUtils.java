package com.dbaagent.util;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class LogTempFileUtils {
    private LogTempFileUtils() {
    }

    public static Path createTempFile(String tempDir, long minFreeDiskMb, String prefix, String suffix) throws IOException {
        Path directory = resolveTempDir(tempDir);
        ensureMinFreeDisk(directory, minFreeDiskMb);
        return Files.createTempFile(directory, prefix, suffix);
    }

    public static InputStream openDeleteOnClose(Path tempFile) throws IOException {
        return new FileInputStream(tempFile.toFile()) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    try {
                        Files.deleteIfExists(tempFile);
                    } catch (IOException ignored) {
                        // Best-effort cleanup
                    }
                }
            }
        };
    }

    private static Path resolveTempDir(String tempDir) throws IOException {
        if (tempDir == null || tempDir.isBlank()) {
            return Paths.get(System.getProperty("java.io.tmpdir"));
        }
        Path directory = Paths.get(tempDir);
        Files.createDirectories(directory);
        return directory;
    }

    private static void ensureMinFreeDisk(Path directory, long minFreeDiskMb) throws IOException {
        if (minFreeDiskMb <= 0) {
            return;
        }
        FileStore store = Files.getFileStore(directory);
        long freeBytes = store.getUsableSpace();
        long minBytes = minFreeDiskMb * 1024 * 1024;
        if (freeBytes < minBytes) {
            throw new IOException(String.format(
                "Insufficient disk space in %s: %d bytes free (min %d bytes required)",
                directory, freeBytes, minBytes));
        }
    }
}
