package com.dbaagent.util;

import com.dbaagent.exception.LogSizeExceededException;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class CappedOutputStream extends FilterOutputStream {
    private final long maxBytes;
    private long bytesWritten;

    public CappedOutputStream(OutputStream out, long maxBytes) {
        super(out);
        this.maxBytes = maxBytes;
    }

    public long getBytesWritten() {
        return bytesWritten;
    }

    @Override
    public void write(int b) throws IOException {
        ensureCapacity(1);
        super.write(b);
        bytesWritten += 1;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        ensureCapacity(len);
        super.write(b, off, len);
        bytesWritten += len;
    }

    private void ensureCapacity(long count) throws IOException {
        if (count <= 0) {
            return;
        }
        if (maxBytes > 0 && bytesWritten + count > maxBytes) {
            throw new LogSizeExceededException(bytesWritten + count, maxBytes);
        }
    }
}
