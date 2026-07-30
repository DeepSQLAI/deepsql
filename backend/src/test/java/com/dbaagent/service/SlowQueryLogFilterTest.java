package com.dbaagent.service;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlowQueryLogFilterTest {

    private String filter(String input) throws Exception {
        StringWriter out = new StringWriter();
        new SlowQueryLogFilter().filter(new BufferedReader(new StringReader(input)), out);
        return out.toString();
    }

    @Test
    void dropsAuditOnly() throws Exception {
        String audit = String.join("\n",
            "2026-05-04 23:46:56 UTC:10.0.0.1(1):u@db:[1]:LOG:  AUDIT: SESSION,1,1,FUNCTION,EXECUTE,,,\"",
            "\tSELECT do_thing()",
            "\t\"",
            "2026-05-04 23:46:57 UTC:10.0.0.1(1):u@db:[1]:LOG:  connection authorized: user=u",
            "");
        assertEquals("", filter(audit));
    }

    @Test
    void keepsSlowDropsInterleavedAudit() throws Exception {
        String mixed = String.join("\n",
            "2026-05-04 23:46:56 UTC:10.0.0.1(1):u@db:[1]:LOG:  AUDIT: SESSION,1,1,READ,SELECT,,,\"SELECT 1\"",
            "2026-05-04 23:46:57 UTC:10.0.0.1(1):u@db:[1]:LOG:  duration: 1200.0 ms  statement: SELECT * FROM t",
            "2026-05-04 23:46:58 UTC:10.0.0.1(1):u@db:[1]:LOG:  checkpoint complete: wrote 1 buffers",
            "");
        String out = filter(mixed);
        assertTrue(out.contains("duration: 1200.0 ms  statement: SELECT * FROM t"));
        assertFalse(out.contains("AUDIT"));
        assertFalse(out.contains("checkpoint"));
    }

    @Test
    void keepsMultilineAutoExplainEntryWhole() throws Exception {
        String ae = String.join("\n",
            "2026-05-04 23:46:56 UTC:10.0.0.1(1):u@db:[1]:LOG:  duration: 4242.5 ms  plan:",
            "\tQuery Text: SELECT a,",
            "\t b FROM t",
            "\tAggregate  (cost=1.0..2.0 rows=1 width=8)",
            "2026-05-04 23:46:57 UTC:10.0.0.1(1):u@db:[1]:LOG:  AUDIT: SESSION,1,1,READ,SELECT,,,\"x\"",
            "");
        String out = filter(ae);
        assertTrue(out.contains("Query Text: SELECT a,"));
        assertTrue(out.contains("b FROM t"));
        assertTrue(out.contains("Aggregate  (cost="));
        assertFalse(out.contains("AUDIT"));
    }

    @Test
    void keepsParseAndBindPhases() throws Exception {
        String pb = String.join("\n",
            "2026-05-04 23:46:56 UTC:10.0.0.1(1):u@db:[1]:LOG:  duration: 1213.9 ms  parse <unnamed>: SELECT count(*) FROM x",
            "2026-05-04 23:46:57 UTC:10.0.0.1(1):u@db:[1]:LOG:  duration: 51.5 ms  bind s1: SELECT 1",
            "");
        String out = filter(pb);
        assertTrue(out.contains("parse <unnamed>: SELECT count(*) FROM x"));
        assertTrue(out.contains("bind s1: SELECT 1"));
    }

    @Test
    void dropsLeadingOrphanContinuationFromSlice() throws Exception {
        String slice = String.join("\n",
            "\t b FROM t  -- orphaned tail of a sliced entry",
            "\tAggregate  (cost=1.0..2.0 rows=1 width=8)",
            "2026-05-04 23:46:57 UTC:10.0.0.1(1):u@db:[1]:LOG:  duration: 9.0 ms  statement: SELECT 2",
            "");
        String out = filter(slice);
        assertFalse(out.contains("orphaned tail"));
        assertTrue(out.contains("statement: SELECT 2"));
    }

    @Test
    void emptyInputProducesEmptyOutputAndZeroCount() throws Exception {
        java.io.StringWriter out = new java.io.StringWriter();
        long kept = new SlowQueryLogFilter().filter(
            new BufferedReader(new StringReader("")), out);
        assertEquals(0L, kept);
        assertEquals("", out.toString());
    }

    @Test
    void filterReturnsKeptEntryCount() throws Exception {
        String mixed = String.join("\n",
            "2026-05-04 23:46:56 UTC:10.0.0.1(1):u@db:[1]:LOG:  AUDIT: SESSION,1,1,READ,SELECT,,,\"x\"",
            "2026-05-04 23:46:57 UTC:10.0.0.1(1):u@db:[1]:LOG:  duration: 10.0 ms  statement: SELECT 1",
            "2026-05-04 23:46:58 UTC:10.0.0.1(1):u@db:[1]:LOG:  duration: 20.0 ms  statement: SELECT 2",
            "");
        StringWriter out = new StringWriter();
        long kept = new SlowQueryLogFilter().filter(
            new BufferedReader(new StringReader(mixed)), out);
        assertEquals(2L, kept);
    }
}
