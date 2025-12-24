package com.voxelbridge.export.texture;

import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.HttpTexture;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;

/**
 * Attempts to read runtime textures from TextureManager when no resource exists.
 */
final class DynamicTextureReader {

    private DynamicTextureReader() {}

    static BufferedImage tryRead(ResourceLocation location) {
        try {
            AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(location, null);
            if (texture == null) {
                return null;
            }
            BufferedImage fromDynamic = readDynamicTexture(texture);
            if (fromDynamic != null) {
                return fromDynamic;
            }
            BufferedImage fromHttp = readHttpTexture(texture);
            if (fromHttp != null) {
                return fromHttp;
            }
        } catch (Throwable t) {
            VoxelBridgeLogger.warn(LogModule.TEXTURE_RESOLVE, String.format("[DynamicTextureReader][WARN] Failed to read %s: %s", location, t.getMessage()));
        }
        return null;
    }

    private static BufferedImage readDynamicTexture(AbstractTexture texture) {
        if (texture instanceof DynamicTexture dynamic) {
            var pixels = dynamic.getPixels();
            if (pixels != null) {
                return TextureLoader.fromNativeImage(pixels);
            }
        }
        return null;
    }

    private static BufferedImage readHttpTexture(AbstractTexture texture) {
        if (!(texture instanceof HttpTexture)) {
            return null;
        }
        File file = findFileField(texture);
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            return ImageIO.read(file);
        } catch (Exception e) {
            VoxelBridgeLogger.warn(LogModule.TEXTURE_RESOLVE, String.format("[DynamicTextureReader][WARN] Failed to read HttpTexture file %s: %s", file, e.getMessage()));
            return null;
        }
    }

    private static File findFileField(AbstractTexture texture) {
        for (String name : new String[] {"file", "cacheFile", "path"}) {
            File f = getFileField(texture, name);
            if (f != null) {
                return f;
            }
        }
        for (Field field : texture.getClass().getDeclaredFields()) {
            if (File.class.isAssignableFrom(field.getType())) {
                File f = getFileField(texture, field.getName());
                if (f != null) {
                    return f;
                }
            }
        }
        return null;
    }

    private static File getFileField(AbstractTexture texture, String name) {
        try {
            Field field = texture.getClass().getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(texture);
            if (value instanceof File) {
                return (File) value;
            }
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
        return null;
    }
}



