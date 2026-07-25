package com.voxelbridge.platform;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.CommandNode;
import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.core.util.color.ColorMode;
import com.voxelbridge.export.CoordinateMode;
import com.voxelbridge.export.ExportControl;
import com.voxelbridge.platform.client.ClientAccessHolder;
import com.voxelbridge.util.client.RayCastUtil;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Fabric-specific client command registration using FabricClientCommandSource.
 */
public final class FabricCommands {

    private FabricCommands() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        var root = ClientCommands.literal("voxelbridge");

        root.then(ClientCommands.literal("pos1")
                .executes(ctx -> {
                    var mc = ClientAccessHolder.get().getMinecraft();
                    BlockPos hit = RayCastUtil.getLookingAt(mc, 20.0);
                    if (hit == null) {
                        if (mc.player == null) {
                            ctx.getSource().sendFeedback(Component.literal("[VoxelBridge] No block targeted."));
                            return 0;
                        }
                        hit = mc.player.blockPosition();
                        ctx.getSource().sendFeedback(Component.literal("[VoxelBridge] No block targeted, use current position: " + hit.toShortString()));
                    }
                    ExportControl.setPos1(hit);
                    ctx.getSource()
                            .sendFeedback(Component
                                    .literal("§b[VoxelBridge] pos1 set to " + ExportControl.getPos1().toShortString()));
                    return 1;
                })
                .then(ClientCommands.argument("coords", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String input = StringArgumentType.getString(ctx, "coords");
                            String[] parts = input.trim().split("\\s+");
                            if (parts.length != 3) {
                                ctx.getSource().sendFeedback(
                                        Component.literal("§c[VoxelBridge] Invalid coordinates. Expected x y z."));
                                return 0;
                            }
                            // resolveRelativePos already handles ~ parsing
                            BlockPos pos = resolveRelativePos(parts[0], parts[1], parts[2]);
                            if (pos == null) {
                                ctx.getSource()
                                        .sendFeedback(Component.literal("§c[VoxelBridge] Invalid coordinates values."));
                                return 0;
                            }
                            ExportControl.setPos1(pos);
                            ctx.getSource().sendFeedback(Component
                                    .literal("§b[VoxelBridge] pos1 set to " + ExportControl.getPos1().toShortString()));
                            return 1;
                        })));

        root.then(ClientCommands.literal("pos2")
                .executes(ctx -> {
                    var mc = ClientAccessHolder.get().getMinecraft();
                    BlockPos hit = RayCastUtil.getLookingAt(mc, 20.0);
                    if (hit == null) {
                        if (mc.player == null) {
                            ctx.getSource().sendFeedback(Component.literal("[VoxelBridge] No block targeted."));
                            return 0;
                        }
                        hit = mc.player.blockPosition();
                        ctx.getSource().sendFeedback(Component.literal("[VoxelBridge] No block targeted, use current position: " + hit.toShortString()));
                    }
                    ExportControl.setPos2(hit);
                    ctx.getSource()
                            .sendFeedback(Component
                                    .literal("§b[VoxelBridge] pos2 set to " + ExportControl.getPos2().toShortString()));
                    return 1;
                })
                .then(ClientCommands.argument("coords", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String input = StringArgumentType.getString(ctx, "coords");
                            String[] parts = input.trim().split("\\s+");
                            if (parts.length != 3) {
                                ctx.getSource().sendFeedback(
                                        Component.literal("§c[VoxelBridge] Invalid coordinates. Expected x y z."));
                                return 0;
                            }
                            BlockPos pos = resolveRelativePos(parts[0], parts[1], parts[2]);
                            if (pos == null) {
                                ctx.getSource()
                                        .sendFeedback(Component.literal("§c[VoxelBridge] Invalid coordinates values."));
                                return 0;
                            }
                            ExportControl.setPos2(pos);
                            ctx.getSource().sendFeedback(Component
                                    .literal("§b[VoxelBridge] pos2 set to " + ExportControl.getPos2().toShortString()));
                            return 1;
                        })));

        root.then(ClientCommands.literal("info").executes(ctx -> {
            ctx.getSource().sendFeedback(Component.literal("§6[VoxelBridge] Selection info:"));
            ctx.getSource().sendFeedback(Component
                    .literal("§b  pos1: §f" + (ExportControl.getPos1() != null ? ExportControl.getPos1() : "unset")));
            ctx.getSource().sendFeedback(Component
                    .literal("§b  pos2: §f" + (ExportControl.getPos2() != null ? ExportControl.getPos2() : "unset")));
            ctx.getSource().sendFeedback(
                    Component.literal("§b  Atlas mode: §f" + ExportRuntimeConfig.getAtlasMode().getDescription()));
            ctx.getSource().sendFeedback(
                    Component.literal("§b  Atlas size: §f" + ExportRuntimeConfig.getAtlasSize().getDescription()));
            ctx.getSource().sendFeedback(
                    Component.literal("§b  Atlas padding: §f" + ExportRuntimeConfig.getAtlasPadding() + "px"));
            ctx.getSource().sendFeedback(Component.literal("§b  Coordinate mode: §f" +
                    (ExportRuntimeConfig.getCoordinateMode() == CoordinateMode.CENTERED ? "centered" : "world")));
            ctx.getSource().sendFeedback(
                    Component.literal("§b  Color mode: §f" + ExportRuntimeConfig.getColorMode().getDescription()));
            ctx.getSource().sendFeedback(Component.literal("§b  Vanilla random transform: " + (ExportRuntimeConfig.isVanillaRandomTransformEnabled() ? "§aon" : "§coff")));
            ctx.getSource().sendFeedback(Component.literal("§b  Animation export: " + (ExportRuntimeConfig.isAnimationEnabled() ? "§aon" : "§coff")));
            ctx.getSource().sendFeedback(Component.literal("§b  Fill cave (dark cave_air): " + (ExportRuntimeConfig.isFillCaveEnabled() ? "§aon" : "§coff")));
            ctx.getSource().sendFeedback(Component.literal("§b  LabPBR decode: " + (ExportRuntimeConfig.isPbrDecodeEnabled() ? "§aon" : "§coff")));
            ctx.getSource().sendFeedback(Component.literal("§b  Logging: " + (ExportRuntimeConfig.isLoggingEnabled() ? "§aon" : "§coff")));
            ctx.getSource().sendFeedback(Component.literal("§b  Collapse double-sided: " + (ExportRuntimeConfig.isExportDoubleSidedEnabled() ? "§aon" : "§coff")));
            ctx.getSource().sendFeedback(Component.literal("\u00a7b  Nonsolid culling: " + (ExportRuntimeConfig.isNonsolidCullingEnabled() ? "\u00a7aon" : "\u00a7coff")));
            ctx.getSource().sendFeedback(
                    Component.literal("§b  Export threads: §f" + ExportRuntimeConfig.getExportThreadCount()));
            return 1;
        }));

        root.then(ClientCommands.literal("config").executes(ctx -> {
            var mc = ClientAccessHolder.get().getMinecraft();
            if (mc == null) {
                return 0;
            }
            FabricConfigScreen.requestOpen(mc.gui.screen());
            return 1;
        }));

        root.then(ClientCommands.literal("log")
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(Component.literal("§b[VoxelBridge] Logging is currently " + (ExportRuntimeConfig.isLoggingEnabled() ? "§aon" : "§coff")));
                    ctx.getSource().sendFeedback(Component.literal("§7   Usage: /voxelbridge log <on|off>"));
                    return 1;
                })
                .then(ClientCommands.literal("on").executes(ctx -> {
                    ExportRuntimeConfig.setLoggingEnabled(true);
                    ctx.getSource().sendFeedback(Component.literal("§a[VoxelBridge] Logging -> ON"));
                    return 1;
                }))
                .then(ClientCommands.literal("off").executes(ctx -> {
                    ExportRuntimeConfig.setLoggingEnabled(false);
                    ctx.getSource().sendFeedback(Component.literal("§c[VoxelBridge] Logging -> OFF"));
                    return 1;
                }))
        );

        root.then(ClientCommands.literal("clear").executes(ctx -> {
            ExportControl.clearSelection();
            ctx.getSource().sendFeedback(Component.literal("§b[VoxelBridge] Selection cleared."));
            return 1;
        }));

        root.then(ClientCommands.literal("texturePacking")
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(Component.literal("§b[VoxelBridge] Current atlas mode: §f"
                            + ExportRuntimeConfig.getAtlasMode().getDescription()));
                    ctx.getSource().sendFeedback(Component.literal("§7   individual: one texture per sprite"));
                    ctx.getSource().sendFeedback(Component.literal("§7   atlas: pack into 8192 UDIM tiles"));
                    return 1;
                })
                .then(ClientCommands.literal("individual").executes(ctx -> {
                    ExportRuntimeConfig.setAtlasMode(ExportRuntimeConfig.AtlasMode.INDIVIDUAL);
                    ctx.getSource()
                            .sendFeedback(Component.literal("§b[VoxelBridge] Atlas mode -> Individual textures"));
                    return 1;
                }))
                .then(ClientCommands.literal("atlas").executes(ctx -> {
                    ExportRuntimeConfig.setAtlasMode(ExportRuntimeConfig.AtlasMode.ATLAS);
                    ctx.getSource().sendFeedback(Component.literal("§b[VoxelBridge] Atlas mode -> Packed atlas"));
                    return 1;
                })));

        root.then(ClientCommands.literal("animation")
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(Component.literal("§b[VoxelBridge] Animation export is currently " + (ExportRuntimeConfig.isAnimationEnabled() ? "§aon" : "§coff")));
                    ctx.getSource().sendFeedback(Component.literal("§7   Usage: /voxelbridge animation <on|off>"));
                    return 1;
                })
                .then(ClientCommands.literal("on").executes(ctx -> {
                    ExportRuntimeConfig.setAnimationEnabled(true);
                    ctx.getSource().sendFeedback(Component.literal("§a[VoxelBridge] Animation export -> ON"));
                    return 1;
                }))
                .then(ClientCommands.literal("off").executes(ctx -> {
                    ExportRuntimeConfig.setAnimationEnabled(false);
                    ctx.getSource().sendFeedback(Component.literal("§c[VoxelBridge] Animation export -> OFF"));
                    return 1;
                })));

        root.then(ClientCommands.literal("fillCave")
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(Component.literal("§b[VoxelBridge] Fill cave is currently " + (ExportRuntimeConfig.isFillCaveEnabled() ? "§aon" : "§coff")));
                    ctx.getSource().sendFeedback(Component.literal("§7   Usage: /voxelbridge fillCave <on|off>"));
                    ctx.getSource().sendFeedback(
                            Component.literal("§7   on : Treat dark cave_air (skylight=0) as solid for culling"));
                    ctx.getSource().sendFeedback(Component.literal("§7   off: Normal culling behavior"));
                    return 1;
                })
                .then(ClientCommands.literal("on").executes(ctx -> {
                    ExportRuntimeConfig.setFillCaveEnabled(true);
                    ctx.getSource()
                            .sendFeedback(Component.literal("§a[VoxelBridge] Fill cave -> ON (dark caves will be culled)"));
                    return 1;
                }))
                .then(ClientCommands.literal("off").executes(ctx -> {
                    ExportRuntimeConfig.setFillCaveEnabled(false);
                    ctx.getSource().sendFeedback(Component.literal("§c[VoxelBridge] Fill cave -> OFF"));
                    return 1;
                })));

        root.then(ClientCommands.literal("collapseDoubleSided")
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(Component.literal("§b[VoxelBridge] Collapse double-sided is currently " + (ExportRuntimeConfig.isExportDoubleSidedEnabled() ? "§aon" : "§coff")));
                    ctx.getSource().sendFeedback(Component.literal("§7   Usage: /voxelbridge collapseDoubleSided <on|off>"));
                    return 1;
                })
                .then(ClientCommands.literal("on").executes(ctx -> {
                    ExportRuntimeConfig.setExportDoubleSidedEnabled(true);
                    ctx.getSource().sendFeedback(Component.literal("§a[VoxelBridge] Collapse double-sided -> ON"));
                    return 1;
                }))
                .then(ClientCommands.literal("off").executes(ctx -> {
                    ExportRuntimeConfig.setExportDoubleSidedEnabled(false);
                    ctx.getSource().sendFeedback(Component.literal("§c[VoxelBridge] Collapse double-sided -> OFF"));
                    return 1;
                })));

        root.then(ClientCommands.literal("atlasSize")
                .executes(ctx -> {
                    ExportRuntimeConfig.AtlasSize current = ExportRuntimeConfig.getAtlasSize();
                    ctx.getSource().sendFeedback(
                            Component.literal("§b[VoxelBridge] Current atlas size: §f" + current.getDescription()));
                    ctx.getSource().sendFeedback(Component.literal("§7   Available sizes:"));
                    for (ExportRuntimeConfig.AtlasSize size : ExportRuntimeConfig.AtlasSize.values()) {
                        String marker = size == current ? "§a> " : "§7  ";
                        ctx.getSource()
                                .sendFeedback(Component.literal(marker + size.getSize() + ": " + size.getDescription()));
                    }
                    ctx.getSource().sendFeedback(Component.literal("§7   Usage: /voxelbridge atlasSize <size>"));
                    return 1;
                })
                .then(ClientCommands.literal("128").executes(ctx -> {
                    ExportRuntimeConfig.setAtlasSize(ExportRuntimeConfig.AtlasSize.SIZE_128);
                    ctx.getSource().sendFeedback(Component.literal("§b[VoxelBridge] Atlas size -> 128x128"));
                    return 1;
                }))
                .then(ClientCommands.literal("256").executes(ctx -> {
                    ExportRuntimeConfig.setAtlasSize(ExportRuntimeConfig.AtlasSize.SIZE_256);
                    ctx.getSource().sendFeedback(Component.literal("§b[VoxelBridge] Atlas size -> 256x256"));
                    return 1;
                }))
                .then(ClientCommands.literal("512").executes(ctx -> {
                    ExportRuntimeConfig.setAtlasSize(ExportRuntimeConfig.AtlasSize.SIZE_512);
                    ctx.getSource().sendFeedback(Component.literal("§b[VoxelBridge] Atlas size -> 512x512"));
                    return 1;
                }))
                .then(ClientCommands.literal("1024").executes(ctx -> {
                    ExportRuntimeConfig.setAtlasSize(ExportRuntimeConfig.AtlasSize.SIZE_1024);
                    ctx.getSource().sendFeedback(Component.literal("§b[VoxelBridge] Atlas size -> 1024x1024"));
                    return 1;
                }))
                .then(ClientCommands.literal("2048").executes(ctx -> {
                    ExportRuntimeConfig.setAtlasSize(ExportRuntimeConfig.AtlasSize.SIZE_2048);
                    ctx.getSource().sendFeedback(Component.literal("§b[VoxelBridge] Atlas size -> 2048x2048"));
                    return 1;
                }))
                .then(ClientCommands.literal("4096").executes(ctx -> {
                    ExportRuntimeConfig.setAtlasSize(ExportRuntimeConfig.AtlasSize.SIZE_4096);
                    ctx.getSource().sendFeedback(Component.literal("§b[VoxelBridge] Atlas size -> 4096x4096"));
                    return 1;
                }))
                .then(ClientCommands.literal("8192").executes(ctx -> {
                    ExportRuntimeConfig.setAtlasSize(ExportRuntimeConfig.AtlasSize.SIZE_8192);
                    ctx.getSource().sendFeedback(Component.literal("§b[VoxelBridge] Atlas size -> 8192x8192"));
                    return 1;
                })));

        root.then(ClientCommands.literal("texturePadding")
                .executes(ctx -> {
                    int current = ExportRuntimeConfig.getAtlasPadding();
                    ctx.getSource()
                            .sendFeedback(Component.literal("§b[VoxelBridge] Current atlas padding: §f" + current + "px"));
                    ctx.getSource().sendFeedback(Component.literal("§7   Allowed values: 0, 4, 8, 12, 16"));
                    ctx.getSource().sendFeedback(Component.literal("§7   Usage: /voxelbridge texturePadding <pixels>"));
                    return 1;
                })
                .then(ClientCommands.argument("pixels", IntegerArgumentType.integer(0, 64))
                        .executes(ctx -> {
                            int pixels = IntegerArgumentType.getInteger(ctx, "pixels");
                            if (!ExportRuntimeConfig.setAtlasPadding(pixels)) {
                                ctx.getSource().sendFeedback(
                                        Component.literal("§c[VoxelBridge] Invalid padding. Allowed: 0, 4, 8, 12, 16"));
                                return 0;
                            }
                            ctx.getSource().sendFeedback(Component.literal("§b[VoxelBridge] Atlas padding -> " + pixels + "px"));
                            return 1;
                        })));

        root.then(ClientCommands.literal("coords")
                .executes(ctx -> {
                    String mode = ExportRuntimeConfig.getCoordinateMode() == CoordinateMode.CENTERED ? "centered" : "world";
                    ctx.getSource().sendFeedback(
                            Component.literal("§b[VoxelBridge] Coordinate mode is currently §f" + mode));
                    ctx.getSource().sendFeedback(Component.literal("§7   centered: model centered at origin (default)"));
                    ctx.getSource().sendFeedback(Component.literal("§7   world: preserve original world coordinates"));
                    return 1;
                })
                .then(ClientCommands.literal("centered").executes(ctx -> {
                    ExportRuntimeConfig.setCoordinateMode(CoordinateMode.CENTERED);
                    ctx.getSource().sendFeedback(Component.literal("§b[VoxelBridge] Coordinate mode -> Centered"));
                    return 1;
                }))
                .then(ClientCommands.literal("world").executes(ctx -> {
                    ExportRuntimeConfig.setCoordinateMode(CoordinateMode.WORLD_ORIGIN);
                    ctx.getSource().sendFeedback(Component.literal("§b[VoxelBridge] Coordinate mode -> World"));
                    return 1;
                })));

        root.then(ClientCommands.literal("randomModel")
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(Component.literal("§b[VoxelBridge] Vanilla random transform is currently " + (ExportRuntimeConfig.isVanillaRandomTransformEnabled() ? "§aon" : "§coff")));
                    ctx.getSource().sendFeedback(Component.literal("§7   Usage: /voxelbridge randomModel <on|off>"));
                    ctx.getSource()
                            .sendFeedback(Component.literal("§7   on : Apply vanilla position-hash random offsets/variants"));
                    ctx.getSource().sendFeedback(Component.literal("§7   off: Disable offsets and keep legacy behavior"));
                    return 1;
                })
                .then(ClientCommands.literal("on").executes(ctx -> {
                    ExportRuntimeConfig.setVanillaRandomTransformEnabled(true);
                    ctx.getSource().sendFeedback(Component.literal("§a[VoxelBridge] Vanilla random transform -> ON"));
                    return 1;
                }))
                .then(ClientCommands.literal("off").executes(ctx -> {
                    ExportRuntimeConfig.setVanillaRandomTransformEnabled(false);
                    ctx.getSource().sendFeedback(Component.literal("§c[VoxelBridge] Vanilla random transform -> OFF"));
                    return 1;
                })));

        root.then(ClientCommands.literal("colorMode")
                .executes(ctx -> {
                    ColorMode current = ExportRuntimeConfig.getColorMode();
                    ctx.getSource().sendFeedback(
                            Component.literal("§b[VoxelBridge] Current color mode: §f" + current.getDescription()));
                    ctx.getSource().sendFeedback(Component.literal("§7   both: COLOR_0 + TEXCOORD_1 colormap (default)"));
                    ctx.getSource().sendFeedback(Component.literal("§7   colormap: TEXCOORD_1 + colormap texture"));
                    ctx.getSource().sendFeedback(Component.literal("§7   vertexcolor: COLOR_0 vertex attribute"));
                    return 1;
                })
                .then(ClientCommands.literal("both").executes(ctx -> {
                    ExportRuntimeConfig.setColorMode(ColorMode.BOTH);
                    ctx.getSource().sendFeedback(Component.literal("§b[VoxelBridge] Color mode -> Both"));
                    return 1;
                }))
                .then(ClientCommands.literal("colormap").executes(ctx -> {
                    ExportRuntimeConfig.setColorMode(ColorMode.COLORMAP);
                    ctx.getSource().sendFeedback(Component.literal("§b[VoxelBridge] Color mode -> ColorMap"));
                    return 1;
                }))
                .then(ClientCommands.literal("vertexcolor").executes(ctx -> {
                    ExportRuntimeConfig.setColorMode(ColorMode.VERTEX_COLOR);
                    ctx.getSource().sendFeedback(Component.literal("§b[VoxelBridge] Color mode -> Vertex Color"));
                    return 1;
                })));

        root.then(ClientCommands.literal("threads")
                .executes(ctx -> {
                    int threads = ExportRuntimeConfig.getExportThreadCount();
                    int cpuCores = Runtime.getRuntime().availableProcessors();
                    ctx.getSource().sendFeedback(Component.literal(
                            "§b[VoxelBridge] Export thread count: §f" + threads + "§7 (CPU cores: " + cpuCores + ")"));
                    ctx.getSource().sendFeedback(Component.literal("§7   Usage: /voxelbridge threads <count> (1-128)"));
                    return 1;
                })
                .then(ClientCommands.argument("count", IntegerArgumentType.integer(1, 128))
                        .executes(ctx -> {
                            int count = IntegerArgumentType.getInteger(ctx, "count");
                            ExportRuntimeConfig.setExportThreadCount(count);
                            ctx.getSource().sendFeedback(Component.literal("§b[VoxelBridge] Export threads -> " + count));
                            return 1;
                        })));

        root.then(ClientCommands.literal("decodePBR")
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(Component.literal("§b[VoxelBridge] LabPBR decode is currently " + (ExportRuntimeConfig.isPbrDecodeEnabled() ? "§aon" : "§coff")));
                    ctx.getSource().sendFeedback(Component.literal("§7   Usage: /voxelbridge decodePBR <on|off>"));
                    return 1;
                })
                .then(ClientCommands.literal("on").executes(ctx -> {
                    ExportRuntimeConfig.setPbrDecodeEnabled(true);
                    ctx.getSource().sendFeedback(Component.literal("§a[VoxelBridge] LabPBR decode -> ON"));
                    return 1;
                }))
                .then(ClientCommands.literal("off").executes(ctx -> {
                    ExportRuntimeConfig.setPbrDecodeEnabled(false);
                    ctx.getSource().sendFeedback(Component.literal("§c[VoxelBridge] LabPBR decode -> OFF"));
                    return 1;
                })));

        root.then(ClientCommands.literal("export").executes(ctx -> {
            var mc = ClientAccessHolder.get().getMinecraft();
            ExportControl.ExportResult result = ExportControl.startExport(mc.level);
            ctx.getSource().sendFeedback(Component.literal("§b[VoxelBridge] " + result.message()));
            return result.started() ? 1 : 0;
        }));

        root.then(ClientCommands.literal("nonsolidCulling")
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(Component.literal("\u00a7b[VoxelBridge] Nonsolid culling is currently " + (ExportRuntimeConfig.isNonsolidCullingEnabled() ? "\u00a7aon" : "\u00a7coff")));
                    ctx.getSource().sendFeedback(Component.literal("\u00a77   Usage: /voxelbridge nonsolidCulling <on|off>"));
                    return 1;
                })
                .then(ClientCommands.literal("on").executes(ctx -> {
                    ExportRuntimeConfig.setNonsolidCullingEnabled(true);
                    ctx.getSource().sendFeedback(Component.literal("\u00a7a[VoxelBridge] Nonsolid culling -> ON"));
                    return 1;
                }))
                .then(ClientCommands.literal("off").executes(ctx -> {
                    ExportRuntimeConfig.setNonsolidCullingEnabled(false);
                    ctx.getSource().sendFeedback(Component.literal("\u00a7c[VoxelBridge] Nonsolid culling -> OFF (will inset instead)"));
                    return 1;
                })));

        // Register the literal and create shortcut
        CommandNode<FabricClientCommandSource> rootNode = dispatcher.register(root);
        dispatcher.register(ClientCommands.literal("vb").redirect(rootNode));
    }

    private static BlockPos resolveRelativePos(String x, String y, String z) {
        var mc = ClientAccessHolder.get().getMinecraft();
        if (mc.player == null) {
            return null;
        }
        double baseX = mc.player.getX();
        double baseY = mc.player.getY();
        double baseZ = mc.player.getZ();
        try {
            int px = floorCoord(parseCoord(x, baseX));
            int py = floorCoord(parseCoord(y, baseY));
            int pz = floorCoord(parseCoord(z, baseZ));
            return new BlockPos(px, py, pz);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double parseCoord(String token, double base) {
        if (token == null) {
            throw new NumberFormatException("empty");
        }
        token = token.trim();
        if (token.isEmpty()) {
            throw new NumberFormatException("empty");
        }
        char first = token.charAt(0);
        if (first == '~' || first == '?') {
            if (token.length() == 1) {
                return base;
            }
            return base + Double.parseDouble(token.substring(1));
        }
        return Double.parseDouble(token);
    }

    private static int floorCoord(double value) {
        return (int) Math.floor(value);
    }
}


