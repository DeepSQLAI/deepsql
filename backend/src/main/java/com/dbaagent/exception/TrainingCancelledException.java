package com.dbaagent.exception;

public class TrainingCancelledException extends RuntimeException {
    public TrainingCancelledException(String message) {
        super(message);
    }
}
