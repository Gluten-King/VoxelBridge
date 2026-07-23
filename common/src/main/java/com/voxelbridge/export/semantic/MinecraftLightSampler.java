package com.voxelbridge.export.semantic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;

/** Produces Iris-compatible 0..240 per-corner light coordinates. */
public final class MinecraftLightSampler {
    private MinecraftLightSampler() {}

    public static float[] sampleFace(Level level, BlockPos blockPos, Direction face, int emission) {
        BlockPos samplePos = face != null ? blockPos.relative(face) : blockPos;
        int block = Math.max(clamp(emission), clamp(level.getBrightness(LightLayer.BLOCK, samplePos)));
        int sky = clamp(level.getBrightness(LightLayer.SKY, samplePos));
        float blockUv = block * 16.0f;
        float skyUv = sky * 16.0f;
        return new float[] {
            blockUv, skyUv, blockUv, skyUv,
            blockUv, skyUv, blockUv, skyUv
        };
    }

    public static float[] fullBright() {
        return new float[] {
            240f, 240f, 240f, 240f,
            240f, 240f, 240f, 240f
        };
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(15, value));
    }
}
