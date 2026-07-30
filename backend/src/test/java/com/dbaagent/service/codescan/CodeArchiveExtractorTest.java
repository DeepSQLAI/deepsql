package com.dbaagent.service.codescan;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class CodeArchiveExtractorTest {

    private final CodeArchiveExtractor extractor = new CodeArchiveExtractor();

    @Test
    void skipsGitPackFilesWithoutFailing() throws Exception {
        byte[] zip = buildZip(builder -> {
            builder.addText("svc/Repo.java", "import jpa; @Entity class X {}");
            // simulate a >10MB git pack file inside the archive
            builder.addBytes(".git/objects/pack/pack-deadbeef.pack", new byte[12 * 1024 * 1024]);
            builder.addText("svc/Util.py", "cursor.execute('SELECT 1')");
        });
        var result = extractor.extract(new MockMultipartFile("file", "src.zip", "application/zip", zip));
        try {
            // Should have kept the two real source files and silently skipped the .git pack
            // (the junk-dir filter beats the size cap, but either way we don't throw).
            assertEquals(2, result.fileCount());
            assertTrue(Files.exists(result.root().resolve("svc/Repo.java")));
            assertTrue(Files.exists(result.root().resolve("svc/Util.py")));
            assertFalse(Files.exists(result.root().resolve(".git/objects/pack/pack-deadbeef.pack")));
        } finally {
            cleanup(result.root());
        }
    }

    @Test
    void skipsOversizedEntryOutsideJunkDirs() throws Exception {
        byte[] zip = buildZip(builder -> {
            builder.addText("ok.java", "@Entity class X {}");
            builder.addBytes("uploads/big.bin", new byte[12 * 1024 * 1024]); // > MAX_ENTRY_BYTES
        });
        var result = extractor.extract(new MockMultipartFile("file", "src.zip", "application/zip", zip));
        try {
            assertEquals(1, result.fileCount(), "the big binary should be dropped, ok.java retained");
            assertTrue(Files.exists(result.root().resolve("ok.java")));
            assertFalse(Files.exists(result.root().resolve("uploads/big.bin")));
        } finally {
            cleanup(result.root());
        }
    }

    @Test
    void rejectsPathTraversalEntries() throws Exception {
        byte[] zip = buildZip(builder -> {
            builder.addText("../escape.java", "@Entity class X {}");
            builder.addText("ok.java", "@Entity class Y {}");
        });
        var result = extractor.extract(new MockMultipartFile("file", "src.zip", "application/zip", zip));
        try {
            assertEquals(1, result.fileCount());
            assertTrue(Files.exists(result.root().resolve("ok.java")));
        } finally {
            cleanup(result.root());
        }
    }

    @Test
    void extractsPlainTarArchive() throws Exception {
        byte[] tar = buildTar(builder -> {
            builder.addText("svc/Repo.java", "@Entity class X {}");
            builder.addText("svc/Util.py", "cursor.execute('SELECT 1')");
        });
        var result = extractor.extract(new MockMultipartFile("file", "src.tar", "application/x-tar", tar));
        try {
            assertEquals(2, result.fileCount());
            assertTrue(Files.exists(result.root().resolve("svc/Repo.java")));
            assertTrue(Files.exists(result.root().resolve("svc/Util.py")));
        } finally {
            cleanup(result.root());
        }
    }

    @Test
    void extractsGzippedTarArchive() throws Exception {
        byte[] targz = gzip(buildTar(builder -> {
            builder.addText("app/Main.java", "@Entity class M {}");
            // a junk-dir entry must be dropped on the tar path too
            builder.addText("node_modules/dep/index.js", "module.exports = {}");
        }));
        var result = extractor.extract(new MockMultipartFile("file", "src.tar.gz", "application/gzip", targz));
        try {
            assertEquals(1, result.fileCount(), "only the real source file should be kept");
            assertTrue(Files.exists(result.root().resolve("app/Main.java")));
            assertFalse(Files.exists(result.root().resolve("node_modules/dep/index.js")));
        } finally {
            cleanup(result.root());
        }
    }

    @Test
    void rejectsUnrecognizedArchiveFormat() {
        byte[] junk = "this is plain text, not an archive at all".getBytes(StandardCharsets.UTF_8);
        var upload = new MockMultipartFile("file", "notes.txt", "text/plain", junk);
        assertThrows(IOException.class, () -> extractor.extract(upload));
    }

    // ---- helpers ----

    private static byte[] buildZip(java.util.function.Consumer<ZipBuilder> consumer) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(buf)) {
            consumer.accept(new ZipBuilder(out));
        }
        return buf.toByteArray();
    }

    private static void cleanup(Path root) throws Exception {
        if (root == null) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignore) { /* */ }
            });
        }
    }

    private record ZipBuilder(ZipOutputStream out) {
        void addText(String name, String content) {
            addBytes(name, content.getBytes(StandardCharsets.UTF_8));
        }
        void addBytes(String name, byte[] bytes) {
            try {
                out.putNextEntry(new ZipEntry(name));
                out.write(bytes);
                out.closeEntry();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static byte[] buildTar(java.util.function.Consumer<TarBuilder> consumer) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (TarArchiveOutputStream out = new TarArchiveOutputStream(buf)) {
            consumer.accept(new TarBuilder(out));
        }
        return buf.toByteArray();
    }

    private static byte[] gzip(byte[] data) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (GzipCompressorOutputStream gz = new GzipCompressorOutputStream(buf)) {
            gz.write(data);
        }
        return buf.toByteArray();
    }

    private record TarBuilder(TarArchiveOutputStream out) {
        void addText(String name, String content) {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            try {
                TarArchiveEntry entry = new TarArchiveEntry(name);
                entry.setSize(bytes.length);
                out.putArchiveEntry(entry);
                out.write(bytes);
                out.closeArchiveEntry();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
