package com.dbaagent.service.codescan;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeFileScannerHashTest {

    private final CodeFileScanner scanner = new CodeFileScanner();

    @Test
    void chunksFromSameFileShareSha256() throws Exception {
        Path dir = Files.createTempDirectory("hash-test-");
        // A file long enough to produce 2 chunks (>120 lines).
        StringBuilder body = new StringBuilder("import org.springframework.data.jpa.repository.JpaRepository;\n");
        body.append("@Repository public interface BookingRepo extends JpaRepository<Booking, Long> {\n");
        for (int i = 0; i < 200; i++) {
            body.append("  @Query(\"SELECT b FROM Booking b WHERE b.status_").append(i).append(" = ?1\") List<Booking> q").append(i).append("(String s);\n");
        }
        body.append("}\n");
        Path repo = Files.createDirectories(dir.resolve("svc"));
        Files.writeString(repo.resolve("BookingRepo.java"), body.toString(), StandardCharsets.UTF_8);

        List<CodeFileScanner.CodeChunk> chunks = scanner.scan(dir);
        assertTrue(chunks.size() >= 2, "expected file split into multiple chunks");
        String sha = chunks.get(0).fileSha256();
        assertNotNull(sha);
        assertEquals(64, sha.length(), "SHA-256 hex must be 64 chars");
        for (var c : chunks) {
            assertEquals(sha, c.fileSha256(), "all chunks from the same file share the same fileSha256");
        }
    }

    @Test
    void differentFileContentProducesDifferentSha() throws Exception {
        Path dir = Files.createTempDirectory("hash-test-diff-");
        Path a = Files.createDirectories(dir.resolve("a"));
        Path b = Files.createDirectories(dir.resolve("b"));
        Files.writeString(a.resolve("x.java"), "@Entity class A { @Query(\"SELECT 1\") int q(); }", StandardCharsets.UTF_8);
        Files.writeString(b.resolve("y.java"), "@Entity class B { @Query(\"SELECT 2\") int q(); }", StandardCharsets.UTF_8);
        var chunks = scanner.scan(dir);
        assertEquals(2, chunks.size());
        assertNotEquals(chunks.get(0).fileSha256(), chunks.get(1).fileSha256());
    }
}
