package com.dbaagent.exception;

public class LogSizeExceededException extends RuntimeException {
    private final long bytesRead;
    private final long maxBytes;

    public LogSizeExceededException(long bytesRead, long maxBytes) {
        super(String.format("Log size exceeded: %d bytes read (max %d)", bytesRead, maxBytes));
        this.bytesRead = bytesRead;
        this.maxBytes = maxBytes;
    }

    public long getBytesRead() {
        return bytesRead;
    }

    public long getMaxBytes() {
        return maxBytes;
    }
}
