package com.voxelbridge.export;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;

/**
 * Minecraft-bound export context (runtime services).
 */
public final class SamplerContext {
    private final Minecraft mc;
    private final BlockColors blockColors;

    public SamplerContext(Minecraft mc) {
        this.mc = mc;
        this.blockColors = mc.getBlockColors();
    }

    public Minecraft getMc() {
        return mc;
    }

    public BlockColors getBlockColors() {
        return blockColors;
    }
}
