package com.voxelbridge.pipeline;

import com.voxelbridge.pipeline.contract.PrimitiveSink;
import com.voxelbridge.pipeline.contract.QuadSink;
import com.voxelbridge.pipeline.contract.Region3i;
import com.voxelbridge.pipeline.contract.RuntimeCapability;
import com.voxelbridge.pipeline.port.RuntimeDiagnostics;
import com.voxelbridge.pipeline.session.ExportSession;

import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/** Version-neutral push pipeline joining world, ordinary model, and special-render ports. */
public final class ExportPipeline {
    private final ExportSession session;

    public ExportPipeline(ExportSession session) {
        if (session == null) throw new IllegalArgumentException("Export session is required");
        this.session = session;
    }

    public ExportSummary exportRegion(Region3i region, QuadSink quadSink, PrimitiveSink primitiveSink) {
        if (region == null || quadSink == null || primitiveSink == null) {
            throw new IllegalArgumentException("Region and sinks are required");
        }
        if (session.isClosed()) throw new IllegalStateException("Export session is closed");

        LongAdder blocks = new LongAdder();
        LongAdder quads = new LongAdder();
        LongAdder primitives = new LongAdder();
        long started = System.nanoTime();
        try {
            if (supports(RuntimeCapability.BLOCK_MODEL_QUADS)) {
                session.runtime().world().visitBlocks(region, block -> {
                    blocks.increment();
                    session.runtime().blockGeometry().emitBlockQuads(block, quad -> {
                        quads.increment();
                        quadSink.accept(quad);
                    });
                });
            } else {
                degraded("block-model-quads", RuntimeCapability.BLOCK_MODEL_QUADS);
            }

            if (supports(RuntimeCapability.ENTITY_RENDER_CAPTURE)
                || supports(RuntimeCapability.BLOCK_ENTITY_RENDER_CAPTURE)) {
                session.runtime().specialRender().emitSpecialPrimitives(region, primitive -> {
                    primitives.increment();
                    primitiveSink.accept(primitive);
                });
            } else {
                degraded("special-render-capture", RuntimeCapability.ENTITY_RENDER_CAPTURE);
            }

            ExportSummary summary = new ExportSummary(
                blocks.sum(), quads.sum(), primitives.sum(), System.nanoTime() - started);
            session.diagnose(RuntimeDiagnostics.Severity.INFO, "pipeline-region-complete",
                "Version-neutral region export completed", Map.of(
                    "blocks", Long.toString(summary.blocks()),
                    "quads", Long.toString(summary.quads()),
                    "primitives", Long.toString(summary.primitives()),
                    "elapsedNanos", Long.toString(summary.elapsedNanos())));
            return summary;
        } catch (RuntimeException failure) {
            session.diagnose(RuntimeDiagnostics.Severity.ERROR, "pipeline-region-error",
                "Version-neutral region export failed", Map.of(
                    "error", failure.getClass().getName(),
                    "message", String.valueOf(failure.getMessage())));
            throw failure;
        }
    }

    private boolean supports(RuntimeCapability capability) {
        return session.runtime().capabilities().supports(capability);
    }

    private void degraded(String category, RuntimeCapability capability) {
        if (!session.firstOccurrence("missing-capability", capability.name())) return;
        session.diagnose(RuntimeDiagnostics.Severity.WARN, category,
            "Runtime capability unavailable; geometry is conservatively omitted", Map.of(
                "capability", capability.name(),
                "sessionId", session.id()));
    }

    public record ExportSummary(long blocks, long quads, long primitives, long elapsedNanos) {}
}
