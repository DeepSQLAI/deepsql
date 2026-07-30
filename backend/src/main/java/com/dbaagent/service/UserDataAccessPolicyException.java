package com.dbaagent.service;

public class UserDataAccessPolicyException extends RuntimeException {

    private final String errorCode;

    public UserDataAccessPolicyException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
