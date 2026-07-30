package com.dbaagent.service.codescan;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;

/**
 * Safely extracts an uploaded source archive into a temp dir. Supports plain
 * {@code .zip} and {@code .tar} archives as well as compressed {@code .tar.gz}
 * / {@code .tgz} uploads (any archive + compressor combination Apache Commons
 * Compress can auto-detect from the stream signature).
 *
 * <p>Enforces total-size, file-count, and per-entry-size limits to prevent zip
 * bombs, and rejects any entry whose path escapes the destination root.
 */
@Component
@Slf4j
public class CodeArchiveExtractor {

    public static final long MAX_TOTAL_BYTES = 200L * 1024 * 1024; // 200 MB extracted
    public static final long MAX_ENTRY_BYTES = 10L * 1024 * 1024;  // 10 MB per file
    public static final int MAX_FILES = 50_000;

    /** Path segments we never extract — they bloat the archive and add nothing for code analysis. */
    private static final Set<String> SKIP_SEGMENTS = Set.of(
        ".git", "node_modules", "target", "build", "dist", ".gradle",
        "__pycache__", ".venv", "venv", "vendor", ".idea", ".vscode",
        "out", "bin", ".next", ".turbo", ".cache", ".DS_Store"
    );

    public Result extract(MultipartFile upload) throws IOException {
        if (upload == null || upload.isEmpty()) {
            throw new IllegalArgumentException("uploaded archive is empty");
        }

        Path root = Files.createTempDirectory("dba-code-scan-");
        long totalBytes = 0L;
        int fileCount = 0;
        String archiveSha256;

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }

        try (InputStream raw = upload.getInputStream();
             DigestInputStream digestStream = new DigestInputStream(raw, digest);
             BufferedInputStream buffered = new BufferedInputStream(digestStream)) {

            byte[] buffer = new byte[8192];
            int skippedJunk = 0;
            int skippedTooLarge = 0;

            try (ArchiveInputStream<?> archive = openArchive(buffered)) {
                ArchiveEntry entry;
                while ((entry = archive.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (name == null || name.isBlank()) {
                        continue;
                    }
                    if (entry.isDirectory()) {
                        continue;
                    }
                    if (name.contains("..") || name.startsWith("/") || name.contains("\\")) {
                        log.warn("rejecting suspicious archive entry: {}", name);
                        continue;
                    }
                    if (hasSkippedSegment(name)) {
                        // .git/, node_modules/, target/, etc. — drain bytes and move on.
                        drainEntry(archive, buffer);
                        skippedJunk++;
                        continue;
                    }

                    Path target = root.resolve(name).normalize();
                    if (!target.startsWith(root)) {
                        log.warn("rejecting path-traversal entry: {}", name);
                        continue;
                    }

                    Files.createDirectories(target.getParent());

                    long entryBytes = 0L;
                    boolean entryTooLarge = false;
                    try (var out = Files.newOutputStream(target)) {
                        int n;
                        while ((n = archive.read(buffer)) > 0) {
                            entryBytes += n;
                            if (entryBytes > MAX_ENTRY_BYTES) {
                                entryTooLarge = true;
                                break;
                            }
                            totalBytes += n;
                            if (totalBytes > MAX_TOTAL_BYTES) {
                                throw new IOException("archive exceeds " + MAX_TOTAL_BYTES + " total bytes");
                            }
                            out.write(buffer, 0, n);
                        }
                    }
                    if (entryTooLarge) {
                        Files.deleteIfExists(target);
                        drainEntry(archive, buffer);
                        skippedTooLarge++;
                        continue;
                    }
                    fileCount++;
                    if (fileCount > MAX_FILES) {
                        throw new IOException("archive exceeds " + MAX_FILES + " files");
                    }
                }

                // Drain whatever the archive reader leaves unread (zip central
                // directory, gzip trailer, tar padding) so the SHA-256 covers
                // the whole upload, not just the entry data.
                while (buffered.read(buffer) != -1) {
                    // discard
                }
            }
            if (skippedJunk > 0 || skippedTooLarge > 0) {
                log.info("archive extract: kept {} files, skipped {} junk-dir entries, skipped {} oversized entries",
                    fileCount, skippedJunk, skippedTooLarge);
            }
            archiveSha256 = bytesToHex(digest.digest());
        }

        return new Result(root, totalBytes, fileCount, archiveSha256);
    }

    /**
     * Detects the upload's archive format and returns a reader positioned at its
     * first entry. Transparently unwraps a gzip/bzip2/xz compressor layer (so
     * {@code .tar.gz} works), then auto-detects the underlying archive
     * (zip/tar). The supplied stream must support {@code mark()}.
     *
     * @throws IOException if the upload is not a recognised archive format
     */
    private static ArchiveInputStream<?> openArchive(BufferedInputStream upload) throws IOException {
        BufferedInputStream content;
        try {
            // A compressed wrapper (.gz/.bz2/.xz) — unwrap it. A plain (already
            // uncompressed) archive raises CompressorException, so we fall back
            // to reading `upload` directly.
            content = new BufferedInputStream(
                new CompressorStreamFactory().createCompressorInputStream(upload));
        } catch (CompressorException notCompressed) {
            content = upload;
        }
        try {
            return new ArchiveStreamFactory().createArchiveInputStream(content);
        } catch (ArchiveException e) {
            throw new IOException(
                "unsupported archive format — upload a .zip, .tar, or .tar.gz file", e);
        }
    }

    private static boolean hasSkippedSegment(String name) {
        for (String segment : name.split("/")) {
            if (SKIP_SEGMENTS.contains(segment)) return true;
        }
        return false;
    }

    /** Read and discard the rest of the current archive entry. */
    private static void drainEntry(ArchiveInputStream<?> archive, byte[] buffer) throws IOException {
        while (archive.read(buffer) > 0) {
            // discard
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** Lightweight wrapper so we can update the digest while reading. */
    private static final class DigestInputStream extends java.io.FilterInputStream {
        private final MessageDigest digest;
        DigestInputStream(InputStream in, MessageDigest digest) {
            super(in);
            this.digest = digest;
        }
        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) digest.update((byte) b);
            return b;
        }
        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) digest.update(b, off, n);
            return n;
        }
    }

    public record Result(Path root, long totalBytes, int fileCount, String archiveSha256) {}
}
