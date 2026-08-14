package com.voxelbridge.export.exporter.entity;

import com.voxelbridge.export.exporter.resolve.ResolvedTexture;
import com.voxelbridge.platform.client.ClientAccessHolder;
import com.voxelbridge.util.ResourceLocationUtil;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.decoration.Painting;

/** Exact-runtime painting atlas behavior kept outside the entity capture pipeline. */
final class PaintingTextureStrategy {
    private static final float FRONT_EPSILON = 1.0e-3f;

    private PaintingTextureStrategy() {}

    static ResolvedTexture select(Painting painting,
                                  ResolvedTexture current,
                                  float[] positions,
                                  double offsetX,
                                  double offsetY,
                                  double offsetZ) {
        ResolvedTexture normalized = normalize(current);
        if (isFrontQuad(painting, positions, offsetX, offsetY, offsetZ)) {
            return normalized;
        }

        TextureAtlasSprite backSprite = ClientAccessHolder.get().getPaintingTextures().getBackSprite();
        if (backSprite == null || isMissing(backSprite)) {
            return normalized;
        }

        ResourceLocation spriteName = backSprite.contents() != null
            ? backSprite.contents().name()
            : normalized.texture();
        spriteName = normalizeName(spriteName);
        return new ResolvedTexture(spriteName, backSprite.getU0(), backSprite.getU1(),
            backSprite.getV0(), backSprite.getV1(), true, backSprite, backSprite.atlasLocation());
    }

    private static ResolvedTexture normalize(ResolvedTexture current) {
        if (current == null || current.texture() == null) return current;
        ResourceLocation normalized = normalizeName(current.texture());
        if (normalized.equals(current.texture())) return current;
        return new ResolvedTexture(normalized, current.u0(), current.u1(),
            current.v0(), current.v1(), current.isAtlasTexture(), current.sprite(), current.atlasLocation());
    }

    private static boolean isFrontQuad(Painting painting,
                                       float[] positions,
                                       double offsetX,
                                       double offsetY,
                                       double offsetZ) {
        Direction direction = painting.getDirection();
        if (direction == null || positions == null || positions.length < 12) return true;

        int axisIndex;
        double frontCoord;
        var bounds = painting.getBoundingBox().move(offsetX, offsetY, offsetZ);
        switch (direction) {
            case NORTH -> { axisIndex = 2; frontCoord = bounds.minZ; }
            case SOUTH -> { axisIndex = 2; frontCoord = bounds.maxZ; }
            case WEST -> { axisIndex = 0; frontCoord = bounds.minX; }
            case EAST -> { axisIndex = 0; frontCoord = bounds.maxX; }
            default -> { return true; }
        }

        float center = 0f;
        for (int i = 0; i < 4; i++) center += positions[i * 3 + axisIndex];
        return Math.abs(center * 0.25f - frontCoord) <= FRONT_EPSILON;
    }

    private static ResourceLocation normalizeName(ResourceLocation spriteName) {
        if (spriteName == null) return null;
        String path = spriteName.getPath();
        if (path.startsWith("textures/painting/") || path.startsWith("painting/")) return spriteName;
        return ResourceLocationUtil.sanitize(spriteName.getNamespace() + ":painting/" + path);
    }

    private static boolean isMissing(TextureAtlasSprite sprite) {
        return sprite.contents() == null || sprite.contents().name().toString().contains("missingno");
    }
}
