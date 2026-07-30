package com.dbaagent.service.agent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ChatSchemaAgnosticArchitectureTest {

    private static final Pattern HARDCODED_SQL_PATTERN = Pattern.compile("\\b(FROM|JOIN)\\s+[A-Z][A-Z0-9_]{2,}\\b");
    private static final Pattern SUSPICIOUS_LITERAL_PATTERN = Pattern.compile("\"([A-Z]{3,}_[A-Z0-9_]+)\"");
    private static final Set<String> ALLOWED_LITERALS = Set.of(
        "BI_QUERY",
        "BRAIN_METADATA",
        "UNIVERSAL_CHAT",
        "METADATA_ANALYSIS"
    );

    private static final List<Path> CHAT_PATH_FILES = List.of(
        Path.of("src/main/java/com/dbaagent/service/agent/AgentPromptClassifier.java"),
        Path.of("src/main/java/com/dbaagent/service/agent/AgentPlanner.java"),
        Path.of("src/main/java/com/dbaagent/service/agent/AgentAnswerComposer.java"),
        Path.of("src/main/java/com/dbaagent/service/agent/AgentOrchestrator.java"),
        Path.of("src/main/java/com/dbaagent/service/agent/UniversalChatTool.java")
    );

    @Test
    void mainChatPathContainsNoHardcodedNamedTableSqlOrTableLiteralShortcuts() throws IOException {
        for (Path relativePath : CHAT_PATH_FILES) {
            String source = Files.readString(relativePath);
            assertThat(HARDCODED_SQL_PATTERN.matcher(source).find())
                .as("hardcoded SQL table references in %s", relativePath)
                .isFalse();

            Matcher matcher = SUSPICIOUS_LITERAL_PATTERN.matcher(source);
            while (matcher.find()) {
                String literal = matcher.group(1);
                assertThat(ALLOWED_LITERALS)
                    .as("suspicious schema-specific literal `%s` in %s", literal, relativePath)
                    .contains(literal);
            }
        }
    }
}
