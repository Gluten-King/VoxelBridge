package com.voxelbridge.util.client;

import com.voxelbridge.export.ExportProgressTracker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressNotifierTest {

    @Test
    void completedOverlaySurvivesReleasedTrackerUntilFadeFinishes() {
        assertTrue(ProgressNotifier.shouldRetainCompletedOverlay(
            ExportProgressTracker.Stage.COMPLETE,
            ExportProgressTracker.Stage.IDLE));
    }

    @Test
    void unfinishedOverlayDoesNotSurviveReleasedTracker() {
        assertFalse(ProgressNotifier.shouldRetainCompletedOverlay(
            ExportProgressTracker.Stage.FINALIZE,
            ExportProgressTracker.Stage.IDLE));
    }
}
