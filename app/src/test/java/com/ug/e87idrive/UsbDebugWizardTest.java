package com.ug.e87idrive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public final class UsbDebugWizardTest {
    @Test public void plansHaveUniqueIdsAndActionableSteps() {
        Set<String> ids = new HashSet<>();
        assertEquals(9, UsbDebugWizard.plans().size());
        for (UsbDebugWizard.Plan plan : UsbDebugWizard.plans()) {
            assertTrue(ids.add(plan.id()));
            assertFalse(plan.title().isEmpty());
            assertFalse(plan.preparation().isEmpty());
            assertFalse(plan.steps().isEmpty());
            for (UsbDebugWizard.Step step : plan.steps()) {
                assertFalse(step.title().isEmpty());
                assertFalse(step.instruction().isEmpty());
            }
        }
    }

    @Test public void doorPlanCoversFiveDoorBody() {
        UsbDebugWizard.Plan doors = null;
        for (UsbDebugWizard.Plan plan : UsbDebugWizard.plans()) {
            if ("doors".equals(plan.id())) doors = plan;
        }
        assertTrue(doors != null);
        assertEquals(10, doors.steps().size());
    }
}
