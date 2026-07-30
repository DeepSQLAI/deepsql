package com.dbaagent.llm.api;

import java.util.Set;

public class UnsupportedLlmProviderException extends RuntimeException {

    public UnsupportedLlmProviderException(String requested, Set<String> supported) {
        super("Unsupported LLM provider '" + requested + "'. Supported: " + supported);
    }
}
