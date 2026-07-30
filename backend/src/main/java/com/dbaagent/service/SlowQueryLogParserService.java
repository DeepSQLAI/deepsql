package com.dbaagent.service;

import com.dbaagent.model.SlowQuery;
import com.dbaagent.model.SlowQueryAnalysis;
import com.dbaagent.model.SlowQuerySource;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.util.TruncatingOutputStream;
import com.dbaagent.util.QueryNormalizer;
import com.dbaagent.util.SqlLiteralSubstitution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Service for parsing MySQL and PostgreSQL slow query log files
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlowQueryLogParserService {
    private final DatabaseProviderRegistry providerRegistry;
    private static final long MAX_IN_MEMORY_BYTES = 5 * 1024 * 1024; // 5MB
    private static final int SNIFF_BYTES = 8192;
    @org.springframework.beans.factory.annotation.Value("${slow-query.log.max-parsed:10000}")
    private int maxParsedQueries = 10000;

    @org.springframework.beans.factory.annotation.Value("${slow-query.log.max-top:200}")
    private int maxTopQueries = 200;

    // 64 MB default (was 500 MB). On the -Xmx3g backend a 500 MB slow-log file
    // parsed into query objects + analysis (alongside concurrent scheduled tasks)
    // can exhaust the heap. 64 MB is ample for the 10k-query parse cap yet leaves
    // headroom; operators can raise it via slow-query.log.max-bytes. Oversized
    // logs are truncated to this cap and the analysis is flagged truncated.
    @org.springframework.beans.factory.annotation.Value("${slow-query.log.max-bytes:67108864}")
    private long maxLogBytes = 67108864L;

    @org.springframework.beans.factory.annotation.Value("${slow-query.log.temp-dir:}")
    private String tempDir;

    @org.springframework.beans.factory.annotation.Value("${slow-query.log.min-free-disk-mb:1024}")
    private long minFreeDiskMb = 1024L;

    /**
     * Temporary log file holder with auto-cleanup.
     * Call keepOpen() to prevent deletion on close (for batch processing).
     */
    public static class TempLogFile implements AutoCloseable {
        private final Path path;
        private final long size;
        private final boolean truncated;
        private boolean shouldDelete = true;

        public TempLogFile(Path path, long size) { this(path, size, false); }
        public TempLogFile(Path path, long size, boolean truncated) {
            this.path = path; this.size = size; this.truncated = truncated;
        }

        public Path path() { return path; }
        public long size() { return size; }
        public boolean truncated() { return truncated; }

        /**
         * Prevent deletion on close. Use when passing file to another step.
         */
        public void keepOpen() {
            this.shouldDelete = false;
        }

        @Override
        public void close() {
            if (shouldDelete && path != null) {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // Best-effort cleanup
                }
            }
        }
    }

    /**
     * Parse and analyze a slow query log file
     */
    public SlowQueryAnalysis parseAndAnalyze(
            InputStream inputStream,
            String databaseType,
            String connectionId
    ) throws Exception {
        log.info("Parsing slow query log file for database type: {}", databaseType);

        try (TempLogFile tempLog = copyToTempFile(inputStream)) {
            SlowQueryAnalysis analysis = parseAndAnalyze(tempLog.path(), databaseType, connectionId);
            if (tempLog.truncated()) analysis.setTruncated(true);
            return analysis;
        }
    }

    public SlowQueryAnalysis parseAndAnalyze(
            Path logFile,
            String databaseType,
            String connectionId
    ) throws Exception {
        List<SlowQuery> queries;
        long size = Files.size(logFile);
        boolean sizeTruncated = false;
        Path truncatedTemp = null;
        if (maxLogBytes > 0 && size > maxLogBytes) {
            log.warn("Log file {} bytes exceeds max {} bytes; truncating to the cap and flagging the analysis.",
                size, maxLogBytes);
            truncatedTemp = truncateToTempFile(logFile, maxLogBytes);
            logFile = truncatedTemp;
            size = Files.size(logFile);
            sizeTruncated = true;
        }
        try {
            if (size <= MAX_IN_MEMORY_BYTES) {
                byte[] rawBytes = Files.readAllBytes(logFile);
                String rawContent = new String(rawBytes, StandardCharsets.UTF_8);

                CloudWatchLog extract = extractCloudWatchLog(rawContent);
                byte[] parseBytes = rawBytes;
                if (extract != null && !extract.logContent.isBlank()) {
                    parseBytes = extract.logContent.getBytes(StandardCharsets.UTF_8);
                    log.info("Detected CloudWatch CSV export ({} entries)", extract.entries);
                }

                queries = parseWithFallback(parseBytes, databaseType);
            } else {
                log.info("Large log detected ({} bytes). Attempting streaming CloudWatch extraction.", size);
                Path extracted = streamExtractCloudWatchLog(logFile);
                if (extracted != null) {
                    try {
                        log.info("CloudWatch CSV detected in large file, parsing extracted log content.");
                        queries = parseWithFallback(extracted, databaseType);
                    } finally {
                        Files.deleteIfExists(extracted);
                    }
                } else {
                    queries = parseWithFallback(logFile, databaseType);
                }
            }

            log.info("Parsed {} slow queries from log file", queries.size());
            SlowQueryAnalysis analysis = buildAnalysis(queries, connectionId);
            if (sizeTruncated) analysis.setTruncated(true);
            return analysis;
        } finally {
            if (truncatedTemp != null) Files.deleteIfExists(truncatedTemp);
        }
    }

    public TempLogFile copyToTempFile(InputStream inputStream) throws Exception {
        Path tempFile = createTempFile();
        try (BufferedInputStream buffered = new BufferedInputStream(inputStream)) {
            if (looksLikeRawPostgresLog(buffered)) {
                FilterResult r = filterToTempFile(buffered, tempFile);
                return new TempLogFile(tempFile, r.size(), r.truncated());
            }
            FilterResult r = copyRawCapped(buffered, tempFile);
            return new TempLogFile(tempFile, r.size(), r.truncated());
        } catch (Exception e) {
            Files.deleteIfExists(tempFile);
            throw e;
        }
    }

    /**
     * Returns true when the stream looks like a raw PostgreSQL log (first
     * non-empty line is a timestamped entry). Returns false for gzip and for
     * CloudWatch CSV exports, which are left to the existing passthrough path.
     * Resets the stream so no bytes are consumed.
     */
    private boolean looksLikeRawPostgresLog(BufferedInputStream in) throws Exception {
        in.mark(SNIFF_BYTES + 1);
        byte[] buf = new byte[SNIFF_BYTES];
        int n = in.read(buf, 0, SNIFF_BYTES);
        in.reset();
        if (n <= 0) return false;
        if (n >= 2 && (buf[0] & 0xFF) == 0x1f && (buf[1] & 0xFF) == 0x8b) return false; // gzip magic
        String head = new String(buf, 0, n, StandardCharsets.UTF_8);
        for (String line : head.split("\n", -1)) {
            if (line.isBlank()) continue;
            return PostgresSlowLogPatterns.isNewEntry(line); // first non-empty line decides
        }
        return false;
    }

    private record FilterResult(long size, boolean truncated) {}

    /**
     * Streams the input through SlowQueryLogFilter into the temp file, capping
     * the FILTERED output at maxLogBytes. Returns size and truncation flag.
     */
    private FilterResult filterToTempFile(InputStream in, Path tempFile) throws Exception {
        boolean truncated;
        try (OutputStream rawOut = Files.newOutputStream(tempFile);
             TruncatingOutputStream capped = new TruncatingOutputStream(rawOut, maxLogBytes);
             Writer writer = new OutputStreamWriter(capped, StandardCharsets.UTF_8);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            new SlowQueryLogFilter().filter(reader, writer);
            writer.flush();
            truncated = capped.isTruncated();
        }
        return new FilterResult(Files.size(tempFile), truncated);
    }

    /**
     * Copies the raw input to the temp file, capping at maxLogBytes by truncating
     * rather than throwing. Used for inputs that are not raw PostgreSQL logs
     * (CloudWatch CSV exports, MySQL logs, S3 objects) so an oversized source
     * degrades to partial results instead of failing the whole job. Stops reading
     * once the cap is reached so a huge source is not fully drained.
     */
    private FilterResult copyRawCapped(InputStream in, Path tempFile) throws Exception {
        byte[] buf = new byte[8192];
        boolean truncated;
        try (OutputStream rawOut = Files.newOutputStream(tempFile);
             TruncatingOutputStream capped = new TruncatingOutputStream(rawOut, maxLogBytes)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                capped.write(buf, 0, n);
                if (capped.isTruncated()) break;
            }
            capped.flush();
            truncated = capped.isTruncated();
        }
        return new FilterResult(Files.size(tempFile), truncated);
    }

    private Path createTempFile() throws Exception {
        Path directory = resolveTempDir();
        ensureMinFreeDisk(directory);
        return Files.createTempFile(directory, "slow-query-log-", ".log");
    }

    private Path resolveTempDir() throws Exception {
        if (tempDir == null || tempDir.isBlank()) {
            return Paths.get(System.getProperty("java.io.tmpdir"));
        }
        Path directory = Paths.get(tempDir);
        Files.createDirectories(directory);
        return directory;
    }

    private void ensureMinFreeDisk(Path directory) throws Exception {
        if (minFreeDiskMb <= 0) {
            return;
        }
        FileStore store = Files.getFileStore(directory);
        long freeBytes = store.getUsableSpace();
        long minBytes = minFreeDiskMb * 1024 * 1024;
        if (freeBytes < minBytes) {
            throw new IllegalStateException(String.format(
                "Insufficient disk space in %s: %d bytes free (min %d bytes required)",
                directory, freeBytes, minBytes));
        }
    }

    /**
     * Parse MySQL slow query log format
     */
    private List<SlowQuery> parseMySQLLog(InputStream inputStream) throws Exception {
        List<SlowQuery> queries = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            SlowQuery currentQuery = null;
            StringBuilder sqlBuilder = new StringBuilder();
            boolean inQuery = false;
            int lineCount = 0;
            int timeLineCount = 0;
            int queryTimeLineCount = 0;
            String firstLine = null;

            // MySQL slow log patterns - support multiple timestamp formats
            // Format 1: ISO format (2024-01-01T00:00:00.000000Z)
            Pattern timePatternIso = Pattern.compile("# Time: (\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[.\\d]*Z?)");
            // Format 2: ISO without T (2024-01-01 00:00:00.000000)
            Pattern timePatternSpace = Pattern.compile("# Time: (\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}[.\\d]*)");
            // Format 3: Old MySQL format (240101 10:30:45)
            Pattern timePatternOld = Pattern.compile("# Time: (\\d{6}) (\\d{2}:\\d{2}:\\d{2})");
            // Format 4: Unix timestamp in microseconds
            Pattern timePatternUnix = Pattern.compile("# Time: (\\d{10,})");
            Pattern userPattern = Pattern.compile("# User@Host: (.+?) @ (.+?) \\[(.*)\\]");
            Pattern queryTimePattern = Pattern.compile("# Query_time: ([\\d.]+)\\s+Lock_time: ([\\d.]+)\\s+Rows_sent: (\\d+)\\s+Rows_examined: (\\d+)");

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                lineCount++;
                if (firstLine == null && !line.isEmpty()) {
                    firstLine = line.length() > 200 ? line.substring(0, 200) + "..." : line;
                }
                if (line.startsWith("# Time:")) timeLineCount++;
                if (line.startsWith("# Query_time:")) queryTimeLineCount++;

                if (line.isEmpty() || line.startsWith("# Time:") || line.startsWith("# administrator")) {
                    // Save previous query if exists
                    if (currentQuery != null && sqlBuilder.length() > 0) {
                        currentQuery.setQueryText(sqlBuilder.toString().trim());
                        currentQuery.setSampleQuery(currentQuery.getQueryText());
                        if (!currentQuery.getQueryText().isEmpty()) {
                            queries.add(currentQuery);
                            if (queries.size() >= maxParsedQueries) {
                                log.warn("Reached max parsed queries ({}). Truncating slow log parsing.",
                                    maxParsedQueries);
                                return queries;
                            }
                        }
                    }

                    // Start new query
                    if (line.startsWith("# Time:")) {
                        currentQuery = new SlowQuery();
                        currentQuery.setSource("FILE_UPLOAD");
                        LocalDateTime parsedTime = parseTimestamp(line, timePatternIso, timePatternSpace, timePatternOld, timePatternUnix);
                        if (parsedTime != null) {
                            currentQuery.setFirstSeen(parsedTime);
                            currentQuery.setLastSeen(parsedTime);
                        }
                        sqlBuilder = new StringBuilder();
                        inQuery = false;
                    }
                    continue;
                }

                if (line.startsWith("# User@Host:")) {
                    Matcher matcher = userPattern.matcher(line);
                    if (matcher.find() && currentQuery != null) {
                        // Extract user information if needed
                    }
                    continue;
                }

                if (line.startsWith("# Query_time:")) {
                    // Create query if not started by # Time: (some logs skip the Time line)
                    if (currentQuery == null) {
                        currentQuery = new SlowQuery();
                        currentQuery.setSource("FILE_UPLOAD");
                        currentQuery.setFirstSeen(LocalDateTime.now());
                        currentQuery.setLastSeen(LocalDateTime.now());
                        sqlBuilder = new StringBuilder();
                    }
                    Matcher matcher = queryTimePattern.matcher(line);
                    if (matcher.find()) {
                        double queryTime = Double.parseDouble(matcher.group(1)) * 1000; // Convert to ms
                        currentQuery.setAvgExecutionTimeMs(queryTime);
                        currentQuery.setMaxExecutionTimeMs(queryTime);
                        currentQuery.setMinExecutionTimeMs(queryTime);
                        currentQuery.setTotalExecutionTimeMs(queryTime);
                        currentQuery.setCallCount(1L);

                        long rowsSent = Long.parseLong(matcher.group(3));
                        long rowsExamined = Long.parseLong(matcher.group(4));
                        currentQuery.setRowsSent(rowsSent);
                        currentQuery.setRowsExamined(rowsExamined);
                        currentQuery.setAvgRowsSent(rowsSent);
                        currentQuery.setAvgRowsExamined(rowsExamined);
                    }
                    inQuery = true;
                    continue;
                }

                // Skip other comment lines
                if (line.startsWith("#")) {
                    continue;
                }

                // This is SQL content
                if (inQuery && currentQuery != null) {
                    // Skip MySQL slow log preamble lines (use db; SET timestamp=N;)
                    if (line.matches("(?i)^use\\s+\\w+\\s*;?\\s*$") ||
                        line.matches("(?i)^SET\\s+timestamp\\s*=.*$")) {
                        continue;
                    }
                    // Preserve newlines between SQL lines. Joining with a space
                    // would let MySQL's "--" line comments swallow the rest of
                    // the query when the slow-log entry had inline comments
                    // (the comment only ends at end-of-line; if there's no
                    // newline, everything to the next entry becomes a comment
                    // and the recovered SQL won't parse).
                    if (sqlBuilder.length() > 0) {
                        sqlBuilder.append("\n");
                    }
                    sqlBuilder.append(line);
                }
            }

            // Add last query
            if (currentQuery != null && sqlBuilder.length() > 0) {
                currentQuery.setQueryText(sqlBuilder.toString().trim());
                currentQuery.setSampleQuery(currentQuery.getQueryText());
                if (!currentQuery.getQueryText().isEmpty()) {
                    queries.add(currentQuery);
                    if (queries.size() >= maxParsedQueries) {
                        log.warn("Reached max parsed queries ({}). Truncating slow log parsing.",
                            maxParsedQueries);
                    }
                }
            }

            // Debug logging for parse diagnostics
            log.info("MySQL slow log parse stats: {} total lines, {} '# Time:' lines, {} '# Query_time:' lines, {} queries parsed",
                lineCount, timeLineCount, queryTimeLineCount, queries.size());
            if (queries.isEmpty() && lineCount > 0) {
                log.warn("No queries parsed from {} lines. First non-empty line: {}", lineCount, firstLine);
            }
        }
        return queries;
    }

    /**
     * Stream-extract CloudWatch CSV log for large files (&gt;5MB).
     * Reads the file line-by-line using the same CSV field/quote logic as the
     * in-memory version, writing extracted message content to a temp file.
     * Returns the temp file path, or null if the file is not a CloudWatch CSV.
     */
    private Path streamExtractCloudWatchLog(Path logFile) {
        try (BufferedReader reader = Files.newBufferedReader(logFile, StandardCharsets.UTF_8)) {
            // Read the first line to check for CloudWatch CSV header
            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.isBlank()) return null;

            // Parse header row to find message column index
            List<String> header = parseCsvRow(headerLine);
            int messageIndex = findHeaderIndex(header, "message", "@message");
            if (messageIndex < 0) return null; // not a CloudWatch CSV

            // Stream-extract message column to a temp file
            Path tempFile = Files.createTempFile(resolveTempDir(), "cw-extracted-", ".log");
            int entries = 0;
            try (java.io.BufferedWriter writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
                String line;
                StringBuilder multiLine = new StringBuilder();
                boolean inQuotes = false;

                while ((line = reader.readLine()) != null) {
                    // Handle multi-line CSV fields (quoted values with embedded newlines)
                    if (inQuotes || multiLine.length() > 0) {
                        multiLine.append('\n').append(line);
                        // Count unescaped quotes to determine if we're still inside a quoted field
                        inQuotes = isStillInQuotedField(multiLine.toString());
                        if (inQuotes) continue;
                        line = multiLine.toString();
                        multiLine.setLength(0);
                    } else {
                        // Check if this line starts a multi-line quoted field
                        inQuotes = isStillInQuotedField(line);
                        if (inQuotes) {
                            multiLine.append(line);
                            continue;
                        }
                    }

                    List<String> row = parseCsvRow(line);
                    if (messageIndex < row.size()) {
                        String message = row.get(messageIndex);
                        if (message != null && !message.isBlank()) {
                            message = normalizeCloudWatchMessage(message);
                            writer.write(message.trim());
                            writer.newLine();
                            entries++;
                        }
                    }
                }
            } catch (Exception e) {
                Files.deleteIfExists(tempFile);
                throw e;
            }

            if (entries == 0) {
                Files.deleteIfExists(tempFile);
                return null;
            }

            log.info("Streaming CloudWatch extraction: {} entries from large file", entries);
            return tempFile;
        } catch (Exception e) {
            log.debug("Large file is not a CloudWatch CSV or extraction failed: {}", e.getMessage());
            return null;
        }
    }

    /** Parse a single CSV row, handling quoted fields with escaped double-quotes. */
    private static List<String> parseCsvRow(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        fields.add(field.toString());
        return fields;
    }

    /** Check if a CSV text has an unmatched quote (still inside a quoted field). */
    private static boolean isStillInQuotedField(String text) {
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    i++; // skip escaped quote
                } else {
                    inQuotes = !inQuotes;
                }
            }
        }
        return inQuotes;
    }

    private CloudWatchLog extractCloudWatchLog(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }

        List<String> header = null;
        int messageIndex = -1;
        StringBuilder combined = new StringBuilder();
        int entries = 0;

        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < content.length() && content.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                row.add(field.toString());
                field.setLength(0);
            } else if ((c == '\n' || c == '\r') && !inQuotes) {
                if (c == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                if (header == null) {
                    header = row;
                    messageIndex = findHeaderIndex(header, "message", "@message");
                    if (messageIndex < 0) {
                        return null;
                    }
                } else {
                    entries = appendCloudWatchMessage(row, messageIndex, combined, entries);
                }
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }

        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            if (header == null) {
                header = row;
                messageIndex = findHeaderIndex(header, "message", "@message");
                if (messageIndex < 0) {
                    return null;
                }
            } else {
                entries = appendCloudWatchMessage(row, messageIndex, combined, entries);
            }
        }

        if (entries == 0) {
            return null;
        }

        return new CloudWatchLog(combined.toString(), entries);
    }

    private String normalizeCloudWatchMessage(String message) {
        if (message == null) {
            return null;
        }
        boolean hasRealNewline = message.indexOf('\n') >= 0 || message.indexOf('\r') >= 0;
        if (!hasRealNewline && (message.contains("\\n") || message.contains("\\r"))) {
            return message
                    .replace("\\r\\n", "\n")
                    .replace("\\n", "\n")
                    .replace("\\r", "\n");
        }
        return message;
    }

    private int findHeaderIndex(List<String> header, String... names) {
        if (header == null) {
            return -1;
        }
        for (int i = 0; i < header.size(); i++) {
            String value = header.get(i) != null ? header.get(i).trim() : "";
            if (value.startsWith("\uFEFF")) {
                value = value.substring(1);
            }
            String normalized = value.toLowerCase(Locale.ROOT);
            for (String name : names) {
                if (normalized.equals(name)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private int appendCloudWatchMessage(List<String> row, int messageIndex, StringBuilder combined, int entries) {
        if (messageIndex < 0 || messageIndex >= row.size()) {
            return entries;
        }
        String message = row.get(messageIndex);
        if (message == null || message.isBlank()) {
            return entries;
        }
        message = normalizeCloudWatchMessage(message);
        if (combined.length() > 0) {
            combined.append('\n');
        }
        combined.append(message.trim());
        combined.append('\n');
        return entries + 1;
    }

    private static class CloudWatchLog {
        private final String logContent;
        private final int entries;

        private CloudWatchLog(String logContent, int entries) {
            this.logContent = logContent;
            this.entries = entries;
        }
    }

    /**
     * Parse PostgreSQL slow query log format
     */
    private List<SlowQuery> parsePostgreSQLLog(InputStream inputStream) throws Exception {
        List<SlowQuery> queries = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            SlowQuery currentQuery = null;
            StringBuilder sqlBuilder = new StringBuilder();
            StringBuilder paramsBuilder = new StringBuilder();

            // PostgreSQL patterns.
            // Timestamp: fractional seconds are optional (RDS CloudWatch omits them).
            // Prefix: anything between timestamp and LOG: varies by log_line_prefix setting
            //   e.g. "UTC [1234]", "UTC:[local]:user@db:[1234]:", "UTC [1234]: [1-1] user=..."
            // Statement keyword: "statement:" (simple) or, for the extended query protocol,
            // one of "parse <name>:", "bind <name>:", "execute <name>:" (prepared statements,
            // e.g. JDBC/asyncpg). All carry a duration worth surfacing.
            //
            // Multi-line queries: RDS emits the first SQL line alongside the duration header,
            // then continuation lines follow without a timestamp prefix. We collect them all
            // by flushing currentQuery only when we see the next timestamp (new log entry).
            //
            // auto_explain: when the auto_explain extension is enabled (common on RDS/Aurora),
            // the slow statement is logged as "duration: N ms  plan:" followed by a
            // tab-indented "Query Text: <sql>" block (which may span multiple lines when the
            // SQL is pretty-printed), an optional "Query Parameters:" line, and an EXPLAIN plan
            // tree. We capture the full Query Text block as queryText (used for fingerprinting,
            // $N placeholders intact) AND capture the "Query Parameters:" values so the sample
            // can be reconstructed with real literals. We stop at the first plan-node line
            // (which always contains "(cost=") so the plan tree never pollutes either.
            Pattern newEntryPattern = PostgresSlowLogPatterns.NEW_ENTRY;
            Pattern logPattern = PostgresSlowLogPatterns.LOG_STATEMENT;
            Pattern planHeaderPattern = PostgresSlowLogPatterns.PLAN_HEADER;
            Pattern durationOnlyPattern = PostgresSlowLogPatterns.DURATION_ONLY;

            // planFormat: current entry is an auto_explain "plan:" block.
            // planQueryText: inside the "Query Text:" block (append to SQL).
            // planParams: inside the "Query Parameters:" block (append to params).
            boolean planFormat = false;
            boolean planQueryText = false;
            boolean planParams = false;

            while ((line = reader.readLine()) != null) {
                // A new timestamp means the previous query (and all its continuation lines) is done.
                if (newEntryPattern.matcher(line).find() && currentQuery != null) {
                    String sql = sqlBuilder.toString().trim();
                    if (!sql.isEmpty()) {
                        finalizePostgresQuery(currentQuery, sql, paramsBuilder.toString().trim());
                        queries.add(currentQuery);
                        if (queries.size() >= maxParsedQueries) {
                            log.warn("Reached max parsed queries ({}). Truncating slow log parsing.", maxParsedQueries);
                            return queries;
                        }
                    }
                    currentQuery = null;
                    sqlBuilder = new StringBuilder();
                    paramsBuilder = new StringBuilder();
                    planFormat = false;
                    planQueryText = false;
                    planParams = false;
                }

                Matcher matcher = logPattern.matcher(line);
                Matcher planHeader = planHeaderPattern.matcher(line);
                Matcher durationOnly = durationOnlyPattern.matcher(line);

                if (matcher.find()) {
                    // Duration + statement on same line; SQL may continue on following lines.
                    currentQuery = newQueryFromHeader(matcher.group(1), matcher.group(2));
                    sqlBuilder = new StringBuilder(matcher.group(3).trim());
                    paramsBuilder = new StringBuilder();
                    planFormat = false;
                    planQueryText = false;
                    planParams = false;

                } else if (planHeader.find()) {
                    // auto_explain header: "duration: N ms  plan:". The SQL arrives on the
                    // following tab-indented "Query Text:" block; the plan tree is skipped.
                    currentQuery = newQueryFromHeader(planHeader.group(1), planHeader.group(2));
                    sqlBuilder = new StringBuilder();
                    paramsBuilder = new StringBuilder();
                    planFormat = true;
                    planQueryText = false;
                    planParams = false;

                } else if (durationOnly.find()) {
                    // Duration-only line; statement keyword appears on the next line.
                    currentQuery = newQueryFromHeader(durationOnly.group(1), durationOnly.group(2));
                    sqlBuilder = new StringBuilder();
                    paramsBuilder = new StringBuilder();
                    planFormat = false;
                    planQueryText = false;
                    planParams = false;

                } else if (currentQuery != null) {
                    String trimmed = line.trim();
                    if (planFormat) {
                        // auto_explain block. Capture the (possibly multi-line) "Query Text:"
                        // and "Query Parameters:" blocks; stop both at the first plan-node line
                        // ("(cost=") so the plan tree never pollutes the recovered SQL/params.
                        if (trimmed.startsWith("Query Text:")) {
                            planQueryText = true;
                            planParams = false;
                            String first = line.substring(line.indexOf(':') + 1).trim();
                            if (!first.isEmpty()) sqlBuilder.append(first);
                        } else if (trimmed.startsWith("Query Parameters:")) {
                            planQueryText = false;
                            planParams = true;
                            String first = line.substring(line.indexOf(':') + 1).trim();
                            if (!first.isEmpty()) paramsBuilder.append(first);
                        } else if (trimmed.contains("(cost=")) {
                            planQueryText = false;
                            planParams = false;
                        } else if (planQueryText && !trimmed.isEmpty()) {
                            if (sqlBuilder.length() > 0) sqlBuilder.append("\n");
                            sqlBuilder.append(trimmed);
                        } else if (planParams && !trimmed.isEmpty()) {
                            // Parameters can wrap onto continuation lines.
                            if (paramsBuilder.length() > 0) paramsBuilder.append(' ');
                            paramsBuilder.append(trimmed);
                        }
                    } else if (trimmed.startsWith("statement:") || trimmed.startsWith("execute ")
                            || trimmed.startsWith("parse ") || trimmed.startsWith("bind ")) {
                        // Statement/extended-protocol keyword line following a duration-only header.
                        int colonIdx = line.indexOf(':');
                        if (colonIdx >= 0) sqlBuilder.append(line.substring(colonIdx + 1).trim());
                    } else if (!trimmed.isEmpty()) {
                        // Continuation SQL line (no timestamp prefix). Preserve
                        // newlines so MySQL "--" line comments (also legal in
                        // Postgres) don't swallow subsequent SQL when the
                        // recovered text gets pasted into a runnable form.
                        if (sqlBuilder.length() > 0) sqlBuilder.append("\n");
                        sqlBuilder.append(trimmed);
                    }
                }
            }

            // Add last query if exists
            if (currentQuery != null && sqlBuilder.length() > 0) {
                String sql = sqlBuilder.toString().trim();
                if (!sql.isEmpty()) {
                    finalizePostgresQuery(currentQuery, sql, paramsBuilder.toString().trim());
                    queries.add(currentQuery);
                    if (queries.size() >= maxParsedQueries) {
                        log.warn("Reached max parsed queries ({}). Truncating slow log parsing.",
                            maxParsedQueries);
                    }
                }
            }
        }
        return queries;
    }

    /**
     * Finalize a parsed Postgres slow-query entry. queryText keeps the SQL
     * exactly as logged ($N placeholders intact) so fingerprinting stays stable;
     * sampleQuery is the runnable form. When bind values were captured from an
     * auto_explain "Query Parameters:" block, they are substituted back into the
     * placeholders so the sample carries real literals. Otherwise sampleQuery ==
     * queryText. (Note: bind values logged on a separate "DETAIL: parameters:"
     * line — extended query protocol without auto_explain — are not yet merged,
     * because that line carries its own timestamp prefix and is flushed as a new
     * entry before it can be associated with the preceding statement.)
     */
    private void finalizePostgresQuery(SlowQuery currentQuery, String sql, String paramsText) {
        currentQuery.setQueryText(sql);
        currentQuery.setSampleQuery(SqlLiteralSubstitution.substitutePgLogParameters(sql, paramsText));
    }

    /**
     * Build a SlowQuery from a PostgreSQL "duration" log header, populating the
     * timestamp and per-execution timing metrics for a single occurrence.
     * Shared by the statement, auto_explain plan, and duration-only headers.
     */
    private SlowQuery newQueryFromHeader(String timestamp, String durationMs) {
        SlowQuery query = new SlowQuery();
        query.setSource("FILE_UPLOAD");
        query.setFirstSeen(LocalDateTime.parse(timestamp.replace(" ", "T")));
        query.setLastSeen(query.getFirstSeen());
        double duration = Double.parseDouble(durationMs);
        query.setAvgExecutionTimeMs(duration);
        query.setMaxExecutionTimeMs(duration);
        query.setMinExecutionTimeMs(duration);
        query.setTotalExecutionTimeMs(duration);
        query.setCallCount(1L);
        return query;
    }

    private List<SlowQuery> parseWithFallback(byte[] data, String databaseType) throws Exception {
        String canonicalType = providerRegistry.getCanonicalName(databaseType);
        boolean preferPostgres = "postgres".equals(canonicalType);

        List<SlowQuery> primary = preferPostgres
                ? parsePostgreSQLLog(new ByteArrayInputStream(data))
                : parseMySQLLog(new ByteArrayInputStream(data));

        if (!primary.isEmpty()) {
            return primary;
        }

        List<SlowQuery> fallback = preferPostgres
                ? parseMySQLLog(new ByteArrayInputStream(data))
                : parsePostgreSQLLog(new ByteArrayInputStream(data));

        if (!fallback.isEmpty()) {
            log.info("Fallback parser detected {} queries using {} format",
                    fallback.size(),
                    preferPostgres ? "MySQL" : "PostgreSQL");
            return fallback;
        }

        return primary;
    }

    private List<SlowQuery> parseWithFallback(Path file, String databaseType) throws Exception {
        String canonicalType = providerRegistry.getCanonicalName(databaseType);
        boolean preferPostgres = "postgres".equals(canonicalType);

        List<SlowQuery> primary = parseFromFile(file, preferPostgres);
        if (!primary.isEmpty()) {
            return primary;
        }

        List<SlowQuery> fallback = parseFromFile(file, !preferPostgres);
        if (!fallback.isEmpty()) {
            log.info("Fallback parser detected {} queries using {} format",
                fallback.size(),
                preferPostgres ? "MySQL" : "PostgreSQL");
            return fallback;
        }

        return primary;
    }

    private List<SlowQuery> parseFromFile(Path file, boolean usePostgres) throws Exception {
        try (InputStream stream = Files.newInputStream(file)) {
            return usePostgres ? parsePostgreSQLLog(stream) : parseMySQLLog(stream);
        }
    }

    private Path truncateToTempFile(Path source, long limit) throws Exception {
        Path truncated = createTempFile();
        try (InputStream in = Files.newInputStream(source);
             OutputStream out = Files.newOutputStream(truncated)) {
            byte[] buf = new byte[8192];
            long remaining = limit;
            int n;
            while (remaining > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, remaining))) != -1) {
                out.write(buf, 0, n);
                remaining -= n;
            }
        }
        return truncated;
    }

    /**
     * Build analysis from parsed queries
     */
    private SlowQueryAnalysis buildAnalysis(List<SlowQuery> queries, String connectionId) {
        SlowQueryAnalysis analysis = new SlowQueryAnalysis();
        analysis.setConnectionId(connectionId);
        analysis.setAnalysisDate(LocalDateTime.now());
        analysis.setTimeRange(SlowQueryAnalysis.TimeRange.ALL_TIME);

        // Basic stats
        analysis.setTotalQueriesAnalyzed((long) queries.size());
        analysis.setTotalSlowQueries((long) queries.size());

        // Group identical queries and aggregate stats
        Map<String, List<SlowQuery>> groupedQueries = queries.stream()
                .collect(Collectors.groupingBy(q -> normalizeQuery(q.getQueryText())));

        List<SlowQuery> aggregatedQueries = new ArrayList<>();
        for (Map.Entry<String, List<SlowQuery>> entry : groupedQueries.entrySet()) {
            List<SlowQuery> group = entry.getValue();
            SlowQuery aggregated = aggregateQueries(group);
            aggregatedQueries.add(aggregated);
        }

        // Calculate total time
        double totalTime = aggregatedQueries.stream()
            .mapToDouble(q -> q.getTotalExecutionTimeMs() != null ? q.getTotalExecutionTimeMs() : 0.0)
            .sum();
        analysis.setTotalDatabaseTimeMs(totalTime);

        // Assign severity
        for (SlowQuery query : aggregatedQueries) {
            assignSeverity(query, totalTime);
            addSuggestions(query);
        }

        List<SlowQuery> topQueries;
        Comparator<SlowQuery> byTotalTimeDesc = (a, b) -> Double.compare(
            b.getTotalExecutionTimeMs() != null ? b.getTotalExecutionTimeMs() : 0.0,
            a.getTotalExecutionTimeMs() != null ? a.getTotalExecutionTimeMs() : 0.0
        );

        if (aggregatedQueries.size() > maxTopQueries) {
            PriorityQueue<SlowQuery> topK = new PriorityQueue<>(maxTopQueries, (a, b) -> Double.compare(
                a.getTotalExecutionTimeMs() != null ? a.getTotalExecutionTimeMs() : 0.0,
                b.getTotalExecutionTimeMs() != null ? b.getTotalExecutionTimeMs() : 0.0
            ));
            for (SlowQuery query : aggregatedQueries) {
                topK.offer(query);
                if (topK.size() > maxTopQueries) {
                    topK.poll();
                }
            }
            topQueries = new ArrayList<>(topK);
            topQueries.sort(byTotalTimeDesc);
            log.info("Limiting slow query results to top {} of {} aggregated queries",
                maxTopQueries, aggregatedQueries.size());
        } else {
            aggregatedQueries.sort(byTotalTimeDesc);
            topQueries = aggregatedQueries;
        }

        analysis.setTopSlowQueries(topQueries);

        // Overall health assessment
        long criticalCount = topQueries.stream()
                .filter(q -> q.getSeverity() == SlowQuery.Severity.CRITICAL)
                .count();
        long highCount = topQueries.stream()
                .filter(q -> q.getSeverity() == SlowQuery.Severity.HIGH)
                .count();

        if (criticalCount > 0) {
            analysis.setOverallHealth("CRITICAL");
        } else if (highCount > 0) {
            analysis.setOverallHealth("POOR");
        } else if (topQueries.size() > 10) {
            analysis.setOverallHealth("FAIR");
        } else {
            analysis.setOverallHealth("GOOD");
        }

        // AI Summary
        analysis.setAiSummary(generateSummary(topQueries, criticalCount, highCount));

        // General recommendations
        List<String> recommendations = new ArrayList<>();
        recommendations.add("Review queries with CRITICAL and HIGH severity first");
        recommendations.add("Consider adding indexes for queries with high rows_examined");
        recommendations.add("Enable slow query logging on your database for continuous monitoring");
        analysis.setGeneralRecommendations(recommendations);

        return analysis;
    }

    /**
     * Normalize query text for grouping
     */
    private String normalizeQuery(String query) {
        return QueryNormalizer.normalize(query);
    }

    /**
     * Aggregate multiple instances of the same query
     */
    private SlowQuery aggregateQueries(List<SlowQuery> queries) {
        SlowQuery aggregated = new SlowQuery();
        aggregated.setQueryText(queries.get(0).getQueryText());
        aggregated.setSampleQuery(queries.get(0).getSampleQuery() != null
            ? queries.get(0).getSampleQuery()
            : queries.get(0).getQueryText());
        aggregated.setSource("FILE_UPLOAD");

        String normalized = QueryNormalizer.normalize(aggregated.getQueryText());
        aggregated.setNormalizedQuery(normalized.isBlank() ? null : normalized);
        // Use the canonical fingerprint as the query id so the identifier shown
        // by `latest`/`analyze` matches what slow_query_run, slow_query_sample,
        // and the timeline key on. Previously this was a bespoke 32-char MD5
        // that never matched the canonical 16-char fingerprint, so a fingerprint
        // copied from `latest` resolved to zero samples.
        String canonical = QueryFingerprintService.computeCanonicalFingerprint(normalized);
        aggregated.setQueryId(canonical != null
            ? canonical
            : QueryNormalizer.generateMD5Hash(String.valueOf(aggregated.getQueryText())));

        // Aggregate metrics
        aggregated.setCallCount((long) queries.size());

        double totalTime = queries.stream()
                .mapToDouble(q -> q.getAvgExecutionTimeMs() != null ? q.getAvgExecutionTimeMs() : 0.0)
                .sum();
        aggregated.setTotalExecutionTimeMs(totalTime);
        aggregated.setAvgExecutionTimeMs(totalTime / queries.size());

        aggregated.setMaxExecutionTimeMs(queries.stream()
                .mapToDouble(q -> q.getMaxExecutionTimeMs() != null ? q.getMaxExecutionTimeMs() : 0.0)
                .max().orElse(0.0));

        aggregated.setMinExecutionTimeMs(queries.stream()
                .mapToDouble(q -> q.getMinExecutionTimeMs() != null ? q.getMinExecutionTimeMs() : Double.MAX_VALUE)
                .min().orElse(0.0));

        // Aggregate row stats if available
        if (queries.stream().anyMatch(q -> q.getRowsExamined() != null)) {
            long totalExamined = queries.stream()
                    .mapToLong(q -> q.getRowsExamined() != null ? q.getRowsExamined() : 0L)
                    .sum();
            aggregated.setRowsExamined(totalExamined);
            aggregated.setAvgRowsExamined(Math.round((double) totalExamined / queries.size()));
        }

        if (queries.stream().anyMatch(q -> q.getRowsSent() != null)) {
            long totalSent = queries.stream()
                    .mapToLong(q -> q.getRowsSent() != null ? q.getRowsSent() : 0L)
                    .sum();
            aggregated.setRowsSent(totalSent);
            aggregated.setAvgRowsSent(Math.round((double) totalSent / queries.size()));
        }

        // Time range
        aggregated.setFirstSeen(queries.stream()
                .map(SlowQuery::getFirstSeen)
                .filter(Objects::nonNull)
                .min(LocalDateTime::compareTo)
                .orElse(null));

        aggregated.setLastSeen(queries.stream()
                .map(SlowQuery::getLastSeen)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null));

        return aggregated;
    }

    /**
     * Assign severity based on execution time and total impact
     */
    private void assignSeverity(SlowQuery query, double totalTime) {
        double avgTime = query.getAvgExecutionTimeMs() != null ? query.getAvgExecutionTimeMs() : 0.0;
        double queryTotalTime = query.getTotalExecutionTimeMs() != null ? query.getTotalExecutionTimeMs() : 0.0;
        double impact = totalTime > 0 ? (queryTotalTime / totalTime) * 100.0 : 0.0;

        query.setPerformanceImpact(impact);

        if (avgTime > 5000 || impact > 30) {
            query.setSeverity(SlowQuery.Severity.CRITICAL);
        } else if (avgTime > 1000 || impact > 15) {
            query.setSeverity(SlowQuery.Severity.HIGH);
        } else if (avgTime > 500 || impact > 5) {
            query.setSeverity(SlowQuery.Severity.MEDIUM);
        } else {
            query.setSeverity(SlowQuery.Severity.LOW);
        }
    }

    /**
     * Add optimization suggestions
     */
    private void addSuggestions(SlowQuery query) {
        List<String> suggestions = new ArrayList<>();

        if (query.getAvgExecutionTimeMs() != null && query.getAvgExecutionTimeMs() > 1000) {
            suggestions.add("Query execution time is very high. Consider optimizing the query or adding indexes.");
        }

        if (query.getRowsExamined() != null && query.getRowsSent() != null && query.getRowsSent() > 0) {
            double ratio = (double) query.getRowsExamined() / query.getRowsSent();
            if (ratio > 100) {
                suggestions.add(String.format("Inefficient query: examining %d rows to return %d rows. Add selective indexes.",
                        query.getRowsExamined(), query.getRowsSent()));
            }
        }

        if (query.getCallCount() != null && query.getCallCount() > 100) {
            suggestions.add("Query is called frequently. Consider caching results or optimizing the query.");
        }

        String queryText = query.getQueryText() != null ? query.getQueryText().toUpperCase() : "";
        if (queryText.contains("SELECT *")) {
            suggestions.add("Avoid SELECT *. Only select the columns you need.");
        }

        if (queryText.contains("ORDER BY") && !queryText.contains("LIMIT")) {
            suggestions.add("ORDER BY without LIMIT can be expensive. Consider adding a LIMIT clause.");
        }

        query.setSuggestions(suggestions);
    }

    /**
     * Generate AI summary
     */
    private String generateSummary(List<SlowQuery> queries, long criticalCount, long highCount) {
        StringBuilder summary = new StringBuilder();
        summary.append("Slow Query Log Analysis Summary:\n\n");

        if (criticalCount > 0) {
            summary.append(String.format("⚠️ CRITICAL: Found %d queries with severe performance issues.\n", criticalCount));
        }
        if (highCount > 0) {
            summary.append(String.format("⚠️ HIGH: Found %d queries with significant performance impact.\n", highCount));
        }

        if (queries.size() > 0) {
            SlowQuery slowest = queries.get(0);
            summary.append(String.format("\nSlowest Query: %.2f ms average execution time\n",
                    slowest.getAvgExecutionTimeMs()));
        }

        summary.append("\nRecommendations:\n");
        summary.append("• Focus on optimizing CRITICAL and HIGH severity queries first\n");
        summary.append("• Review queries with high execution counts for caching opportunities\n");
        summary.append("• Add indexes for queries with high rows_examined ratios\n");

        return summary.toString();
    }

    /**
     * Parse timestamp from MySQL slow query log in various formats.
     * Supports: ISO format, space-separated ISO, old MySQL format (YYMMDD), Unix timestamp.
     */
    private LocalDateTime parseTimestamp(String line, Pattern... patterns) {
        // Try ISO format: 2024-01-01T00:00:00.000000Z
        Matcher m = patterns[0].matcher(line);
        if (m.find()) {
            try {
                String ts = m.group(1).replace("Z", "").replace("T", " ");
                if (ts.contains(".")) {
                    ts = ts.substring(0, Math.min(ts.length(), 26)); // Limit precision
                }
                return LocalDateTime.parse(ts.replace(" ", "T"));
            } catch (Exception e) {
                log.debug("Failed to parse ISO timestamp: {}", m.group(1));
            }
        }

        // Try space-separated: 2024-01-01 00:00:00.000000
        m = patterns[1].matcher(line);
        if (m.find()) {
            try {
                String ts = m.group(1);
                if (ts.contains(".")) {
                    ts = ts.substring(0, Math.min(ts.length(), 26));
                }
                return LocalDateTime.parse(ts.replace(" ", "T"));
            } catch (Exception e) {
                log.debug("Failed to parse space timestamp: {}", m.group(1));
            }
        }

        // Try old MySQL format: 240101 10:30:45
        m = patterns[2].matcher(line);
        if (m.find()) {
            try {
                String dateStr = m.group(1); // YYMMDD
                String timeStr = m.group(2); // HH:MM:SS
                int year = 2000 + Integer.parseInt(dateStr.substring(0, 2));
                int month = Integer.parseInt(dateStr.substring(2, 4));
                int day = Integer.parseInt(dateStr.substring(4, 6));
                String[] timeParts = timeStr.split(":");
                return LocalDateTime.of(year, month, day,
                    Integer.parseInt(timeParts[0]),
                    Integer.parseInt(timeParts[1]),
                    Integer.parseInt(timeParts[2]));
            } catch (Exception e) {
                log.debug("Failed to parse old format timestamp: {} {}", m.group(1), m.group(2));
            }
        }

        // Try Unix timestamp in seconds or microseconds
        m = patterns[3].matcher(line);
        if (m.find()) {
            try {
                long ts = Long.parseLong(m.group(1));
                // If > 10^12, assume microseconds
                if (ts > 1000000000000L) {
                    ts = ts / 1000000;
                } else if (ts > 1000000000L && ts < 10000000000L) {
                    // Assume seconds
                }
                return LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochSecond(ts),
                    java.time.ZoneId.systemDefault());
            } catch (Exception e) {
                log.debug("Failed to parse Unix timestamp: {}", m.group(1));
            }
        }

        return null;
    }
}
