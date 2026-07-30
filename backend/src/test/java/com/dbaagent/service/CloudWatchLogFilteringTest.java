package com.dbaagent.service;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilteredLogEvent;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudWatchLogFilteringTest {

    private FilteredLogEvent ev(String message) {
        return FilteredLogEvent.builder().message(message).build();
    }

    @Test
    void writeFilteredEvents_dropsAuditKeepsSlow() throws Exception {
        List<FilteredLogEvent> events = List.of(
            ev("2026-05-04 23:46:56 UTC:10.0.0.1(1):u@db:[1]:LOG:  AUDIT: SESSION,1,1,READ,SELECT,,,\"x\""),
            ev("2026-05-04 23:46:57 UTC:10.0.0.1(1):u@db:[1]:LOG:  duration: 1200.0 ms  statement: SELECT * FROM t"),
            ev("2026-05-04 23:46:58 UTC:10.0.0.1(1):u@db:[1]:LOG:  connection authorized: user=u")
        );
        StringWriter sw = new StringWriter();
        BufferedWriter bw = new BufferedWriter(sw);
        long kept = CloudWatchLogFetchService.writeFilteredEvents(events, new SlowQueryLogFilter(), bw);
        bw.flush();
        String out = sw.toString();

        assertEquals(1L, kept);
        assertTrue(out.contains("duration: 1200.0 ms  statement: SELECT * FROM t"));
        assertFalse(out.contains("AUDIT"));
        assertFalse(out.contains("connection authorized"));
    }

    @Test
    void writeFilteredEvents_skipsNullAndEmptyMessages() throws Exception {
        java.util.List<FilteredLogEvent> events = java.util.Arrays.asList(
            FilteredLogEvent.builder().build(),            // null message
            ev(""),                                        // empty message
            ev("2026-05-04 23:46:57 UTC:10.0.0.1(1):u@db:[1]:LOG:  duration: 5.0 ms  statement: SELECT 9")
        );
        java.io.StringWriter sw = new java.io.StringWriter();
        BufferedWriter bw = new BufferedWriter(sw);
        long kept = CloudWatchLogFetchService.writeFilteredEvents(events, new SlowQueryLogFilter(), bw);
        bw.flush();
        assertEquals(1L, kept);
        assertTrue(sw.toString().contains("SELECT 9"));
    }

    @Test
    void writeFilteredEvents_truncatesAtPerStreamByteBudget() throws Exception {
        java.util.List<FilteredLogEvent> events = java.util.List.of(
            ev("2026-05-04 23:46:56 UTC:10.0.0.1(1):u@db:[1]:LOG:  duration: 10.0 ms  statement: SELECT 1"),
            ev("2026-05-04 23:46:57 UTC:10.0.0.1(1):u@db:[1]:LOG:  duration: 20.0 ms  statement: SELECT 2"),
            ev("2026-05-04 23:46:58 UTC:10.0.0.1(1):u@db:[1]:LOG:  duration: 30.0 ms  statement: SELECT 3")
        );
        java.io.StringWriter sw = new java.io.StringWriter();
        java.io.BufferedWriter bw = new java.io.BufferedWriter(sw);
        SlowQueryLogFilter filter = new SlowQueryLogFilter();
        com.dbaagent.util.TruncatingOutputStream budget =
            new com.dbaagent.util.TruncatingOutputStream(java.io.OutputStream.nullOutputStream(), 90);
        long kept = CloudWatchLogFetchService.writeFilteredEvents(events, filter, bw, budget);
        bw.flush();
        assertTrue(budget.isTruncated(), "budget should be exhausted");
        assertTrue(kept >= 1 && kept < 3, "kept a partial set, was: " + kept);
    }

    @Test
    void writeFilteredEvents_budgetChargesOnlyKeptLines() throws Exception {
        java.util.List<FilteredLogEvent> events = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            events.add(ev("2026-05-04 23:46:56 UTC:10.0.0.1(1):u@db:[1]:LOG:  AUDIT: SESSION," + i + ",1,READ,SELECT,,,\"x\""));
        }
        events.add(ev("2026-05-04 23:46:57 UTC:10.0.0.1(1):u@db:[1]:LOG:  duration: 10.0 ms  statement: SELECT 1"));
        events.add(ev("2026-05-04 23:46:58 UTC:10.0.0.1(1):u@db:[1]:LOG:  duration: 20.0 ms  statement: SELECT 2"));

        java.io.StringWriter sw = new java.io.StringWriter();
        java.io.BufferedWriter bw = new java.io.BufferedWriter(sw);
        // Budget big enough for the two slow lines (~120 bytes) but far smaller than the
        // ~50 audit lines combined (~4000 bytes). If audit were charged, this would truncate.
        com.dbaagent.util.TruncatingOutputStream budget =
            new com.dbaagent.util.TruncatingOutputStream(java.io.OutputStream.nullOutputStream(), 250);
        long kept = CloudWatchLogFetchService.writeFilteredEvents(events, new SlowQueryLogFilter(), bw, budget);
        bw.flush();

        assertEquals(2L, kept);
        assertFalse(budget.isTruncated(), "audit lines must not consume the budget");
        assertTrue(sw.toString().contains("SELECT 1") && sw.toString().contains("SELECT 2"));
    }

    @Test
    void writeFilteredEvents_keepsMultilineEntrySpanningEvents() throws Exception {
        // RDS may publish each physical line as its own event; the filter state
        // must carry across events so a plan entry stays whole.
        List<FilteredLogEvent> events = List.of(
            ev("2026-05-04 23:46:56 UTC:10.0.0.1(1):u@db:[1]:LOG:  duration: 4242.5 ms  plan:"),
            ev("\tQuery Text: SELECT a, b FROM t"),
            ev("\tAggregate  (cost=1.0..2.0 rows=1 width=8)"),
            ev("2026-05-04 23:46:57 UTC:10.0.0.1(1):u@db:[1]:LOG:  AUDIT: SESSION,1,1,READ,SELECT,,,\"x\"")
        );
        StringWriter sw = new StringWriter();
        BufferedWriter bw = new BufferedWriter(sw);
        long kept = CloudWatchLogFetchService.writeFilteredEvents(events, new SlowQueryLogFilter(), bw);
        bw.flush();
        String out = sw.toString();

        assertEquals(1L, kept);
        assertTrue(out.contains("Query Text: SELECT a, b FROM t"));
        assertTrue(out.contains("Aggregate  (cost="));
        assertFalse(out.contains("AUDIT"));
    }
}
