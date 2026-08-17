package com.voxelbridge.export;

import com.voxelbridge.adapter.Adapters;
import com.voxelbridge.core.util.geometry.GeometryUtil;
import com.voxelbridge.export.quad.QuadData;
import com.voxelbridge.export.util.geometry.VertexExtractor;
import com.voxelbridge.pipeline.contract.BlockPos3i;
import com.voxelbridge.pipeline.contract.BlockSample;
import com.voxelbridge.pipeline.contract.Face;
import com.voxelbridge.pipeline.contract.MaterialFacts;
import com.voxelbridge.pipeline.contract.OcclusionFacts;
import com.voxelbridge.pipeline.contract.QuadInput;
import com.voxelbridge.pipeline.contract.QuadSink;
import com.voxelbridge.pipeline.contract.ResourceId;
import com.voxelbridge.pipeline.contract.SpriteRef;
import com.voxelbridge.pipeline.port.BlockGeometrySource;
import com.voxelbridge.pipeline.port.WorldSource;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/** Minecraft 1.21.11 baked-model adapter for the stable pipeline contract. */
final class MinecraftBlockGeometrySource12111 implements BlockGeometrySource {
    private final Level level;
    private final WorldSource world;
    private final boolean vanillaRandomTransform;
    private final QuadInput output = new QuadInput();
    private final float[] positions = new float[12];
    private final float[] uv0 = new float[8];
    private final float[] normal = new float[3];
    private final float[] colors = new float[16];
    private final int[] packedColors = new int[4];
    private final Map<String, SpriteRef> sprites = new HashMap<>();

    MinecraftBlockGeometrySource12111(Level level, WorldSource world, boolean vanillaRandomTransform) {
        this.level = level;
        this.world = world;
        this.vanillaRandomTransform = vanillaRandomTransform;
    }

    @Override
    public void emitBlockQuads(BlockSample block, QuadSink sink) {
        if (block == null || sink == null) return;
        BlockPos3i inputPosition = block.position();
        BlockPos position = new BlockPos(inputPosition.x(), inputPosition.y(), inputPosition.z());
        BlockState state = level.getBlockState(position);
        if (state.isAir()) return;
        Object model = Adapters.getRender().getBlockModel(state);
        if (model == null) return;

        long seed = state.is(Blocks.LILY_PAD)
            ? GeometryUtil.computeBushSeed(position.getX(), position.getY(), position.getZ())
            : Mth.getSeed(position.getX(), position.getY(), position.getZ());
        var batch = Adapters.getRender().getQuadBatch(model, state, position, level, seed);
        Vec3 randomOffset = vanillaRandomTransform
            ? new Vec3(block.randomOffsetX(), block.randomOffsetY(), block.randomOffsetZ())
            : Vec3.ZERO;
        MaterialFacts material = new MaterialFacts(
            MaterialFacts.BlendMode.UNKNOWN,
            block.lightEmission() > 0,
            false,
            true,
            0);

        for (QuadData quad : batch.quads()) {
            if (quad == null || quad.sprite() == null) continue;
            TextureAtlasSprite sprite = quad.sprite();
            VertexExtractor.extractPositionsUv(
                quad, position, sprite, 0d, 0d, 0d, randomOffset,
                positions, uv0, packedColors);
            unpackColors(packedColors, colors);
            fillNormal(positions, normal);
            String spriteKey = Adapters.getRender().getSpriteName(sprite);
            SpriteRef spriteRef = sprites.computeIfAbsent(spriteKey, ignored -> sprite(spriteKey, sprite));
            Face face = face(quad.direction());
            Face cullFace = face(quad.cullDirection());
            OcclusionFacts occlusion = cullFace == Face.NONE
                ? OcclusionFacts.visible()
                : world.occlusion(inputPosition.x(), inputPosition.y(), inputPosition.z(), cullFace);
            output.set(
                positions, uv0, null, normal, colors,
                spriteRef, material, occlusion, face, cullFace, batch.source().name());
            sink.accept(output);
        }
    }

    private static SpriteRef sprite(String key, TextureAtlasSprite sprite) {
        ResourceId id = ResourceId.parse(key);
        ResourceId atlas = ResourceId.parse(sprite.atlasLocation().toString());
        return new SpriteRef(
            id, atlas, sprite.getU0(), sprite.getU1(), sprite.getV0(), sprite.getV1(),
            sprite.contents().width(), sprite.contents().height(), false);
    }

    private static Face face(Direction direction) {
        if (direction == null) return Face.NONE;
        return switch (direction) {
            case DOWN -> Face.DOWN;
            case UP -> Face.UP;
            case NORTH -> Face.NORTH;
            case SOUTH -> Face.SOUTH;
            case WEST -> Face.WEST;
            case EAST -> Face.EAST;
        };
    }

    private static void unpackColors(int[] packed, float[] target) {
        for (int index = 0; index < 4; index++) {
            int value = packed[index];
            int offset = index * 4;
            target[offset] = ((value >> 16) & 0xff) / 255f;
            target[offset + 1] = ((value >> 8) & 0xff) / 255f;
            target[offset + 2] = (value & 0xff) / 255f;
            target[offset + 3] = ((value >>> 24) & 0xff) / 255f;
        }
    }

    private static void fillNormal(float[] vertices, float[] target) {
        float ax = vertices[3] - vertices[0];
        float ay = vertices[4] - vertices[1];
        float az = vertices[5] - vertices[2];
        float bx = vertices[6] - vertices[0];
        float by = vertices[7] - vertices[1];
        float bz = vertices[8] - vertices[2];
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length > 1.0e-8f) {
            target[0] = nx / length;
            target[1] = ny / length;
            target[2] = nz / length;
        } else {
            target[0] = target[1] = target[2] = 0f;
        }
    }
}
