package com.voxelbridge.client;

import com.voxelbridge.command.VoxelBridgeCommands;
import com.voxelbridge.export.ExportProgressTracker;
import com.voxelbridge.thread.ExportThread;
import com.voxelbridge.util.io.IOUtil;
import com.voxelbridge.util.client.RayCastUtil;
import com.voxelbridge.VoxelBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.nio.file.Path;

/**
 * Handles the VoxelBridge hotkeys for selecting positions and triggering exports.
 */
@SuppressWarnings("removal") // TODO: migrate to new subscriber API when available
@EventBusSubscriber(modid = VoxelBridge.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public class KeyInputHandler {

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        if (KeyBindings.KEY_SET_POS1.consumeClick()) {
            BlockPos hit = RayCastUtil.getLookingAt(mc, 20.0);
            if (hit == null) {
                mc.player.displayClientMessage(Component.literal("[VoxelBridge] No block targeted."), false);
            } else {
                VoxelBridgeCommands.setPos1(hit);
                ExportProgressTracker.previewSelection(hit, VoxelBridgeCommands.getPos2());
                mc.player.displayClientMessage(Component.literal("[VoxelBridge] pos1 set to " + hit), false);
            }
        }

        if (KeyBindings.KEY_SET_POS2.consumeClick()) {
            BlockPos hit = RayCastUtil.getLookingAt(mc, 20.0);
            if (hit == null) {
                mc.player.displayClientMessage(Component.literal("[VoxelBridge] No block targeted."), false);
            } else {
                VoxelBridgeCommands.setPos2(hit);
                ExportProgressTracker.previewSelection(VoxelBridgeCommands.getPos1(), hit);
                mc.player.displayClientMessage(Component.literal("[VoxelBridge] pos2 set to " + hit), false);
            }
        }

        if (KeyBindings.KEY_CLEAR.consumeClick()) {
            VoxelBridgeCommands.clearSelection();
            mc.player.displayClientMessage(Component.literal("[VoxelBridge] Selection cleared."), false);
        }

        if (KeyBindings.KEY_EXPORT.consumeClick()) {
            BlockPos pos1 = VoxelBridgeCommands.getPos1();
            BlockPos pos2 = VoxelBridgeCommands.getPos2();

            if (pos1 == null || pos2 == null) {
                mc.player.displayClientMessage(
                        Component.literal("[VoxelBridge] Please set pos1 and pos2 first (Numpad 7/9)."), false);
                return;
            }

            try {
                Path outDir = IOUtil.ensureExportDir();
                Thread exportThread = new ExportThread(mc.level, pos1, pos2, outDir);
                mc.player.displayClientMessage(
                        Component.literal("[VoxelBridge] Exporting to glTF..."), false);
                exportThread.start();
            } catch (Exception e) {
                e.printStackTrace();
                mc.player.displayClientMessage(
                        Component.literal("[VoxelBridge] Export failed: " + e.getMessage()), false);
            }
        }
    }
}
