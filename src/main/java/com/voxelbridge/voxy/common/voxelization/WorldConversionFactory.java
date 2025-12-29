package com.voxelbridge.voxy.common.voxelization;

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import com.voxelbridge.voxy.common.world.other.Mapper;
import com.voxelbridge.voxy.common.world.other.Mipper;
// import net.fabricmc.loader.api.FabricLoader; // Removed
import net.minecraft.core.Holder;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.HashMapPalette;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.SingleValuePalette;
import com.voxelbridge.util.debug.LogModule;
import com.voxelbridge.util.debug.VoxelBridgeLogger;

import java.util.WeakHashMap;

public class WorldConversionFactory {
    // private static final boolean LITHIUM_INSTALLED = FabricLoader.getInstance().isModLoaded("lithium");

    private static final class Cache {
        private final int[] biomeCache = new int[4*4*4];
        private final WeakHashMap<Mapper, Reference2IntOpenHashMap<BlockState>> localMapping = new WeakHashMap<>();
        private int[] paletteCache = new int[1024];
        private Reference2IntOpenHashMap<BlockState> getLocalMapping(Mapper mapper) {
            return this.localMapping.computeIfAbsent(mapper, (a_)->new Reference2IntOpenHashMap<>());
        }
        private int[] getPaletteCache(int size) {
            if (this.paletteCache.length < size) {
                this.paletteCache = new int[size];
            }
            return this.paletteCache;
        }
    }

    private static final ThreadLocal<Cache> THREAD_LOCAL = ThreadLocal.withInitial(Cache::new);

    private static int setupLocalPalette(Palette<BlockState> vp, Reference2IntOpenHashMap<BlockState> blockCache, Mapper mapper, int[] pc) {
        int c = vp.getSize();
        if (vp instanceof LinearPalette<BlockState>) {
            for (int i = 0; i < vp.getSize(); i++) {
                var state = vp.valueFor(i);
                int blockId = -1;
                if (state != null) {
                    blockId = blockCache.getOrDefault(state, -1);
                    if (blockId == -1) {
                        blockId = mapper.getIdForBlockState(state);
                        blockCache.put(state, blockId);
                    }
                }
                pc[i] = blockId;
            }
        } else if (vp instanceof HashMapPalette<BlockState> pal) {
            //var map = pal.map;
            //TODO: heavily optimize this by reading the map directly

            for (int i = 0; i < vp.getSize(); i++) {
                BlockState state = null;
                int blockId = -1;
                try { state = vp.valueFor(i); } catch (Exception e) {}
                if (state != null) {
                    blockId = blockCache.getOrDefault(state, -1);
                    if (blockId == -1) {
                        blockId = mapper.getIdForBlockState(state);
                        blockCache.put(state, blockId);
                    }
                }
                pc[i] = blockId;
            }

        } else if (vp instanceof SingleValuePalette<BlockState>) {
            int blockId = -1;
            var state = vp.valueFor(0);
            if (state != null) {
                blockId = blockCache.getOrDefault(state, -1);
                if (blockId == -1) {
                    blockId = mapper.getIdForBlockState(state);
                    blockCache.put(state, blockId);
                }
            }
            pc[0] = blockId;
        } else {
            // Lithium support removed
            throw new IllegalStateException("Unknown palette type: " + vp.getClass().getName());
        }
        return c;
    }

