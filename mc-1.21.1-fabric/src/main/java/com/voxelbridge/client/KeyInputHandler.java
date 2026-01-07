package com.voxelbridge.client;

import com.voxelbridge.export.ExportControl;
import com.voxelbridge.util.client.RayCastUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.text.Text;

/**
 * Handles VoxelBridge hotkeys on Fabric (client tick).
 */
public final class KeyInputHandler {

    private KeyInputHandler() {}

    public static void onClientTick(MinecraftClient mc) {
        if (mc == null || mc.player == null || mc.world == null) {
            return;
        }

        if (KeyBindings.KEY_SET_POS1.wasPressed()) {
            BlockPos hit = RayCastUtil.getLookingAt(mc, 20.0);
            if (hit == null) {
                mc.player.sendMessage(Text.literal("[VoxelBridge] No block targeted."), false);
            } else {
                ExportControl.setPos1(hit);
                mc.player.sendMessage(Text.literal("[VoxelBridge] pos1 set to " + hit), false);
            }
        }

        if (KeyBindings.KEY_SET_POS2.wasPressed()) {
            BlockPos hit = RayCastUtil.getLookingAt(mc, 20.0);
            if (hit == null) {
                mc.player.sendMessage(Text.literal("[VoxelBridge] No block targeted."), false);
            } else {
                ExportControl.setPos2(hit);
                mc.player.sendMessage(Text.literal("[VoxelBridge] pos2 set to " + hit), false);
            }
        }

        if (KeyBindings.KEY_CLEAR.wasPressed()) {
            ExportControl.clearSelection();
            mc.player.sendMessage(Text.literal("[VoxelBridge] Selection cleared."), false);
        }

        if (KeyBindings.KEY_EXPORT.wasPressed()) {
            ExportControl.ExportResult result = ExportControl.startExport(mc.world);
            mc.player.sendMessage(Text.literal("[VoxelBridge] " + result.message()), false);
        }
    }
}
