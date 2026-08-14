package com.voxelbridge.export.exporter.blockentity;

import com.voxelbridge.core.ir.IrSink;
import com.voxelbridge.core.util.color.ColorUtil;
import com.voxelbridge.export.ExportContext;
import com.voxelbridge.export.texture.BlockEntityTextureManager;
import com.voxelbridge.export.texture.EntityTextureManager;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.WoodType;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
public final class SignBlockEntityHandler implements BlockEntityHandler {

    private static final int SCALE = 16; // Scale up texture for readable text

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
            return BlockEntityExportResult.NOT_HANDLED;
        }

        WoodType woodType = signBlock.type();
        String woodName = woodType.name();
        
        ResourceLocation baseTextureLoc;
        if (woodName.contains(":")) {
             String[] parts = woodName.split(":");
             baseTextureLoc = new ResourceLocation(parts[0], "textures/entity/signs/" + parts[1] + ".png");
        } else {
             baseTextureLoc = new ResourceLocation("minecraft", "textures/entity/signs/" + woodName + ".png");
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
        for (Component c : text.getMessages(false)) {
            sb.append(c.getString());
        }
        sb.append(text.getColor().getId());
        sb.append(text.hasGlowingText());
    }

    private void generateSignTexture(ExportContext ctx, ResourceLocation baseLoc, SignText front, SignText back, EntityTextureManager.TextureHandle handle) {
        BufferedImage base = BlockEntityTextureManager.getTexture(ctx, baseLoc.toString());
        if (base == null) {
             VoxelBridgeLogger.warn(LogModule.BLOCKENTITY, "Could not load base sign texture: " + baseLoc);
             return; 
        }
        
        int w = base.getWidth() * SCALE;
        int h = base.getHeight() * SCALE;
        BufferedImage baked = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = baked.createGraphics();
        
        // Draw base scaled
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(base, 0, 0, w, h, null);
        
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR); // Better for text
        
        // Standard Sign UVs (projected to scaled image)
        // Front: u=[2, 26], v=[2, 14]
        drawSignFace(g, front, 2 * SCALE, 2 * SCALE, 24 * SCALE, 12 * SCALE);
        
        // Back: u=[28, 52], v=[2, 14]
        drawSignFace(g, back, 28 * SCALE, 2 * SCALE, 24 * SCALE, 12 * SCALE);
        
        g.dispose();
        
        BlockEntityTextureManager.registerGenerated(ctx, handle, baked);
    }

    private void drawSignFace(Graphics2D g, SignText text, int x, int y, int w, int h) {
        Component[] lines = text.getMessages(false);
        boolean hasText = false;
        for (Component c : lines) {
            if (!c.getString().isEmpty()) hasText = true;
        }
        if (!hasText) return;

        // Sign text area logic
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
            int drawX = x + (w - textW) / 2; // Center align
            int drawY = y + (i * lineHeight) + (lineHeight - fm.getDescent() + fm.getAscent()) / 2 - fm.getDescent(); // Approx vertical center
            
            g.drawString(line, drawX, drawY);
        }
    }
    
    private Color getColor(net.minecraft.world.item.DyeColor dye) {
        float[] c = dye.getTextureDiffuseColors();
        return new Color(c[0], c[1], c[2]);
    }

    private static class MapBasedTextureOverride implements TextureOverrideMap {
        private final Map<ResourceLocation, EntityTextureManager.TextureHandle> overrides = new HashMap<>();

        public void put(ResourceLocation key, EntityTextureManager.TextureHandle value) {
            overrides.put(key, value);
        }

        @Override
        public EntityTextureManager.TextureHandle resolve(ResourceLocation spriteName) {
            return overrides.get(spriteName);
        }

        @Override
        public boolean skipQuad(ResourceLocation spriteName, float[] localU, float[] localV) {
            return false;
        }
    }
}

