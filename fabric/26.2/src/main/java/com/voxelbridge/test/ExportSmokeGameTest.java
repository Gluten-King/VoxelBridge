package com.voxelbridge.test;

import com.voxelbridge.export.ExportControl;
import com.voxelbridge.export.exporter.blockentity.BlockEntityTextureResolver;
import com.voxelbridge.export.exporter.entity.EntityTextureResolver;
import com.voxelbridge.platform.FabricConfigScreen;
import me.shedaniel.clothconfig2.gui.ClothConfigScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * In-world smoke test for the 26.2 port: join SP, place a few blocks,
 * run a small glTF export, open config screen, exercise texture resolvers.
 */
public final class ExportSmokeGameTest implements FabricClientGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("voxelbridge-gametest");

    @Override
    public void runTest(ClientGameTestContext context) {
        LOGGER.info("[VB-GT] starting export smoke gametest");

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientLevel().waitForChunksRender();
            LOGGER.info("[VB-GT] world joined and chunks rendered");

            // Place a compact fixture around the player for export coverage.
            BlockPos origin = context.computeOnClient(client -> client.player.blockPosition());
            BlockPos chestPos = origin.offset(2, 0, 0);
            BlockPos signPos = origin.offset(3, 0, 0);
            BlockPos bedFoot = origin.offset(4, 0, 0);

            singleplayer.getServer().runOnServer(server -> {
                var level = server.overworld();
                // Platform under fixture
                for (int dx = 0; dx <= 6; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        level.setBlock(origin.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 3);
                    }
                }
                level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
                level.setBlock(signPos, Blocks.OAK_SIGN.defaultBlockState(), 3);
                // Bed may be block-model-only in 26.2; placement still exercises block path.
                level.setBlock(bedFoot, Blocks.BED.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState(), 3);
                // Simple solids
                level.setBlock(origin.offset(1, 0, 0), Blocks.OAK_LOG.defaultBlockState(), 3);
                level.setBlock(origin.offset(1, 1, 0), Blocks.GLASS.defaultBlockState(), 3);
                level.setBlock(origin.offset(1, 0, 1), Blocks.WATER.defaultBlockState(), 3);
            });

            // Let client receive block updates.
            context.waitTicks(20);
            singleplayer.getClientLevel().waitForChunksRender();

            // Texture resolver smoke (must not throw on 26.2 without BED/SIGN sheets).
            context.runOnClient(client -> {
                BlockEntity chest = client.level.getBlockEntity(chestPos);
                if (chest instanceof ChestBlockEntity) {
                    var resolved = BlockEntityTextureResolver.INSTANCE.resolve(chest, null);
                    LOGGER.info("[VB-GT] chest texture resolve -> {}", resolved != null ? resolved.texture() : null);
                }
                BlockEntity sign = client.level.getBlockEntity(signPos);
                if (sign instanceof SignBlockEntity) {
                    var resolved = BlockEntityTextureResolver.INSTANCE.resolve(sign, null);
                    LOGGER.info("[VB-GT] sign texture resolve -> {}", resolved != null ? resolved.texture() : null);
                }
                // Entity resolver atlas list should not reference removed sheets.
                EntityTextureResolver.INSTANCE.resolve(client.player, null);
                // Touch remaining sheets used by resolver.
                if (Sheets.CHEST_SHEET == null) {
                    throw new AssertionError("CHEST_SHEET missing");
                }
            });
            LOGGER.info("[VB-GT] texture resolvers OK");

            // Selection + export.
            Path exportRoot = Path.of("export");
            long before = newestExportEpoch(exportRoot);

            AtomicReference<String> startMsg = new AtomicReference<>();
            context.runOnClient(client -> {
                BlockPos p1 = origin.offset(0, -1, -1);
                BlockPos p2 = origin.offset(6, 2, 2);
                ExportControl.setPos1(p1);
                ExportControl.setPos2(p2);
                var result = ExportControl.startExport(client.level);
                startMsg.set(result.message());
                if (!result.started()) {
                    throw new AssertionError("export did not start: " + result.message());
                }
                if (client.player != null) {
                    client.player.sendSystemMessage(Component.literal("[VB-GT] " + result.message()));
                }
            });
            LOGGER.info("[VB-GT] export started: {}", startMsg.get());

            // Wait up to ~3 minutes for a fully-written export artifact.
            // The exporter may create the .gltf path before finishing the write.
            int waited = context.waitFor(client -> {
                Path newest = newestExportFile(exportRoot);
                if (newest == null || !Files.exists(newest) || newest.toFile().lastModified() <= before) {
                    return false;
                }
                try {
                    return Files.size(newest) >= 100;
                } catch (Exception e) {
                    return false;
                }
            }, 20 * 180);

            Path out = newestExportFile(exportRoot);
            if (out == null || !Files.exists(out)) {
                throw new AssertionError("export produced no glTF under export/");
            }
            final long size;
            try {
                size = Files.size(out);
            } catch (Exception e) {
                throw new AssertionError("failed reading export size: " + out, e);
            }
            if (size < 100) {
                throw new AssertionError("export file too small: " + out + " size=" + size);
            }
            // Prefer also seeing companion bin/textures if present, but size alone is enough.
            LOGGER.info("[VB-GT] export OK file={} size={} waitedTicks={}", out, size, waited);

            String base = out.getFileName().toString().replaceAll("\\.gltf$", "").replaceAll("\\.glb$", "");
            Path bin = out.resolveSibling(base + ".bin");
            if (Files.exists(bin)) {
                try {
                    LOGGER.info("[VB-GT] companion bin size={}", Files.size(bin));
                } catch (Exception ignored) {
                }
            }
            Path textures = out.resolveSibling("textures");
            if (Files.isDirectory(textures)) {
                LOGGER.info("[VB-GT] textures dir present");
            }

            // Config screen (Gui.screen/setScreen path).
            context.runOnClient(client -> FabricConfigScreen.requestOpen(client.gui.screen()));
            context.waitTick();
            context.runOnClient(FabricConfigScreen::onClientTick);
            context.waitFor(client -> client.gui.screen() instanceof ClothConfigScreen, 20 * 10);
            String screenName = context.computeOnClient(c -> {
                var screen = c.gui.screen();
                return screen != null ? screen.getClass().getName() : "null";
            });
            LOGGER.info("[VB-GT] config screen opened: " + screenName);
            context.setScreen(() -> null);
            context.waitForScreen(null);
            LOGGER.info("[VB-GT] returned to game from config");

            // Clear selection without error.
            context.runOnClient(client -> ExportControl.clearSelection());
        }

        LOGGER.info("[VB-GT] export smoke gametest PASSED");
    }

    private static long newestExportEpoch(Path exportRoot) {
        Path f = newestExportFile(exportRoot);
        return f == null ? 0L : f.toFile().lastModified();
    }

    private static Path newestExportFile(Path exportRoot) {
        if (!Files.isDirectory(exportRoot)) {
            return null;
        }
        try (Stream<Path> stream = Files.walk(exportRoot)) {
            return stream
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return n.endsWith(".gltf") || n.endsWith(".glb");
                    })
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
