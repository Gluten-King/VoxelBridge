package com.voxelbridge.export;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.color.block.BlockColors;

/**
 * Minecraft-bound export context (runtime services).
 */
public final class SamplerContext {
    private final MinecraftClient mc;
    private final BlockColors blockColors;

    public SamplerContext(MinecraftClient mc) {
        this.mc = mc;
        this.blockColors = mc.getBlockColors();
    }

    public MinecraftClient getMc() {
        return mc;
    }

    public BlockColors getBlockColors() {
        return blockColors;
    }
}
