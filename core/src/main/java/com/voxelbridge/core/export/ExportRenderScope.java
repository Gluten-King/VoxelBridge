package com.voxelbridge.core.export;

/**
 * Marks renderer calls that are being evaluated for an export rather than a live frame.
 * Optional mod integrations use this narrow, thread-local scope to select their CPU fallback
 * renderer without changing the player's normal rendering configuration.
 */
public final class ExportRenderScope implements AutoCloseable {
    private static final ThreadLocal<Integer> DEPTH = new ThreadLocal<>();

    private boolean closed;

    private ExportRenderScope() {
        Integer depth = DEPTH.get();
        DEPTH.set(depth == null ? 1 : depth + 1);
    }

    public static ExportRenderScope open() {
        return new ExportRenderScope();
    }

    public static boolean isActive() {
        Integer depth = DEPTH.get();
        return depth != null && depth > 0;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Integer currentDepth = DEPTH.get();
        int depth = currentDepth == null ? 0 : currentDepth - 1;
        if (depth <= 0) {
            DEPTH.remove();
        } else {
            DEPTH.set(depth);
        }
    }
}
