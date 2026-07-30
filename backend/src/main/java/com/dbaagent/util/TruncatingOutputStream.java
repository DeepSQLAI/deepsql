package com.dbaagent.util;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Bounded output stream that, unlike {@link CappedOutputStream}, never throws.
 * Once the cap is reached it silently drops further bytes and records that
 * truncation occurred. A cap of 0 or less means unbounded.
 */
public class TruncatingOutputStream extends FilterOutputStream {
    private final long maxBytes;
    private long bytesWritten;
    private boolean truncated;

    public TruncatingOutputStream(OutputStream out, long maxBytes) {
        super(out);
        this.maxBytes = maxBytes;
    }

    public long getBytesWritten() {
        return bytesWritten;
    }

    public boolean isTruncated() {
        return truncated;
    }

    @Override
    public void write(int b) throws IOException {
        if (maxBytes > 0 && bytesWritten + 1 > maxBytes) {
            truncated = true;
            return;
        }
        out.write(b);
        bytesWritten += 1;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (len <= 0) {
            return;
        }
        if (maxBytes <= 0) {
            out.write(b, off, len);
            bytesWritten += len;
            return;
        }
        long remaining = maxBytes - bytesWritten;
        if (remaining <= 0) {
            truncated = true;
            return;
        }
        if (len <= remaining) {
            out.write(b, off, len);
            bytesWritten += len;
        } else {
            // remaining < len (an int) here, so the cast is safe
            out.write(b, off, (int) remaining);
            bytesWritten += remaining;
            truncated = true;
        }
    }
}
