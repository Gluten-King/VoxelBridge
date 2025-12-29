package com.voxelbridge.voxy.importer;

import com.voxelbridge.voxy.common.world.WorldEngine;

public interface IDataImporter {
    interface ICompletionCallback{void onCompletion(int chunks);}
    interface IUpdateCallback{void onUpdate(int finished, int outOf);}

    void runImport(IUpdateCallback updateCallback, ICompletionCallback completionCallback);

    WorldEngine getEngine();

    void shutdown();
    boolean isRunning();
}
