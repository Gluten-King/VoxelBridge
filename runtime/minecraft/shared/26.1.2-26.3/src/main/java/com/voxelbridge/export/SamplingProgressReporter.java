package com.voxelbridge.export;

import com.voxelbridge.util.client.ProgressNotifier;
import net.minecraft.client.Minecraft;

import java.util.concurrent.atomic.AtomicLong;

/** Session-scoped progress throttling for streaming sampling. */
final class SamplingProgressReporter {
    private static final long MIN_INTERVAL_NANOS = 200_000_000L;

    private SamplingProgressReporter() {}

    static void notify(ExportContext context, Minecraft minecraft) {
        if (context == null || minecraft == null) return;
        AtomicLong last = context.session().computeAttribute(
            "streaming:progress-last-nanos", AtomicLong::new);
        long now = System.nanoTime();
        long previous = last.get();
        if (now - previous < MIN_INTERVAL_NANOS || !last.compareAndSet(previous, now)) return;
        ProgressNotifier.showDetailed(minecraft, ExportProgressTracker.progress());
    }
}
