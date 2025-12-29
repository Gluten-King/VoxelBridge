package com.voxelbridge.voxy.common.world.other;

import static com.voxelbridge.voxy.common.world.other.Mapper.withLight;

//Mipper for data
public class Mipper {
    //TODO: compute the opacity of the block then mip w.r.t those blocks
    // as distant horizons done


    //TODO: also pass in the level its mipping from, cause at lower levels you want to preserve block details
    // but at higher details you want more air



    //TODO: instead of opacity only, add a level to see if the visual bounding box allows for seeing through top down etc
    public static long mip(long I000, long I100, long I001, long I101,
                           long I010, long I110, long I011, long I111,
                          Mapper mapper) {
        // Choose the most "solid" sample (opacity + height), matching voxy-dev behavior.
        long fluidPick = pickFluidSample(mapper, I000, I100, I001, I101, I010, I110, I011, I111);
        if (fluidPick != 0) {
            return fluidPick;
        }
        int max = -1;

        if (!Mapper.isAir(I111)) {
            max = (mapper.getBlockStateOpacity(I111) << 4) | 0b111;
        }
        if (!Mapper.isAir(I110)) {
            max = Math.max((mapper.getBlockStateOpacity(I110) << 4) | 0b110, max);
        }
        if (!Mapper.isAir(I011)) {
            max = Math.max((mapper.getBlockStateOpacity(I011) << 4) | 0b011, max);
        }
        if (!Mapper.isAir(I010)) {
            max = Math.max((mapper.getBlockStateOpacity(I010) << 4) | 0b010, max);
        }
        if (!Mapper.isAir(I101)) {
            max = Math.max((mapper.getBlockStateOpacity(I101) << 4) | 0b101, max);
        }
        if (!Mapper.isAir(I100)) {
            max = Math.max((mapper.getBlockStateOpacity(I100) << 4) | 0b100, max);
        }
        if (!Mapper.isAir(I001)) {
            max = Math.max((mapper.getBlockStateOpacity(I001) << 4) | 0b001, max);
        }
        if (!Mapper.isAir(I000)) {
            max = Math.max((mapper.getBlockStateOpacity(I000) << 4), max);
        }

        if (max != -1) {
            return switch (max & 0b111) {
                case 0 -> I000;
                case 1 -> I001;
                case 2 -> I010;
                case 3 -> I011;
                case 4 -> I100;
                case 5 -> I101;
                case 6 -> I110;
                case 7 -> I111;
                default -> throw new IllegalStateException("Unexpected value: " + (max & 0b111));
            };
        }

        int blockLight = (Mapper.getLightId(I000) & 0xF0) + (Mapper.getLightId(I001) & 0xF0) + (Mapper.getLightId(I010) & 0xF0) + (Mapper.getLightId(I011) & 0xF0) +
                (Mapper.getLightId(I100) & 0xF0) + (Mapper.getLightId(I101) & 0xF0) + (Mapper.getLightId(I110) & 0xF0) + (Mapper.getLightId(I111) & 0xF0);
        int skyLight = (Mapper.getLightId(I000) & 0x0F) + (Mapper.getLightId(I001) & 0x0F) + (Mapper.getLightId(I010) & 0x0F) + (Mapper.getLightId(I011) & 0x0F) +
                (Mapper.getLightId(I100) & 0x0F) + (Mapper.getLightId(I101) & 0x0F) + (Mapper.getLightId(I110) & 0x0F) + (Mapper.getLightId(I111) & 0x0F);
        blockLight = blockLight / 8;
        skyLight = (int) Math.ceil((double) skyLight / 8);

        return withLight(I111, (blockLight << 4) | skyLight);
    }

    private static long pickFluidSample(Mapper mapper,
                                        long I000, long I100, long I001, long I101,
                                        long I010, long I110, long I011, long I111) {
        long best = 0;
        int bestAmount = Integer.MAX_VALUE;
        int bestY = Integer.MAX_VALUE;
        long[] samples = new long[]{I000, I100, I001, I101, I010, I110, I011, I111};
        int[] ys = new int[]{0, 0, 0, 0, 1, 1, 1, 1};
        for (int i = 0; i < samples.length; i++) {
            long sample = samples[i];
            if (Mapper.isAir(sample)) {
                continue;
            }
            int blockId = Mapper.getBlockId(sample);
            if (blockId == 0) {
                continue;
            }
            var state = mapper.getBlockStateFromBlockId(blockId);
            var fluid = state.getFluidState();
            if (fluid.isEmpty()) {
                continue;
            }
            int amount = fluid.getAmount();
            int y = ys[i];
            if (amount < bestAmount || (amount == bestAmount && y < bestY)) {
                bestAmount = amount;
                bestY = y;
                best = sample;
            }
        }
        return best;
    }
}

