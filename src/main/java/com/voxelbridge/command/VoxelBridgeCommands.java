package com.voxelbridge.command;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.export.CoordinateMode;
import com.voxelbridge.export.GpuBakeDebugService;
import com.voxelbridge.export.SimpleGpuBakeDebugService;
import com.voxelbridge.export.ExportProgressTracker;
import com.voxelbridge.util.io.IOUtil;
import com.voxelbridge.util.client.RayCastUtil;
import com.voxelbridge.thread.ExportThread;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import com.voxelbridge.voxy.common.config.section.MemorySectionStorage;
import com.voxelbridge.voxy.client.core.model.GpuBakeDebugProbe;
import com.voxelbridge.voxy.client.core.model.ColourDepthTextureData;
import com.voxelbridge.voxy.client.core.model.TextureUtils;
import com.voxelbridge.export.texture.TextureLoader;
import com.voxelbridge.voxy.common.world.other.Mapper;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.tree.CommandNode;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * Client-side /voxelbridge command registration.
 * Handles selection management and export options.
 */
public final class VoxelBridgeCommands {

    private static BlockPos pos1;
    private static BlockPos pos2;
    private static final int BAKE_DEBUG_MIN_ID = 0;
    private static final int BAKE_DEBUG_MAX_ID = 50;
    private static final int BAKE_DEBUG_SIZE = 256;

    private VoxelBridgeCommands() {}

    public static BlockPos getPos1() {
        return pos1;
    }

    public static BlockPos getPos2() {
        return pos2;
    }

    public static void setPos1(BlockPos pos) {
        pos1 = pos;
        ExportProgressTracker.previewSelection(pos1, pos2);
    }

    public static void setPos2(BlockPos pos) {
        pos2 = pos;
        ExportProgressTracker.previewSelection(pos1, pos2);
    }

    public static void clearSelection() {
        pos1 = null;
        pos2 = null;
        ExportProgressTracker.clear();
    }

    public static void register(RegisterClientCommandsEvent event) {
        var root = Commands.literal("voxelbridge");

        root.then(Commands.literal("pos1").executes(ctx -> {
            Minecraft mc = Minecraft.getInstance();
            BlockPos hit = RayCastUtil.getLookingAt(mc, 20.0);
            if (hit == null) {
                ctx.getSource().sendSystemMessage(Component.literal("c[VoxelBridge] No block targeted."));
                return 0;
            }
            pos1 = hit;
            ExportProgressTracker.previewSelection(pos1, pos2);
            ctx.getSource().sendSystemMessage(Component.literal("a[VoxelBridge] pos1 set to " + pos1));
            return 1;
        }));

        root.then(Commands.literal("pos2").executes(ctx -> {
            Minecraft mc = Minecraft.getInstance();
            BlockPos hit = RayCastUtil.getLookingAt(mc, 20.0);
            if (hit == null) {
                ctx.getSource().sendSystemMessage(Component.literal("c[VoxelBridge] No block targeted."));
                return 0;
            }
            pos2 = hit;
            ExportProgressTracker.previewSelection(pos1, pos2);
            ctx.getSource().sendSystemMessage(Component.literal("a[VoxelBridge] pos2 set to " + pos2));
            return 1;
        }));

        root.then(Commands.literal("info").executes(ctx -> {
            ctx.getSource().sendSystemMessage(Component.literal("6[VoxelBridge] Selection info:"));
            ctx.getSource().sendSystemMessage(Component.literal("e  pos1: f" + (pos1 != null ? pos1 : "unset")));
            ctx.getSource().sendSystemMessage(Component.literal("e  pos2: f" + (pos2 != null ? pos2 : "unset")));
            ctx.getSource().sendSystemMessage(Component.literal("e  Atlas mode: f" + ExportRuntimeConfig.getAtlasMode().getDescription()));
            ctx.getSource().sendSystemMessage(Component.literal("e  Atlas size: f" + ExportRuntimeConfig.getAtlasSize().getDescription()));
            ctx.getSource().sendSystemMessage(Component.literal("e  Atlas padding: f" + ExportRuntimeConfig.getAtlasPadding() + "px"));
            ctx.getSource().sendSystemMessage(Component.literal("e  Coordinate mode: f" +
                    (ExportRuntimeConfig.getCoordinateMode() == CoordinateMode.CENTERED ? "centered" : "world")));
            ctx.getSource().sendSystemMessage(Component.literal("e  Color mode: f" + ExportRuntimeConfig.getColorMode().getDescription()));
            ctx.getSource().sendSystemMessage(Component.literal("e  Vanilla random transform: f" +
                    (ExportRuntimeConfig.isVanillaRandomTransformEnabled() ? "on" : "off")));
            ctx.getSource().sendSystemMessage(Component.literal("e  LOD export: f" +
                    (ExportRuntimeConfig.isLodEnabled() ? "on" : "off")));
            ctx.getSource().sendSystemMessage(Component.literal("e  Animation export: f" +
                    (ExportRuntimeConfig.isAnimationEnabled() ? "on" : "off")));
            ctx.getSource().sendSystemMessage(Component.literal("e  Fill cave (dark cave_air): f" +
                    (ExportRuntimeConfig.isFillCaveEnabled() ? "on" : "off")));
            ctx.getSource().sendSystemMessage(Component.literal("e  LabPBR decode: f" +
                    (ExportRuntimeConfig.isPbrDecodeEnabled() ? "on" : "off")));
            ctx.getSource().sendSystemMessage(Component.literal("e  Export threads: f" + ExportRuntimeConfig.getExportThreadCount()));
            return 1;
        }));

        root.then(Commands.literal("clear").executes(ctx -> {
            clearSelection();
            ctx.getSource().sendSystemMessage(Component.literal("e[VoxelBridge] Selection cleared."));
            return 1;
        }));

        root.then(Commands.literal("atlas")
                .executes(ctx -> {
                    ctx.getSource().sendSystemMessage(Component.literal("e[VoxelBridge] Current atlas mode: f" + ExportRuntimeConfig.getAtlasMode().getDescription()));
                    ctx.getSource().sendSystemMessage(Component.literal("7   individual: one texture per sprite"));
                    ctx.getSource().sendSystemMessage(Component.literal("7   atlas: pack into 8192 UDIM tiles"));
                    return 1;
                })
                .then(Commands.literal("individual").executes(ctx -> {
                    ExportRuntimeConfig.setAtlasMode(ExportRuntimeConfig.AtlasMode.INDIVIDUAL);
                    ctx.getSource().sendSystemMessage(Component.literal("a[VoxelBridge] Atlas mode -> Individual textures"));
                    return 1;
                }))
                .then(Commands.literal("atlas").executes(ctx -> {
                    ExportRuntimeConfig.setAtlasMode(ExportRuntimeConfig.AtlasMode.ATLAS);
                    ctx.getSource().sendSystemMessage(Component.literal("a[VoxelBridge] Atlas mode -> Packed atlas (UDIM 8192)"));
                    return 1;
                }))
        );

        root.then(Commands.literal("animation")
                .executes(ctx -> {
                    ctx.getSource().sendSystemMessage(Component.literal("6[VoxelBridge] Animation export is currently f"
                            + (ExportRuntimeConfig.isAnimationEnabled() ? "on" : "off")));
                    ctx.getSource().sendSystemMessage(Component.literal("7   Usage: /voxelbridge animation <on|off>"));
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    ExportRuntimeConfig.setAnimationEnabled(true);
                    ctx.getSource().sendSystemMessage(Component.literal("a[VoxelBridge] Animation export -> ON"));
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    ExportRuntimeConfig.setAnimationEnabled(false);
                    ctx.getSource().sendSystemMessage(Component.literal("a[VoxelBridge] Animation export -> OFF"));
                    return 1;
                }))
        );

