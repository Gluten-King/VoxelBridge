package com.voxelbridge.platform.texture;

import com.voxelbridge.platform.client.ClientAccessHolder;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Attempts to read runtime textures from TextureManager when no resource exists.
 */
public final class DynamicTextureReader {

    private DynamicTextureReader() {}

    public static BufferedImage tryRead(Identifier location) {
        if (location == null) {
            return null;
        }
        BufferedImage img = tryReadInternal(location);
        if (img != null) {
            return img;
        }
        Identifier alt = toDynamicKey(location);
        if (alt != null && !alt.equals(location)) {
            return tryReadInternal(alt);
        }
        return null;
    }

    private static BufferedImage tryReadInternal(Identifier location) {
        try {
            AbstractTexture texture = ClientAccessHolder.get().getTextureManager().getTexture(location);
            if (texture == null) {
                return null;
            }
            BufferedImage fromDynamic = readDynamicTexture(texture);
            if (fromDynamic != null) {
                return fromDynamic;
            }
            BufferedImage fromNative = readNativeImageTexture(texture);
            if (fromNative != null) {
                return fromNative;
            }
            BufferedImage fromHttp = readHttpTexture(texture);
            if (fromHttp != null) {
                return fromHttp;
            }
        } catch (Throwable t) {
            VoxelBridgeLogger.warn(LogModule.TEXTURE_RESOLVE,
                String.format("[DynamicTextureReader][WARN] Failed to read %s: %s", location, t.getMessage()));
        }
        return null;
    }

    private static BufferedImage readDynamicTexture(AbstractTexture texture) {
        if (texture instanceof NativeImageBackedTexture dynamic) {
            NativeImage pixels = dynamic.getImage();
            if (pixels != null) {
                return TextureLoader.fromNativeImage(pixels);
            }
        }
        return null;
    }

    private static BufferedImage readHttpTexture(AbstractTexture texture) {
        if (!isInstance(texture, "net.minecraft.client.texture.HttpTexture")) {
            return null;
        }
        File file = findFileField(texture);
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            return ImageIO.read(file);
        } catch (Exception e) {
            VoxelBridgeLogger.warn(LogModule.TEXTURE_RESOLVE,
                String.format("[DynamicTextureReader][WARN] Failed to read HttpTexture file %s: %s", file, e.getMessage()));
            return null;
        }
    }

    private static BufferedImage readNativeImageTexture(AbstractTexture texture) {
        NativeImage nativeImg = findNativeImage(texture);
        if (nativeImg == null) {
            nativeImg = invokeNativeImageMethod(texture);
        }
        if (nativeImg == null) {
            return null;
        }
        return TextureLoader.fromNativeImage(nativeImg);
    }

    private static NativeImage findNativeImage(AbstractTexture texture) {
        Class<?> type = texture.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (!NativeImage.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(texture);
                    if (value instanceof NativeImage nativeImg) {
                        return nativeImg;
                    }
                } catch (IllegalAccessException ignored) {
                    return null;
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static NativeImage invokeNativeImageMethod(AbstractTexture texture) {
        Class<?> type = texture.getClass();
        while (type != null && type != Object.class) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getParameterCount() != 0) {
                    continue;
                }
                if (!NativeImage.class.isAssignableFrom(method.getReturnType())) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    Object value = method.invoke(texture);
                    if (value instanceof NativeImage nativeImg) {
                        return nativeImg;
                    }
                } catch (ReflectiveOperationException ignored) {
                    return null;
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static boolean isInstance(AbstractTexture texture, String className) {
        try {
            Class<?> clazz = Class.forName(className);
            return clazz.isInstance(texture);
        } catch (Throwable ignored) {
            return false;
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

    private static Identifier toDynamicKey(Identifier location) {
        String path = location.getPath();
        if (!path.startsWith("textures/") || !path.endsWith(".png")) {
            return null;
        }
        String trimmed = path.substring("textures/".length(), path.length() - 4);
        if (trimmed.isEmpty()) {
            return null;
        }
        return Identifier.of(location.getNamespace(), trimmed);
    }
}
