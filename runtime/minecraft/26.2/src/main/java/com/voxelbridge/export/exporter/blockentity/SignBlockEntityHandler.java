package com.voxelbridge.export.exporter.blockentity;

import com.voxelbridge.core.ir.IrSink;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.texture.BlockEntityTextureManager;
import com.voxelbridge.export.texture.EntityTextureManager;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.WoodType;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Bakes sign text into the wood texture used by the sign model.
 *
 * <p>MC 26.2+ standing/wall signs are plain block models ({@code textures/block/<wood>_sign.png})
 * and only the glyphs are submitted via {@code submitText}. Older versions used the entity sheet
 * ({@code textures/entity/signs/<wood>.png}) with front/back UV rectangles. This handler supports
 * both layouts and registers a per-position sprite override so the block mesh samples the baked
 * atlas entry.
 */
public final class SignBlockEntityHandler implements BlockEntityHandler {

    private static final int SCALE = 16;

    // Matches SignBlockEntity / AbstractSignRenderer.
    private static final int MC_TEXT_LINE_HEIGHT = 10;
    private static final int MC_MAX_TEXT_LINE_WIDTH = 90;
    /** Vanilla default glyph cell height in font pixels. */
    private static final int MC_GLYPH_HEIGHT = 8;

    // 26.2 block/<wood>_sign.png — model UVs are in the classic 0..16 space
    // (full texture), NOT raw texels. template_sign_rot_* board faces:
    //   south (front): [0, 1] - [12, 7]
    //   north (back):  [0, 8] - [12, 14]
    // On a 32×32 sheet that is pixels [0,2]-[24,14] and [0,16]-[24,28].
    private static final float BLOCK_FRONT_U0 = 0f;
    private static final float BLOCK_FRONT_V0 = 1f;
    private static final float BLOCK_FRONT_U1 = 12f;
    private static final float BLOCK_FRONT_V1 = 7f;
    private static final float BLOCK_BACK_U0 = 0f;
    private static final float BLOCK_BACK_V0 = 8f;
    private static final float BLOCK_BACK_U1 = 12f;
    private static final float BLOCK_BACK_V1 = 14f;
    private static final float MODEL_UV_MAX = 16f;

    // Legacy entity/signs/<wood>.png sheet (typically 64×32), real texel units.
    private static final int ENTITY_FRONT_U = 2;
    private static final int ENTITY_FRONT_V = 2;
    private static final int ENTITY_BACK_U = 28;
    private static final int ENTITY_BACK_V = 2;
    private static final int ENTITY_FACE_W = 24;
    private static final int ENTITY_FACE_H = 12;

