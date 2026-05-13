package com.zenith.plugin.opencraft.agent;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FleetRunServiceTest {

    @Test
    void lifecycleMutationsPreserveStepsAndAppendEvents() {
        final Clock clock = Clock.fixed(Instant.parse("2026-05-12T12:00:00Z"), ZoneOffset.UTC);
        final FleetRunService service = new FleetRunService(clock, 25);

        final FleetRunSnapshot created = service.createRun(
            "terminal",
            "manager-1",
            "manager",
            "Coordinate PearlPlus refresh",
            List.of("bot1", "bot2"),
            true,
            1
        );
        final String runId = created.runId();

        service.startRun(runId).orElseThrow();
        final FleetRunSnapshot withStep = service.addStep(runId, "bot1", "pearlplus.refresh", "Refresh primary stasis")
            .orElseThrow();
        final String stepId = withStep.steps().getFirst().stepId();

        service.updateStepStatus(runId, stepId, FleetStepStatus.RUNNING, "PearlPlus acknowledged dispatch").orElseThrow();
        final FleetRunSnapshot completed = service.completeRun(runId, "All peers completed their window").orElseThrow();

        assertEquals(FleetRunStatus.COMPLETED, completed.status());
        assertEquals(1, completed.steps().size());
        assertEquals(FleetStepStatus.RUNNING, completed.steps().getFirst().status());
        assertEquals("PearlPlus acknowledged dispatch", completed.steps().getFirst().detail());
        assertEquals(5, completed.events().size());
        assertEquals(FleetEventType.RUN_CREATED, completed.events().getFirst().type());
        assertEquals(FleetEventType.RUN_COMPLETED, completed.events().getLast().type());
        assertEquals(
            completed.events().get(completed.events().size() - 2).eventId(),
            completed.events().getLast().previousEventId()
        );
    }

    @Test
    void retentionLimitEvictsOldestRuns() {
        final Clock clock = Clock.fixed(Instant.parse("2026-05-12T12:00:00Z"), ZoneOffset.UTC);
        final FleetRunService service = new FleetRunService(clock, 10);

        String firstRunId = null;
        for (int index = 0; index < 11; index++) {
            final FleetRunSnapshot run = service.createRun(
                "terminal",
                "manager-1",
                "manager",
                "Run " + index,
                List.of("bot1"),
                true,
                1
            );
            if (index == 0) {
                firstRunId = run.runId();
            }
        }

        final List<FleetRunSnapshot> recentRuns = service.recentRuns(20);
        final String evictedRunId = firstRunId;
        assertEquals(10, recentRuns.size());
        assertFalse(service.getRun(evictedRunId).isPresent());
        assertTrue(recentRuns.stream().noneMatch(run -> run.runId().equals(evictedRunId)));
    }

    @Test
    void terminalRunsRejectFurtherMutation() {
        final Clock clock = Clock.fixed(Instant.parse("2026-05-12T12:00:00Z"), ZoneOffset.UTC);
        final FleetRunService service = new FleetRunService(clock, 25);

        final String runId = service.createRun(
            "terminal",
            "manager-1",
            "manager",
            "Coordinate PearlPlus refresh",
            List.of("bot1"),
            true,
            1
        ).runId();

        service.completeRun(runId, "done").orElseThrow();

        assertThrows(IllegalStateException.class, () ->
            service.addStep(runId, "bot1", "pearlplus.refresh", "Retry after completion")
        );
    }

    @Test
    void stepTargetMustBelongToRun() {
        final Clock clock = Clock.fixed(Instant.parse("2026-05-12T12:00:00Z"), ZoneOffset.UTC);
        final FleetRunService service = new FleetRunService(clock, 25);

        final String runId = service.createRun(
            "terminal",
            "manager-1",
            "manager",
            "Coordinate PearlPlus refresh",
            List.of("bot1"),
            true,
            1
        ).runId();

        assertThrows(IllegalArgumentException.class, () ->
            service.addStep(runId, "bot2", "pearlplus.refresh", "Unexpected peer")
        );
    }
}
