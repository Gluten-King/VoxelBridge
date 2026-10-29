package com.voxelbridge.adapter;

import com.mojang.blaze3d.platform.NativeImage;
import com.voxelbridge.compat.FabricTextureAccess;
import com.voxelbridge.export.texture.DynamicTextureUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.io.File;
import java.io.FileInputStream;
import java.util.Optional;

public final class FabricDynamicTextureReader extends AbstractDynamicTextureReader {

    public static final FabricDynamicTextureReader INSTANCE = new FabricDynamicTextureReader();

    private FabricDynamicTextureReader() {
        super("Fabric/1.21.8");
    }

    @Override
    protected void preheatMapTexture(Identifier location) {
        int mapId = DynamicTextureUtil.parseMapId(location);
        if (mapId < 0) {
            return;
        }
        MapId id = new MapId(mapId);
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        var mapTextureManager = mc.getMapTextureManager();
        if (mapTextureManager == null) {
            return;
        }
        try {
            MapItemSavedData data = MapItem.getSavedData(id, mc.level);
            if (data != null) {
                debug("MapTextureManager.update: " + id);
                mapTextureManager.update(id, data);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    protected Optional<NativeImage> loadMapPixels(Identifier location) {
        return Optional.empty();
    }

    @Override
    protected NativeImage getDynamicTexturePixels(AbstractTexture texture) {
        return FabricTextureAccess.getDynamicTexturePixels(texture);
    }

    @Override
    protected Optional<NativeImage> loadHttpTexture(AbstractTexture texture) {
        File file = FabricTextureAccess.getHttpTextureFile(texture);
        if (file != null && file.exists()) {
            try {
                return Optional.of(NativeImage.read(new FileInputStream(file)));
            } catch (Exception ignored) {
            }
        }
        return Optional.empty();
    }
}