    @Override
    public BlockEntityExportResult export(
        ExportContext ctx,
        Level level,
        BlockState state,
        BlockEntity blockEntity,
        BlockPos pos,
        IrSink sceneSink,
        double offsetX,
        double offsetY,
        double offsetZ,
        BlockEntityRenderBatch renderBatch
    ) {
        if (!(blockEntity instanceof SignBlockEntity sign)) {
            return BlockEntityExportResult.NOT_HANDLED;
        }
        if (!(state.getBlock() instanceof SignBlock signBlock)) {
            System.err.println("[VB-Sign] not SignBlock: " + state.getBlock().getClass().getName());
            return BlockEntityExportResult.NOT_HANDLED;
        }

        WoodType woodType = signBlock.type();
        String woodName = woodType.name();
        String woodPath = woodName.contains(":")
            ? woodName.substring(woodName.indexOf(':') + 1)
            : woodName;
        String namespace = woodName.contains(":")
            ? woodName.substring(0, woodName.indexOf(':'))
            : "minecraft";

        SignText front = sign.getFrontText();
        SignText back = sign.getBackText();
        String front0 = front != null ? front.getMessage(0, false).getString() : "";
        System.err.println("[VB-Sign] enter pos=" + pos.toShortString()
            + " wood=" + woodName + " front0='" + front0 + "'"
            + " hasText=" + (hasAnyText(front) || hasAnyText(back)));
        if (!hasAnyText(front) && !hasAnyText(back)) {
            // Empty sign — keep vanilla blank texture via normal block/BE path.
            return BlockEntityExportResult.NOT_HANDLED;
        }

        String contentHash = computeContentHash(front, back, woodName);
        String generatedSpriteKey = "blockentity:generated/sign_" + contentHash;
        String textureLoc = "voxelbridge:generated/sign_" + contentHash;
        String relativePath = "textures/blockentity/generated/sign_" + contentHash + ".png";

        EntityTextureManager.TextureHandle handle = new EntityTextureManager.TextureHandle(
            generatedSpriteKey,
            generatedSpriteKey,
            relativePath,
            textureLoc
        );

        BakeLayout layout;
        try {
            // Resource reads are safe off-thread; avoid executeBlocking from workers
            // (can stall if the render thread is waiting on the sampler pool).
            if (ctx.getTextureRepository().getRegisteredLocation(generatedSpriteKey) == null) {
                layout = bakeSignTexture(ctx, namespace, woodPath, front, back, handle);
            } else {
                // Already baked; still need keys for per-pos override.
                layout = BakeLayout.blockCached(namespace, woodPath);
            }
        } catch (Throwable t) {
            System.err.println("[VB-Sign] bake threw: " + t);
            t.printStackTrace(System.err);
            VoxelBridgeLogger.warn(LogModule.BLOCKENTITY, "[Sign] bake failed: " + t.getMessage());
            return BlockEntityExportResult.NOT_HANDLED;
        }

        if (layout == null) {
            System.err.println("[VB-Sign] bake returned null layout wood=" + woodName);
            VoxelBridgeLogger.warn(LogModule.BLOCKENTITY,
                "[Sign] no layout for wood=" + woodName + " front0=" + front0);
            return BlockEntityExportResult.NOT_HANDLED;
        }

        // Remap the blank wood sprite to the baked one for this block position only.
        // Terrain atlas keys look like "minecraft:block/oak_sign".
        ctx.putBlockSpriteOverride(pos.asLong(), layout.originalSpriteKey, generatedSpriteKey);
        ctx.putBlockSpriteOverride(pos.asLong(), layout.blockSpriteAlias, generatedSpriteKey);
        ctx.putBlockSpriteOverride(pos.asLong(), "minecraft:block/" + woodPath + "_sign", generatedSpriteKey);
        ctx.putBlockSpriteOverride(pos.asLong(), namespace + ":block/" + woodPath + "_sign", generatedSpriteKey);
        // Material name follows the baked sprite so glTF/DCC tools show a distinct signed mesh.
        ctx.registerSpriteMaterial(generatedSpriteKey, generatedSpriteKey);

        System.err.println("[VB-Sign] baked ok key=" + generatedSpriteKey + " layout=" + layout.kind
            + " override@" + pos.toShortString());
        VoxelBridgeLogger.info(LogModule.BLOCKENTITY,
            "[Sign] Baked text into " + generatedSpriteKey + " (layout=" + layout.kind
                + ") at " + pos.toShortString());

        // Sign board itself is the block model; BER only draws glyphs.
        // Keep block model so the board mesh is exported with our override applied.
        return BlockEntityExportResult.RENDERED_KEEP_BLOCK;
    }

