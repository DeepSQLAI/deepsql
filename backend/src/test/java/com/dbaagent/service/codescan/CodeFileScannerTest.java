package com.dbaagent.service.codescan;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodeFileScannerTest {

    private final CodeFileScanner scanner = new CodeFileScanner();

    @Test
    void keepsJavaFilesWithDbSignal() throws Exception {
        Path dir = Files.createTempDirectory("scan-test-");
        Path repo = Files.createDirectories(dir.resolve("svc"));
        Files.writeString(
            repo.resolve("BookingRepo.java"),
            """
            package svc;
            import org.springframework.data.jpa.repository.JpaRepository;
            @Repository
            public interface BookingRepo extends JpaRepository<Booking, Long> {
                @Query("SELECT b FROM Booking b WHERE b.status = 'ACTIVE'")
                List<Booking> findActive();
            }
            """,
            StandardCharsets.UTF_8
        );
        // file with no DB signal — should be filtered out
        Files.writeString(
            repo.resolve("Util.java"),
            "package svc; class Util { static int add(int a, int b) { return a+b; } }",
            StandardCharsets.UTF_8
        );

        List<CodeFileScanner.CodeChunk> chunks = scanner.scan(dir);
        assertEquals(1, chunks.size(), "only the repo with SQL/JPA should make it through");
        assertEquals("svc/BookingRepo.java", chunks.get(0).path());
        assertEquals("java", chunks.get(0).language());
    }

    @Test
    void skipsNodeModulesAndDotGit() throws Exception {
        Path dir = Files.createTempDirectory("scan-test-skip-");
        Path noisy = Files.createDirectories(dir.resolve("node_modules").resolve("foo"));
        Files.writeString(
            noisy.resolve("vendored.js"),
            "exports.q = 'SELECT * FROM users';",
            StandardCharsets.UTF_8
        );
        Path git = Files.createDirectories(dir.resolve(".git"));
        Files.writeString(git.resolve("config"), "[core] repositoryformatversion = 0", StandardCharsets.UTF_8);

        Path real = Files.createDirectories(dir.resolve("app"));
        Files.writeString(
            real.resolve("orders.py"),
            "def list_orders():\n    cursor.execute('SELECT id FROM orders')\n    return cursor.fetchall()\n",
            StandardCharsets.UTF_8
        );

        List<CodeFileScanner.CodeChunk> chunks = scanner.scan(dir);
        assertEquals(1, chunks.size());
        assertEquals("app/orders.py", chunks.get(0).path());
    }

    @Test
    void skipsLikelySecretFiles() throws Exception {
        Path dir = Files.createTempDirectory("scan-test-secrets-");
        Files.writeString(dir.resolve(".env"), "DB_PASSWORD=hunter2", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("secrets.yml"), "token: SELECT-but-secret", StandardCharsets.UTF_8);

        Path app = Files.createDirectories(dir.resolve("app"));
        Files.writeString(
            app.resolve("dao.go"),
            "package app\nimport \"gorm.io/gorm\"\nfunc Q(db *gorm.DB) { db.Where(\"x = ?\", 1).Find(nil) }\n",
            StandardCharsets.UTF_8
        );

        List<CodeFileScanner.CodeChunk> chunks = scanner.scan(dir);
        assertEquals(1, chunks.size());
        assertEquals("app/dao.go", chunks.get(0).path());
    }
}
