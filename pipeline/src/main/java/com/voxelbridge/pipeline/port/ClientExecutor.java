package com.voxelbridge.pipeline.port;

import java.util.concurrent.Callable;

public interface ClientExecutor {
    boolean isClientThread();

    <T> T callBlocking(Callable<T> task);

    default void runBlocking(Runnable task) {
        callBlocking(() -> {
            task.run();
            return null;
        });
    }

    default void awaitGpuWork() {
    }
}
