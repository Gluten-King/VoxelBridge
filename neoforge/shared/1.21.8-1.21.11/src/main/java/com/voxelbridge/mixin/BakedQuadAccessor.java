package com.voxelbridge.mixin;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BakedQuad.class)
public interface BakedQuadAccessor {
    @Accessor("sprite")
    TextureAtlasSprite voxelbridge$getSprite();

    @Accessor("direction")
    Direction voxelbridge$getDirection();

    @Accessor("vertices")
    int[] voxelbridge$getVertices();

    @Accessor("tintIndex")
    int voxelbridge$getTintIndex();
}
