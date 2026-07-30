package com.dbaagent.service.codescan;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Walks the extracted source tree, drops files that don't look DB-relevant,
 * and emits bounded {@link CodeChunk}s for the LLM extractor.
 *
 * The pre-filter is intentionally permissive — extraction is LLM-driven, so
 * we only need to keep noise (license headers, vendored CSS, generated code)
 * out of the prompt budget.
 */
@Component
@Slf4j
public class CodeFileScanner {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
        "java", "kt", "kts", "scala",
        "py",
        "js", "jsx", "mjs", "cjs",
        "ts", "tsx",
        "go",
        "rb",
        "php",
        "cs",
        "sql",
        "xml", "yml", "yaml", "properties"
    );

    private static final Set<String> SKIP_PATH_SEGMENTS = Set.of(
        "node_modules", "target", "build", "dist", ".git", ".gradle",
        "__pycache__", ".venv", "venv", "vendor", ".idea", ".vscode",
        "out", "bin", ".next", ".turbo"
    );

    /** Files we will *never* read — likely to contain secrets. */
    private static final List<Pattern> SECRET_FILE_PATTERNS = List.of(
        Pattern.compile("(?i).*\\.env(\\..+)?$"),
        Pattern.compile("(?i).*secrets?(\\..+)?$"),
        Pattern.compile("(?i).*credentials?(\\..+)?$"),
        Pattern.compile("(?i).*\\.(pem|key|p12|pfx|jks)$"),
        Pattern.compile("(?i).*id_rsa.*"),
        Pattern.compile("(?i).*\\.htpasswd$")
    );

    /** Cheap "this file probably touches the DB" signal. */
    private static final Pattern DB_SIGNAL = Pattern.compile(
        "(?is).*(\\b(SELECT|INSERT|UPDATE|DELETE|MERGE|FROM|JOIN|WHERE|CREATE\\s+TABLE)\\b"
        + "|@(Entity|Table|Column|Query|Repository|MappedSuperclass|JoinColumn|ManyToOne|OneToMany)"
        + "|JdbcTemplate|EntityManager|executeQuery|prepareStatement|createQuery|preparedStatement"
        + "|PreparedStatement|ResultSet"
        + "|sequelize\\.define|sequelize\\.query|knex\\(|prisma\\."
        + "|sqlalchemy|Base\\s*=\\s*declarative_base|session\\.query|cursor\\.execute"
        + "|ActiveRecord|belongs_to|has_many|has_one|scope\\s+:"
        + "|gorm:|gorm\\.|db\\.Where|db\\.Find|db\\.Model"
        + "|TypeOrm|TypeORM|getRepository|createQueryBuilder"
        + "|Mongo|MongoClient|find\\(|insertOne|updateOne)"
        + ".*"
    );

    public static final int MAX_LINES_PER_CHUNK = 120;
    public static final int MAX_BYTES_PER_CHUNK = 6_000;

    public List<CodeChunk> scan(Path root) throws IOException {
        return scan(root, null);
    }

    /**
     * Scan with optional focus. When focus is non-empty, chunks whose body
     * mentions any focus token (ALL_CAPS identifiers in the focus text) sort
     * to the front of the returned list. The caller can then cap to a budget
     * and naturally drop the noisier non-focus chunks first.
     */
    public List<CodeChunk> scan(Path root, String focus) throws IOException {
        Set<String> focusTokens = extractFocusTokens(focus);
        List<CodeChunk> chunks = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                .filter(p -> !shouldSkipPath(p))
                .filter(this::extensionAllowed)
                .filter(this::notLikelyASecretFile)
                .forEach(file -> addChunksFromFile(root, file, chunks));
        }
        if (!focusTokens.isEmpty()) {
            // Stable partition: focus-positive chunks first, others second.
            List<CodeChunk> positive = new ArrayList<>();
            List<CodeChunk> negative = new ArrayList<>();
            for (CodeChunk chunk : chunks) {
                if (matchesAnyToken(chunk.body(), focusTokens)) {
                    positive.add(chunk);
                } else {
                    negative.add(chunk);
                }
            }
            log.info("focus prioritisation: {} focus-positive chunks, {} other chunks",
                positive.size(), negative.size());
            positive.addAll(negative);
            return positive;
        }
        return chunks;
    }

    private static Set<String> extractFocusTokens(String focus) {
        if (focus == null || focus.isBlank()) return Set.of();
        Set<String> tokens = new java.util.HashSet<>();
        var matcher = java.util.regex.Pattern
            .compile("\\b[A-Z][A-Z0-9_]{2,}\\b")
            .matcher(focus);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    private static boolean matchesAnyToken(String body, Set<String> tokens) {
        if (body == null || body.isEmpty()) return false;
        // Cheap substring check; the body is bounded to MAX_BYTES_PER_CHUNK so
        // O(N * tokens) is acceptable.
        for (String t : tokens) {
            if (body.contains(t)) return true;
        }
        return false;
    }

    private boolean shouldSkipPath(Path path) {
        for (Path segment : path) {
            if (SKIP_PATH_SEGMENTS.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private boolean extensionAllowed(Path path) {
        String fileName = path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return false;
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ALLOWED_EXTENSIONS.contains(ext);
    }

    private boolean notLikelyASecretFile(Path path) {
        String name = path.getFileName().toString();
        return SECRET_FILE_PATTERNS.stream().noneMatch(p -> p.matcher(name).matches());
    }

    private void addChunksFromFile(Path root, Path file, List<CodeChunk> out) {
        try {
            String body;
            try {
                body = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException ioe) {
                // Likely binary or non-UTF-8; skip silently.
                return;
            }
            if (body.isBlank()) return;
            if (!DB_SIGNAL.matcher(body).matches()) {
                return;
            }
            String relPath = root.relativize(file).toString().replace('\\', '/');
            String language = detectLanguage(file.getFileName().toString());
            String fileSha = sha256(body);

            String[] lines = body.split("\n", -1);
            int total = lines.length;
            int start = 0;
            while (start < total) {
                int end = Math.min(start + MAX_LINES_PER_CHUNK, total);
                StringBuilder chunkBody = new StringBuilder();
                int bytes = 0;
                int lineCursor = start;
                while (lineCursor < end) {
                    String line = lines[lineCursor];
                    int lineBytes = line.length() + 1;
                    if (bytes + lineBytes > MAX_BYTES_PER_CHUNK && chunkBody.length() > 0) {
                        break;
                    }
                    chunkBody.append(line).append('\n');
                    bytes += lineBytes;
                    lineCursor++;
                }
                if (chunkBody.length() > 0) {
                    out.add(new CodeChunk(
                        relPath,
                        start + 1,
                        lineCursor,
                        language,
                        chunkBody.toString(),
                        fileSha
                    ));
                }
                start = lineCursor;
            }
        } catch (RuntimeException re) {
            log.warn("failed to chunk {}: {}", file, re.getMessage());
        }
    }

    private static String sha256(String body) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            // Should never happen with SHA-256; fall back to "" so the chunk is
            // always treated as "changed" (safe degradation).
            return "";
        }
    }

    private String detectLanguage(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return "text";
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * A bounded chunk of source code emitted to the LLM extractor. {@link #fileSha256}
     * is the SHA-256 of the FULL file the chunk came from (not just the chunk body) —
     * the orchestrator uses it to look up whether this file changed since the last
     * scan and skip the LLM call when it didn't.
     */
    public record CodeChunk(
        String path,
        int startLine,
        int endLine,
        String language,
        String body,
        String fileSha256
    ) {}
}
