package com.voxelbridge.voxy.common.config.section;

import com.voxelbridge.voxy.common.config.IMappingStorage;
import com.voxelbridge.voxy.common.world.WorldSection;

public abstract class SectionStorage implements IMappingStorage {
    public abstract int loadSection(WorldSection into);

    public abstract void saveSection(WorldSection section);
}

