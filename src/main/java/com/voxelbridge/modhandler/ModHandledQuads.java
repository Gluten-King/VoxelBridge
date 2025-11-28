package com.voxelbridge.modhandler;

import net.minecraft.client.renderer.block.model.BakedQuad;

import java.util.List;

/**
 * Simple container for quads returned by a mod-specific handler.
 */
public record ModHandledQuads(List<BakedQuad> quads) {}

