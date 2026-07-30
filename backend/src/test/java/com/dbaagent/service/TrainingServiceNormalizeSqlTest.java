package com.dbaagent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TrainingServiceNormalizeSqlTest {

    @Test
    void nullReturnsNull() {
        assertNull(TrainingService.normalizeSql(null));
    }

    @Test
    void trimsWhitespace() {
        assertEquals("SELECT 1", TrainingService.normalizeSql("  SELECT 1  "));
    }

    @Test
    void collapsesInternalWhitespace() {
        assertEquals("SELECT 1", TrainingService.normalizeSql("SELECT  1"));
    }

    @Test
    void stripsTrailingSemicolon() {
        assertEquals("SELECT 1", TrainingService.normalizeSql("SELECT 1;"));
    }

    @Test
    void stripsMultipleSemicolons() {
        assertEquals("SELECT 1", TrainingService.normalizeSql("SELECT 1;;"));
    }

    @Test
    void collapsesNewlinesAndTabs() {
        assertEquals("SELECT * FROM users", TrainingService.normalizeSql("SELECT\n  *\n  FROM\n  users"));
    }

    @Test
    void collapsesTabs() {
        assertEquals("SELECT 1 FROM table1", TrainingService.normalizeSql("SELECT\t1\tFROM\ttable1"));
    }

    @Test
    void combinedNormalization() {
        assertEquals("SELECT 1", TrainingService.normalizeSql("  SELECT  1 ;  "));
    }
}
