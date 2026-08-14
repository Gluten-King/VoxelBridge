package com.voxelbridge.adapter;

import com.voxelbridge.client.SelectionRendererCompat;
import com.voxelbridge.platform.neoforge.NeoForgeEventBusBridge;

/**
 * 1.21.8 selection renderer registration (AfterTranslucentBlocks).
 */
public final class NeoForgeSelectionRenderBridge implements SelectionRenderBridge {

    @Override
    public void register(Object gameBus) {
        NeoForgeEventBusBridge.addListener(gameBus, SelectionRendererCompat::onRenderLevel);
    }
}
