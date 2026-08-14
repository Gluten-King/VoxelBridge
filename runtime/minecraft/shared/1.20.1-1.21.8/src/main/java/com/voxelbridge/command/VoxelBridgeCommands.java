package com.voxelbridge.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.tree.CommandNode;
import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.core.util.color.ColorMode;
import com.voxelbridge.export.CoordinateMode;
import com.voxelbridge.export.ExportControl;
import com.voxelbridge.platform.client.ClientAccessHolder;
import com.voxelbridge.util.client.RayCastUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import com.mojang.brigadier.CommandDispatcher;

/**
 * Client-side /voxelbridge command registration.
 * Handles selection management and export options.
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

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("voxelbridge");

        root.then(Commands.literal("pos1")
            .executes(ctx -> {
                var mc = ClientAccessHolder.get().getMinecraft();
                BlockPos hit = RayCastUtil.getLookingAt(mc, 20.0);
                if (hit == null) {
                    if (mc.player == null) {
                        ctx.getSource().sendSystemMessage(Component.literal("[VoxelBridge] No block targeted."));
                        return 0;
                    }
                    hit = mc.player.blockPosition();
                    ctx.getSource().sendSystemMessage(Component.literal("[VoxelBridge] No block targeted, use current position: " + hit.toShortString()));
                }
                setPos1(hit);
                ctx.getSource().sendSystemMessage(Component.literal("§b[VoxelBridge] pos1 set to " + getPos1().toShortString()));
                return 1;
            })
            .then(Commands.argument("pos", BlockPosArgument.blockPos())
                .executes(ctx -> {
                    BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
                    setPos1(pos);
                    ctx.getSource().sendSystemMessage(Component.literal("§b[VoxelBridge] pos1 set to " + getPos1().toShortString()));
                    return 1;
                })));

        root.then(Commands.literal("pos2")
            .executes(ctx -> {
                var mc = ClientAccessHolder.get().getMinecraft();
                BlockPos hit = RayCastUtil.getLookingAt(mc, 20.0);
                if (hit == null) {
                    if (mc.player == null) {
                        ctx.getSource().sendSystemMessage(Component.literal("[VoxelBridge] No block targeted."));
                        return 0;
                    }
                    hit = mc.player.blockPosition();
                    ctx.getSource().sendSystemMessage(Component.literal("[VoxelBridge] No block targeted, use current position: " + hit.toShortString()));
                }
                setPos2(hit);
                ctx.getSource().sendSystemMessage(Component.literal("§b[VoxelBridge] pos2 set to " + getPos2().toShortString()));
                return 1;
            })
            .then(Commands.argument("pos", BlockPosArgument.blockPos())
                .executes(ctx -> {
                    BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
                    setPos2(pos);
                    ctx.getSource().sendSystemMessage(Component.literal("§b[VoxelBridge] pos2 set to " + getPos2().toShortString()));
                    return 1;
                })));

        root.then(Commands.literal("info").executes(ctx -> {
            ctx.getSource().sendSystemMessage(Component.literal("§6[VoxelBridge] Selection info:"));
                ctx.getSource().sendSystemMessage(Component.literal("§b  pos1: §f" + (getPos1() != null ? getPos1().toShortString() : "unset")));
                ctx.getSource().sendSystemMessage(Component.literal("§b  pos2: §f" + (getPos2() != null ? getPos2().toShortString() : "unset")));
            ctx.getSource().sendSystemMessage(Component.literal("§b  Atlas mode: §f" + ExportRuntimeConfig.getAtlasMode().getDescription()));
            ctx.getSource().sendSystemMessage(Component.literal("§b  Atlas size: §f" + ExportRuntimeConfig.getAtlasSize().getDescription()));
            ctx.getSource().sendSystemMessage(Component.literal("§b  Atlas padding: §f" + ExportRuntimeConfig.getAtlasPadding() + "px"));
            ctx.getSource().sendSystemMessage(Component.literal("§b  Coordinate mode: §f" +
                    (ExportRuntimeConfig.getCoordinateMode() == CoordinateMode.CENTERED ? "centered" : "world")));
            ctx.getSource().sendSystemMessage(Component.literal("§b  Color mode: §f" + ExportRuntimeConfig.getColorMode().getDescription()));
            ctx.getSource().sendSystemMessage(Component.literal("§b  Vanilla random transform: " + (ExportRuntimeConfig.isVanillaRandomTransformEnabled() ? "§aon" : "§coff")));
            ctx.getSource().sendSystemMessage(Component.literal("§b  Animation export: " + (ExportRuntimeConfig.isAnimationEnabled() ? "§aon" : "§coff")));
            ctx.getSource().sendSystemMessage(Component.literal("§b  Fill cave (dark cave_air): " + (ExportRuntimeConfig.isFillCaveEnabled() ? "§aon" : "§coff")));
            ctx.getSource().sendSystemMessage(Component.literal("§b  LabPBR decode: " + (ExportRuntimeConfig.isPbrDecodeEnabled() ? "§aon" : "§coff")));
            ctx.getSource().sendSystemMessage(Component.literal("§b  Logging: " + (ExportRuntimeConfig.isLoggingEnabled() ? "§aon" : "§coff")));
            ctx.getSource().sendSystemMessage(Component.literal("§b  Collapse double-sided: " + (ExportRuntimeConfig.isExportDoubleSidedEnabled() ? "§aon" : "§coff")));
            ctx.getSource().sendSystemMessage(Component.literal("\u00a7b  Nonsolid culling: " + (ExportRuntimeConfig.isNonsolidCullingEnabled() ? "\u00a7aon" : "\u00a7coff")));
            ctx.getSource().sendSystemMessage(Component.literal("§b  Export threads: §f" + ExportRuntimeConfig.getExportThreadCount()));
            return 1;
        }));

        root.then(Commands.literal("clear").executes(ctx -> {
            clearSelection();
            ctx.getSource().sendSystemMessage(Component.literal("§b[VoxelBridge] Selection cleared."));
            return 1;
        }));

        root.then(Commands.literal("texturePacking")
                .executes(ctx -> {
                    ctx.getSource().sendSystemMessage(Component.literal("§b[VoxelBridge] Current atlas mode: §f" + ExportRuntimeConfig.getAtlasMode().getDescription()));
                    ctx.getSource().sendSystemMessage(Component.literal("§7   individual: one texture per sprite"));
                    ctx.getSource().sendSystemMessage(Component.literal("§7   atlas: pack into 8192 UDIM tiles"));
                    return 1;
                })
                .then(Commands.literal("individual").executes(ctx -> {
                    ExportRuntimeConfig.setAtlasMode(ExportRuntimeConfig.AtlasMode.INDIVIDUAL);
                    ctx.getSource().sendSystemMessage(Component.literal("§b[VoxelBridge] Atlas mode -> Individual textures"));
                    return 1;
                }))
                .then(Commands.literal("atlas").executes(ctx -> {
                    ExportRuntimeConfig.setAtlasMode(ExportRuntimeConfig.AtlasMode.ATLAS);
                    ctx.getSource().sendSystemMessage(Component.literal("§b[VoxelBridge] Atlas mode -> Packed atlas (UDIM 8192)"));
                    return 1;
                }))
        );

        root.then(Commands.literal("animation")
                .executes(ctx -> {
                    ctx.getSource().sendSystemMessage(Component.literal("§b[VoxelBridge] Animation export is currently " + (ExportRuntimeConfig.isAnimationEnabled() ? "§aon" : "§coff")));
                    ctx.getSource().sendSystemMessage(Component.literal("§7   Usage: /voxelbridge animation <on|off>"));
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    ExportRuntimeConfig.setAnimationEnabled(true);
                    ctx.getSource().sendSystemMessage(Component.literal("§a[VoxelBridge] Animation export -> ON"));
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    ExportRuntimeConfig.setAnimationEnabled(false);
                    ctx.getSource().sendSystemMessage(Component.literal("§c[VoxelBridge] Animation export -> OFF"));
                    return 1;
                }))
        );

        root.then(Commands.literal("fillCave")
                .executes(ctx -> {
                    ctx.getSource().sendSystemMessage(Component.literal("§b[VoxelBridge] Fill cave is currently " + (ExportRuntimeConfig.isFillCaveEnabled() ? "§aon" : "§coff")));
                    ctx.getSource().sendSystemMessage(Component.literal("§7   Usage: /voxelbridge fillCave <on|off>"));
                    ctx.getSource().sendSystemMessage(Component.literal("§7   on : Treat dark cave_air (skylight=0) as solid for culling"));
                    ctx.getSource().sendSystemMessage(Component.literal("§7   off: Normal culling behavior"));
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    ExportRuntimeConfig.setFillCaveEnabled(true);
                    ctx.getSource().sendSystemMessage(Component.literal("§a[VoxelBridge] Fill cave -> ON (dark caves will be culled)"));
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    ExportRuntimeConfig.setFillCaveEnabled(false);
                    ctx.getSource().sendSystemMessage(Component.literal("§c[VoxelBridge] Fill cave -> OFF"));
                    return 1;
                }))
        );

        var collapseDoubleSided = Commands.literal("collapseDoubleSided")
                .executes(ctx -> {
                    ctx.getSource().sendSystemMessage(Component.literal("§b[VoxelBridge] Collapse double-sided is currently " + (ExportRuntimeConfig.isExportDoubleSidedEnabled() ? "§aon" : "§coff")));
                    ctx.getSource().sendSystemMessage(Component.literal("§7   Usage: /voxelbridge collapseDoubleSided <on|off>"));
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    ExportRuntimeConfig.setExportDoubleSidedEnabled(true);
                    ctx.getSource().sendSystemMessage(Component.literal("§a[VoxelBridge] Collapse double-sided -> ON"));
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    ExportRuntimeConfig.setExportDoubleSidedEnabled(false);
                    ctx.getSource().sendSystemMessage(Component.literal("§c[VoxelBridge] Collapse double-sided -> OFF"));
                    return 1;
                }));
        root.then(collapseDoubleSided);

        root.then(Commands.literal("nonsolidCulling")
                .executes(ctx -> {
                    ctx.getSource().sendSystemMessage(Component.literal("\u00a7b[VoxelBridge] Nonsolid culling is currently " + (ExportRuntimeConfig.isNonsolidCullingEnabled() ? "\u00a7aon" : "\u00a7coff")));
                    ctx.getSource().sendSystemMessage(Component.literal("\u00a77   Usage: /voxelbridge nonsolidCulling <on|off>"));
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    ExportRuntimeConfig.setNonsolidCullingEnabled(true);
                    ctx.getSource().sendSystemMessage(Component.literal("\u00a7a[VoxelBridge] Nonsolid culling -> ON"));
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    ExportRuntimeConfig.setNonsolidCullingEnabled(false);
                    ctx.getSource().sendSystemMessage(Component.literal("\u00a7c[VoxelBridge] Nonsolid culling -> OFF (will inset instead)"));
                    return 1;
                })));

        root.then(Commands.literal("atlasSize")
                .executes(ctx -> {
                    ExportRuntimeConfig.AtlasSize current = ExportRuntimeConfig.getAtlasSize();
                    ctx.getSource().sendSystemMessage(Component.literal("§b[VoxelBridge] Current atlas size: §f" + current.getDescription()));
                    ctx.getSource().sendSystemMessage(Component.literal("§7   Available sizes:"));
                    for (ExportRuntimeConfig.AtlasSize size : ExportRuntimeConfig.AtlasSize.values()) {
                        String marker = size == current ? "§a> " : "§7  ";
                        ctx.getSource().sendSystemMessage(Component.literal(marker + size.getSize() + ": " + size.getDescription()));
                    }
                    ctx.getSource().sendSystemMessage(Component.literal("§7   Usage: /voxelbridge atlasSize <size>"));
                    return 1;
                })
                .then(Commands.literal("128").executes(ctx -> {
                    ExportRuntimeConfig.setAtlasSize(ExportRuntimeConfig.AtlasSize.SIZE_128);
                    ctx.getSource().sendSystemMessage(Component.literal("§b[VoxelBridge] Atlas size -> 128x128"));
                    return 1;
                }))
                .then(Commands.literal("256").executes(ctx -> {
                    ExportRuntimeConfig.setAtlasSize(ExportRuntimeConfig.AtlasSize.SIZE_256);
                    ctx.getSource().sendSystemMessage(Component.literal("§b[VoxelBridge] Atlas size -> 256x256"));
                    return 1;
                }))
                .then(Commands.literal("512").executes(ctx -> {
                    ExportRuntimeConfig.setAtlasSize(ExportRuntimeConfig.AtlasSize.SIZE_512);
                    ctx.getSource().sendSystemMessage(Component.literal("§b[VoxelBridge] Atlas size -> 512x512"));
                    return 1;
                }))
                .then(Commands.literal("1024").executes(ctx -> {
                    ExportRuntimeConfig.setAtlasSize(ExportRuntimeConfig.AtlasSize.SIZE_1024);
                    ctx.getSource().sendSystemMessage(Component.literal("§b[VoxelBridge] Atlas size -> 1024x1024"));
                    return 1;
                }))
                .then(Commands.literal("2048").executes(ctx -> {
                    ExportRuntimeConfig.setAtlasSize(ExportRuntimeConfig.AtlasSize.SIZE_2048);
                    ctx.getSource().sendSystemMessage(Component.literal("§b[VoxelBridge] Atlas size -> 2048x2048"));
                    return 1;
                }))
                .then(Commands.literal("4096").executes(ctx -> {
                    ExportRuntimeConfig.setAtlasSize(ExportRuntimeConfig.AtlasSize.SIZE_4096);
                    ctx.getSource().sendSystemMessage(Component.literal("§b[VoxelBridge] Atlas size -> 4096x4096"));
                    return 1;
                }))
                .then(Commands.literal("8192").executes(ctx -> {
                    ExportRuntimeConfig.setAtlasSize(ExportRuntimeConfig.AtlasSize.SIZE_8192);
                    ctx.getSource().sendSystemMessage(Component.literal("§b[VoxelBridge] Atlas size -> 8192x8192"));
                    return 1;
                }))
        );

        root.then(Commands.literal("texturePadding")
                .executes(ctx -> {
                    int current = ExportRuntimeConfig.getAtlasPadding();
                    ctx.getSource().sendSystemMessage(Component.literal("§b[VoxelBridge] Current atlas padding: §f" + current + "px"));
                    ctx.getSource().sendSystemMessage(Component.literal("§7   Allowed values: 0, 4, 8, 12, 16"));
                    ctx.getSource().sendSystemMessage(Component.literal("§7   Usage: /voxelbridge texturePadding <pixels>"));
                    return 1;
                })
                .then(Commands.argument("pixels", IntegerArgumentType.integer(0, 64)).executes(ctx -> {
                    int pixels = IntegerArgumentType.getInteger(ctx, "pixels");
                    if (!ExportRuntimeConfig.setAtlasPadding(pixels)) {
                        ctx.getSource().sendSystemMessage(Component.literal("§c[VoxelBridge] Invalid padding. Allowed: 0, 4, 8, 12, 16"));
                        return 0;
                    }
                    ctx.getSource().sendSystemMessage(Component.literal("§b[VoxelBridge] Atlas padding -> " + pixels + "px"));
                    return 1;
                }))
        );

        root.then(Commands.literal("coords")
                .executes(ctx -> {
                    String mode = ExportRuntimeConfig.getCoordinateMode() == CoordinateMode.CENTERED ? "centered" : "world";
                    ctx.getSource().sendSystemMessage(Component.literal("§b[VoxelBridge] Coordinate mode is currently §f" + mode));
                    ctx.getSource().sendSystemMessage(Component.literal("§7   centered: model centered at origin (default)"));
                    ctx.getSource().sendSystemMessage(Component.literal("§7   world: preserve original world coordinates"));
                    return 1;
                })
                .then(Commands.literal("centered").executes(ctx -> {
                    ExportRuntimeConfig.setCoordinateMode(CoordinateMode.CENTERED);
                    ctx.getSource().sendSystemMessage(Component.literal("§b[VoxelBridge] Coordinate mode -> Centered (model at origin)"));
                    return 1;
                }))
                .then(Commands.literal("world").executes(ctx -> {
                    ExportRuntimeConfig.setCoordinateMode(CoordinateMode.WORLD_ORIGIN);
                    ctx.getSource().sendSystemMessage(Component.literal("§b[VoxelBridge] Coordinate mode -> World (preserve coordinates)"));
                    return 1;
                }))
        );

        root.then(Commands.literal("randomModel")
                .executes(ctx -> {
                    ctx.getSource().sendSystemMessage(Component.literal("§b[VoxelBridge] Vanilla random transform is currently " + (ExportRuntimeConfig.isVanillaRandomTransformEnabled() ? "§aon" : "§coff")));
                    ctx.getSource().sendSystemMessage(Component.literal("§7   Usage: /voxelbridge randomModel <on|off>"));
                    ctx.getSource().sendSystemMessage(Component.literal("§7   on : Apply vanilla position-hash random offsets/variants"));
                    ctx.getSource().sendSystemMessage(Component.literal("§7   off: Disable offsets and keep legacy behavior"));
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    ExportRuntimeConfig.setVanillaRandomTransformEnabled(true);
                    ctx.getSource().sendSystemMessage(Component.literal("§a[VoxelBridge] Vanilla random transform -> ON"));
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    ExportRuntimeConfig.setVanillaRandomTransformEnabled(false);
                    ctx.getSource().sendSystemMessage(Component.literal("§c[VoxelBridge] Vanilla random transform -> OFF"));
                    return 1;
                }))
        );

        root.then(Commands.literal("colorMode")
                .executes(ctx -> {
                    ColorMode current = ExportRuntimeConfig.getColorMode();
                    ctx.getSource().sendSystemMessage(Component.literal("§b[VoxelBridge] Current color mode: §f" + current.getDescription()));
                    ctx.getSource().sendSystemMessage(Component.literal("§7   both: COLOR_0 + TEXCOORD_1 colormap (default)"));
                    ctx.getSource().sendSystemMessage(Component.literal("§7   colormap: TEXCOORD_1 + colormap texture"));
                    ctx.getSource().sendSystemMessage(Component.literal("§7   vertexcolor: COLOR_0 vertex attribute"));
                    return 1;
                })
                .then(Commands.literal("both").executes(ctx -> {
                    ExportRuntimeConfig.setColorMode(ColorMode.BOTH);
                    ctx.getSource().sendSystemMessage(Component.literal("§b[VoxelBridge] Color mode -> Both"));
                    return 1;
                }))
                .then(Commands.literal("colormap").executes(ctx -> {
                    ExportRuntimeConfig.setColorMode(ColorMode.COLORMAP);
                    ctx.getSource().sendSystemMessage(Component.literal("§b[VoxelBridge] Color mode -> ColorMap"));
                    return 1;
                }))
                .then(Commands.literal("vertexcolor").executes(ctx -> {
                    ExportRuntimeConfig.setColorMode(ColorMode.VERTEX_COLOR);
                    ctx.getSource().sendSystemMessage(Component.literal("§b[VoxelBridge] Color mode -> Vertex Color"));
                    return 1;
                }))
        );

        root.then(Commands.literal("threads")
                .executes(ctx -> {
                    int threads = ExportRuntimeConfig.getExportThreadCount();
                    int cpuCores = Runtime.getRuntime().availableProcessors();
                    ctx.getSource().sendSystemMessage(Component.literal("§b[VoxelBridge] Export thread count: §f" + threads + "§7 (CPU cores: " + cpuCores + ")"));
                    ctx.getSource().sendSystemMessage(Component.literal("§7   Usage: /voxelbridge threads <count> (1-128)"));
                    return 1;
                })
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 128)).executes(ctx -> {
                    int count = IntegerArgumentType.getInteger(ctx, "count");
                    ExportRuntimeConfig.setExportThreadCount(count);
                    ctx.getSource().sendSystemMessage(Component.literal("§b[VoxelBridge] Export threads -> " + count));
                    return 1;
                }))
        );

        root.then(Commands.literal("decodePBR")
                .executes(ctx -> {
                    ctx.getSource().sendSystemMessage(Component.literal("§b[VoxelBridge] LabPBR decode is currently " + (ExportRuntimeConfig.isPbrDecodeEnabled() ? "§aon" : "§coff")));
                    ctx.getSource().sendSystemMessage(Component.literal("§7   Usage: /voxelbridge decodePBR <on|off>"));
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    ExportRuntimeConfig.setPbrDecodeEnabled(true);
                    ctx.getSource().sendSystemMessage(Component.literal("§a[VoxelBridge] LabPBR decode -> ON"));
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    ExportRuntimeConfig.setPbrDecodeEnabled(false);
                    ctx.getSource().sendSystemMessage(Component.literal("§c[VoxelBridge] LabPBR decode -> OFF"));
                    return 1;
                }))
        );

        root.then(Commands.literal("export").executes(ctx -> {
            var mc = ClientAccessHolder.get().getMinecraft();
            ExportControl.ExportResult result = ExportControl.startExport(mc.level);
            ctx.getSource().sendSystemMessage(Component.literal("§b[VoxelBridge] " + result.message()));
            return result.started() ? 1 : 0;
        }));

        root.then(Commands.literal("config").executes(ctx -> {
            var mc = ClientAccessHolder.get().getMinecraft();
            com.voxelbridge.platform.ConfigScreenBridge.openConfigScreen(mc);
            return 1;
        }));

        root.then(Commands.literal("log")
                .executes(ctx -> {
                    ctx.getSource().sendSystemMessage(Component.literal("§b[VoxelBridge] Logging is currently " + (ExportRuntimeConfig.isLoggingEnabled() ? "§aon" : "§coff")));
                    ctx.getSource().sendSystemMessage(Component.literal("§7   Usage: /voxelbridge log <on|off>"));
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    ExportRuntimeConfig.setLoggingEnabled(true);
                    ctx.getSource().sendSystemMessage(Component.literal("§a[VoxelBridge] Logging -> ON"));
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    ExportRuntimeConfig.setLoggingEnabled(false);
                    ctx.getSource().sendSystemMessage(Component.literal("§c[VoxelBridge] Logging -> OFF"));
                    return 1;
                }))
        );

        // Register the literal once and reuse the returned node for the "vb" shortcut.
        CommandNode<CommandSourceStack> rootNode = dispatcher.register(root);
        dispatcher.register(Commands.literal("vb").redirect(rootNode));
    }
}


