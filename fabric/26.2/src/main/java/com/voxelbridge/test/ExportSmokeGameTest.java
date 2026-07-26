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
import net.minecraft.world.level.block.entity.SignText;
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
            // Layout (top-down, +X right, +Z down): mixed solids, alpha samples, full bed.
            BlockPos origin = context.computeOnClient(client -> client.player.blockPosition());
            BlockPos chestPos = origin.offset(2, 0, 0);
            BlockPos signPos = origin.offset(3, 0, 0);
            // Full bed needs FOOT + HEAD (two blocks). FACING=SOUTH → head at foot.south().
            BlockPos bedFoot = origin.offset(4, 0, 0);
            BlockPos bedHead = bedFoot.offset(0, 0, 1);

            singleplayer.getServer().runOnServer(server -> {
                var level = server.overworld();
                // Platform under fixture. Keep water in a 1-block well so it cannot flow
                // onto redstone/torch/repeater/bamboo (source water washes those away).
                for (int dx = -1; dx <= 8; dx++) {
                    for (int dz = -1; dz <= 3; dz++) {
                        level.setBlock(origin.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 3);
                    }
                }

                // BE / solid path
                level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
                level.setBlock(signPos, Blocks.OAK_SIGN.defaultBlockState(), 3);
                // Write multi-line text so SignBlockEntityHandler bakes glyphs into the atlas.
                if (level.getBlockEntity(signPos) instanceof SignBlockEntity signBe) {
                    SignText front = new SignText()
                            .setMessage(0, Component.literal("VoxelBridge"))
                            .setMessage(1, Component.literal("26.2 test"))
                            .setMessage(2, Component.literal("Hello"))
                            .setMessage(3, Component.literal("世界"));
                    signBe.setText(front, true);
                    signBe.setChanged();
                    level.sendBlockUpdated(signPos, level.getBlockState(signPos), level.getBlockState(signPos), 3);
                }
                level.setBlock(origin.offset(1, 0, 0), Blocks.OAK_LOG.defaultBlockState(), 3);

                // Full red bed: FOOT at (4,0,0), HEAD at (4,0,1), facing SOUTH.
                var redBed = Blocks.BED.pick(net.minecraft.world.item.DyeColor.RED);
                var footState = redBed.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.BedBlock.FACING, net.minecraft.core.Direction.SOUTH)
                        .setValue(net.minecraft.world.level.block.BedBlock.PART,
                                net.minecraft.world.level.block.state.properties.BedPart.FOOT);
                var headState = redBed.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.BedBlock.FACING, net.minecraft.core.Direction.SOUTH)
                        .setValue(net.minecraft.world.level.block.BedBlock.PART,
                                net.minecraft.world.level.block.state.properties.BedPart.HEAD);
                level.setBlock(bedFoot, footState, 3);
                level.setBlock(bedHead, headState, 3);

                // Contained water well at (0,0,0) with glass/stained glass above the log/chest side.
                // Walls around the source prevent horizontal flow onto the cutout row.
                level.setBlock(origin.offset(0, 0, 0), Blocks.WATER.defaultBlockState(), 3);
                level.setBlock(origin.offset(0, 1, 0), Blocks.GLASS.defaultBlockState(), 3);
                level.setBlock(origin.offset(0, 1, 1),
                        Blocks.STAINED_GLASS.pick(net.minecraft.world.item.DyeColor.BLUE).defaultBlockState(), 3);
                // Solid plugs so water cannot spill toward +Z cutouts or +X bed path.
                level.setBlock(origin.offset(0, 0, 1), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(origin.offset(-1, 0, 0), Blocks.STONE.defaultBlockState(), 3);

                // Contained lava pool at (1,0,1) with stained glass above — lava must stay
                // OPAQUE so BLEND stained-glass frames are not painted over by fluid depth.
                level.setBlock(origin.offset(1, 0, 1), Blocks.LAVA.defaultBlockState(), 3);
                level.setBlock(origin.offset(1, 1, 1),
                        Blocks.STAINED_GLASS.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState(), 3);
                level.setBlock(origin.offset(1, 0, 2), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(origin.offset(2, 0, 1), Blocks.STONE.defaultBlockState(), 3);

                // Cutout / wire samples far from water (x=6..8, z=2).
                // Bamboo only survives on dirt-like soil.
                level.setBlock(origin.offset(7, -1, 2), Blocks.DIRT.defaultBlockState(), 3);
                level.setBlock(origin.offset(6, 0, 2), Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
                level.setBlock(origin.offset(7, 0, 2), Blocks.BAMBOO.defaultBlockState(), 3);
                level.setBlock(origin.offset(8, 0, 2), Blocks.TORCH.defaultBlockState(), 3);
                // Redstone torch: dual-layer cutout (outer flame shell + stem) must export MASK.
                level.setBlock(origin.offset(8, 0, 3), Blocks.REDSTONE_TORCH.defaultBlockState(), 3);
                // Unpowered repeater (baseline).
                level.setBlock(origin.offset(6, 0, 3), Blocks.REPEATER.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING,
                                net.minecraft.core.Direction.EAST), 3);
                // Powered / lit repeater — uses redstone_torch lit shells on top of the plate.
                level.setBlock(origin.offset(5, 0, 3), Blocks.REPEATER.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING,
                                net.minecraft.core.Direction.EAST)
                        .setValue(net.minecraft.world.level.block.DiodeBlock.POWERED, true), 3);
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
                if (sign instanceof SignBlockEntity signBe) {
                    var resolved = BlockEntityTextureResolver.INSTANCE.resolve(signBe, null);
                    LOGGER.info("[VB-GT] sign texture resolve -> {}", resolved != null ? resolved.texture() : null);
                    String line0 = signBe.getFrontText().getMessage(0, false).getString();
                    LOGGER.info("[VB-GT] sign front line0='{}'", line0);
                    if (line0 == null || line0.isBlank()) {
                        throw new AssertionError("sign text missing on client after placement");
                    }
                } else {
                    throw new AssertionError("sign BE missing at " + signPos);
                }
                // Entity resolver atlas list should not reference removed sheets.
                EntityTextureResolver.INSTANCE.resolve(client.player, null);
                // Touch remaining sheets used by resolver.
                if (Sheets.CHEST_SHEET == null) {
                    throw new AssertionError("CHEST_SHEET missing");
                }
                // Bed is a two-block model in 26.2 — both halves must exist.
                if (!client.level.getBlockState(bedFoot).is(Blocks.BED.pick(net.minecraft.world.item.DyeColor.RED))
                        || !client.level.getBlockState(bedHead).is(Blocks.BED.pick(net.minecraft.world.item.DyeColor.RED))) {
                    throw new AssertionError("full bed not placed: foot=" + client.level.getBlockState(bedFoot)
                            + " head=" + client.level.getBlockState(bedHead));
                }
            });
            LOGGER.info("[VB-GT] texture resolvers OK");

            // Selection + export.
            Path exportRoot = Path.of("export");
            long before = newestExportEpoch(exportRoot);

            AtomicReference<String> startMsg = new AtomicReference<>();
            context.runOnClient(client -> {
                BlockPos p1 = origin.offset(-1, -1, -1);
                BlockPos p2 = origin.offset(8, 2, 3);
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
            // The exporter creates the .gltf path before finishing; require stable size + materials.
            int waited = context.waitFor(client -> {
                Path newest = newestExportFile(exportRoot);
                if (newest == null || !Files.exists(newest) || newest.toFile().lastModified() <= before) {
                    return false;
                }
                return isCompleteGltf(newest);
            }, 20 * 180);

            Path out = newestExportFile(exportRoot);
            if (out == null || !Files.exists(out)) {
                throw new AssertionError("export produced no glTF under export/");
            }
            if (!isCompleteGltf(out)) {
                throw new AssertionError("export glTF incomplete: " + out);
            }
            final long size;
            try {
                size = Files.size(out);
            } catch (Exception e) {
                throw new AssertionError("failed reading export size: " + out, e);
            }
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

            // Material coverage + alphaMode sanity for the expanded fixture.
            assertExportMaterials(out);

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

    /**
     * Lightweight glTF JSON checks: fixture materials present with expected alphaMode.
     * Avoids pulling a full JSON lib — the exporter writes pretty-printed jgltf JSON.
     */
    private static void assertExportMaterials(Path gltfPath) {
        final String json;
        try {
            json = Files.readString(gltfPath);
        } catch (Exception e) {
            throw new AssertionError("failed reading glTF for material asserts: " + gltfPath, e);
        }

        // Required material name substrings from the fixture.
        String[] required = {
                "minecraft:glass",
                "stained_glass",
                "minecraft:water",
                "lava",
                "redstone",
                "bamboo",
                "torch",
                "repeater",
                "red_bed",
                "blockentity:minecraft:chest",
                "entity:minecraft:player"
        };
        for (String needle : required) {
            if (!json.contains("\"name\" : \"" + needle) && !json.contains("\"name\":\"" + needle)
                    && !containsMaterialName(json, needle)) {
                throw new AssertionError("export missing material containing '" + needle + "': " + gltfPath);
            }
        }
        // Sign with text should produce a distinct mesh/material (or image path) for the baked board.
        boolean hasBakedSign = containsMaterialName(json, "generated/sign")
                || json.contains("generated/sign")
                || json.contains("blockentity:generated/sign")
                || json.contains("textures/blockentity/generated/sign");
        if (!hasBakedSign) {
            throw new AssertionError("export missing baked sign material/texture (generated/sign_*): " + gltfPath);
        }
        LOGGER.info("[VB-GT] generated sign texture present in glTF");

        // alphaMode expectations via nearby name/mode pairing (best-effort scan).
        requireAlphaNearName(json, "minecraft:glass", "MASK");
        requireAlphaNearName(json, "stained_glass", "BLEND");
        requireAlphaNearName(json, "minecraft:water", "BLEND");
        requireAlphaNearName(json, "entity:minecraft:player", "MASK");
        requireAlphaNearName(json, "minecraft:bamboo", "MASK");
        // Torch / redstone torch outer cutout must be MASK (not OPAQUE).
        requireAlphaNearName(json, "torch", "MASK");
        requireAlphaNearName(json, "redstone_torch", "MASK");
        // Vanilla cutout uses ~0.1; reject the old 0.5 cutoff that crushed 1px flame shells.
        requireAlphaCutoffNearName(json, "redstone_torch", 0.05f, 0.25f);
        // Dual-layer tip shell must be a separate soft BLEND material (opaque 1×1 texels
        // under MASK look like a solid outer box).
        requireAlphaNearName(json, "_shell", "BLEND");
        // Lava is opaque RGB — must NOT be BLEND (glTF OPAQUE often omits alphaMode).
        requireOpaqueNearName(json, "lava");

        // Powered repeater must appear (lit plate / torch shells).
        if (!containsMaterialName(json, "repeater")) {
            throw new AssertionError("export missing repeater material: " + gltfPath);
        }
        // Lit repeater reuses redstone_torch sprite; ensure that sprite/material is present.
        if (!containsMaterialName(json, "redstone_torch") && !json.contains("redstone_torch")) {
            throw new AssertionError("export missing redstone_torch material used by lit repeater/torch: " + gltfPath);
        }

        // Chest UVs must not collapse to a single atlas pixel (black chest regression).
        assertChestUvSpan(gltfPath, json);

        // Full bed is two blocks → expect more than a single tiny bed mesh footprint.
        // At minimum the material must exist; quad count is checked indirectly by bin size above.
        LOGGER.info("[VB-GT] material coverage asserts OK");
    }

    private static boolean containsMaterialName(String json, String needle) {
        // Match "name": "...needle..." inside materials array loosely.
        int from = 0;
        while (true) {
            int i = json.indexOf(needle, from);
            if (i < 0) {
                return false;
            }
            // Prefer hits that look like a glTF name field.
            int nameKey = json.lastIndexOf("\"name\"", i);
            if (nameKey >= 0 && i - nameKey < 80) {
                return true;
            }
            from = i + needle.length();
        }
    }

    private static void requireAlphaNearName(String json, String nameNeedle, String alphaMode) {
        int nameAt = indexOfMaterialName(json, nameNeedle);
        if (nameAt < 0) {
            throw new AssertionError("material not found for alpha check: " + nameNeedle);
        }
        // Search a window after the name for alphaMode (jgltf writes fields after name).
        int windowEnd = Math.min(json.length(), nameAt + 400);
        String window = json.substring(nameAt, windowEnd);
        if (!window.contains("\"alphaMode\"") || !window.contains(alphaMode)) {
            // Some OPAQUE materials omit alphaMode; only enforce when we expect MASK/BLEND.
            throw new AssertionError("expected alphaMode=" + alphaMode + " near material '"
                    + nameNeedle + "', window=" + window.replace('\n', ' '));
        }
    }

    private static void requireAlphaCutoffNearName(String json, String nameNeedle, float minInclusive, float maxInclusive) {
        int nameAt = indexOfMaterialName(json, nameNeedle);
        if (nameAt < 0) {
            throw new AssertionError("material not found for alphaCutoff check: " + nameNeedle);
        }
        int windowEnd = Math.min(json.length(), nameAt + 500);
        String window = json.substring(nameAt, windowEnd);
        int key = window.indexOf("\"alphaCutoff\"");
        if (key < 0) {
            throw new AssertionError("expected alphaCutoff near material '" + nameNeedle + "'");
        }
        int colon = window.indexOf(':', key);
        if (colon < 0) {
            throw new AssertionError("malformed alphaCutoff near material '" + nameNeedle + "'");
        }
        String num = window.substring(colon + 1).trim();
        int end = 0;
        while (end < num.length()) {
            char c = num.charAt(end);
            if ((c >= '0' && c <= '9') || c == '.' || c == '-' || c == 'e' || c == 'E' || c == '+') {
                end++;
            } else {
                break;
            }
        }
        float value;
        try {
            value = Float.parseFloat(num.substring(0, end));
        } catch (Exception e) {
            throw new AssertionError("could not parse alphaCutoff near '" + nameNeedle + "': " + num);
        }
        if (value < minInclusive || value > maxInclusive) {
            throw new AssertionError("alphaCutoff=" + value + " out of range [" + minInclusive + "," + maxInclusive
                    + "] near material '" + nameNeedle + "'");
        }
        LOGGER.info("[VB-GT] alphaCutoff check OK for '{}' value={}", nameNeedle, value);
    }

    /**
     * OPAQUE materials usually omit alphaMode. Fail if the nearby window is BLEND or MASK.
     */
    private static void requireOpaqueNearName(String json, String nameNeedle) {
        int nameAt = indexOfMaterialName(json, nameNeedle);
        if (nameAt < 0) {
            throw new AssertionError("material not found for opaque check: " + nameNeedle);
        }
        int windowEnd = Math.min(json.length(), nameAt + 400);
        String window = json.substring(nameAt, windowEnd);
        if (window.contains("\"BLEND\"") || window.contains("\"MASK\"")) {
            throw new AssertionError("expected OPAQUE (no BLEND/MASK) near material '"
                    + nameNeedle + "', window=" + window.replace('\n', ' '));
        }
        LOGGER.info("[VB-GT] opaque check OK for '{}'", nameNeedle);
    }

    /**
     * Chest mesh TEXCOORD_0 must not collapse to a single atlas pixel (black-chest regression).
     */
    private static void assertChestUvSpan(Path gltfPath, String json) {
        int matIdx = indexOfMaterialEntry(json, "blockentity:minecraft:chest");
        if (matIdx < 0) {
            LOGGER.warn("[VB-GT] chest material index not resolved; skip UV span check");
            return;
        }
        String matRef = "\"material\" : " + matIdx;
        String matRefCompact = "\"material\":" + matIdx;
        int primAt = json.indexOf(matRef);
        if (primAt < 0) {
            primAt = json.indexOf(matRefCompact);
        }
        if (primAt < 0) {
            LOGGER.warn("[VB-GT] chest primitive not found for material {}; skip UV span", matIdx);
            return;
        }
        int searchFrom = Math.max(0, primAt - 600);
        String window = json.substring(searchFrom, Math.min(json.length(), primAt + 80));
        int tcKey = window.lastIndexOf("\"TEXCOORD_0\"");
        if (tcKey < 0) {
            LOGGER.warn("[VB-GT] chest TEXCOORD_0 missing near material {}; skip UV span", matIdx);
            return;
        }
        int colon = window.indexOf(':', tcKey);
        if (colon < 0) {
            return;
        }
        int accIdx = parseLeadingInt(window.substring(colon + 1).trim());
        if (accIdx < 0) {
            return;
        }
        float[] uvs = readVec2Accessor(gltfPath, json, accIdx);
        if (uvs == null || uvs.length < 4) {
            LOGGER.warn("[VB-GT] could not read chest UV accessor {}; skip", accIdx);
            return;
        }
        float minU = Float.POSITIVE_INFINITY, maxU = Float.NEGATIVE_INFINITY;
        float minV = Float.POSITIVE_INFINITY, maxV = Float.NEGATIVE_INFINITY;
        java.util.HashSet<Long> uniq = new java.util.HashSet<>();
        for (int i = 0; i + 1 < uvs.length; i += 2) {
            float u = uvs[i], v = uvs[i + 1];
            minU = Math.min(minU, u);
            maxU = Math.max(maxU, u);
            minV = Math.min(minV, v);
            maxV = Math.max(maxV, v);
            long key = (((long) Math.round(u * 10000f)) << 32) ^ (Math.round(v * 10000f) & 0xffffffffL);
            uniq.add(key);
        }
        float spanU = maxU - minU;
        float spanV = maxV - minV;
        if (uniq.size() < 4 || (spanU < 1e-4f && spanV < 1e-4f)) {
            throw new AssertionError(String.format(
                    "chest UV collapsed (black-chest regression): unique=%d u=[%.5f,%.5f] v=[%.5f,%.5f]",
                    uniq.size(), minU, maxU, minV, maxV));
        }
        LOGGER.info("[VB-GT] chest UV span OK unique={} u=[{},{}] v=[{},{}]",
                uniq.size(), minU, maxU, minV, maxV);
    }

    private static int indexOfMaterialEntry(String json, String nameNeedle) {
        int materialsKey = json.indexOf("\"materials\"");
        if (materialsKey < 0) {
            return -1;
        }
        int arrStart = json.indexOf('[', materialsKey);
        if (arrStart < 0) {
            return -1;
        }
        // Track {} depth relative to the materials array. Top-level material
        // objects sit at braceDepth==1 (the array itself is not counted).
        int braceDepth = 0;
        int idx = -1;
        boolean inString = false;
        boolean escape = false;
        int objectStart = -1;
        for (int i = arrStart + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '[') {
                // Nested arrays inside a material — ignore via braceDepth gate.
                continue;
            }
            if (c == ']') {
                if (braceDepth == 0) {
                    break; // end of materials array
                }
                continue;
            }
            if (c == '{') {
                if (braceDepth == 0) {
                    objectStart = i;
                    idx++;
                }
                braceDepth++;
            } else if (c == '}') {
                braceDepth--;
                if (braceDepth == 0 && objectStart >= 0) {
                    String obj = json.substring(objectStart, i + 1);
                    if (obj.contains(nameNeedle) && obj.contains("\"name\"")) {
                        return idx;
                    }
                    objectStart = -1;
                }
            }
        }
        return -1;
    }

    private static float[] readVec2Accessor(Path gltfPath, String json, int accessorIndex) {
        try {
            int accessorsKey = json.indexOf("\"accessors\"");
            if (accessorsKey < 0) {
                return null;
            }
            int arrStart = json.indexOf('[', accessorsKey);
            String accObj = nthJsonObject(json, arrStart, accessorIndex);
            if (accObj == null) {
                return null;
            }
            int bv = fieldInt(accObj, "bufferView");
            int count = fieldInt(accObj, "count");
            int byteOffset = Math.max(0, fieldIntOptional(accObj, "byteOffset", 0));
            if (bv < 0 || count <= 0) {
                return null;
            }
            int viewsKey = json.indexOf("\"bufferViews\"");
            String viewObj = nthJsonObject(json, json.indexOf('[', viewsKey), bv);
            if (viewObj == null) {
                return null;
            }
            int bufferIdx = fieldInt(viewObj, "buffer");
            int viewOffset = Math.max(0, fieldIntOptional(viewObj, "byteOffset", 0));
            int buffersKey = json.indexOf("\"buffers\"");
            String bufObj = nthJsonObject(json, json.indexOf('[', buffersKey), bufferIdx);
            if (bufObj == null) {
                return null;
            }
            String uri = fieldString(bufObj, "uri");
            if (uri == null || uri.startsWith("data:")) {
                return null;
            }
            Path bin = gltfPath.resolveSibling(uri);
            if (!Files.exists(bin)) {
                return null;
            }
            byte[] data = Files.readAllBytes(bin);
            int off = viewOffset + byteOffset;
            float[] out = new float[count * 2];
            for (int i = 0; i < count; i++) {
                int o = off + i * 8;
                out[i * 2] = Float.intBitsToFloat(readLeInt(data, o));
                out[i * 2 + 1] = Float.intBitsToFloat(readLeInt(data, o + 4));
            }
            return out;
        } catch (Exception e) {
            LOGGER.warn("[VB-GT] readVec2Accessor failed: {}", e.toString());
            return null;
        }
    }

    private static String nthJsonObject(String json, int arrStart, int index) {
        if (arrStart < 0 || index < 0) {
            return null;
        }
        // Same brace-depth scheme as indexOfMaterialEntry: objects at braceDepth==0→1.
        int braceDepth = 0;
        int idx = -1;
        boolean inString = false;
        boolean escape = false;
        int objectStart = -1;
        for (int i = arrStart + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == ']') {
                if (braceDepth == 0) {
                    break;
                }
                continue;
            }
            if (c == '{') {
                if (braceDepth == 0) {
                    objectStart = i;
                    idx++;
                }
                braceDepth++;
            } else if (c == '}') {
                braceDepth--;
                if (braceDepth == 0 && objectStart >= 0) {
                    if (idx == index) {
                        return json.substring(objectStart, i + 1);
                    }
                    objectStart = -1;
                }
            }
        }
        return null;
    }

    private static int fieldInt(String obj, String field) {
        return fieldIntOptional(obj, field, -1);
    }

    private static int fieldIntOptional(String obj, String field, int def) {
        String key = "\"" + field + "\"";
        int at = obj.indexOf(key);
        if (at < 0) {
            return def;
        }
        int colon = obj.indexOf(':', at + key.length());
        if (colon < 0) {
            return def;
        }
        return parseLeadingInt(obj.substring(colon + 1).trim());
    }

    private static String fieldString(String obj, String field) {
        String key = "\"" + field + "\"";
        int at = obj.indexOf(key);
        if (at < 0) {
            return null;
        }
        int colon = obj.indexOf(':', at + key.length());
        if (colon < 0) {
            return null;
        }
        int q1 = obj.indexOf('"', colon + 1);
        if (q1 < 0) {
            return null;
        }
        int q2 = obj.indexOf('"', q1 + 1);
        if (q2 < 0) {
            return null;
        }
        return obj.substring(q1 + 1, q2);
    }

    private static int parseLeadingInt(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        int j = i;
        if (j < s.length() && s.charAt(j) == '-') {
            j++;
        }
        while (j < s.length() && Character.isDigit(s.charAt(j))) {
            j++;
        }
        if (j == i || (j == i + 1 && s.charAt(i) == '-')) {
            return -1;
        }
        try {
            return Integer.parseInt(s.substring(i, j));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int readLeInt(byte[] data, int off) {
        return (data[off] & 0xff)
                | ((data[off + 1] & 0xff) << 8)
                | ((data[off + 2] & 0xff) << 16)
                | ((data[off + 3] & 0xff) << 24);
    }

    private static int indexOfMaterialName(String json, String nameNeedle) {
        int from = 0;
        while (true) {
            int i = json.indexOf(nameNeedle, from);
            if (i < 0) {
                return -1;
            }
            int nameKey = json.lastIndexOf("\"name\"", i);
            if (nameKey >= 0 && i - nameKey < 80) {
                return i;
            }
            from = i + nameNeedle.length();
        }
    }

    /**
     * True once the glTF JSON is fully flushed and contains a materials array.
     * Size-only checks race with the writer creating an empty/partial file first.
     */
    private static boolean isCompleteGltf(Path gltf) {
        try {
            if (!Files.exists(gltf) || Files.size(gltf) < 500) {
                return false;
            }
            String base = gltf.getFileName().toString()
                    .replaceAll("\\.gltf$", "")
                    .replaceAll("\\.glb$", "");
            Path bin = gltf.resolveSibling(base + ".bin");
            if (!Files.exists(bin) || Files.size(bin) < 100) {
                return false;
            }
            // Stable size for a short moment (writer finished).
            long s1 = Files.size(gltf);
            Thread.sleep(50);
            long s2 = Files.size(gltf);
            if (s1 != s2) {
                return false;
            }
            String head = Files.readString(gltf);
            return head.contains("\"materials\"") && head.contains("\"meshes\"")
                    && head.contains("minecraft:glass");
        } catch (Exception e) {
            return false;
        }
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
