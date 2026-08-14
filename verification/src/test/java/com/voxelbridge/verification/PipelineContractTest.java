package com.voxelbridge.verification;

import com.voxelbridge.pipeline.ExportPipeline;
import com.voxelbridge.pipeline.contract.BlockPos3i;
import com.voxelbridge.pipeline.contract.BlockSample;
import com.voxelbridge.pipeline.contract.CapturedPrimitive;
import com.voxelbridge.pipeline.contract.Face;
import com.voxelbridge.pipeline.contract.MaterialFacts;
import com.voxelbridge.pipeline.contract.OcclusionFacts;
import com.voxelbridge.pipeline.contract.QuadInput;
import com.voxelbridge.pipeline.contract.Region3i;
import com.voxelbridge.pipeline.contract.ResourceId;
import com.voxelbridge.pipeline.contract.RuntimeCapabilities;
import com.voxelbridge.pipeline.contract.RuntimeCapability;
import com.voxelbridge.pipeline.contract.SpriteRef;
import com.voxelbridge.pipeline.geometry.FaceBounds;
import com.voxelbridge.pipeline.geometry.FaceCoveragePolicy;
import com.voxelbridge.pipeline.geometry.FaceInset;
import com.voxelbridge.pipeline.resource.ResourceIds;
import com.voxelbridge.pipeline.resource.PbrResourceCandidates;
import com.voxelbridge.pipeline.resource.TextureResourcePaths;
import com.voxelbridge.pipeline.region.ChunkWindowPlan;
import com.voxelbridge.pipeline.port.ClientExecutor;
import com.voxelbridge.pipeline.port.BlockGeometrySource;
import com.voxelbridge.pipeline.port.RuntimeDiagnostics;
import com.voxelbridge.pipeline.port.SpecialRenderSource;
import com.voxelbridge.pipeline.port.TextureSource;
import com.voxelbridge.pipeline.port.WorldSource;
import com.voxelbridge.pipeline.session.ExportSession;
import com.voxelbridge.pipeline.session.RuntimeServices;
import com.voxelbridge.core.texture.AnimatedFrameSet;
import com.voxelbridge.core.texture.AnimationFrameSplitter;
import com.voxelbridge.core.texture.AnimationMetadata;
import com.voxelbridge.core.texture.TextureRepository;
import com.voxelbridge.pipeline.texture.AnimationDetectionService;
import com.voxelbridge.export.texture.PbrImages;

import java.awt.image.BufferedImage;
import java.util.concurrent.Callable;
import java.util.ArrayList;
import java.util.List;

/** Dependency-free invariants for the version-neutral runtime contract. */
public final class PipelineContractTest {
    private PipelineContractTest() {}