    public static VoxelizedSection convert(VoxelizedSection section,
                                           Mapper stateMapper,
                                           PalettedContainer<BlockState> blockContainer,
                                           PalettedContainerRO<Holder<Biome>> biomeContainer,
                                           ILightingSupplier lightSupplier) {

        var cache = THREAD_LOCAL.get();
        var blockCache = cache.getLocalMapping(stateMapper);

        var biomes = cache.biomeCache;
        var data = section.section;

        var vp = blockContainer.data.palette();
        Object storage = blockContainer.data.storage();
        String paletteType = vp != null ? vp.getClass().getSimpleName() : "null";
        int paletteSize = vp != null ? vp.getSize() : 0;
        String storageType = storage != null ? storage.getClass().getSimpleName() : "null";
        
        var pc = cache.getPaletteCache(vp.getSize());
        GlobalPalette<BlockState> bps = null;

        int pcc = 0;
        if (vp instanceof GlobalPalette<BlockState> _bps) {
            bps = _bps;
            pcc = bps.getSize();
        } else {
            pcc = setupLocalPalette(vp, blockCache, stateMapper, pc);
            pcc = Math.max(0,pcc-1);
        }

        {
            int i = 0;
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    for (int x = 0; x < 4; x++) {
                        biomes[i++] = stateMapper.getIdForBiome(biomeContainer.get(x, y, z));
                    }
                }
            }
        }


        int nonZeroCnt = 0;
        if (storage instanceof SimpleBitStorage bStor) {
            var bDat = bStor.getRaw();
            int iterPerLong = (64 / bStor.getBits()) - 1;

            int MSK = (1 << bStor.getBits()) - 1;
            int eBits = bStor.getBits();

            long sample = 0;
            int c = 0;
            int dec = 0;
            for (int i = 0; i <= 0xFFF; i++) {
                if (dec-- == 0) {
                    sample = bDat[c++];
                    dec = iterPerLong;
                }
                int bId;
                if (bps == null) {
                    bId = pc[Math.min((int) (sample & MSK), pcc)];
                } else {
                    bId = stateMapper.getIdForBlockState(bps.valueFor((int) (sample&MSK)));
                }
                sample >>>= eBits;

                byte light = lightSupplier.supply(i&0xF, (i>>8)&0xF, (i>>4)&0xF);
                nonZeroCnt += (bId != 0)?1:0;
                data[i] = Mapper.composeMappingId(light, bId, biomes[Integer.compress(i,0b1100_1100_1100)]);
            }
        } else {
            if (!(storage instanceof ZeroBitStorage)) {
                throw new IllegalStateException("Unknown storage: " + storage.getClass().getName());
            }
            int bId = pc[0];
            if (bId == 0) {//Its air
                for (int i = 0; i <= 0xFFF; i++) {
                    data[i] = Mapper.airWithLight(lightSupplier.supply(i&0xF, (i>>8)&0xF, (i>>4)&0xF));
                }
            } else {
                nonZeroCnt = 4096;
                for (int i = 0; i <= 0xFFF; i++) {
                    byte light = lightSupplier.supply(i&0xF, (i>>8)&0xF, (i>>4)&0xF);
                    data[i] = Mapper.composeMappingId(light, bId, biomes[Integer.compress(i,0b1100_1100_1100)]);
                }
            }
        }
        section.lvl0NonAirCount = nonZeroCnt;
        VoxelBridgeLogger.info(LogModule.LOD, String.format(
            "[LOD] convert section=[%d,%d,%d] palette=%s(%d) storage=%s nonAir=%d",
            section.x, section.y, section.z, paletteType, paletteSize, storageType, nonZeroCnt));
        return section;
    }

    private static int G(int x, int y, int z) {
        return ((y<<8)|(z<<4)|x);
    }

    private static int H(int x, int y, int z) {
        return ((y<<6)|(z<<3)|x) + 16*16*16;
    }

    private static int I(int x, int y, int z) {
        return ((y<<4)|(z<<2)|x) + 8*8*8 + 16*16*16;
    }

    private static int J(int x, int y, int z) {
        return ((y<<2)|(z<<1)|x) + 4*4*4 + 8*8*8 + 16*16*16;
    }

    public static void mipSection(VoxelizedSection section, Mapper mapper) {
        var data = section.section;

        //Mip L1
        int i = 0;
        int MSK = 0b1110_1110_1110;
        int iMSK1 = (~MSK)+1;
        int q = 0;
        while (true) {
            data[16*16*16 + i++] = Mipper.mip(
                    data[q|G(0,0,0)], data[q|G(1,0,0)], data[q|G(0,0,1)], data[q|G(1,0,1)],
                    data[q|G(0,1,0)], data[q|G(1,1,0)], data[q|G(0,1,1)], data[q|G(1,1,1)],
                    mapper
            );
            if (q == MSK)
                break;
            q = (q+iMSK1)&MSK;
        }

        //Mip L2
        i = 0;
        for (int y = 0; y < 8; y+=2) {
            for (int z = 0; z < 8; z += 2) {
                for (int x = 0; x < 8; x += 2) {
                    data[16*16*16 + 8*8*8 + i++] =
                            Mipper.mip(
                                    data[H(x, y, z)],       data[H(x+1, y, z)],       data[H(x, y, z+1)],      data[H(x+1, y, z+1)],
                                    data[H(x, y+1, z)],  data[H(x+1, y+1, z)],  data[H(x, y+1, z+1)], data[H(x+1, y+1, z+1)],
                                    mapper);
                }
            }
        }

        //Mip L3
        i = 0;
        for (int y = 0; y < 4; y+=2) {
            for (int z = 0; z < 4; z += 2) {
                for (int x = 0; x < 4; x += 2) {
                    data[16*16*16 + 8*8*8 + 4*4*4 + i++] =
                            Mipper.mip(
                                    data[I(x, y, z)],       data[I(x+1, y, z)],       data[I(x, y, z+1)],      data[I(x+1, y, z+1)],
                                    data[I(x, y+1, z)],   data[I(x+1, y+1, z)],  data[I(x, y+1, z+1)], data[I(x+1, y+1, z+1)],
                                    mapper);
                }
            }
        }

        //Mip L4
        data[16*16*16 + 8*8*8 + 4*4*4 + 2*2*2] =
                Mipper.mip(
                        data[J(0, 0, 0)], data[J(1, 0, 0)], data[J(0, 0, 1)], data[J(1, 0, 1)],
                        data[J(0, 1, 0)], data[J(1, 1, 0)], data[J(0, 1, 1)], data[J(1, 1, 1)],
                        mapper);
    }
}

