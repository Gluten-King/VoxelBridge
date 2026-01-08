package com.voxelbridge.platform.texture;

import net.minecraft.client.texture.NativeImage;
import com.voxelbridge.platform.client.ClientAccessHolder;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.MissingSprite;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Attempts to read runtime textures from TextureManager/GPU only.
 */
final class DynamicTextureReader {

    private static final ConcurrentHashMap<Identifier, BufferedImage> CACHE = new ConcurrentHashMap<>();

    private DynamicTextureReader() {}

    static BufferedImage tryRead(Identifier location, boolean preserveAnimationStrip) {
        try {
            if (location == null) return null;
            if (CACHE.containsKey(location)) {
                BufferedImage cached = CACHE.get(location);
                return preserveAnimationStrip ? cached : TextureLoader.extractFirstFrame(cached);
            }

            TextureManager manager = ClientAccessHolder.get().getTextureManager();
            if (manager == null) {
                return null;
            }

            BufferedImage fromMap = readFromTextureMap(manager, location);
            if (fromMap != null) {
                CACHE.put(location, fromMap);
                return preserveAnimationStrip ? fromMap : TextureLoader.extractFirstFrame(fromMap);
            }

            AbstractTexture texture = manager.getTexture(location);
            if (texture == null || isMissingTexture(manager, texture)) {
                return null;
            }
            BufferedImage fromDynamic = readDynamicTexture(texture);
            if (fromDynamic != null) {
                CACHE.put(location, fromDynamic);
                return preserveAnimationStrip ? fromDynamic : TextureLoader.extractFirstFrame(fromDynamic);
            }
            BufferedImage fromNative = readNativeImageTexture(texture);
            if (fromNative != null) {
                CACHE.put(location, fromNative);
                return preserveAnimationStrip ? fromNative : TextureLoader.extractFirstFrame(fromNative);
            }
            BufferedImage fromGpu = readGpuTexture(texture);
            if (fromGpu != null) {
                CACHE.put(location, fromGpu);
                return preserveAnimationStrip ? fromGpu : TextureLoader.extractFirstFrame(fromGpu);
            }
            BufferedImage fromHttp = readHttpTexture(texture);
            if (fromHttp != null) {
                CACHE.put(location, fromHttp);
                return preserveAnimationStrip ? fromHttp : TextureLoader.extractFirstFrame(fromHttp);
            }

            // 最后根据哈希在纹理表中模糊搜索（玩家皮肤/披风等）。
            BufferedImage fromSearch = searchByHash(manager, location);
            if (fromSearch != null) {
                CACHE.put(location, fromSearch);
                return preserveAnimationStrip ? fromSearch : TextureLoader.extractFirstFrame(fromSearch);
            }
        } catch (Throwable t) {
            VoxelBridgeLogger.warn(LogModule.TEXTURE_RESOLVE, String.format("[DynamicTextureReader][WARN] Failed to read %s: %s", location, t.getMessage()));
        }
        return null;
    }

    private static BufferedImage readDynamicTexture(AbstractTexture texture) {
        if (texture instanceof NativeImageBackedTexture dynamic) {
            var pixels = dynamic.getImage();
            if (pixels != null) {
                return TextureLoader.fromNativeImage(pixels);
            }
        }
        return null;
    }

    private static BufferedImage searchByHash(TextureManager manager, Identifier location) {
        String hash = extractHash(location.getPath());
        if (hash == null) {
            return null;
        }
        Map<Identifier, AbstractTexture> map = getTextureMap(manager);
        if (map == null || map.isEmpty()) {
            return null;
        }
        for (Map.Entry<Identifier, AbstractTexture> entry : map.entrySet()) {
            Identifier key = entry.getKey();
            if (key == null) continue;
            String path = key.getPath();
            if (path != null && path.contains(hash)) {
                AbstractTexture texture = entry.getValue();
                if (texture == null || isMissingTexture(manager, texture)) {
                    continue;
                }
                BufferedImage img = readDynamicTexture(texture);
                if (img != null) return img;
                img = readNativeImageTexture(texture);
                if (img != null) return img;
                img = readGpuTexture(texture);
                if (img != null) return img;
                img = readHttpTexture(texture);
                if (img != null) return img;
            }
        }
        return null;
    }

    private static String extractHash(String path) {
        if (path == null || path.isEmpty()) return null;
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        if (name.endsWith(".png")) {
            name = name.substring(0, name.length() - 4);
        }
        return name.isEmpty() ? null : name;
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
            VoxelBridgeLogger.warn(LogModule.TEXTURE_RESOLVE, String.format("[DynamicTextureReader][WARN] Failed to read HttpTexture file %s: %s", file, e.getMessage()));
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

    private static BufferedImage readGpuTexture(AbstractTexture texture) {
        try {
            int id = texture.getGlId();
            if (id <= 0) {
                return null;
            }
            NativeImage nativeImg = invokeDownloadTexture(id);
            if (nativeImg == null) {
                return null;
            }
            try {
                return TextureLoader.fromNativeImage(nativeImg);
            } finally {
                nativeImg.close();
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static NativeImage invokeDownloadTexture(int id) {
        try {
            for (String name : new String[] {"loadFromTextureImage", "downloadTexture"}) {
                try {
                    Method method = NativeImage.class.getDeclaredMethod(name, int.class, boolean.class);
                    method.setAccessible(true);
                    Object value = method.invoke(null, id, false);
                    if (value instanceof NativeImage nativeImg) {
                        return nativeImg;
                    }
                } catch (NoSuchMethodException ignored) {
                    // Try next candidate
                }
            }
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
        return null;
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

    @SuppressWarnings("unchecked")
    private static BufferedImage readFromTextureMap(TextureManager manager, Identifier location) {
        Map<Identifier, AbstractTexture> map = getTextureMap(manager);
        if (map == null) {
            return null;
        }
        AbstractTexture texture = map.get(location);
        if (texture == null || isMissingTexture(manager, texture)) {
            return null;
        }
        BufferedImage img = readDynamicTexture(texture);
        if (img != null) return img;
        img = readNativeImageTexture(texture);
        if (img != null) return img;
        return readGpuTexture(texture);
    }

    private static boolean isMissingTexture(TextureManager manager, AbstractTexture texture) {
        try {
            Identifier missingId = MissingSprite.getMissingSpriteId();
            AbstractTexture missing = manager.getTexture(missingId);
            return texture == missing;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Map<Identifier, AbstractTexture> getTextureMap(TextureManager manager) {
        Class<?> type = manager.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(manager);
                    if (value instanceof Map<?, ?> m) {
                        if (m.isEmpty()) {
                            return (Map<Identifier, AbstractTexture>) m;
                        }
                        Object sample = m.values().iterator().next();
                        if (sample instanceof AbstractTexture) {
                            return (Map<Identifier, AbstractTexture>) m;
                        }
                    }
                } catch (IllegalAccessException ignored) {
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }
}
