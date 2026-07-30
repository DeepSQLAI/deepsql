package com.dbaagent.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresSlowLogPatternsTest {

    @Test
    void isNewEntry_trueForTimestampedLine() {
        assertTrue(PostgresSlowLogPatterns.isNewEntry(
            "2026-05-04 23:46:56 UTC:10.0.0.1(1):u@db:[1]:LOG:  duration: 5.0 ms  statement: SELECT 1"));
    }

    @Test
    void isNewEntry_falseForContinuationLine() {
        assertFalse(PostgresSlowLogPatterns.isNewEntry("\tQuery Text: SELECT 1"));
    }

    @Test
    void isSlowQueryHeader_trueForAllDurationForms() {
        String p = "2026-05-04 23:46:56 UTC:10.0.0.1(1):u@db:[1]:LOG:  duration: 5.0 ms  ";
        assertTrue(PostgresSlowLogPatterns.isSlowQueryHeader(p + "statement: SELECT 1"));
        assertTrue(PostgresSlowLogPatterns.isSlowQueryHeader(p + "execute s1: SELECT 1"));
        assertTrue(PostgresSlowLogPatterns.isSlowQueryHeader(p + "parse <unnamed>: SELECT 1"));
        assertTrue(PostgresSlowLogPatterns.isSlowQueryHeader(p + "bind s1: SELECT 1"));
        assertTrue(PostgresSlowLogPatterns.isSlowQueryHeader(p + "plan:"));
        assertTrue(PostgresSlowLogPatterns.isSlowQueryHeader(
            "2026-05-04 23:46:56 UTC:10.0.0.1(1):u@db:[1]:LOG:  duration: 5.0 ms"));
    }

    @Test
    void nullInputsAreFalse() {
        assertFalse(PostgresSlowLogPatterns.isNewEntry(null));
        assertFalse(PostgresSlowLogPatterns.isSlowQueryHeader(null));
    }

    @Test
    void isSlowQueryHeader_falseForAuditAndOther() {
        assertFalse(PostgresSlowLogPatterns.isSlowQueryHeader(
            "2026-05-04 23:46:56 UTC:10.0.0.1(1):u@db:[1]:LOG:  AUDIT: SESSION,1,1,READ,SELECT,,,\"SELECT 1\""));
        assertFalse(PostgresSlowLogPatterns.isSlowQueryHeader(
            "2026-05-04 23:46:56 UTC:10.0.0.1(1):u@db:[1]:LOG:  connection authorized: user=u database=db"));
        assertFalse(PostgresSlowLogPatterns.isSlowQueryHeader("\tQuery Text: SELECT 1"));
    }
}