    private static boolean hasAnyText(SignText text) {
        if (text == null) {
            return false;
        }
        for (Component c : text.getMessages(false)) {
            if (c != null && !c.getString().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private String computeContentHash(SignText front, SignText back, String woodName) {
        StringBuilder sb = new StringBuilder(woodName);
        appendSide(sb, front);
        appendSide(sb, back);
        return Integer.toHexString(sb.toString().hashCode());
    }

    private void appendSide(StringBuilder sb, SignText text) {
        if (text == null) {
            sb.append('|');
            return;
        }
        for (Component c : text.getMessages(false)) {
            sb.append(c.getString());
        }
        sb.append(text.getColor().getId());
        sb.append(text.hasGlowingText());
        sb.append('|');
    }

    private BakeLayout bakeSignTexture(
        ExportContext ctx,
        String namespace,
        String woodPath,
        SignText front,
        SignText back,
        EntityTextureManager.TextureHandle handle
    ) {
        // Prefer 26.2+ block texture; fall back to legacy entity sheet.
        String[] blockCandidates = {
            namespace + ":textures/block/" + woodPath + "_sign.png",
            namespace + ":block/" + woodPath + "_sign",
            "minecraft:textures/block/" + woodPath + "_sign.png",
            "minecraft:block/" + woodPath + "_sign"
        };
        BufferedImage base = null;
        String loadedKey = null;

        // The terrain sampler caches block sprites under export sprite keys,
        // not necessarily under their source resource paths. Read the live
        // blocks-atlas sprite first so resource-pack sign textures work too.
        try {
            Identifier spriteId = Identifier.fromNamespaceAndPath(
                namespace, "block/" + woodPath + "_sign");
            TextureAtlasSprite sprite = Minecraft.getInstance()
                .getAtlasManager()
                .getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS)
                .getSprite(spriteId);
            if (sprite != null && sprite.contents() != null
                    && !sprite.contents().name().toString().contains("missingno")) {
                base = ctx.readSprite(sprite);
                loadedKey = spriteId.toString();
            }
        } catch (Throwable ignored) {
        }
        for (String key : blockCandidates) {
            if (base != null) {
                break;
            }
            base = BlockEntityTextureManager.getTexture(ctx, key);
            if (base == null) {
                base = ctx.readTexture(key);
            }
            if (base != null) {
                loadedKey = key;
                break;
            }
        }
        BakeLayout layout;
        if (base != null) {
            System.err.println("[VB-Sign] loaded block tex key=" + loadedKey
                + " size=" + base.getWidth() + "x" + base.getHeight());
            layout = BakeLayout.forBlockSheet(namespace, woodPath, base.getWidth(), base.getHeight());
        } else {
            String[] entityCandidates = {
                namespace + ":textures/entity/signs/" + woodPath + ".png",
                namespace + ":entity/signs/" + woodPath,
                "minecraft:textures/entity/signs/" + woodPath + ".png"
            };
            for (String key : entityCandidates) {
                base = BlockEntityTextureManager.getTexture(ctx, key);
                if (base == null) {
                    base = ctx.readTexture(key);
                }
                if (base != null) {
                    loadedKey = key;
                    break;
                }
            }
            if (base == null) {
                System.err.println("[VB-Sign] could not load any base texture for wood=" + woodPath);
                VoxelBridgeLogger.warn(LogModule.BLOCKENTITY,
                    "Could not load sign base texture (block or entity) for wood=" + woodPath);
                return null;
            }
            System.err.println("[VB-Sign] loaded entity tex key=" + loadedKey
                + " size=" + base.getWidth() + "x" + base.getHeight());
            layout = BakeLayout.forEntitySheet(namespace, woodPath);
        }

        int w = base.getWidth() * SCALE;
        int h = base.getHeight() * SCALE;
        BufferedImage baked = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = baked.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(base, 0, 0, w, h, null);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

            // Pixel rects on the upscaled bake image.
            int fx = Math.round(layout.frontU0 * SCALE);
            int fy = Math.round(layout.frontV0 * SCALE);
            int fw = Math.round((layout.frontU1 - layout.frontU0) * SCALE);
            int fh = Math.round((layout.frontV1 - layout.frontV0) * SCALE);
            int bx = Math.round(layout.backU0 * SCALE);
            int by = Math.round(layout.backV0 * SCALE);
            int bw = Math.round((layout.backU1 - layout.backU0) * SCALE);
            int bh = Math.round((layout.backV1 - layout.backV0) * SCALE);
            System.err.println("[VB-Sign] face px front=[" + fx + "," + fy + " " + fw + "x" + fh
                + "] back=[" + bx + "," + by + " " + bw + "x" + bh + "] bake=" + w + "x" + h);

            drawSignFace(g, front, fx, fy, fw, fh);
            drawSignFace(g, back, bx, by, bw, bh);
        } finally {
            g.dispose();
        }

        BlockEntityTextureManager.registerGenerated(ctx, handle, baked);
        return layout;
    }

    /**
     * Bakes one face using AbstractSignRenderer spacing, fitted into the board UV rect.
     *
     * <p>In-game, glyph quads are separate geometry in front of the wood and may extend
     * slightly past the board; when baking into the wood UV we must keep all ink inside
     * the face. Horizontal scale matches StandingSignRenderer ({@code 96} font px = board
     * width). Vertical placement centers the 4-line block (baselines at
     * {@code i * 10 - 20}) using actual AWT ascent/descent.
     */
    private void drawSignFace(Graphics2D g, SignText text, int faceX, int faceY, int faceW, int faceH) {
        if (text == null || faceW <= 0 || faceH <= 0) {
            return;
        }
        Component[] lines = text.getMessages(false);
        boolean hasText = false;
        for (Component c : lines) {
            if (c != null && !c.getString().isEmpty()) {
                hasText = true;
                break;
            }
        }
        if (!hasText) {
            return;
        }

        // 96 font pixels across the board width (1 block after scale 1/96).
        float pxPerFont = faceW / 96.0f;
        float targetGlyphPx = MC_GLYPH_HEIGHT * pxPerFont;
        Font font = fitFont(g, targetGlyphPx);
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();

        // Shrink uniformly if the 4-line block (with real ascent/descent) is taller than the face.
        float ascent = fm.getAscent();
        float descent = fm.getDescent();
        // Baselines at -20,-10,0,10 → first baseline - ascent .. last baseline + descent
        float mid = 4 * MC_TEXT_LINE_HEIGHT * 0.5f; // 20
        float firstBaseline = 0 * MC_TEXT_LINE_HEIGHT - mid; // -20
        float lastBaseline = 3 * MC_TEXT_LINE_HEIGHT - mid;  // +10
        float blockTop = firstBaseline * pxPerFont - ascent;
        float blockBot = lastBaseline * pxPerFont + descent;
        float blockH = blockBot - blockTop;
        if (blockH > faceH && blockH > 0f) {
            float shrink = faceH / blockH;
            pxPerFont *= shrink;
            targetGlyphPx = MC_GLYPH_HEIGHT * pxPerFont;
            font = fitFont(g, targetGlyphPx);
            g.setFont(font);
            fm = g.getFontMetrics();
            ascent = fm.getAscent();
            descent = fm.getDescent();
            blockTop = firstBaseline * pxPerFont - ascent;
            blockBot = lastBaseline * pxPerFont + descent;
            blockH = blockBot - blockTop;
        }

        // Center the ink block on the face (MC text origin is near board center, but
        // baking cannot afford overflow past the UV rect).
        float blockCenter = (blockTop + blockBot) * 0.5f;
        float centerX = faceX + faceW * 0.5f;
        float centerY = faceY + faceH * 0.5f;
        // Baseline at fontY maps to: faceCenter + fontY*px - blockCenter
        // so the ink block center lands on the face center.
        float yOrigin = centerY - blockCenter;

        g.setColor(new java.awt.Color(darkSignColor(text), true));

        for (int i = 0; i < 4; i++) {
            String line = lines[i] != null ? lines[i].getString() : "";
            if (line.isEmpty()) {
                continue;
            }

            FontMetrics lineFm = fm;
            float widthFontPx = lineFm.stringWidth(line) / pxPerFont;
            if (widthFontPx > MC_MAX_TEXT_LINE_WIDTH && widthFontPx > 0f) {
                float shrink = MC_MAX_TEXT_LINE_WIDTH / widthFontPx;
                Font shrunk = font.deriveFont(font.getSize2D() * shrink);
                g.setFont(shrunk);
                lineFm = g.getFontMetrics();
            }

            float textW = lineFm.stringWidth(line);
            float drawX = centerX - textW * 0.5f;
            float fontY = i * MC_TEXT_LINE_HEIGHT - mid;
            float drawY = yOrigin + fontY * pxPerFont;
            g.drawString(line, drawX, drawY);
            g.setFont(font);
        }
    }

    /** Pick a plain font whose ascent+descent matches {@code targetGlyphPx}. */
    private static Font fitFont(Graphics2D g, float targetGlyphPx) {
        float size = Math.max(1f, targetGlyphPx);
        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(1, Math.round(size)));
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        float body = fm.getAscent() + fm.getDescent();
        if (body > 0.5f) {
            size = size * (targetGlyphPx / body);
            font = font.deriveFont(Math.max(1f, size));
        }
        return font;
    }

