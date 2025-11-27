package com.voxelbridge.export.exporter.blockentity;

import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.scene.SceneSink;
import com.voxelbridge.export.texture.BlockEntityTextureManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.awt.image.BufferedImage;

@OnlyIn(Dist.CLIENT)
final class BannerBlockEntityHandler implements BlockEntityHandler {

    @Override
    public BlockEntityExportResult export(
        ExportContext ctx,
        Level level,
        BlockState state,
        BlockEntity blockEntity,
        BlockPos pos,
        SceneSink sceneSink,
        double offsetX,
        double offsetY,
        double offsetZ
    ) {
        if (!(blockEntity instanceof BannerBlockEntity banner)) {
            return BlockEntityExportResult.NOT_HANDLED;
        }

        // Bake combined banner texture and set overrides
        BannerTextureBaker.BannerTextures textures = BannerTextureBaker.bake(ctx, banner);
        BufferedImage baked = ctx.getGeneratedEntityTextures().get(textures.bakedHandle().spriteKey());
        String beSpriteKey = null;
        com.voxelbridge.export.texture.EntityTextureManager.TextureHandle blockEntityHandle = null;
        if (baked != null) {
            beSpriteKey = BlockEntityTextureManager.registerGenerated(ctx, textures.bakedHandle(), baked);
            blockEntityHandle = new com.voxelbridge.export.texture.EntityTextureManager.TextureHandle(
                beSpriteKey,
                textures.bakedHandle().materialName(),
                textures.bakedHandle().relativePath(),
                textures.bakedHandle().textureLocation()
            );
        } else {
            com.voxelbridge.util.ExportLogger.log("[BannerBlockEntityHandler][WARN] Missing baked image for " + textures.bakedHandle().spriteKey());
        }

        TextureOverrideMap overrideWrapper = textures.overrides();
        if (blockEntityHandle != null) {
            com.voxelbridge.export.texture.EntityTextureManager.TextureHandle finalHandle = blockEntityHandle;
            TextureOverrideMap delegate = textures.overrides();
            overrideWrapper = new TextureOverrideMap() {
                @Override
                public com.voxelbridge.export.texture.EntityTextureManager.TextureHandle resolve(net.minecraft.resources.ResourceLocation spriteName) {
                    return delegate.resolve(spriteName) != null ? finalHandle : null;
                }

                @Override
                public boolean skipQuad(net.minecraft.resources.ResourceLocation spriteName, float[] localU, float[] localV) {
                    return delegate.skipQuad(spriteName, localU, localV);
                }
            };
        }

        boolean rendered = BlockEntityRenderer.render(
            ctx,
            blockEntity,
            sceneSink,
            pos.getX() + offsetX,
            pos.getY() + offsetY,
            pos.getZ() + offsetZ,
            overrideWrapper
        );

        return rendered ? BlockEntityExportResult.RENDERED_KEEP_BLOCK : BlockEntityExportResult.NOT_HANDLED;
    }
}
