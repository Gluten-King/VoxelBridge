package com.voxelbridge.export.exporter.blockentity;

import com.voxelbridge.core.ir.IrSink;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.texture.BlockEntityTextureManager;
import com.voxelbridge.export.texture.EntityTextureManager;
import com.voxelbridge.platform.texture.TextureLoader;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.block.BlockState;
import net.minecraft.block.SignBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.block.WoodType;
import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

public final class SignBlockEntityHandler implements BlockEntityHandler {

    private static final int SCALE = 16;

    @Override
    public BlockEntityExportResult export(
        ExportContext ctx,
        World level,
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
            return BlockEntityExportResult.NOT_HANDLED;
        }

        WoodType woodType = signBlock.getWoodType();
        String woodName = woodType.name();

        Identifier baseTextureLoc;
        if (woodName.contains(":")) {
            String[] parts = woodName.split(":");
            baseTextureLoc = Identifier.of(parts[0], "textures/entity/signs/" + parts[1] + ".png");
        } else {
            baseTextureLoc = Identifier.of("minecraft", "textures/entity/signs/" + woodName + ".png");
        }

        SignText front = sign.getFrontText();
        SignText back = sign.getBackText();

        String contentHash = computeContentHash(front, back, woodName);
        String generatedSpriteKey = "blockentity:generated/sign_" + contentHash;
        String textureLoc = "voxelbridge:generated/sign_" + contentHash;

        EntityTextureManager.TextureHandle handle = new EntityTextureManager.TextureHandle(
            generatedSpriteKey,
            generatedSpriteKey,
            "textures/blockentity/generated/sign_" + contentHash + ".png",
            textureLoc
        );

        if (ctx.getTextureRepository().getRegisteredLocation(generatedSpriteKey) == null) {
            generateSignTexture(ctx, baseTextureLoc, front, back, handle);
        }

        MapBasedTextureOverride overrides = new MapBasedTextureOverride();
        overrides.put(baseTextureLoc, handle);

        BlockEntityRenderer.RenderTask task = BlockEntityRenderer.createTask(
            ctx,
            blockEntity,
            sceneSink,
            pos.getX() + offsetX,
            pos.getY() + offsetY,
            pos.getZ() + offsetZ,
            overrides
        );

        if (task != null) {
            if (renderBatch != null) {
                renderBatch.enqueue(task);
            } else {
                task.run();
            }
            return BlockEntityExportResult.RENDERED_KEEP_BLOCK;
        }

        return BlockEntityExportResult.NOT_HANDLED;
    }

    private String computeContentHash(SignText front, SignText back, String woodName) {
        StringBuilder sb = new StringBuilder(woodName);
        appendSide(sb, front);
        appendSide(sb, back);
        return Integer.toHexString(sb.toString().hashCode());
    }

    private void appendSide(StringBuilder sb, SignText text) {
        for (Text c : text.getMessages(false)) {
            sb.append(c.getString());
        }
        sb.append(text.getColor().getId());
        sb.append(text.isGlowing());
    }

    private void generateSignTexture(ExportContext ctx, Identifier baseLoc, SignText front, SignText back, EntityTextureManager.TextureHandle handle) {
        BufferedImage base = BlockEntityTextureManager.getTexture(ctx, baseLoc.toString());
        if (base == null) {
            VoxelBridgeLogger.warn(LogModule.BLOCKENTITY, "Could not load base sign texture: " + baseLoc);
            return;
        }

        int w = base.getWidth() * SCALE;
        int h = base.getHeight() * SCALE;
        BufferedImage baked = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = baked.createGraphics();

        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(base, 0, 0, w, h, null);

        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        drawSignFace(g, front, 2 * SCALE, 2 * SCALE, 24 * SCALE, 12 * SCALE);
        drawSignFace(g, back, 28 * SCALE, 2 * SCALE, 24 * SCALE, 12 * SCALE);

        g.dispose();

        BlockEntityTextureManager.registerGenerated(ctx, handle, baked);
    }

    private void drawSignFace(Graphics2D g, SignText text, int x, int y, int w, int h) {
        Text[] lines = text.getMessages(false);
        boolean hasText = false;
        for (Text c : lines) {
            if (!c.getString().isEmpty()) hasText = true;
        }
        if (!hasText) return;

        int fontSize = (int) (2.5 * SCALE);
        Font font = new Font("SansSerif", Font.BOLD, fontSize);
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();

        int lineHeight = h / 4;

        Color textColor = getColor(text.getColor());
        g.setColor(textColor);

        for (int i = 0; i < 4; i++) {
            String line = lines[i].getString();
            if (line.isEmpty()) continue;

            int textW = fm.stringWidth(line);
            int drawX = x + (w - textW) / 2;
            int drawY = y + (i * lineHeight) +
                (lineHeight - fm.getDescent() + fm.getAscent()) / 2 - fm.getDescent();

            g.drawString(line, drawX, drawY);
        }
    }

    private Color getColor(DyeColor dye) {
        float[] c = TextureLoader.rgbMul(dye.getSignColor());
        return new Color(c[0], c[1], c[2]);
    }

    private static class MapBasedTextureOverride implements TextureOverrideMap {
        private final Map<Identifier, EntityTextureManager.TextureHandle> overrides = new HashMap<>();

        public void put(Identifier key, EntityTextureManager.TextureHandle value) {
            overrides.put(key, value);
        }

        @Override
        public EntityTextureManager.TextureHandle resolve(Identifier spriteName) {
            return overrides.get(spriteName);
        }

        @Override
        public boolean skipQuad(Identifier spriteName, float[] localU, float[] localV) {
            return false;
        }
    }
}
