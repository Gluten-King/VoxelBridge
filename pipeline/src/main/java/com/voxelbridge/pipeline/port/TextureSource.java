package com.voxelbridge.pipeline.port;

import com.voxelbridge.core.texture.AnimationMetadata;
import com.voxelbridge.pipeline.contract.ResourceId;
import com.voxelbridge.pipeline.contract.SpriteRef;

import java.awt.image.BufferedImage;
import java.util.Set;

public interface TextureSource {
    BufferedImage readTexture(ResourceId resource, boolean preserveAnimationStrip);

    BufferedImage readSprite(SpriteRef sprite);

    boolean hasResource(ResourceId resource);

    default byte[] readResource(ResourceId resource) {
        return null;
    }

    default Set<ResourceId> listPngResources(String pathPrefix) {
        return Set.of();
    }

    default AnimationMetadata readAnimationMetadata(ResourceId resource) {
        return null;
    }
}
