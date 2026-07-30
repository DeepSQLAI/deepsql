package com.dbaagent.util;

import com.dbaagent.exception.LogSizeExceededException;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

public class CappedInputStream extends FilterInputStream {
    private final long maxBytes;
    private long bytesRead;

    public CappedInputStream(InputStream in, long maxBytes) {
        super(in);
        this.maxBytes = maxBytes;
    }

    public long getBytesRead() {
        return bytesRead;
    }

    @Override
    public int read() throws IOException {
        int result = super.read();
        if (result != -1) {
            recordBytes(1);
        }
        return result;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int result = super.read(b, off, len);
        if (result > 0) {
            recordBytes(result);
        }
        return result;
    }

    private void recordBytes(long count) throws IOException {
        if (count <= 0) {
            return;
        }
        bytesRead += count;
        if (maxBytes > 0 && bytesRead > maxBytes) {
            throw new LogSizeExceededException(bytesRead, maxBytes);
        }
    }
}
