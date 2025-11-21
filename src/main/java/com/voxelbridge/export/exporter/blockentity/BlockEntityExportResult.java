package com.voxelbridge.export.exporter.blockentity;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Result of attempting to export a BlockEntity.
 *
 * @param rendered Whether the BlockEntity was successfully rendered
 * @param replaceBlockModel If true, skip rendering the standard block model
 */
@OnlyIn(Dist.CLIENT)
public record BlockEntityExportResult(boolean rendered, boolean replaceBlockModel) {

    public static final BlockEntityExportResult NOT_HANDLED =
        new BlockEntityExportResult(false, false);

    public static final BlockEntityExportResult RENDERED_KEEP_BLOCK =
        new BlockEntityExportResult(true, false);

    public static final BlockEntityExportResult RENDERED_REPLACE_BLOCK =
        new BlockEntityExportResult(true, true);

    public static BlockEntityExportResult rendered(boolean replaceBlockModel) {
        return replaceBlockModel ? RENDERED_REPLACE_BLOCK : RENDERED_KEEP_BLOCK;
    }
}
