package com.voxelbridge.mixin.compat.flywheel;

import com.voxelbridge.core.export.ExportRenderScope;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes Flywheel-backed mods use their ordinary CPU renderer only while VoxelBridge renders a
 * block entity into its capture buffer. The mixin is optional and has no effect when Flywheel is
 * absent; normal game frames continue to use Flywheel.
 */
@Pseudo
@Mixin(targets = "dev.engine_room.flywheel.api.visualization.VisualizationManager", remap = false)
public interface FlywheelVisualizationManagerMixin {
    @Inject(method = "supportsVisualization", at = @At("HEAD"), cancellable = true, require = 0)
    private static void voxelbridge$useCpuFallbackForExport(CallbackInfoReturnable<Boolean> cir) {
        if (ExportRenderScope.isActive()) {
            cir.setReturnValue(false);
        }
    }
}
