package com.dbaagent.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TruncatingOutputStreamTest {

    @Test
    void underCapWritesEverythingAndIsNotTruncated() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        TruncatingOutputStream out = new TruncatingOutputStream(sink, 100);
        out.write("hello".getBytes());
        out.flush();
        assertArrayEquals("hello".getBytes(), sink.toByteArray());
        assertFalse(out.isTruncated());
        assertEquals(5, out.getBytesWritten());
    }

    @Test
    void stopsAtCapFlagsTruncatedNeverThrows() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        TruncatingOutputStream out = new TruncatingOutputStream(sink, 4);
        out.write("abcdefgh".getBytes());
        out.flush();
        assertArrayEquals("abcd".getBytes(), sink.toByteArray());
        assertTrue(out.isTruncated());
        assertEquals(4, out.getBytesWritten());
    }

    @Test
    void partialWriteAtBoundaryKeepsOnlyWhatFits() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        TruncatingOutputStream out = new TruncatingOutputStream(sink, 3);
        out.write("ab".getBytes());
        out.write("cd".getBytes());
        out.flush();
        assertArrayEquals("abc".getBytes(), sink.toByteArray());
        assertTrue(out.isTruncated());
        assertEquals(3, out.getBytesWritten());
    }

    @Test
    void zeroOrNegativeCapMeansUnbounded() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        TruncatingOutputStream out = new TruncatingOutputStream(sink, 0);
        out.write("anything".getBytes());
        out.flush();
        assertArrayEquals("anything".getBytes(), sink.toByteArray());
        assertFalse(out.isTruncated());
    }

    @Test
    void singleByteWritesRespectCapAndCount() throws Exception {
        java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream();
        TruncatingOutputStream out = new TruncatingOutputStream(sink, 2);
        out.write('a');  // 1 -> ok
        out.write('b');  // 2 -> ok (at cap)
        out.write('c');  // would exceed -> dropped, truncated
        out.flush();
        assertArrayEquals("ab".getBytes(), sink.toByteArray());
        assertTrue(out.isTruncated());
        assertEquals(2, out.getBytesWritten());
    }

    @Test
    void negativeCapMeansUnbounded() throws Exception {
        java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream();
        TruncatingOutputStream out = new TruncatingOutputStream(sink, -1);
        out.write("data".getBytes());
        out.write('!');
        out.flush();
        assertArrayEquals("data!".getBytes(), sink.toByteArray());
        assertFalse(out.isTruncated());
    }
}
