package com.voxelbridge.platform;

import com.voxelbridge.config.ExportConfigStore;
import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.core.util.color.ColorMode;
import com.voxelbridge.export.CoordinateMode;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.gui.ClothConfigScreen;
import me.shedaniel.clothconfig2.gui.entries.EnumListEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Arrays;

/**
 * Fabric-only Cloth Config screen factory.
 */
public final class FabricConfigScreen {

    private static volatile boolean pendingOpen = false;
    private static volatile Screen pendingParent = null;
    private static volatile ClothConfigScreen activeScreen;
    private static volatile EnumListEntry<ExportRuntimeConfig.AtlasMode> atlasModeEntry;
    private static volatile AbstractConfigListEntry<?> atlasSizeEntry;
    private static volatile AbstractConfigListEntry<?> atlasPaddingEntry;

    private FabricConfigScreen() {
    }

    public static void requestOpen(Screen parent) {
        pendingParent = parent;
        pendingOpen = true;
    }

    public static void onClientTick(Minecraft mc) {
        if (pendingOpen) {
            Screen parent = pendingParent != null ? pendingParent : mc.screen;
            pendingParent = null;
            pendingOpen = false;
            mc.setScreen(create(parent));
            return;
        }

        ClothConfigScreen screen = activeScreen;
        if (screen == null) {
            return;
        }
        if (mc.screen != screen) {
            activeScreen = null;
            atlasModeEntry = null;
            atlasSizeEntry = null;
            atlasPaddingEntry = null;
            return;
        }
        updateAtlasEntryStates();
    }

    public static Screen create(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("config.voxelbridge.title"));

        builder.setSavingRunnable(ExportConfigStore::save);

        ConfigEntryBuilder entries = builder.entryBuilder();

        buildGeneralCategory(builder, entries);
        buildAtlasCategory(builder, entries);
        buildPerformanceCategory(builder, entries);

