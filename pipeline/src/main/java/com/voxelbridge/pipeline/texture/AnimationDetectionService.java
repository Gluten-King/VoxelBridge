package com.voxelbridge.pipeline.texture;

import com.voxelbridge.core.texture.AnimatedFrameSet;
import com.voxelbridge.core.texture.AnimationFrameSplitter;
import com.voxelbridge.core.texture.AnimationMetadata;
import com.voxelbridge.core.texture.TextureRepository;
import com.voxelbridge.pipeline.contract.ResourceId;
import com.voxelbridge.pipeline.port.TextureSource;

import java.awt.image.BufferedImage;

/** Version-neutral, metadata-only animation detection. */
public final class AnimationDetectionService {
    private AnimationDetectionService() {}

    public static AnimatedFrameSet ensure(TextureSource source,
                                          TextureRepository repository,
                                          String spriteKey,
                                          ResourceId textureResource) {
        if (source == null || repository == null || spriteKey == null || textureResource == null) return null;
        if (repository.hasAnimation(spriteKey)) return repository.getAnimation(spriteKey);

        AnimationMetadata metadata = source.readAnimationMetadata(textureResource);
        if (metadata == null) return null;
        BufferedImage strip = source.readTexture(textureResource, true);
        AnimatedFrameSet frames = AnimationFrameSplitter.split(strip, metadata);
        if (frames != null && !frames.isEmpty()) repository.putAnimation(spriteKey, frames);
        return frames;
    }
}