        root.then(Commands.literal("fillcave")
                .executes(ctx -> {
                    ctx.getSource().sendSystemMessage(Component.literal("6[VoxelBridge] Fill cave is currently f"
                            + (ExportRuntimeConfig.isFillCaveEnabled() ? "on" : "off")));
                    ctx.getSource().sendSystemMessage(Component.literal("7   Usage: /voxelbridge fillcave <on|off>"));
                    ctx.getSource().sendSystemMessage(Component.literal("7   on : Treat dark cave_air (skylight=0) as solid for culling"));
                    ctx.getSource().sendSystemMessage(Component.literal("7   off: Normal culling behavior"));
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    ExportRuntimeConfig.setFillCaveEnabled(true);
                    ctx.getSource().sendSystemMessage(Component.literal("a[VoxelBridge] Fill cave -> ON (dark caves will be culled)"));
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    ExportRuntimeConfig.setFillCaveEnabled(false);
                    ctx.getSource().sendSystemMessage(Component.literal("a[VoxelBridge] Fill cave -> OFF"));
                    return 1;
                }))
        );

        root.then(Commands.literal("atlassize")
                .executes(ctx -> {
                    ExportRuntimeConfig.AtlasSize current = ExportRuntimeConfig.getAtlasSize();
                    ctx.getSource().sendSystemMessage(Component.literal("6[VoxelBridge] Current atlas size: f" + current.getDescription()));
                    ctx.getSource().sendSystemMessage(Component.literal("7   Available sizes:"));
                    for (ExportRuntimeConfig.AtlasSize size : ExportRuntimeConfig.AtlasSize.values()) {
                        String marker = size == current ? "a> " : "7  ";
                        ctx.getSource().sendSystemMessage(Component.literal(marker + size.getSize() + ": " + size.getDescription()));
                    }
                    ctx.getSource().sendSystemMessage(Component.literal("7   Usage: /voxelbridge atlassize <size>"));
                    return 1;
                })
                .then(Commands.literal("128").executes(ctx -> {
                    ExportRuntimeConfig.setAtlasSize(ExportRuntimeConfig.AtlasSize.SIZE_128);
                    ctx.getSource().sendSystemMessage(Component.literal("a[VoxelBridge] Atlas size -> 128x128"));
                    return 1;
                }))
                .then(Commands.literal("256").executes(ctx -> {
                    ExportRuntimeConfig.setAtlasSize(ExportRuntimeConfig.AtlasSize.SIZE_256);
                    ctx.getSource().sendSystemMessage(Component.literal("a[VoxelBridge] Atlas size -> 256x256"));
                    return 1;
                }))
                .then(Commands.literal("512").executes(ctx -> {
                    ExportRuntimeConfig.setAtlasSize(ExportRuntimeConfig.AtlasSize.SIZE_512);
                    ctx.getSource().sendSystemMessage(Component.literal("a[VoxelBridge] Atlas size -> 512x512"));
                    return 1;
                }))
                .then(Commands.literal("1024").executes(ctx -> {
                    ExportRuntimeConfig.setAtlasSize(ExportRuntimeConfig.AtlasSize.SIZE_1024);
                    ctx.getSource().sendSystemMessage(Component.literal("a[VoxelBridge] Atlas size -> 1024x1024"));
                    return 1;
                }))
                .then(Commands.literal("2048").executes(ctx -> {
                    ExportRuntimeConfig.setAtlasSize(ExportRuntimeConfig.AtlasSize.SIZE_2048);
                    ctx.getSource().sendSystemMessage(Component.literal("a[VoxelBridge] Atlas size -> 2048x2048"));
                    return 1;
                }))
                .then(Commands.literal("4096").executes(ctx -> {
                    ExportRuntimeConfig.setAtlasSize(ExportRuntimeConfig.AtlasSize.SIZE_4096);
                    ctx.getSource().sendSystemMessage(Component.literal("a[VoxelBridge] Atlas size -> 4096x4096"));
                    return 1;
                }))
                .then(Commands.literal("8192").executes(ctx -> {
                    ExportRuntimeConfig.setAtlasSize(ExportRuntimeConfig.AtlasSize.SIZE_8192);
                    ctx.getSource().sendSystemMessage(Component.literal("a[VoxelBridge] Atlas size -> 8192x8192"));
                    return 1;
                }))
        );

        root.then(Commands.literal("atlaspad")
                .executes(ctx -> {
                    int current = ExportRuntimeConfig.getAtlasPadding();
                    ctx.getSource().sendSystemMessage(Component.literal("6[VoxelBridge] Current atlas padding: f" + current + "px"));
                    ctx.getSource().sendSystemMessage(Component.literal("7   Allowed values: 0, 4, 8, 12, 16"));
                    ctx.getSource().sendSystemMessage(Component.literal("7   Usage: /voxelbridge atlaspad <pixels>"));
                    return 1;
                })
                .then(Commands.argument("pixels", IntegerArgumentType.integer(0, 64)).executes(ctx -> {
                    int pixels = IntegerArgumentType.getInteger(ctx, "pixels");
                    if (!ExportRuntimeConfig.setAtlasPadding(pixels)) {
                        ctx.getSource().sendSystemMessage(Component.literal("c[VoxelBridge] Invalid padding. Allowed: 0, 4, 8, 12, 16"));
                        return 0;
                    }
                    ctx.getSource().sendSystemMessage(Component.literal("a[VoxelBridge] Atlas padding -> " + pixels + "px"));
                    return 1;
                }))
        );

        root.then(Commands.literal("coords")
                .executes(ctx -> {
                    String mode = ExportRuntimeConfig.getCoordinateMode() == CoordinateMode.CENTERED ? "centered" : "world";
                    ctx.getSource().sendSystemMessage(Component.literal("6[VoxelBridge] Coordinate mode is currently f" + mode));
                    ctx.getSource().sendSystemMessage(Component.literal("7   centered: model centered at origin (default)"));
                    ctx.getSource().sendSystemMessage(Component.literal("7   world: preserve original world coordinates"));
                    return 1;
                })
                .then(Commands.literal("centered").executes(ctx -> {
                    ExportRuntimeConfig.setCoordinateMode(CoordinateMode.CENTERED);
                    ctx.getSource().sendSystemMessage(Component.literal("a[VoxelBridge] Coordinate mode -> Centered (model at origin)"));
                    return 1;
                }))
                .then(Commands.literal("world").executes(ctx -> {
                    ExportRuntimeConfig.setCoordinateMode(CoordinateMode.WORLD_ORIGIN);
                    ctx.getSource().sendSystemMessage(Component.literal("a[VoxelBridge] Coordinate mode -> World (preserve coordinates)"));
                    return 1;
                }))
        );

        root.then(Commands.literal("poshash")
                .executes(ctx -> {
                    ctx.getSource().sendSystemMessage(Component.literal("6[VoxelBridge] Vanilla random transform is currently f"
                            + (ExportRuntimeConfig.isVanillaRandomTransformEnabled() ? "on" : "off")));
                    ctx.getSource().sendSystemMessage(Component.literal("7   Usage: /voxelbridge poshash <on|off>"));
                    ctx.getSource().sendSystemMessage(Component.literal("7   on : Apply vanilla position-hash random offsets/variants"));
                    ctx.getSource().sendSystemMessage(Component.literal("7   off: Disable offsets and keep legacy behavior"));
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    ExportRuntimeConfig.setVanillaRandomTransformEnabled(true);
                    ctx.getSource().sendSystemMessage(Component.literal("a[VoxelBridge] Vanilla random transform -> ON"));
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    ExportRuntimeConfig.setVanillaRandomTransformEnabled(false);
                    ctx.getSource().sendSystemMessage(Component.literal("a[VoxelBridge] Vanilla random transform -> OFF"));
                    return 1;
                }))
        );

        root.then(Commands.literal("lod")
                .executes(ctx -> {
                    ctx.getSource().sendSystemMessage(Component.literal("6[VoxelBridge] LOD export is currently f"
                            + (ExportRuntimeConfig.isLodEnabled() ? "on" : "off")));
                    ctx.getSource().sendSystemMessage(Component.literal("7   Usage: /voxelbridge lod <on|off>"));
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    ExportRuntimeConfig.setLodEnabled(true);
                    ctx.getSource().sendSystemMessage(Component.literal("a[VoxelBridge] LOD export -> ON (far chunks use lower LOD/white mesh)"));
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    ExportRuntimeConfig.setLodEnabled(false);
                    ctx.getSource().sendSystemMessage(Component.literal("a[VoxelBridge] LOD export -> OFF (full detail only)"));
                    return 1;
                }))
                .then(Commands.literal("greedy")
                    .executes(ctx -> {
                        boolean on = ExportRuntimeConfig.isLodGreedyMeshingEnabled();
                        ctx.getSource().sendSystemMessage(Component.literal("6[VoxelBridge] LOD greedy meshing is currently f" + (on ? "on" : "off")));
                        ctx.getSource().sendSystemMessage(Component.literal("7   Usage: /voxelbridge lod greedy <on|off>"));
                        ctx.getSource().sendSystemMessage(Component.literal("7   开启后：强制贪婪合并 + 纹理走 INDIVIDUAL"));
                        return 1;
                    })
                    .then(Commands.literal("on").executes(ctx -> {
                        ExportRuntimeConfig.setLodGreedyMeshingEnabled(true);
                        ctx.getSource().sendSystemMessage(Component.literal("a[VoxelBridge] LOD greedy meshing -> ON (强制 INDIVIDUAL atlas)"));
                        return 1;
                    }))
                    .then(Commands.literal("off").executes(ctx -> {
                        ExportRuntimeConfig.setLodGreedyMeshingEnabled(false);
                        ctx.getSource().sendSystemMessage(Component.literal("a[VoxelBridge] LOD greedy meshing -> OFF"));
                        return 1;
                    }))
                )
                .then(Commands.literal("radius")
                    .executes(ctx -> {
                        ctx.getSource().sendSystemMessage(Component.literal("6[VoxelBridge] Current LOD fine radius: f" + ExportRuntimeConfig.getLodFineChunkRadius() + " chunks"));
                        ctx.getSource().sendSystemMessage(Component.literal("7   Usage: /voxelbridge lod radius <chunks>"));
                        return 1;
                    })
                    .then(Commands.argument("chunks", IntegerArgumentType.integer(1, 64)).executes(ctx -> {
                        int radius = IntegerArgumentType.getInteger(ctx, "chunks");
                        ExportRuntimeConfig.setLodFineChunkRadius(radius);
                        ctx.getSource().sendSystemMessage(Component.literal("a[VoxelBridge] LOD fine radius -> " + radius + " chunks"));
                        return 1;
                    }))
                )
        );

        root.then(Commands.literal("colormode")
                .executes(ctx -> {
                    ExportRuntimeConfig.ColorMode current = ExportRuntimeConfig.getColorMode();
                    ctx.getSource().sendSystemMessage(Component.literal("6[VoxelBridge] Current color mode: f" + current.getDescription()));
                    ctx.getSource().sendSystemMessage(Component.literal("7   colormap: TEXCOORD_1 + colormap texture (default)"));
                    ctx.getSource().sendSystemMessage(Component.literal("7   vertexcolor: COLOR_0 vertex attribute"));
                    return 1;
                })
                .then(Commands.literal("colormap").executes(ctx -> {
                    ExportRuntimeConfig.setColorMode(ExportRuntimeConfig.ColorMode.COLORMAP);
                    ctx.getSource().sendSystemMessage(Component.literal("a[VoxelBridge] Color mode -> ColorMap"));
                    return 1;
                }))
                .then(Commands.literal("vertexcolor").executes(ctx -> {
                    ExportRuntimeConfig.setColorMode(ExportRuntimeConfig.ColorMode.VERTEX_COLOR);
                    ctx.getSource().sendSystemMessage(Component.literal("a[VoxelBridge] Color mode -> Vertex Color"));
                    return 1;
                }))
        );

        root.then(Commands.literal("threads")
                .executes(ctx -> {
                    int threads = ExportRuntimeConfig.getExportThreadCount();
                    int cpuCores = Runtime.getRuntime().availableProcessors();
                    ctx.getSource().sendSystemMessage(Component.literal("6[VoxelBridge] Export thread count: f" + threads + "7 (CPU cores: " + cpuCores + ")"));
                    ctx.getSource().sendSystemMessage(Component.literal("7   Usage: /voxelbridge threads <count> (1-32)"));
                    return 1;
                })
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 32)).executes(ctx -> {
                    int count = IntegerArgumentType.getInteger(ctx, "count");
                    ExportRuntimeConfig.setExportThreadCount(count);
                    ctx.getSource().sendSystemMessage(Component.literal("a[VoxelBridge] Export threads -> " + count));
                    return 1;
                }))
        );

        root.then(Commands.literal("pbrdecode")
                .executes(ctx -> {
                    ctx.getSource().sendSystemMessage(Component.literal("6[VoxelBridge] LabPBR decode is currently f"
                            + (ExportRuntimeConfig.isPbrDecodeEnabled() ? "on" : "off")));
                    ctx.getSource().sendSystemMessage(Component.literal("7   Usage: /voxelbridge pbrdecode <on|off>"));
                    return 1;
                })
                .then(Commands.literal("on").executes(ctx -> {
                    ExportRuntimeConfig.setPbrDecodeEnabled(true);
                    ctx.getSource().sendSystemMessage(Component.literal("a[VoxelBridge] LabPBR decode -> ON"));
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    ExportRuntimeConfig.setPbrDecodeEnabled(false);
                    ctx.getSource().sendSystemMessage(Component.literal("a[VoxelBridge] LabPBR decode -> OFF"));
                    return 1;
                }))
        );

        root.then(Commands.literal("export").executes(ctx -> {
            if (pos1 == null || pos2 == null) {
                ctx.getSource().sendSystemMessage(Component.literal("c[VoxelBridge] Please set pos1 and pos2 first."));
                return 0;
            }

            Minecraft mc = Minecraft.getInstance();
            Level level = mc.level;
            if (level == null) {
                ctx.getSource().sendSystemMessage(Component.literal("c[VoxelBridge] No world loaded."));
                return 0;
            }

            try {
                Path outDir = IOUtil.ensureExportDir();
                Thread exportThread = new ExportThread(level, pos1, pos2, outDir);
                ctx.getSource().sendSystemMessage(Component.literal("a[VoxelBridge] Starting export (glTF) ..."));
                exportThread.start();
                return 1;
            } catch (Exception e) {
                e.printStackTrace();
                ctx.getSource().sendSystemMessage(Component.literal("c[VoxelBridge] Export failed: " + e.getMessage()));
                return 0;
            }
        }));

        root.then(Commands.literal("bakedebug")
                .executes(ctx -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.level == null) {
                        ctx.getSource().sendSystemMessage(Component.literal("c[VoxelBridge] No world loaded."));
                        return 0;
                    }
                    CommandSourceStack source = ctx.getSource();
                    source.sendSystemMessage(Component.literal("a[VoxelBridge] Bake debug started (ids 0-50)."));
                    Thread worker = new Thread(() -> runBakeDebug(source, BAKE_DEBUG_MIN_ID, BAKE_DEBUG_MAX_ID), "VoxelBridge-BakeDebug");
                    worker.setDaemon(true);
                    worker.start();
                    return 1;
                })
                .then(Commands.argument("id", IntegerArgumentType.integer(0, 100000)).executes(ctx -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.level == null) {
                        ctx.getSource().sendSystemMessage(Component.literal("c[VoxelBridge] No world loaded."));
                        return 0;
                    }
                    int id = IntegerArgumentType.getInteger(ctx, "id");
                    CommandSourceStack source = ctx.getSource();
                    source.sendSystemMessage(Component.literal("a[VoxelBridge] Bake debug started (id " + id + ")."));
                    Thread worker = new Thread(() -> runBakeDebug(source, id, id), "VoxelBridge-BakeDebug");
                    worker.setDaemon(true);
                    worker.start();
                    return 1;
                }).then(Commands.argument("maxId", IntegerArgumentType.integer(0, 100000)).executes(ctx -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.level == null) {
                        ctx.getSource().sendSystemMessage(Component.literal("c[VoxelBridge] No world loaded."));
                        return 0;
                    }
                    int minId = IntegerArgumentType.getInteger(ctx, "id");
                    int maxId = IntegerArgumentType.getInteger(ctx, "maxId");
                    CommandSourceStack source = ctx.getSource();
                    source.sendSystemMessage(Component.literal("a[VoxelBridge] Bake debug started (ids " + minId + "-" + maxId + ")."));
                    Thread worker = new Thread(() -> runBakeDebug(source, minId, maxId), "VoxelBridge-BakeDebug");
                    worker.setDaemon(true);
                    worker.start();
                    return 1;
                }))));

        root.then(Commands.literal("simplebakedebug").executes(ctx -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) {
                ctx.getSource().sendSystemMessage(Component.literal("c[VoxelBridge] No world loaded."));
                return 0;
            }
            CommandSourceStack source = ctx.getSource();
            source.sendSystemMessage(Component.literal("a[VoxelBridge] Simple bake debug started (ids 0-50)."));
            Thread worker = new Thread(() -> runSimpleBakeDebug(source), "VoxelBridge-SimpleBakeDebug");
            worker.setDaemon(true);
            worker.start();
            return 1;
        }));

        root.then(Commands.literal("bakeprobe")
                .executes(ctx -> armBakeProbe(ctx.getSource(), 120, 2))
                .then(Commands.argument("frames", IntegerArgumentType.integer(1, 6000)).executes(ctx -> {
                    int frames = IntegerArgumentType.getInteger(ctx, "frames");
                    return armBakeProbe(ctx.getSource(), frames, 2);
                }).then(Commands.argument("blockId", IntegerArgumentType.integer(0, 1_000_000)).executes(ctx -> {
                    int frames = IntegerArgumentType.getInteger(ctx, "frames");
                    int blockId = IntegerArgumentType.getInteger(ctx, "blockId");
                    return armBakeProbe(ctx.getSource(), frames, blockId);
                })))
        );

        root.then(Commands.literal("fbtest").executes(ctx -> {
            Minecraft mc = Minecraft.getInstance();
            CommandSourceStack source = ctx.getSource();
            source.sendSystemMessage(Component.literal("e[VoxelBridge] Running framebuffer test..."));
            mc.submit(() -> {
                try (var test = new com.voxelbridge.export.MinimalBakeTest(16)) {
                    String fbStatus = test.testFramebufferStatus();
                    source.sendSystemMessage(Component.literal("7  Framebuffer status: " + fbStatus));

                    var clearResult = test.testClearColor();
                    source.sendSystemMessage(Component.literal("7  " + clearResult.summary()));

                    var readResult = test.testReadPixels();
                    source.sendSystemMessage(Component.literal("7  " + readResult.summary()));

                    var quadResult = test.testRenderQuad();
                    source.sendSystemMessage(Component.literal("7  " + quadResult.summary()));

                    var blockResult = test.testRenderBlock();
                    source.sendSystemMessage(Component.literal("7  " + blockResult.summary()));

                    if (clearResult.expectedColorPixels() == clearResult.totalPixels()) {
                        source.sendSystemMessage(Component.literal("a  ClearColor test PASSED"));
                    } else {
                        source.sendSystemMessage(Component.literal("c  ClearColor test FAILED"));
                    }

                    if (readResult.expectedColorPixels() == readResult.totalPixels()) {
                        source.sendSystemMessage(Component.literal("a  ReadPixels test PASSED"));
                    } else {
                        source.sendSystemMessage(Component.literal("c  ReadPixels test FAILED"));
                    }

                    if (quadResult.nonZeroPixels() > 0) {
                        source.sendSystemMessage(Component.literal("a  RenderQuad test PASSED"));
                    } else {
                        source.sendSystemMessage(Component.literal("c  RenderQuad test FAILED (no pixels rendered)"));
                    }

                    if (blockResult.nonZeroPixels() > 0) {
                        source.sendSystemMessage(Component.literal("a  RenderBlock test PASSED"));
                    } else {
                        source.sendSystemMessage(Component.literal("c  RenderBlock test FAILED (no pixels rendered)"));
                    }
                } catch (Exception e) {
                    source.sendSystemMessage(Component.literal("c  Test failed: " + e.getMessage()));
                    e.printStackTrace();
                }
                return null;
            });
            return 1;
        }));

        // Query block state id - useful for finding ids for bakedebug
        root.then(Commands.literal("blockid").executes(ctx -> {
            Minecraft mc = Minecraft.getInstance();
            BlockPos hit = RayCastUtil.getLookingAt(mc, 20.0);
            if (hit == null) {
                ctx.getSource().sendSystemMessage(Component.literal("c[VoxelBridge] No block targeted."));
                return 0;
            }
            BlockState state = mc.level.getBlockState(hit);
            int id = Block.BLOCK_STATE_REGISTRY.getId(state);
            ctx.getSource().sendSystemMessage(Component.literal("a[VoxelBridge] Block at " + hit + ":"));
            ctx.getSource().sendSystemMessage(Component.literal("e  State: f" + state));
            ctx.getSource().sendSystemMessage(Component.literal("e  ID: f" + id));
            return 1;
        }));

        // Register the literal once and reuse the returned node for the "vb" shortcut.
        CommandNode<CommandSourceStack> rootNode = event.getDispatcher().register(root);
        event.getDispatcher().register(Commands.literal("vb").redirect(rootNode));
    }

    private static int armBakeProbe(CommandSourceStack source, int frames, int blockId) {
        BlockState state = Block.BLOCK_STATE_REGISTRY.byId(blockId);
        if (state == null) {
            state = Blocks.GRANITE.defaultBlockState();
            source.sendSystemMessage(Component.literal("e[VoxelBridge] Unknown blockId " + blockId + ", fallback to granite."));
        }
        GpuBakeDebugProbe.arm(state, frames);
        source.sendSystemMessage(Component.literal("a[VoxelBridge] Bake probe armed: id=" + blockId + " frames=" + frames));
        return 1;
    }

    private static void runBakeDebug(CommandSourceStack source, int requestedMinId, int requestedMaxId) {
        Minecraft mc = Minecraft.getInstance();
        Path outDir;
        Path debugDir;
        boolean loggerStarted = false;
        try {
            outDir = IOUtil.ensureExportDir();
            debugDir = outDir.resolve("bakedebug");
            Files.createDirectories(debugDir);
            VoxelBridgeLogger.initialize(outDir);
            loggerStarted = true;
        } catch (Exception e) {
            mc.execute(() -> source.sendSystemMessage(Component.literal("c[VoxelBridge] Bake debug failed: " + e.getMessage())));
            return;
        }

        MemorySectionStorage storage = new MemorySectionStorage();
        Mapper mapper = new Mapper(storage);
        ensureMapperSize(mapper, requestedMaxId + 1);

        int availableMaxId = Math.min(requestedMaxId, mapper.getBlockStateCount() - 1);
        if (availableMaxId < requestedMinId) {
            mc.execute(() -> source.sendSystemMessage(Component.literal("c[VoxelBridge] Bake debug aborted: no mapped block states.")));
            return;
        }

        GpuBakeDebugService debugService = null;
        try {
            debugService = mc.submit(() -> new GpuBakeDebugService(BAKE_DEBUG_SIZE)).join();
            writeBakeDebugOutput(debugDir, mapper, debugService, mc, requestedMinId, availableMaxId);
        } catch (Exception e) {
            mc.execute(() -> source.sendSystemMessage(Component.literal("c[VoxelBridge] Bake debug failed: " + e.getMessage())));
            return;
        } finally {
            if (debugService != null) {
                GpuBakeDebugService svc = debugService;
                mc.submit(() -> {
                    svc.close();
                    return null;
                }).join();
            }
            if (loggerStarted) {
                VoxelBridgeLogger.close();
            }
        }

        mc.execute(() -> source.sendSystemMessage(Component.literal("a[VoxelBridge] Bake debug done: " + debugDir.toAbsolutePath())));
    }

    private static void ensureMapperSize(Mapper mapper, int minSize) {
        int registrySize = Block.BLOCK_STATE_REGISTRY.size();
        for (int i = 0; i < registrySize && mapper.getBlockStateCount() < minSize; i++) {
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(i);
            if (state != null) {
                mapper.getIdForBlockState(state);
            }
        }
    }

    private static void writeBakeDebugOutput(Path debugDir,
                                             Mapper mapper,
                                             GpuBakeDebugService debugService,
                                             Minecraft mc,
                                             int minId,
                                             int maxId) throws Exception {
        Path indexFile = debugDir.resolve("index.txt");
        AtlasDebugOverlay overlay = AtlasDebugOverlay.tryCreate(mc, debugDir);
        try (BufferedWriter writer = Files.newBufferedWriter(indexFile, StandardCharsets.UTF_8)) {
            for (int id = minId; id <= maxId; id++) {
                BlockState state = mapper.getBlockStateFromBlockId(id);
                GpuBakeDebugService.BakeResult result = mc.submit(() -> debugService.bake(state)).join();
                ColourDepthTextureData[] textures = result.textures();
                var uvStats = result.uvStats();
                writer.write(String.format(Locale.ROOT, "id=%d state=%s", id, state));
                writer.newLine();
                if (uvStats != null && uvStats.count() > 0) {
                    writer.write(String.format(Locale.ROOT,
                        "  uv min=[%.6f, %.6f] max=[%.6f, %.6f] count=%d invalid=%s",
                        uvStats.minU(), uvStats.minV(), uvStats.maxU(), uvStats.maxV(),
                        uvStats.count(), uvStats.invalid()));
                    writer.newLine();
                    if (uvStats.debugLog() != null && !uvStats.debugLog().isEmpty()) {
                        writer.write("  [DEBUG LOG]");
                        writer.newLine();
                        for (String line : uvStats.debugLog().split("\n")) {
                            writer.write("    " + line);
                            writer.newLine();
                        }
                    }
                } else {
                    writer.write("  uv empty");
                    writer.newLine();
                }
                for (Direction dir : Direction.values()) {
                    String face = dir.getSerializedName();
                    ColourDepthTextureData tex = textures[dir.get3DDataValue()];
                    DebugFaceOutput out = writeDebugFace(debugDir, id, face, tex, TextureUtils.WRITE_CHECK_ALPHA, true);
                    writer.write(String.format(Locale.ROOT, "  %s base=%s overlay=%s combined=%s meta=%s stencil=%s", face,
                        out.baseName != null ? out.baseName : "-",
                        out.overlayName != null ? out.overlayName : "-",
                        out.combinedName != null ? out.combinedName : "-",
                        out.metaName != null ? out.metaName : "-",
                        out.stencilName != null ? out.stencilName : "-"));
                    writer.newLine();
                }
                if (overlay != null && uvStats != null && uvStats.pointCount() > 0) {
                    overlay.addPoints(id, uvStats.points(), uvStats.pointCount());
                    overlay.addLines(id, uvStats.points(), uvStats.pointCount());
                }
            }
        }
        if (overlay != null) {
            overlay.writeOutputs();
        }
    }

    private static DebugFaceOutput writeDebugFace(Path debugDir,
                                                  int id,
                                                  String face,
                                                  ColourDepthTextureData tex,
                                                  int checkMode,
                                                  boolean forceWrite) throws IOException {
        int w = tex.width();
        int h = tex.height();
        int[] basePixels = new int[w * h];
        int[] overlayPixels = new int[w * h];
        int[] combinedPixels = new int[w * h];
        int[] metaPixels = new int[w * h];
        int[] stencilPixels = new int[w * h];
        int[] colours = tex.colour();
        int[] depths = tex.depth();
        boolean hasBase = false;
        boolean hasOverlay = false;
        boolean hasCombined = false;
        boolean hasMeta = false;
        boolean hasStencil = false;

        for (int y = 0; y < h; y++) {
            int srcRow = y * w;
            int dstRow = (h - 1 - y) * w;
            for (int x = 0; x < w; x++) {
                int srcIdx = srcRow + x;
                if (!isWritten(colours[srcIdx], depths[srcIdx], checkMode)) {
                    continue;
                }
                int argb = toArgb(colours[srcIdx]);
                int dstIdx = dstRow + x;
                combinedPixels[dstIdx] = argb;
                hasCombined = true;
                int depthVal = depths[srcIdx];
                metaPixels[dstIdx] = 0xFF000000;
                hasMeta = true;
                int stencil = depthVal & 0xFF;
                int gray = stencil;
                stencilPixels[dstIdx] = (0xFF << 24) | (gray << 16) | (gray << 8) | gray;
                if (stencil != 0) {
                    hasStencil = true;
                }
                basePixels[dstIdx] = argb;
                hasBase = true;
            }
        }

        String baseName = null;
        if (hasBase || forceWrite) {
            baseName = String.format(Locale.ROOT, "block_%03d_%s.png", id, face);
            BufferedImage baseImage = createImage(w, h, basePixels);
            ImageIO.write(baseImage, "PNG", debugDir.resolve(baseName).toFile());
        }

        String overlayName = null;
        if (hasOverlay) {
            overlayName = String.format(Locale.ROOT, "block_%03d_%s_overlay.png", id, face);
            BufferedImage overlayImage = createImage(w, h, overlayPixels);
            ImageIO.write(overlayImage, "PNG", debugDir.resolve(overlayName).toFile());
        }

        String combinedName = null;
        if (hasCombined || forceWrite) {
            combinedName = String.format(Locale.ROOT, "block_%03d_%s_combined.png", id, face);
            BufferedImage combinedImage = createImage(w, h, combinedPixels);
            ImageIO.write(combinedImage, "PNG", debugDir.resolve(combinedName).toFile());
        }

        String metaName = null;
        if (hasMeta || forceWrite) {
            metaName = String.format(Locale.ROOT, "block_%03d_%s_meta.png", id, face);
            BufferedImage metaImage = createImage(w, h, metaPixels);
            ImageIO.write(metaImage, "PNG", debugDir.resolve(metaName).toFile());
        }

        String stencilName = null;
        if (hasStencil || forceWrite) {
            stencilName = String.format(Locale.ROOT, "block_%03d_%s_stencil.png", id, face);
            BufferedImage stencilImage = createImage(w, h, stencilPixels);
            ImageIO.write(stencilImage, "PNG", debugDir.resolve(stencilName).toFile());
        }

        return new DebugFaceOutput(baseName, overlayName, combinedName, metaName, stencilName);
    }

    private static boolean isWritten(int colour, int depth, int checkMode) {
        if (checkMode == TextureUtils.WRITE_CHECK_STENCIL) {
            return (depth & 0xFF) != 0;
        }
        if (checkMode == TextureUtils.WRITE_CHECK_DEPTH) {
            return (depth >>> 8) != ((1 << 24) - 1);
        }
        return ((colour >>> 24) & 0xFF) > 1;
    }

    private static int toArgb(int abgr) {
        int a = (abgr >>> 24) & 0xFF;
        int b = (abgr >>> 16) & 0xFF;
        int g = (abgr >>> 8) & 0xFF;
        int r = abgr & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static BufferedImage createImage(int w, int h, int[] pixels) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, w, h, pixels, 0, w);
        return img;
    }

    private static final class AtlasDebugOverlay {
        private static final String ATLAS_BASE_NAME = "atlas_blocks.png";
        private static final String ATLAS_POINTS_NAME = "atlas_blocks_uv_points.png";
        private static final String ATLAS_LINES_NAME = "atlas_blocks_uv_lines.png";
        private static final String ATLAS_LEGEND_NAME = "atlas_blocks_uv_points_legend.txt";

        private final BufferedImage base;
        private final BufferedImage overlay;
        private final BufferedImage lineOverlay;
        private final Graphics2D graphics;
        private final Graphics2D lineGraphics;
        private final int width;
        private final int height;
        private final Path debugDir;
        private final StringBuilder legend = new StringBuilder();

        private AtlasDebugOverlay(BufferedImage base, Path debugDir) {
            this.base = base;
            this.debugDir = debugDir;
            this.width = base.getWidth();
            this.height = base.getHeight();
            this.overlay = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            this.lineOverlay = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = this.overlay.createGraphics();
            g.setComposite(AlphaComposite.Src);
            g.drawImage(base, 0, 0, null);
            g.setComposite(AlphaComposite.SrcOver);
            this.graphics = g;
            Graphics2D lg = this.lineOverlay.createGraphics();
            lg.setComposite(AlphaComposite.Src);
            lg.drawImage(base, 0, 0, null);
            lg.setComposite(AlphaComposite.SrcOver);
            lg.setStroke(new BasicStroke(1.0f));
            this.lineGraphics = lg;
        }

        static AtlasDebugOverlay tryCreate(Minecraft mc, Path debugDir) {
            TextureAtlas atlas = mc.getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
            if (atlas == null || atlas.getTextures().isEmpty()) {
                return null;
            }
            Integer widthVal = readIntProperty(atlas, "getWidth", "width");
            Integer heightVal = readIntProperty(atlas, "getHeight", "height");
            int width = widthVal != null ? widthVal : -1;
            int height = heightVal != null ? heightVal : -1;
            if (width <= 0 || height <= 0) {
                int[] bounds = computeAtlasBounds(atlas.getTextures().values());
                width = bounds[0];
                height = bounds[1];
            }
            if (width <= 0 || height <= 0) {
                return null;
            }

            BufferedImage base = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = base.createGraphics();
            g.setComposite(AlphaComposite.Src);
            g.setColor(new Color(0, 0, 0, 0));
            g.fillRect(0, 0, width, height);
            for (TextureAtlasSprite sprite : atlas.getTextures().values()) {
                BufferedImage spriteImg = TextureLoader.fromSprite(sprite);
                if (spriteImg == null) {
                    continue;
                }
                Integer x = readSpriteCoord(sprite, "getX", "x");
                Integer y = readSpriteCoord(sprite, "getY", "y");
                if (x == null || y == null) {
                    continue;
                }
                g.drawImage(spriteImg, x, y, null);
            }
            g.dispose();
            return new AtlasDebugOverlay(base, debugDir);
        }

        void addPoints(int id, float[] points, int pointCount) {
            if (points == null || pointCount <= 0) {
                return;
            }
            Color baseColor = colorForId(id);
            this.graphics.setColor(baseColor);
            int outOfRange = 0;
            int plotted = 0;
            for (int i = 0; i < pointCount; i++) {
                int idx = i * 2;
                if (idx + 1 >= points.length) {
                    break;
                }
                float u = points[idx];
                float v = points[idx + 1];
                if (!Float.isFinite(u) || !Float.isFinite(v)) {
                    continue;
                }
                int x = Math.round(u * (width - 1));
                int y = Math.round(v * (height - 1));
                boolean oob = x < 0 || x >= width || y < 0 || y >= height;
                if (oob) {
                    outOfRange++;
                    x = clamp(x, 0, width - 1);
                    y = clamp(y, 0, height - 1);
                }
                drawCross(this.graphics, x, y);
                plotted++;
            }
            if (plotted > 0) {
                String hex = String.format(Locale.ROOT, "#%02X%02X%02X", baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue());
                legend.append(String.format(Locale.ROOT, "id=%d color=%s points=%d outOfRange=%d%n", id, hex, plotted, outOfRange));
            }
        }

        void addLines(int id, float[] points, int pointCount) {
            if (points == null || pointCount < 4) {
                return;
            }
            Color baseColor = colorForId(id);
            this.lineGraphics.setColor(baseColor);
            int maxPoints = Math.min(pointCount, points.length / 2);
            for (int i = 0; i + 3 < maxPoints; i += 4) {
                int[] xs = new int[4];
                int[] ys = new int[4];
                boolean validQuad = true;
                for (int v = 0; v < 4; v++) {
                    int idx = (i + v) * 2;
                    float u = points[idx];
                    float vv = points[idx + 1];
                    if (!Float.isFinite(u) || !Float.isFinite(vv)) {
                        validQuad = false;
                        break;
                    }
                    int x = Math.round(u * (width - 1));
                    int y = Math.round(vv * (height - 1));
                    xs[v] = clamp(x, 0, width - 1);
                    ys[v] = clamp(y, 0, height - 1);
                }
                if (!validQuad) {
                    continue;
                }
                drawQuadOutline(this.lineGraphics, xs, ys);
            }
        }

        void writeOutputs() throws IOException {
            ImageIO.write(base, "PNG", debugDir.resolve(ATLAS_BASE_NAME).toFile());
            ImageIO.write(overlay, "PNG", debugDir.resolve(ATLAS_POINTS_NAME).toFile());
            ImageIO.write(lineOverlay, "PNG", debugDir.resolve(ATLAS_LINES_NAME).toFile());
            graphics.dispose();
            lineGraphics.dispose();
            if (legend.length() > 0) {
                Files.writeString(debugDir.resolve(ATLAS_LEGEND_NAME), legend.toString(), StandardCharsets.UTF_8);
            }
        }

        private static int[] computeAtlasBounds(Iterable<TextureAtlasSprite> sprites) {
            int maxX = 0;
            int maxY = 0;
            for (TextureAtlasSprite sprite : sprites) {
                Integer x = readSpriteCoord(sprite, "getX", "x");
                Integer y = readSpriteCoord(sprite, "getY", "y");
                if (x == null || y == null) {
                    continue;
                }
                int w = sprite.contents().width();
                int h = sprite.contents().height();
                maxX = Math.max(maxX, x + w);
                maxY = Math.max(maxY, y + h);
            }
            return new int[]{maxX, maxY};
        }

        private static Color colorForId(int id) {
            float hue = (id % 360) / 360.0f;
            Color base = Color.getHSBColor(hue, 0.85f, 1.0f);
            return new Color(base.getRed(), base.getGreen(), base.getBlue(), 200);
        }

        private static void drawCross(Graphics2D g, int x, int y) {
            g.fillRect(x - 1, y, 3, 1);
            g.fillRect(x, y - 1, 1, 3);
        }

        private static void drawQuadOutline(Graphics2D g, int[] xs, int[] ys) {
            g.drawLine(xs[0], ys[0], xs[1], ys[1]);
            g.drawLine(xs[1], ys[1], xs[2], ys[2]);
            g.drawLine(xs[2], ys[2], xs[3], ys[3]);
            g.drawLine(xs[3], ys[3], xs[0], ys[0]);
        }
    }

    private static Integer readSpriteCoord(TextureAtlasSprite sprite, String... names) {
        Integer val = readIntProperty(sprite, names);
        if (val != null) {
            return val;
        }
        Object contents = invokeOptional(sprite, "contents");
        if (contents != null) {
            return readIntProperty(contents, names);
        }
        return null;
    }

    private static Integer readIntProperty(Object obj, String... names) {
        if (obj == null) {
            return null;
        }
        for (String name : names) {
            try {
                java.lang.reflect.Field field = obj.getClass().getField(name);
                Object val = field.get(obj);
                if (val instanceof Number num) {
                    return num.intValue();
                }
            } catch (Exception ignored) {
            }
            try {
                java.lang.reflect.Method method = obj.getClass().getMethod(name);
                Object val = method.invoke(obj);
                if (val instanceof Number num) {
                    return num.intValue();
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static Object invokeOptional(Object target, String methodName) {
        try {
            java.lang.reflect.Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static void runSimpleBakeDebug(CommandSourceStack source) {
        Minecraft mc = Minecraft.getInstance();
        Path outDir;
        Path debugDir;
        try {
            outDir = IOUtil.ensureExportDir();
            debugDir = outDir.resolve("simplebakedebug");
            Files.createDirectories(debugDir);
        } catch (Exception e) {
            mc.execute(() -> source.sendSystemMessage(Component.literal("c[VoxelBridge] Simple bake debug failed: " + e.getMessage())));
            return;
        }

        MemorySectionStorage storage = new MemorySectionStorage();
        Mapper mapper = new Mapper(storage);
        ensureMapperSize(mapper, BAKE_DEBUG_MAX_ID + 1);

        int availableMaxId = Math.min(BAKE_DEBUG_MAX_ID, mapper.getBlockStateCount() - 1);
        if (availableMaxId < BAKE_DEBUG_MIN_ID) {
            mc.execute(() -> source.sendSystemMessage(Component.literal("c[VoxelBridge] Simple bake debug aborted: no mapped block states.")));
            return;
        }

        SimpleGpuBakeDebugService debugService = null;
        try {
            debugService = mc.submit(() -> new SimpleGpuBakeDebugService(BAKE_DEBUG_SIZE)).join();
            writeSimpleBakeDebugOutput(debugDir, mapper, debugService, mc, availableMaxId);
        } catch (Exception e) {
            e.printStackTrace();
            mc.execute(() -> source.sendSystemMessage(Component.literal("c[VoxelBridge] Simple bake debug failed: " + e.getMessage())));
            return;
        } finally {
            if (debugService != null) {
                SimpleGpuBakeDebugService svc = debugService;
                mc.submit(() -> {
                    svc.close();
                    return null;
                }).join();
            }
        }

        mc.execute(() -> source.sendSystemMessage(Component.literal("a[VoxelBridge] Simple bake debug done: " + debugDir.toAbsolutePath())));
    }

    private static void writeSimpleBakeDebugOutput(Path debugDir,
                                                   Mapper mapper,
                                                   SimpleGpuBakeDebugService debugService,
                                                   Minecraft mc,
                                                   int maxId) throws Exception {
        Path indexFile = debugDir.resolve("index.txt");
        try (BufferedWriter writer = Files.newBufferedWriter(indexFile, StandardCharsets.UTF_8)) {
            for (int id = BAKE_DEBUG_MIN_ID; id <= maxId; id++) {
                BlockState state = mapper.getBlockStateFromBlockId(id);
                SimpleGpuBakeDebugService.BakeResult result = mc.submit(() -> debugService.bake(state)).join();
                ColourDepthTextureData[] textures = result.textures();
                writer.write(String.format(Locale.ROOT, "id=%d state=%s", id, state));
                writer.newLine();
                for (Direction dir : Direction.values()) {
                    String face = dir.getSerializedName();
                    ColourDepthTextureData tex = textures[dir.get3DDataValue()];
                    DebugFaceOutput out = writeDebugFace(debugDir, id, face, tex, TextureUtils.WRITE_CHECK_ALPHA, true);
                    writer.write(String.format(Locale.ROOT, "  %s base=%s overlay=%s combined=%s meta=%s stencil=%s", face,
                        out.baseName != null ? out.baseName : "-",
                        out.overlayName != null ? out.overlayName : "-",
                        out.combinedName != null ? out.combinedName : "-",
                        out.metaName != null ? out.metaName : "-",
                        out.stencilName != null ? out.stencilName : "-"));
                    writer.newLine();
                }
            }
        }
    }

    private record DebugFaceOutput(String baseName,
                                   String overlayName,
                                   String combinedName,
                                   String metaName,
                                   String stencilName) {}
}
