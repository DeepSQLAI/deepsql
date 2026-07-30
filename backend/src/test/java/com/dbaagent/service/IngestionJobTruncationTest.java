package com.dbaagent.service;

import com.dbaagent.model.IngestionJob;
import com.dbaagent.model.IngestionJobEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestionJobTruncationTest {
    @Test
    void completeWithTruncationKeepsCompletedStatusAndSetsFlag() {
        IngestionJob job = new IngestionJob();
        job.complete("Analyzed partial results (size limit hit)", "hist-1", 42, true);
        assertEquals(IngestionJob.Status.COMPLETED, job.getStatus());
        assertTrue(job.isTruncated());
        assertEquals(42, job.getSlowQueriesFound());
    }

    @Test
    void completeWithoutTruncationLeavesFlagFalse() {
        IngestionJob job = new IngestionJob();
        job.complete("Full results", "hist-3", 5, false);
        assertEquals(IngestionJob.Status.COMPLETED, job.getStatus());
        assertFalse(job.isTruncated());
    }

    @Test
    void entityCompleteWithTruncationSetsFlagAtomically() {
        IngestionJobEntity job = new IngestionJobEntity();
        job.complete("Analyzed partial results (size limit hit)", "hist-2", 7, true);
        assertEquals(IngestionJobEntity.Status.COMPLETED, job.getStatus());
        assertTrue(job.isTruncated());
        assertEquals(7, job.getSlowQueriesFound());
    }
}
