package com.voxelbridge.pipeline.session;

import com.voxelbridge.core.export.ExportState;
import com.voxelbridge.pipeline.port.RuntimeDiagnostics;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.Set;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;

/** Session-scoped state replacing static renderer caches and ThreadLocal overrides. */
public final class ExportSession implements AutoCloseable {
    private final String id;
    private final ExportState state;
    private final RuntimeServices runtime;
    private final ExportSessionOptions options;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<Runnable> closeActions = new ConcurrentLinkedDeque<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final long startedNanos = System.nanoTime();

    public ExportSession(RuntimeServices runtime) {
        this(UUID.randomUUID().toString(), new ExportState(), runtime, ExportSessionOptions.defaults());
    }

    public ExportSession(String id, ExportState state, RuntimeServices runtime) {
        this(id, state, runtime, ExportSessionOptions.defaults());
    }

    public ExportSession(ExportState state, RuntimeServices runtime, ExportSessionOptions options) {
        this(UUID.randomUUID().toString(), state, runtime, options);
    }

    public ExportSession(String id, ExportState state, RuntimeServices runtime, ExportSessionOptions options) {
        if (id == null || id.isBlank() || state == null || runtime == null || options == null) {
            throw new IllegalArgumentException("Session id, state, runtime, and options are required");
        }
        this.id = id;
        this.state = state;
        this.runtime = runtime;
        this.options = options;
        diagnose(RuntimeDiagnostics.Severity.INFO, "session-created", "Export session created", Map.of(
            "sessionId", id,
            "capabilities", runtime.capabilities().toString(),
            "atlasMode", options.atlasMode().name(),
            "workerThreads", Integer.toString(options.workerThreads())
        ));
    }

    public String id() { return id; }
    public ExportState state() { return state; }
    public RuntimeServices runtime() { return runtime; }
    public ExportSessionOptions options() { return options; }
    public boolean isClosed() { return closed.get(); }

    public Object attribute(String key) {
        return attributes.get(key);
    }

    public void putAttribute(String key, Object value) {
        ensureOpen();
        if (value == null) attributes.remove(key);
        else attributes.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T computeAttribute(String key, Supplier<T> factory) {
        ensureOpen();
        if (key == null || factory == null) throw new IllegalArgumentException("Attribute key and factory are required");
        return (T) attributes.computeIfAbsent(key, ignored -> factory.get());
    }

    public void removeAttribute(String key) {
        ensureOpen();
        if (key != null) attributes.remove(key);
    }

    public void onClose(Runnable action) {
        ensureOpen();
        if (action != null) closeActions.addFirst(action);
    }

    /** Session-local log/probe de-duplication. */
    public boolean firstOccurrence(String category, String value) {
        ensureOpen();
        String key = "once:" + (category == null ? "default" : category);
        @SuppressWarnings("unchecked")
        Set<String> seen = (Set<String>) attributes.computeIfAbsent(
            key, ignored -> ConcurrentHashMap.newKeySet());
        return seen.add(value == null ? "<null>" : value);
    }

    public void diagnose(RuntimeDiagnostics.Severity severity,
                         String category,
                         String message,
                         Map<String, String> context) {
        try {
            runtime.diagnostics().record(new RuntimeDiagnostics.Event(severity, category, message, context));
        } catch (RuntimeException ignored) {
            // Diagnostics are observational and must never make an otherwise valid export fail.
        }
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("Export session is closed");
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        diagnose(RuntimeDiagnostics.Severity.INFO, "session-closed", "Export session closed", Map.of(
            "sessionId", id,
            "elapsedNanos", Long.toString(System.nanoTime() - startedNanos)
        ));
        RuntimeException failure = null;
        Runnable action;
        while ((action = closeActions.pollFirst()) != null) {
            try {
                action.run();
            } catch (RuntimeException e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
        }
        attributes.clear();
        if (failure != null) throw failure;
    }
}
