package com.voxelbridge.export.texture;

/**
 * Platform-side adapters for core PBR atlas placement interfaces.
 */
public final class PbrPlacementAdapters {
    private PbrPlacementAdapters() {}

    /**
     * Adapter to convert {@link com.voxelbridge.core.export.ExportState.TexturePlacement}
     * to the core {@link com.voxelbridge.core.texture.PbrAtlasWriter.Placement} interface.
     */
    public static final class TexturePlacementAdapter implements com.voxelbridge.core.texture.PbrAtlasWriter.Placement {
        private final com.voxelbridge.core.export.ExportState.TexturePlacement placement;

        public TexturePlacementAdapter(com.voxelbridge.core.export.ExportState.TexturePlacement placement) {
            this.placement = placement;
        }

        @Override public int page() { return placement.page(); }
        @Override public int udim() {
            return 1001 + (placement.page() % 10) + (placement.page() / 10) * 10;
        }
        @Override public int x() { return placement.x(); }
        @Override public int y() { return placement.y(); }
        @Override public int width() { return placement.w(); }
        @Override public int height() { return placement.h(); }
    }

    /**
     * Adapter to convert {@link TextureAtlasPacker.Placement}
     * to the core {@link com.voxelbridge.core.texture.PbrAtlasWriter.Placement} interface.
     */
    public static final class PackerPlacementAdapter implements com.voxelbridge.core.texture.PbrAtlasWriter.Placement {
        private final TextureAtlasPacker.Placement placement;

        public PackerPlacementAdapter(TextureAtlasPacker.Placement placement) {
            this.placement = placement;
        }

        @Override public int page() { return placement.page(); }
        @Override public int udim() { return placement.udim(); }
        @Override public int x() { return placement.x(); }
        @Override public int y() { return placement.y(); }
        @Override public int width() { return placement.width(); }
        @Override public int height() { return placement.height(); }
    }
}
