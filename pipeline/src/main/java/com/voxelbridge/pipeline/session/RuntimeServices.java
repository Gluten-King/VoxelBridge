package com.voxelbridge.pipeline.session;

import com.voxelbridge.pipeline.contract.RuntimeCapabilities;
import com.voxelbridge.pipeline.port.BlockGeometrySource;
import com.voxelbridge.pipeline.port.ClientExecutor;
import com.voxelbridge.pipeline.port.RuntimeDiagnostics;
import com.voxelbridge.pipeline.port.SpecialRenderSource;
import com.voxelbridge.pipeline.port.TextureSource;
import com.voxelbridge.pipeline.port.WorldSource;

/** All exact-version runtime ports required by one export session. */
public record RuntimeServices(
    WorldSource world,
    BlockGeometrySource blockGeometry,
    SpecialRenderSource specialRender,
    TextureSource textures,
    ClientExecutor client,
    RuntimeDiagnostics diagnostics,
    RuntimeCapabilities capabilities
) {
    public RuntimeServices {
        if (world == null || blockGeometry == null || specialRender == null
            || textures == null || client == null || capabilities == null) {
            throw new IllegalArgumentException("Runtime service ports must not be null");
        }
        if (diagnostics == null) diagnostics = RuntimeDiagnostics.NOOP;
    }

    /** Temporary factory used while production call sites migrate port by port. */
    public static RuntimeServices migrating(
        TextureSource textures,
        ClientExecutor client,
        RuntimeCapabilities capabilities
    ) {
        return migrating(textures, client, RuntimeDiagnostics.NOOP, capabilities);
    }

    /** Temporary factory used while production call sites migrate port by port. */
    public static RuntimeServices migrating(
        TextureSource textures,
        ClientExecutor client,
        RuntimeDiagnostics diagnostics,
        RuntimeCapabilities capabilities
    ) {
        return migrating(WorldSource.EMPTY, textures, client, diagnostics, capabilities);
    }

    /** Temporary factory with a migrated world port and remaining render ports conservative. */
    public static RuntimeServices migrating(
        WorldSource world,
        TextureSource textures,
        ClientExecutor client,
        RuntimeDiagnostics diagnostics,
        RuntimeCapabilities capabilities
    ) {
        return new RuntimeServices(
            world,
            BlockGeometrySource.EMPTY,
            SpecialRenderSource.EMPTY,
            textures,
            client,
            diagnostics,
            capabilities
        );
    }
}
