package com.dbaagent.service;

import org.springframework.http.HttpStatus;

import java.util.List;

public class QueryExecutionPolicyException extends RuntimeException {

    public static final String CHAT_MUTATION_BLOCKED = "CHAT_MUTATION_BLOCKED";
    public static final String EDITOR_MUTATION_FORBIDDEN = "EDITOR_MUTATION_FORBIDDEN";
    public static final String EDITOR_MUTATION_CONFIRMATION_REQUIRED = "EDITOR_MUTATION_CONFIRMATION_REQUIRED";
    public static final String UNSAFE_MUTATION_BLOCKED = "UNSAFE_MUTATION_BLOCKED";
    public static final String DB_WRITE_PRIVILEGE_DENIED = "DB_WRITE_PRIVILEGE_DENIED";
    public static final String MULTI_STATEMENT_MISSING_SEMICOLONS = "MULTI_STATEMENT_MISSING_SEMICOLONS";

    private final String errorCode;
    private final HttpStatus httpStatus;
    private final boolean requiresConfirmation;
    private final String queryType;
    private final List<String> warnings;

    public QueryExecutionPolicyException(
        String errorCode,
        HttpStatus httpStatus,
        String message,
        boolean requiresConfirmation,
        String queryType,
        List<String> warnings
    ) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
        this.requiresConfirmation = requiresConfirmation;
        this.queryType = queryType;
        this.warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static QueryExecutionPolicyException chatReadOnly(String queryType) {
        return new QueryExecutionPolicyException(
            CHAT_MUTATION_BLOCKED,
            HttpStatus.BAD_REQUEST,
            "DeepSQL chat is read-only and safe by design. I can't execute DDL or DML in chat, but I can help you prepare a safe SELECT to inspect the target rows first.",
            false,
            queryType,
            List.of()
        );
    }

    public static QueryExecutionPolicyException editorMutationForbidden(String queryType) {
        return new QueryExecutionPolicyException(
            EDITOR_MUTATION_FORBIDDEN,
            HttpStatus.FORBIDDEN,
            "Only admins can execute DDL or DML from the SQL Editor. This Editor run was blocked before any database changes were attempted.",
            false,
            queryType,
            List.of()
        );
    }

    public static QueryExecutionPolicyException confirmationRequired(String queryType, List<String> warnings) {
        return new QueryExecutionPolicyException(
            EDITOR_MUTATION_CONFIRMATION_REQUIRED,
            HttpStatus.OK,
            "This statement will modify the database. Review the safety notes and confirm before DeepSQL submits it.",
            true,
            queryType,
            warnings
        );
    }

    public static QueryExecutionPolicyException unsafeMutation(String message, String queryType) {
        return new QueryExecutionPolicyException(
            UNSAFE_MUTATION_BLOCKED,
            HttpStatus.BAD_REQUEST,
            message,
            false,
            queryType,
            List.of()
        );
    }

    public static QueryExecutionPolicyException multiStatementMissingSemicolons() {
        return new QueryExecutionPolicyException(
            MULTI_STATEMENT_MISSING_SEMICOLONS,
            HttpStatus.BAD_REQUEST,
            "Multiple SQL statements were detected without semicolons between them. Add a semicolon (;) after each statement, or send one statement at a time.",
            false,
            "UNKNOWN",
            List.of()
        );
    }

    public static QueryExecutionPolicyException dbWritePrivilegeDenied(String detail, String queryType) {
        return new QueryExecutionPolicyException(
            DB_WRITE_PRIVILEGE_DENIED,
            HttpStatus.BAD_REQUEST,
            "DeepSQL submitted the statement, but the selected database connection user does not have the required write privileges. "
                + detail,
            false,
            queryType,
            List.of()
        );
    }

    public String getErrorCode() {
        return errorCode;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public boolean isRequiresConfirmation() {
        return requiresConfirmation;
    }

    public String getQueryType() {
        return queryType;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public boolean isChatReadOnlyBlock() {
        return CHAT_MUTATION_BLOCKED.equals(errorCode);
    }
}
