package com.voxelbridge.pipeline.port;

import java.util.Map;

@FunctionalInterface
public interface RuntimeDiagnostics {
    RuntimeDiagnostics NOOP = event -> {};

    void record(Event event);

    record Event(Severity severity, String category, String message, Map<String, String> context) {
        public Event {
            if (severity == null) severity = Severity.INFO;
            if (category == null) category = "runtime";
            if (message == null) message = "";
            context = context == null ? Map.of() : Map.copyOf(context);
        }
    }

    enum Severity {
        TRACE,
        INFO,
        WARN,
        ERROR
    }
}
