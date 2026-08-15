package com.ug.e87idrive;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DiagnosticCandidateStoreTest {
    @Test public void oneSessionRemainsObserved() {
        assertEquals("OBSERVADO", DiagnosticCandidateStore.statusFor(1, 1));
    }

    @Test public void repeatedSessionIsNotAutomaticallyConfirmed() {
        assertEquals("REPETIDO", DiagnosticCandidateStore.statusFor(2, 1));
    }

    @Test public void threeStrongSessionsAreOnlyReadyForReview() {
        assertEquals("LISTO PARA REVISAR", DiagnosticCandidateStore.statusFor(3, 3));
    }
}
