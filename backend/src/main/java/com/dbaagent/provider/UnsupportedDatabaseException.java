package com.dbaagent.provider;

import java.util.Set;

/**
 * Exception thrown when an unsupported database type is requested.
 */
public class UnsupportedDatabaseException extends RuntimeException {

    private final String requestedType;
    private final Set<String> supportedTypes;

    public UnsupportedDatabaseException(String requestedType, Set<String> supportedTypes) {
        super(String.format(
            "Unsupported database type: '%s'. Supported types: %s",
            requestedType,
            supportedTypes
        ));
        this.requestedType = requestedType;
        this.supportedTypes = supportedTypes;
    }

    public String getRequestedType() {
        return requestedType;
    }

    public Set<String> getSupportedTypes() {
        return supportedTypes;
    }
}