        return builder.build();
    }

    private static void buildGeneralCategory(ConfigBuilder builder, ConfigEntryBuilder entries) {
        ConfigCategory general = builder.getOrCreateCategory(
                Component.translatable("config.voxelbridge.category.general"));

        general.addEntry(entries.startEnumSelector(
                        Component.translatable("config.voxelbridge.coordinateMode"),
                        CoordinateMode.class,
                        ExportRuntimeConfig.getCoordinateMode())
                .setDefaultValue(CoordinateMode.CENTERED)
                .setTooltip(Component.translatable("config.voxelbridge.coordinateMode.tooltip"))
                .setEnumNameProvider(value -> {
                    CoordinateMode mode = (CoordinateMode) value;
                    return switch (mode) {
                        case CENTERED -> Component.translatable("config.voxelbridge.coordinateMode.centered");
                        case WORLD_ORIGIN -> Component.translatable("config.voxelbridge.coordinateMode.world");
                    };
                })
                .setSaveConsumer(ExportRuntimeConfig::setCoordinateMode)
                .build());

        general.addEntry(entries.startEnumSelector(
                        Component.translatable("config.voxelbridge.colorMode"),
                        ColorMode.class,
                        ExportRuntimeConfig.getColorMode())
                .setDefaultValue(ColorMode.BOTH)
                .setTooltip(Component.translatable("config.voxelbridge.colorMode.tooltip"))
                .setEnumNameProvider(value -> {
                    ColorMode mode = (ColorMode) value;
                    return switch (mode) {
                        case BOTH -> Component.translatable("config.voxelbridge.colorMode.both");
                        case VERTEX_COLOR -> Component.translatable("config.voxelbridge.colorMode.vertexcolor");
                        case COLORMAP -> Component.translatable("config.voxelbridge.colorMode.colormap");
                    };
                })
                .setSaveConsumer(ExportRuntimeConfig::setColorMode)
                .build());

        general.addEntry(entries.startBooleanToggle(
                        Component.translatable("config.voxelbridge.randomModel"),
                        ExportRuntimeConfig.isVanillaRandomTransformEnabled())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("config.voxelbridge.randomModel.tooltip"))
                .setSaveConsumer(ExportRuntimeConfig::setVanillaRandomTransformEnabled)
                .build());

        general.addEntry(entries.startBooleanToggle(
                        Component.translatable("config.voxelbridge.animation"),
                        ExportRuntimeConfig.isAnimationEnabled())
                .setDefaultValue(false)
                .setTooltip(Component.translatable("config.voxelbridge.animation.tooltip"))
                .setSaveConsumer(ExportRuntimeConfig::setAnimationEnabled)
                .build());

        general.addEntry(entries.startBooleanToggle(
                        Component.translatable("config.voxelbridge.fillCave"),
                        ExportRuntimeConfig.isFillCaveEnabled())
                .setDefaultValue(false)
                .setTooltip(Component.translatable("config.voxelbridge.fillCave.tooltip"))
                .setSaveConsumer(ExportRuntimeConfig::setFillCaveEnabled)
                .build());

        general.addEntry(entries.startBooleanToggle(
                        Component.translatable("config.voxelbridge.decodePBR"),
                        ExportRuntimeConfig.isPbrDecodeEnabled())
                .setDefaultValue(false)
                .setTooltip(Component.translatable("config.voxelbridge.decodePBR.tooltip"))
                .setSaveConsumer(ExportRuntimeConfig::setPbrDecodeEnabled)
                .build());

        general.addEntry(entries.startBooleanToggle(
                        Component.translatable("config.voxelbridge.logging"),
                        ExportRuntimeConfig.isLoggingEnabled())
                .setDefaultValue(false)
                .setTooltip(Component.translatable("config.voxelbridge.logging.tooltip"))
                .setSaveConsumer(ExportRuntimeConfig::setLoggingEnabled)
                .build());

        general.addEntry(entries.startBooleanToggle(
                        Component.translatable("config.voxelbridge.exportDoubleSided"),
                        ExportRuntimeConfig.isExportDoubleSidedEnabled())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("config.voxelbridge.exportDoubleSided.tooltip"))
                .setSaveConsumer(ExportRuntimeConfig::setExportDoubleSidedEnabled)
                .build());

        general.addEntry(entries.startBooleanToggle(
                        Component.translatable("config.voxelbridge.nonsolidCulling"),
                        ExportRuntimeConfig.isNonsolidCullingEnabled())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("config.voxelbridge.nonsolidCulling.tooltip"))
                .setSaveConsumer(ExportRuntimeConfig::setNonsolidCullingEnabled)
                .build());
    }

    private static void buildAtlasCategory(ConfigBuilder builder, ConfigEntryBuilder entries) {
        ConfigCategory atlas = builder.getOrCreateCategory(
                Component.translatable("config.voxelbridge.category.texturePacking"));

        EnumListEntry<ExportRuntimeConfig.AtlasMode> modeEntry = entries.startEnumSelector(
                        Component.translatable("config.voxelbridge.texturePacking"),
                        ExportRuntimeConfig.AtlasMode.class,
                        ExportRuntimeConfig.getAtlasMode())
                .setDefaultValue(ExportRuntimeConfig.AtlasMode.ATLAS)
                .setTooltip(Component.translatable("config.voxelbridge.texturePacking.tooltip"))
                .setEnumNameProvider(value -> {
                    ExportRuntimeConfig.AtlasMode mode = (ExportRuntimeConfig.AtlasMode) value;
                    return switch (mode) {
                        case INDIVIDUAL -> Component.translatable("config.voxelbridge.texturePacking.individual");
                        case ATLAS -> Component.translatable("config.voxelbridge.texturePacking.atlas");
                    };
                })
                .setSaveConsumer(ExportRuntimeConfig::setAtlasMode)
                .build();
        atlas.addEntry(modeEntry);

        EnumListEntry<ExportRuntimeConfig.AtlasSize> sizeEntry = entries.startEnumSelector(
                        Component.translatable("config.voxelbridge.atlasSize"),
                        ExportRuntimeConfig.AtlasSize.class,
                        ExportRuntimeConfig.getAtlasSize())
                .setDefaultValue(ExportRuntimeConfig.AtlasSize.SIZE_8192)
                .setTooltip(Component.translatable("config.voxelbridge.atlasSize.tooltip"))
                .setEnumNameProvider(value -> Component.literal(((ExportRuntimeConfig.AtlasSize) value).getSize() + " px"))
                .setSaveConsumer(ExportRuntimeConfig::setAtlasSize)
                .build();
        atlas.addEntry(sizeEntry);

        PaddingOption current = PaddingOption.fromValue(ExportRuntimeConfig.getAtlasPadding());
        EnumListEntry<PaddingOption> paddingEntry = entries.startEnumSelector(
                        Component.translatable("config.voxelbridge.texturePadding"),
                        PaddingOption.class,
                        current)
                .setDefaultValue(PaddingOption.PAD_0)
                .setTooltip(Component.translatable("config.voxelbridge.texturePadding.tooltip"))
                .setEnumNameProvider(value -> Component.literal(((PaddingOption) value).label))
                .setSaveConsumer(value -> ExportRuntimeConfig.setAtlasPadding(value.value))
                .build();
        atlas.addEntry(paddingEntry);

        builder.setAfterInitConsumer(screen -> {
            if (screen instanceof ClothConfigScreen clothScreen) {
                registerDynamicEnablement(clothScreen, modeEntry, sizeEntry, paddingEntry);
            }
        });
    }

    private static void buildPerformanceCategory(ConfigBuilder builder, ConfigEntryBuilder entries) {
        ConfigCategory perf = builder.getOrCreateCategory(
                Component.translatable("config.voxelbridge.category.performance"));

        perf.addEntry(entries.startIntSlider(
                        Component.translatable("config.voxelbridge.exportThreads"),
                        ExportRuntimeConfig.getExportThreadCount(),
                        1,
                        128)
                .setDefaultValue(16)
                .setTooltip(Component.translatable("config.voxelbridge.exportThreads.tooltip"))
                .setSaveConsumer(ExportRuntimeConfig::setExportThreadCount)
                .build());
    }

    private enum PaddingOption {
        PAD_0(0, "0 px"),
        PAD_4(4, "4 px"),
        PAD_8(8, "8 px"),
        PAD_12(12, "12 px"),
        PAD_16(16, "16 px");

        private final int value;
        private final String label;

        PaddingOption(int value, String label) {
            this.value = value;
            this.label = label;
        }

        private static PaddingOption fromValue(int value) {
            return Arrays.stream(values())
                    .filter(option -> option.value == value)
                    .findFirst()
                    .orElse(PAD_0);
        }
    }

    private static void registerDynamicEnablement(ClothConfigScreen screen,
                                                  EnumListEntry<ExportRuntimeConfig.AtlasMode> modeEntry,
                                                  AbstractConfigListEntry<?> sizeEntry,
                                                  AbstractConfigListEntry<?> paddingEntry) {
        activeScreen = screen;
        atlasModeEntry = modeEntry;
        atlasSizeEntry = sizeEntry;
        atlasPaddingEntry = paddingEntry;
        updateAtlasEntryStates();
    }

    private static void updateAtlasEntryStates() {
        EnumListEntry<ExportRuntimeConfig.AtlasMode> modeEntry = atlasModeEntry;
        AbstractConfigListEntry<?> sizeEntry = atlasSizeEntry;
        AbstractConfigListEntry<?> paddingEntry = atlasPaddingEntry;
        if (modeEntry == null || sizeEntry == null || paddingEntry == null) {
            return;
        }
        boolean enabled = modeEntry.getValue() == ExportRuntimeConfig.AtlasMode.ATLAS;
        sizeEntry.setEditable(enabled);
        paddingEntry.setEditable(enabled);
    }
}


