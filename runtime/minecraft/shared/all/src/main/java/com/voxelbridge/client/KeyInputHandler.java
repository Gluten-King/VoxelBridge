package com.voxelbridge.client;

import com.voxelbridge.export.ExportControl;
import com.voxelbridge.util.client.RayCastUtil;
import com.voxelbridge.platform.client.ClientAccessHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Handles the VoxelBridge hotkeys for selecting positions and triggering exports.
 */
public class KeyInputHandler {

    public static void onClientTick() {
        var mc = ClientAccessHolder.get().getMinecraft();
        ClientAutomationExtension.onClientTick(mc);
        if (mc.player == null || mc.level == null) {
            return;
        }

        if (KeyBindings.KEY_SET_POS1.consumeClick()) {
            BlockPos hit = RayCastUtil.getLookingAt(mc, 20.0);
            if (hit == null) {
                hit = mc.player.blockPosition();
                mc.player.displayClientMessage(
                        Component.literal("[VoxelBridge] No block targeted, use current position: " + hit.toShortString()),
                        false);
            }
            ExportControl.setPos1(hit);
            mc.player.displayClientMessage(Component.literal("[VoxelBridge] pos1 set to " + hit.toShortString()), false);
        }

        if (KeyBindings.KEY_SET_POS2.consumeClick()) {
            BlockPos hit = RayCastUtil.getLookingAt(mc, 20.0);
            if (hit == null) {
                hit = mc.player.blockPosition();
                mc.player.displayClientMessage(
                        Component.literal("[VoxelBridge] No block targeted, use current position: " + hit.toShortString()),
                        false);
            }
            ExportControl.setPos2(hit);
            mc.player.displayClientMessage(Component.literal("[VoxelBridge] pos2 set to " + hit.toShortString()), false);
        }

        if (KeyBindings.KEY_CLEAR.consumeClick()) {
            ExportControl.clearSelection();
            mc.player.displayClientMessage(Component.literal("[VoxelBridge] Selection cleared."), false);
        }

        if (KeyBindings.KEY_EXPORT.consumeClick()) {
            ExportControl.ExportResult result = ExportControl.startExport(mc.level);
            mc.player.displayClientMessage(Component.literal("[VoxelBridge] " + result.message()), false);
        }

        if (KeyBindings.KEY_CONFIG.consumeClick()) {
            com.voxelbridge.platform.ConfigScreenBridge.openConfigScreen(mc);
        }
    }
}
