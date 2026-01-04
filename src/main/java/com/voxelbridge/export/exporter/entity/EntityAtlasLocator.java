package com.voxelbridge.export.exporter.entity;

import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;

/**
 * Finds the sprite inside an atlas that contains a given UV coordinate.
 */
@OnlyIn(Dist.CLIENT)
final class EntityAtlasLocator {
    private final Minecraft mc;
    private static Method getTexturesMethod;
    private static Field texturesByNameField;
    private static boolean reflectionInitialized = false;

    EntityAtlasLocator(Minecraft mc) {
        this.mc = mc;
    }

    TextureAtlasSprite find(ResourceLocation atlasLocation, float u, float v) {
        if (atlasLocation == null) {
            return null;
        }
        AbstractTexture tex = mc.getTextureManager().getTexture(atlasLocation);
        if (tex == null) {
            VoxelBridgeLogger.debug(LogModule.ENTITY, "[EntityAtlasLocator] No texture found for atlas: " + atlasLocation);
            return null;
        }
        if (!(tex instanceof TextureAtlas atlas)) {
            VoxelBridgeLogger.debug(LogModule.ENTITY, "[EntityAtlasLocator] Texture is not TextureAtlas: " + tex.getClass().getName());
            return null;
        }

        Collection<TextureAtlasSprite> sprites = getSprites(atlas);
        if (sprites == null || sprites.isEmpty()) {
            VoxelBridgeLogger.debug(LogModule.ENTITY, "[EntityAtlasLocator] No sprites found in atlas: " + atlasLocation);
            return null;
        }

        for (TextureAtlasSprite sprite : sprites) {
            if (contains(sprite, u, v)) {
                VoxelBridgeLogger.debug(LogModule.ENTITY, "[EntityAtlasLocator] Found sprite: " + sprite.contents().name() + " for UV (" + u + ", " + v + ")");
                return sprite;
            }
        }

        VoxelBridgeLogger.debug(LogModule.ENTITY, "[EntityAtlasLocator] No sprite contains UV (" + u + ", " + v + ") in atlas: " + atlasLocation);
        return null;
    }

    @SuppressWarnings("unchecked")
    private Collection<TextureAtlasSprite> getSprites(TextureAtlas atlas) {
        // Try direct method first
        try {
            Map<ResourceLocation, TextureAtlasSprite> textures = atlas.getTextures();
            if (textures != null && !textures.isEmpty()) {
                return textures.values();
            }
        } catch (Throwable ignored) {
        }

        // Fall back to reflection
        if (!reflectionInitialized) {
            initReflection();
        }

        // Try getTextures method via reflection
        if (getTexturesMethod != null) {
            try {
                Object result = getTexturesMethod.invoke(atlas);
                if (result instanceof Map<?, ?> map) {
                    return (Collection<TextureAtlasSprite>) map.values();
                }
            } catch (Throwable ignored) {
            }
        }

        // Try texturesByName field via reflection
        if (texturesByNameField != null) {
            try {
                Object result = texturesByNameField.get(atlas);
                if (result instanceof Map<?, ?> map) {
                    return (Collection<TextureAtlasSprite>) map.values();
                }
            } catch (Throwable ignored) {
            }
        }

        return null;
    }

    private static synchronized void initReflection() {
        if (reflectionInitialized) {
            return;
        }
        reflectionInitialized = true;

        Class<?> atlasClass = TextureAtlas.class;

        // Try to find getTextures method
        for (String methodName : new String[]{"getTextures", "texturesByName", "getTexturesByName"}) {
            try {
                Method m = atlasClass.getDeclaredMethod(methodName);
                m.setAccessible(true);
                getTexturesMethod = m;
                VoxelBridgeLogger.debug(LogModule.ENTITY, "[EntityAtlasLocator] Found method: " + methodName);
                break;
            } catch (NoSuchMethodException ignored) {
            }
        }

        // Try to find texturesByName field
        for (String fieldName : new String[]{"texturesByName", "textures", "f_118255_"}) {
            try {
                Field f = atlasClass.getDeclaredField(fieldName);
                f.setAccessible(true);
                texturesByNameField = f;
                VoxelBridgeLogger.debug(LogModule.ENTITY, "[EntityAtlasLocator] Found field: " + fieldName);
                break;
            } catch (NoSuchFieldException ignored) {
            }
        }

        // Search all fields for Map<ResourceLocation, TextureAtlasSprite>
        if (texturesByNameField == null) {
            for (Field f : atlasClass.getDeclaredFields()) {
                if (Map.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    texturesByNameField = f;
                    VoxelBridgeLogger.debug(LogModule.ENTITY, "[EntityAtlasLocator] Found Map field: " + f.getName());
                    break;
                }
            }
        }
    }

    private boolean contains(TextureAtlasSprite sprite, float u, float v) {
        return u >= sprite.getU0() && u <= sprite.getU1() && v >= sprite.getV0() && v <= sprite.getV1();
    }
}
