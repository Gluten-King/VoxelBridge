package com.voxelbridge.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.voxelbridge.export.ExportControl;
import com.voxelbridge.config.ExportRuntimeConfig;
import com.voxelbridge.core.util.color.ColorMode;
import com.voxelbridge.export.CoordinateMode;
import com.voxelbridge.platform.client.ClientAccessHolder;
import com.voxelbridge.util.client.RayCastUtil;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

/**
 * Fabric client-side /voxelbridge command registration (trimmed feature set).
 */
public final class VoxelBridgeCommands {

    private VoxelBridgeCommands() {}

    public static void registerFabric(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        var root = ClientCommandManager.literal("voxelbridge");

        root.then(ClientCommandManager.literal("pos1").executes(ctx -> {
            var mc = ClientAccessHolder.get().getMinecraft();
            BlockPos hit = RayCastUtil.getLookingAt(mc, 20.0);
            if (hit == null) {
                ctx.getSource().sendFeedback(Text.literal("[VoxelBridge] No block targeted."));
                return 0;
            }
            ExportControl.setPos1(hit);
            ctx.getSource().sendFeedback(Text.literal("[VoxelBridge] pos1 set to " + ExportControl.getPos1()));
            return 1;
        }));

        root.then(ClientCommandManager.literal("pos2").executes(ctx -> {
            var mc = ClientAccessHolder.get().getMinecraft();
            BlockPos hit = RayCastUtil.getLookingAt(mc, 20.0);
            if (hit == null) {
                ctx.getSource().sendFeedback(Text.literal("[VoxelBridge] No block targeted."));
                return 0;
            }
            ExportControl.setPos2(hit);
            ctx.getSource().sendFeedback(Text.literal("[VoxelBridge] pos2 set to " + ExportControl.getPos2()));
            return 1;
        }));

        root.then(ClientCommandManager.literal("clear").executes(ctx -> {
            ExportControl.clearSelection();
            ctx.getSource().sendFeedback(Text.literal("[VoxelBridge] Selection cleared."));
            return 1;
        }));

        root.then(ClientCommandManager.literal("info").executes(ctx -> {
            ctx.getSource().sendFeedback(Text.literal("[VoxelBridge] Selection:"));
            ctx.getSource().sendFeedback(Text.literal("  pos1: " + (ExportControl.getPos1() != null ? ExportControl.getPos1() : "unset")));
            ctx.getSource().sendFeedback(Text.literal("  pos2: " + (ExportControl.getPos2() != null ? ExportControl.getPos2() : "unset")));
            ctx.getSource().sendFeedback(Text.literal("  Atlas mode: " + ExportRuntimeConfig.getAtlasMode().getDescription()));
            ctx.getSource().sendFeedback(Text.literal("  Atlas size: " + ExportRuntimeConfig.getAtlasSize().getDescription()));
            ctx.getSource().sendFeedback(Text.literal("  Atlas padding: " + ExportRuntimeConfig.getAtlasPadding() + "px"));
            ctx.getSource().sendFeedback(Text.literal("  Coordinate mode: " + (ExportRuntimeConfig.getCoordinateMode() == CoordinateMode.CENTERED ? "centered" : "world")));
            ctx.getSource().sendFeedback(Text.literal("  Color mode: " + ExportRuntimeConfig.getColorMode().getDescription()));
            ctx.getSource().sendFeedback(Text.literal("  Animation: " + (ExportRuntimeConfig.isAnimationEnabled() ? "on" : "off")));
            ctx.getSource().sendFeedback(Text.literal("  Fill cave: " + (ExportRuntimeConfig.isFillCaveEnabled() ? "on" : "off")));
            ctx.getSource().sendFeedback(Text.literal("  LabPBR decode: " + (ExportRuntimeConfig.isPbrDecodeEnabled() ? "on" : "off")));
            ctx.getSource().sendFeedback(Text.literal("  Export threads: " + ExportRuntimeConfig.getExportThreadCount()));
            return 1;
        }));

        root.then(ClientCommandManager.literal("export").executes(ctx -> {
            var mc = ClientAccessHolder.get().getMinecraft();
            var result = ExportControl.startExport(mc.world);
            ctx.getSource().sendFeedback(Text.literal("[VoxelBridge] " + result.message()));
            return result.started() ? 1 : 0;
        }));

        // Register root and alias "vb"
        dispatcher.register(root);
        dispatcher.register(ClientCommandManager.literal("vb").redirect(root.build()));
    }
}
