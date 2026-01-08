package com.voxelbridge.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.tree.CommandNode;
import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.core.util.color.ColorMode;
import com.voxelbridge.export.CoordinateMode;
import com.voxelbridge.export.ExportControl;
import com.voxelbridge.platform.client.ClientAccessHolder;
import com.voxelbridge.util.client.RayCastUtil;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

/**
 * Fabric client-side /voxelbridge command registration.
 * Mirrors NeoForge commands and options.
 */
public final class VoxelBridgeCommands {

    private VoxelBridgeCommands() {}

    public static BlockPos getPos1() {
        return ExportControl.getPos1();
    }

    public static BlockPos getPos2() {
        return ExportControl.getPos2();
    }

    public static void setPos1(BlockPos pos) {
        ExportControl.setPos1(pos);
    }

    public static void setPos2(BlockPos pos) {
        ExportControl.setPos2(pos);
    }

    public static void clearSelection() {
        ExportControl.clearSelection();
    }

    public static void registerFabric(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        var root = ClientCommandManager.literal("voxelbridge");

        root.then(ClientCommandManager.literal("pos1").executes(ctx -> {
            var mc = ClientAccessHolder.get().getMinecraft();
            BlockPos hit = RayCastUtil.getLookingAt(mc, 20.0);
            if (hit == null) {
                ctx.getSource().sendFeedback(Text.literal("c[VoxelBridge] No block targeted."));
                return 0;
            }
            setPos1(hit);
            ctx.getSource().sendFeedback(Text.literal("a[VoxelBridge] pos1 set to " + getPos1()));
            return 1;
        }));

        root.then(ClientCommandManager.literal("pos2").executes(ctx -> {
            var mc = ClientAccessHolder.get().getMinecraft();
            BlockPos hit = RayCastUtil.getLookingAt(mc, 20.0);
            if (hit == null) {
                ctx.getSource().sendFeedback(Text.literal("c[VoxelBridge] No block targeted."));
                return 0;
            }
            setPos2(hit);
            ctx.getSource().sendFeedback(Text.literal("a[VoxelBridge] pos2 set to " + getPos2()));
            return 1;
        }));

        root.then(ClientCommandManager.literal("info").executes(ctx -> {
            ctx.getSource().sendFeedback(Text.literal("6[VoxelBridge] Selection info:"));
            ctx.getSource().sendFeedback(Text.literal("e  pos1: f" + (getPos1() != null ? getPos1() : "unset")));
            ctx.getSource().sendFeedback(Text.literal("e  pos2: f" + (getPos2() != null ? getPos2() : "unset")));
            ctx.getSource().sendFeedback(Text.literal("e  Atlas mode: f" + ExportRuntimeConfig.getAtlasMode().getDescription()));
            ctx.getSource().sendFeedback(Text.literal("e  Atlas size: f" + ExportRuntimeConfig.getAtlasSize().getDescription()));
            ctx.getSource().sendFeedback(Text.literal("e  Atlas padding: f" + ExportRuntimeConfig.getAtlasPadding() + "px"));
            ctx.getSource().sendFeedback(Text.literal("e  Coordinate mode: f" +
                (ExportRuntimeConfig.getCoordinateMode() == CoordinateMode.CENTERED ? "centered" : "world")));
            ctx.getSource().sendFeedback(Text.literal("e  Color mode: f" + ExportRuntimeConfig.getColorMode().getDescription()));
            ctx.getSource().sendFeedback(Text.literal("e  Vanilla random transform: f" +
                (ExportRuntimeConfig.isVanillaRandomTransformEnabled() ? "on" : "off")));
            ctx.getSource().sendFeedback(Text.literal("e  Animation export: f" +
                (ExportRuntimeConfig.isAnimationEnabled() ? "on" : "off")));
            ctx.getSource().sendFeedback(Text.literal("e  Fill cave (dark cave_air): f" +
                (ExportRuntimeConfig.isFillCaveEnabled() ? "on" : "off")));
            ctx.getSource().sendFeedback(Text.literal("e  LabPBR decode: f" +
                (ExportRuntimeConfig.isPbrDecodeEnabled() ? "on" : "off")));
            ctx.getSource().sendFeedback(Text.literal("e  Export threads: f" + ExportRuntimeConfig.getExportThreadCount()));
            return 1;
        }));

        root.then(ClientCommandManager.literal("clear").executes(ctx -> {
            clearSelection();
            ctx.getSource().sendFeedback(Text.literal("e[VoxelBridge] Selection cleared."));
            return 1;
        }));

        root.then(ClientCommandManager.literal("atlas")
            .executes(ctx -> {
                ctx.getSource().sendFeedback(Text.literal("e[VoxelBridge] Current atlas mode: f" + ExportRuntimeConfig.getAtlasMode().getDescription()));
                ctx.getSource().sendFeedback(Text.literal("7   individual: one texture per sprite"));
                ctx.getSource().sendFeedback(Text.literal("7   atlas: pack into 8192 UDIM tiles"));
                return 1;
            })
            .then(ClientCommandManager.literal("individual").executes(ctx -> {
                ExportRuntimeConfig.setAtlasMode(ExportRuntimeConfig.AtlasMode.INDIVIDUAL);
                ctx.getSource().sendFeedback(Text.literal("a[VoxelBridge] Atlas mode -> Individual textures"));
                return 1;
            }))
            .then(ClientCommandManager.literal("atlas").executes(ctx -> {
                ExportRuntimeConfig.setAtlasMode(ExportRuntimeConfig.AtlasMode.ATLAS);
                ctx.getSource().sendFeedback(Text.literal("a[VoxelBridge] Atlas mode -> Packed atlas (UDIM 8192)"));
                return 1;
            }))
        );

        root.then(ClientCommandManager.literal("animation")
            .executes(ctx -> {
                ctx.getSource().sendFeedback(Text.literal("6[VoxelBridge] Animation export is currently f"
                    + (ExportRuntimeConfig.isAnimationEnabled() ? "on" : "off")));
                ctx.getSource().sendFeedback(Text.literal("7   Usage: /voxelbridge animation <on|off>"));
                return 1;
            })
            .then(ClientCommandManager.literal("on").executes(ctx -> {
                ExportRuntimeConfig.setAnimationEnabled(true);
                ctx.getSource().sendFeedback(Text.literal("a[VoxelBridge] Animation export -> ON"));
                return 1;
            }))
            .then(ClientCommandManager.literal("off").executes(ctx -> {
                ExportRuntimeConfig.setAnimationEnabled(false);
                ctx.getSource().sendFeedback(Text.literal("a[VoxelBridge] Animation export -> OFF"));
                return 1;
            }))
        );

        root.then(ClientCommandManager.literal("fillcave")
            .executes(ctx -> {
                ctx.getSource().sendFeedback(Text.literal("6[VoxelBridge] Fill cave is currently f"
                    + (ExportRuntimeConfig.isFillCaveEnabled() ? "on" : "off")));
                ctx.getSource().sendFeedback(Text.literal("7   Usage: /voxelbridge fillcave <on|off>"));
                ctx.getSource().sendFeedback(Text.literal("7   on : Treat dark cave_air (skylight=0) as solid for culling"));
                ctx.getSource().sendFeedback(Text.literal("7   off: Normal culling behavior"));
                return 1;
            })
            .then(ClientCommandManager.literal("on").executes(ctx -> {
                ExportRuntimeConfig.setFillCaveEnabled(true);
                ctx.getSource().sendFeedback(Text.literal("a[VoxelBridge] Fill cave -> ON (dark caves will be culled)"));
                return 1;
            }))
            .then(ClientCommandManager.literal("off").executes(ctx -> {
                ExportRuntimeConfig.setFillCaveEnabled(false);
                ctx.getSource().sendFeedback(Text.literal("a[VoxelBridge] Fill cave -> OFF"));
                return 1;
            }))
        );

        root.then(ClientCommandManager.literal("atlassize")
            .executes(ctx -> {
                ExportRuntimeConfig.AtlasSize current = ExportRuntimeConfig.getAtlasSize();
                ctx.getSource().sendFeedback(Text.literal("6[VoxelBridge] Current atlas size: f" + current.getDescription()));
                ctx.getSource().sendFeedback(Text.literal("7   Available sizes:"));
                for (ExportRuntimeConfig.AtlasSize size : ExportRuntimeConfig.AtlasSize.values()) {
                    String marker = size == current ? "a> " : "7  ";
                    ctx.getSource().sendFeedback(Text.literal(marker + size.getSize() + ": " + size.getDescription()));
                }
                ctx.getSource().sendFeedback(Text.literal("7   Usage: /voxelbridge atlassize <size>"));
                return 1;
            })
            .then(ClientCommandManager.literal("128").executes(ctx -> {
                ExportRuntimeConfig.setAtlasSize(ExportRuntimeConfig.AtlasSize.SIZE_128);
                ctx.getSource().sendFeedback(Text.literal("a[VoxelBridge] Atlas size -> 128x128"));
                return 1;
            }))
            .then(ClientCommandManager.literal("256").executes(ctx -> {
                ExportRuntimeConfig.setAtlasSize(ExportRuntimeConfig.AtlasSize.SIZE_256);
                ctx.getSource().sendFeedback(Text.literal("a[VoxelBridge] Atlas size -> 256x256"));
                return 1;
            }))
            .then(ClientCommandManager.literal("512").executes(ctx -> {
                ExportRuntimeConfig.setAtlasSize(ExportRuntimeConfig.AtlasSize.SIZE_512);
                ctx.getSource().sendFeedback(Text.literal("a[VoxelBridge] Atlas size -> 512x512"));
                return 1;
            }))
            .then(ClientCommandManager.literal("1024").executes(ctx -> {
                ExportRuntimeConfig.setAtlasSize(ExportRuntimeConfig.AtlasSize.SIZE_1024);
                ctx.getSource().sendFeedback(Text.literal("a[VoxelBridge] Atlas size -> 1024x1024"));
                return 1;
            }))
            .then(ClientCommandManager.literal("2048").executes(ctx -> {
                ExportRuntimeConfig.setAtlasSize(ExportRuntimeConfig.AtlasSize.SIZE_2048);
                ctx.getSource().sendFeedback(Text.literal("a[VoxelBridge] Atlas size -> 2048x2048"));
                return 1;
            }))
            .then(ClientCommandManager.literal("4096").executes(ctx -> {
                ExportRuntimeConfig.setAtlasSize(ExportRuntimeConfig.AtlasSize.SIZE_4096);
                ctx.getSource().sendFeedback(Text.literal("a[VoxelBridge] Atlas size -> 4096x4096"));
                return 1;
            }))
            .then(ClientCommandManager.literal("8192").executes(ctx -> {
                ExportRuntimeConfig.setAtlasSize(ExportRuntimeConfig.AtlasSize.SIZE_8192);
                ctx.getSource().sendFeedback(Text.literal("a[VoxelBridge] Atlas size -> 8192x8192"));
                return 1;
            }))
        );

        root.then(ClientCommandManager.literal("atlaspad")
            .executes(ctx -> {
                int current = ExportRuntimeConfig.getAtlasPadding();
                ctx.getSource().sendFeedback(Text.literal("6[VoxelBridge] Current atlas padding: f" + current + "px"));
                ctx.getSource().sendFeedback(Text.literal("7   Allowed values: 0, 4, 8, 12, 16"));
                ctx.getSource().sendFeedback(Text.literal("7   Usage: /voxelbridge atlaspad <pixels>"));
                return 1;
            })
            .then(ClientCommandManager.argument("pixels", IntegerArgumentType.integer(0, 64)).executes(ctx -> {
                int pixels = IntegerArgumentType.getInteger(ctx, "pixels");
                if (!ExportRuntimeConfig.setAtlasPadding(pixels)) {
                    ctx.getSource().sendFeedback(Text.literal("c[VoxelBridge] Invalid padding. Allowed: 0, 4, 8, 12, 16"));
                    return 0;
                }
                ctx.getSource().sendFeedback(Text.literal("a[VoxelBridge] Atlas padding -> " + pixels + "px"));
                return 1;
            }))
        );

        root.then(ClientCommandManager.literal("coords")
            .executes(ctx -> {
                String mode = ExportRuntimeConfig.getCoordinateMode() == CoordinateMode.CENTERED ? "centered" : "world";
                ctx.getSource().sendFeedback(Text.literal("6[VoxelBridge] Coordinate mode is currently f" + mode));
                ctx.getSource().sendFeedback(Text.literal("7   centered: model centered at origin (default)"));
                ctx.getSource().sendFeedback(Text.literal("7   world: preserve original world coordinates"));
                return 1;
            })
            .then(ClientCommandManager.literal("centered").executes(ctx -> {
                ExportRuntimeConfig.setCoordinateMode(CoordinateMode.CENTERED);
                ctx.getSource().sendFeedback(Text.literal("a[VoxelBridge] Coordinate mode -> Centered (model at origin)"));
                return 1;
            }))
            .then(ClientCommandManager.literal("world").executes(ctx -> {
                ExportRuntimeConfig.setCoordinateMode(CoordinateMode.WORLD_ORIGIN);
                ctx.getSource().sendFeedback(Text.literal("a[VoxelBridge] Coordinate mode -> World (preserve coordinates)"));
                return 1;
            }))
        );

        root.then(ClientCommandManager.literal("poshash")
            .executes(ctx -> {
                ctx.getSource().sendFeedback(Text.literal("6[VoxelBridge] Vanilla random transform is currently f"
                    + (ExportRuntimeConfig.isVanillaRandomTransformEnabled() ? "on" : "off")));
                ctx.getSource().sendFeedback(Text.literal("7   Usage: /voxelbridge poshash <on|off>"));
                ctx.getSource().sendFeedback(Text.literal("7   on : Apply vanilla position-hash random offsets/variants"));
                ctx.getSource().sendFeedback(Text.literal("7   off: Disable offsets and keep legacy behavior"));
                return 1;
            })
            .then(ClientCommandManager.literal("on").executes(ctx -> {
                ExportRuntimeConfig.setVanillaRandomTransformEnabled(true);
                ctx.getSource().sendFeedback(Text.literal("a[VoxelBridge] Vanilla random transform -> ON"));
                return 1;
            }))
            .then(ClientCommandManager.literal("off").executes(ctx -> {
                ExportRuntimeConfig.setVanillaRandomTransformEnabled(false);
                ctx.getSource().sendFeedback(Text.literal("a[VoxelBridge] Vanilla random transform -> OFF"));
                return 1;
            }))
        );

        root.then(ClientCommandManager.literal("colormode")
            .executes(ctx -> {
                ColorMode current = ExportRuntimeConfig.getColorMode();
                ctx.getSource().sendFeedback(Text.literal("6[VoxelBridge] Current color mode: f" + current.getDescription()));
                ctx.getSource().sendFeedback(Text.literal("7   colormap: TEXCOORD_1 + colormap texture (default)"));
                ctx.getSource().sendFeedback(Text.literal("7   vertexcolor: COLOR_0 vertex attribute"));
                return 1;
            })
            .then(ClientCommandManager.literal("colormap").executes(ctx -> {
                ExportRuntimeConfig.setColorMode(ColorMode.COLORMAP);
                ctx.getSource().sendFeedback(Text.literal("a[VoxelBridge] Color mode -> ColorMap"));
                return 1;
            }))
            .then(ClientCommandManager.literal("vertexcolor").executes(ctx -> {
                ExportRuntimeConfig.setColorMode(ColorMode.VERTEX_COLOR);
                ctx.getSource().sendFeedback(Text.literal("a[VoxelBridge] Color mode -> Vertex Color"));
                return 1;
            }))
        );

        root.then(ClientCommandManager.literal("threads")
            .executes(ctx -> {
                int threads = ExportRuntimeConfig.getExportThreadCount();
                int cpuCores = Runtime.getRuntime().availableProcessors();
                ctx.getSource().sendFeedback(Text.literal("6[VoxelBridge] Export thread count: f" + threads + "7 (CPU cores: " + cpuCores + ")"));
                ctx.getSource().sendFeedback(Text.literal("7   Usage: /voxelbridge threads <count> (1-32)"));
                return 1;
            })
            .then(ClientCommandManager.argument("count", IntegerArgumentType.integer(1, 32)).executes(ctx -> {
                int count = IntegerArgumentType.getInteger(ctx, "count");
                ExportRuntimeConfig.setExportThreadCount(count);
                ctx.getSource().sendFeedback(Text.literal("a[VoxelBridge] Export threads -> " + count));
                return 1;
            }))
        );

        root.then(ClientCommandManager.literal("pbrdecode")
            .executes(ctx -> {
                ctx.getSource().sendFeedback(Text.literal("6[VoxelBridge] LabPBR decode is currently f"
                    + (ExportRuntimeConfig.isPbrDecodeEnabled() ? "on" : "off")));
                ctx.getSource().sendFeedback(Text.literal("7   Usage: /voxelbridge pbrdecode <on|off>"));
                return 1;
            })
            .then(ClientCommandManager.literal("on").executes(ctx -> {
                ExportRuntimeConfig.setPbrDecodeEnabled(true);
                ctx.getSource().sendFeedback(Text.literal("a[VoxelBridge] LabPBR decode -> ON"));
                return 1;
            }))
            .then(ClientCommandManager.literal("off").executes(ctx -> {
                ExportRuntimeConfig.setPbrDecodeEnabled(false);
                ctx.getSource().sendFeedback(Text.literal("a[VoxelBridge] LabPBR decode -> OFF"));
                return 1;
            }))
        );

        root.then(ClientCommandManager.literal("export").executes(ctx -> {
            var mc = ClientAccessHolder.get().getMinecraft();
            var result = ExportControl.startExport(mc.world);
            ctx.getSource().sendFeedback(Text.literal("a[VoxelBridge] " + result.message()));
            return result.started() ? 1 : 0;
        }));

        // Register root and alias "vb"
        CommandNode<FabricClientCommandSource> rootNode = dispatcher.register(root);
        dispatcher.register(ClientCommandManager.literal("vb").redirect(rootNode));
    }
}