    public static void main(String[] args) {
        require(ResourceId.parse("stone").toString().equals("minecraft:stone"),
            "Default namespace parsing failed");
        require(ResourceId.parse("example:block/tile").compareTo(ResourceId.parse("minecraft:stone")) < 0,
            "Resource ids must have deterministic ordering");
        require(ResourceIds.sanitizeKey("Bad Namespace:block:name with spaces")
                .equals("bad_namespace:block/name_with_spaces"),
            "Resource sanitization must be version independent");
        var pbrCandidates = PbrResourceCandidates.candidates(
            ResourceId.parse("minecraft:block/ctm/glass/3"), "_n");
        require(pbrCandidates.get(0).toString()
                .equals("minecraft:textures/block/ctm/glass/3_n.png"),
            "PBR direct candidate priority changed");
        require(pbrCandidates.stream().anyMatch(id -> id.toString()
                .equals("minecraft:textures/block/glass_n.png")),
            "PBR CTM fallback candidate was lost");
        require(TextureResourcePaths.fromSpriteKey("minecraft:stone").toString()
                .equals("minecraft:textures/block/stone.png"),
            "Bare block sprite conversion changed");
        require(TextureResourcePaths.fromSpriteKey(
                "blockentity:minecraft/entity/chest/normal").toString()
                .equals("minecraft:textures/entity/chest/normal.png"),
            "Block-entity pseudo namespace conversion changed");
        require(TextureResourcePaths.appendSuffix(
                "minecraft:textures/block/stone.png", "_n").toString()
                .equals("minecraft:textures/block/stone_n.png"),
            "Texture suffix insertion changed");

        Region3i region = new Region3i(new BlockPos3i(5, 8, 9), new BlockPos3i(-1, 2, 3));
        require(region.min().equals(new BlockPos3i(-1, 2, 3)), "Region min was not normalized");
        require(region.contains(new BlockPos3i(0, 5, 5)), "Normalized region containment failed");
        require(new BlockPos3i(1, 2, 3).offset(Face.WEST).equals(new BlockPos3i(0, 2, 3)),
            "Canonical face offset failed");

        SpriteRef sprite = SpriteRef.standalone(ResourceId.parse("minecraft:block/stone"), 16, 16, false);
        QuadInput quad = new QuadInput().set(
            new float[12], null, null, new float[] {0f, 1f, 0f}, null,
            sprite, MaterialFacts.opaque(), OcclusionFacts.visible(), Face.UP, Face.UP, "test");
        require(quad.uv0() == null, "Missing UV must remain representable");
        require(quad.occlusion().vanillaVisible(), "Visibility fact was lost");

        CapturedPrimitive primitive = new CapturedPrimitive(
            new float[9], null, null, null, sprite, MaterialFacts.opaque(), "glyph");
        require(primitive.vertexCount() == 3, "Captured primitive vertex count failed");

        RuntimeCapabilities capabilities = RuntimeCapabilities.of(
            RuntimeCapability.BLOCK_MODEL_QUADS,
            RuntimeCapability.DYNAMIC_TEXTURE_READBACK);
        require(capabilities.supports(RuntimeCapability.BLOCK_MODEL_QUADS), "Capability was lost");
        require(!capabilities.supports(RuntimeCapability.ENTITY_RENDER_CAPTURE),
            "Unsupported capability was reported");

        FaceBounds full = new FaceBounds(0f, 1f, 0f, 1f);
        FaceBounds fencePost = new FaceBounds(0.375f, 0.625f, 0f, 1f);
        FaceBounds partial = new FaceBounds(0f, 0.25f, 0f, 1f);
        require(FaceCoveragePolicy.shouldCullAgainstNeighbor(
            true, false, true, true, fencePost, full),
            "A fully covered non-solid face should be culled");
        require(!FaceCoveragePolicy.shouldCullAgainstNeighbor(
            true, false, true, true, fencePost, partial),
            "Partial neighbor coverage must conservatively retain the face");
        require(!FaceCoveragePolicy.shouldCullAgainstNeighbor(
            true, false, false, true, fencePost, full),
            "Unknown runtime coverage must conservatively retain the face");
        require(FaceCoveragePolicy.shouldCullAgainstNeighbor(
            true, false, new OcclusionFacts(true, true, true, true, null),
            new float[] {0.375f, 0.625f, 0f, 1f}, 1.0e-3f),
            "Normalized full-face facts should cull a covered face");
        require(!FaceCoveragePolicy.shouldCullAgainstNeighbor(
            true, false, OcclusionFacts.unknown(),
            new float[] {0.375f, 0.625f, 0f, 1f}, 1.0e-3f),
            "Unknown normalized facts must retain geometry");

        float[] insetPositions = {1f, 2f, 3f, 4f, 5f, 6f};
        FaceInset.apply(insetPositions, Face.EAST, 0.25f);
        require(insetPositions[0] == 0.75f && insetPositions[3] == 3.75f,
            "Face inset must update every vertex");

        ChunkWindowPlan chunks = ChunkWindowPlan.create(new Region3i(
            new BlockPos3i(-1, 0, -1), new BlockPos3i(16, 10, 16)));
        require(chunks.units().size() == 9, "Chunk planning across negative boundaries failed");
        require(chunks.units().get(0).minX() == -1 && chunks.units().get(0).maxX() == -1,
            "Chunk work bounds were not clipped to the region");
        require(chunks.workerCount(16, 8) == 6, "Worker budgeting must reserve two processors");

        BufferedImage missing = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        missing.setRGB(0, 0, 0xFFFF00FF);
        missing.setRGB(1, 0, 0xFF000000);
        missing.setRGB(0, 1, 0xFF000000);
        missing.setRGB(1, 1, 0xFFFF00FF);
        BufferedImage normal = PbrImages.sanitizeMissingTexture(missing, PbrImages.DEFAULT_NORMAL_COLOR);
        require(normal != missing && normal.getRGB(0, 0) == PbrImages.DEFAULT_NORMAL_COLOR,
            "PBR missing-texture fallback was not normalized");
        BufferedImage ordinary = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        ordinary.setRGB(0, 0, 0xFF123456);
        require(PbrImages.sanitizeMissingTexture(ordinary, PbrImages.DEFAULT_NORMAL_COLOR) == ordinary,
            "Ordinary PBR images must be retained by identity");
        AnimatedFrameSet baseFrames = new AnimatedFrameSet(List.of(ordinary), 2);
        AnimatedFrameSet fallbackFrames = PbrImages.matchOrDefault(null, baseFrames, PbrImages.DEFAULT_SPECULAR_COLOR);
        require(fallbackFrames != null && fallbackFrames.frames().get(0).getWidth() == 1,
            "Missing animated PBR frames did not receive a deterministic fallback");

        BufferedImage strip = new BufferedImage(2, 4, BufferedImage.TYPE_INT_ARGB);
        strip.setRGB(0, 0, 0xFFFF0000);
        strip.setRGB(0, 2, 0xFF00FF00);
        AnimationMetadata orderedMetadata = new AnimationMetadata(
            2, List.of(new AnimationMetadata.FrameTiming(1, 3),
                new AnimationMetadata.FrameTiming(0, 4)), false, 2, 2);
        AnimatedFrameSet splitFrames = AnimationFrameSplitter.split(strip, orderedMetadata);
        require(splitFrames != null && splitFrames.frames().size() == 2,
            "Metadata-driven frame splitting failed");
        require(splitFrames.frames().get(0).getRGB(0, 0) == 0xFF00FF00,
            "Custom animation frame order was not preserved");

        TextureRepository animationRepository = new TextureRepository();
        ResourceId animationResource = ResourceId.parse("minecraft:textures/block/test.png");
        AnimatedFrameSet detected = AnimationDetectionService.ensure(new TextureSource() {
            @Override
            public BufferedImage readTexture(ResourceId resource, boolean preserveAnimationStrip) {
                return strip;
            }

            @Override
            public BufferedImage readSprite(SpriteRef sprite) {
                return strip;
            }

            @Override
            public boolean hasResource(ResourceId resource) {
                return true;
            }

            @Override
            public AnimationMetadata readAnimationMetadata(ResourceId resource) {
                return orderedMetadata;
            }
        }, animationRepository, "minecraft:block/test", animationResource);
        require(detected != null && animationRepository.hasAnimation("minecraft:block/test"),
            "TextureSource animation detection did not populate the session repository");

        RuntimeServices fakeRuntime = RuntimeServices.migrating(
            new TextureSource() {
                @Override
                public BufferedImage readTexture(ResourceId resource, boolean preserveAnimationStrip) {
                    return new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
                }

                @Override
                public BufferedImage readSprite(SpriteRef sprite) {
                    return readTexture(sprite.id(), false);
                }

                @Override
                public boolean hasResource(ResourceId resource) {
                    return true;
                }
            },
            new ClientExecutor() {
                @Override
                public boolean isClientThread() {
                    return true;
                }

                @Override
                public <T> T callBlocking(Callable<T> task) {
                    try {
                        return task.call();
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                }
            },
            capabilities
        );
        ExportSession first = new ExportSession(fakeRuntime);
        ExportSession second = new ExportSession(fakeRuntime);
        first.putAttribute("probe", "first");
        require(second.attribute("probe") == null, "Export sessions leaked mutable state");

        BlockSample pipelineBlock = new BlockSample(
            new BlockPos3i(1, 2, 3), ResourceId.parse("minecraft:stone"), 0, 0f, 0f, 0f);
        List<RuntimeDiagnostics.Event> diagnostics = new ArrayList<>();
        RuntimeServices executableRuntime = new RuntimeServices(
            new WorldSource() {
                @Override
                public void visitBlocks(Region3i requested, BlockVisitor visitor) {
                    visitor.visit(pipelineBlock);
                }

                @Override
                public OcclusionFacts occlusion(int x, int y, int z, Face face) {
                    return OcclusionFacts.visible();
                }
            },
            (block, sink) -> sink.accept(quad),
            (requested, sink) -> sink.accept(primitive),
            fakeRuntime.textures(),
            fakeRuntime.client(),
            diagnostics::add,
            RuntimeCapabilities.of(
                RuntimeCapability.BLOCK_MODEL_QUADS,
                RuntimeCapability.ENTITY_RENDER_CAPTURE)
        );
        try (ExportSession executableSession = new ExportSession(executableRuntime)) {
            ExportPipeline.ExportSummary summary = new ExportPipeline(executableSession)
                .exportRegion(region, ignored -> {}, ignored -> {});
            require(summary.blocks() == 1 && summary.quads() == 1 && summary.primitives() == 1,
                "Runtime ports were not composed by the version-neutral pipeline");
            require(diagnostics.stream().anyMatch(event ->
                    event.category().equals("pipeline-region-complete")),
                "Pipeline completion diagnostics were not emitted");
        }
        first.close();
        require(first.isClosed() && !second.isClosed(), "Closing one session affected another");
        second.close();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