    /** Same darkening as AbstractSignRenderer.getDarkColor for non-glowing text. */
    private static int darkSignColor(SignText text) {
        DyeColor dye = text.getColor();
        int color = dye != null ? dye.getTextColor() : 0xFF000000;
        if (text.hasGlowingText()) {
            return 0xFF000000 | (color & 0x00FFFFFF);
        }
        if (dye == DyeColor.BLACK) {
            return 0xFF000000;
        }
        return 0xFF000000 | (ARGB.scaleRGB(color, 0.4f) & 0x00FFFFFF);
    }

    /**
     * Face rects in source-texture pixels (before SCALE upscale).
     * Block-model sheets use classic 0..16 model UVs mapped onto the PNG.
     */
    private record BakeLayout(
        String kind,
        String originalSpriteKey,
        String blockSpriteAlias,
        float frontU0, float frontV0, float frontU1, float frontV1,
        float backU0, float backV0, float backU1, float backV1
    ) {
        static BakeLayout blockCached(String namespace, String woodPath) {
            // Dimensions unused for override-only path; placeholder 32×32.
            return forBlockSheet(namespace, woodPath, 32, 32).withKind("cached");
        }

        static BakeLayout forBlockSheet(String namespace, String woodPath, int texW, int texH) {
            // model UV 0..16 → pixels
            float sx = texW / MODEL_UV_MAX;
            float sy = texH / MODEL_UV_MAX;
            return new BakeLayout(
                "block",
                namespace + ":block/" + woodPath + "_sign",
                namespace + ":block/" + woodPath + "_sign",
                BLOCK_FRONT_U0 * sx, BLOCK_FRONT_V0 * sy, BLOCK_FRONT_U1 * sx, BLOCK_FRONT_V1 * sy,
                BLOCK_BACK_U0 * sx, BLOCK_BACK_V0 * sy, BLOCK_BACK_U1 * sx, BLOCK_BACK_V1 * sy
            );
        }

        static BakeLayout forEntitySheet(String namespace, String woodPath) {
            return new BakeLayout(
                "entity",
                "blockentity:" + namespace + "/entity/signs/" + woodPath,
                namespace + ":entity/signs/" + woodPath,
                ENTITY_FRONT_U, ENTITY_FRONT_V,
                ENTITY_FRONT_U + ENTITY_FACE_W, ENTITY_FRONT_V + ENTITY_FACE_H,
                ENTITY_BACK_U, ENTITY_BACK_V,
                ENTITY_BACK_U + ENTITY_FACE_W, ENTITY_BACK_V + ENTITY_FACE_H
            );
        }

        BakeLayout withKind(String k) {
            return new BakeLayout(k, originalSpriteKey, blockSpriteAlias,
                frontU0, frontV0, frontU1, frontV1, backU0, backV0, backU1, backV1);
        }
    }
}
