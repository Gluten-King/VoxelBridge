package com.voxelbridge.voxy.common.util;

import java.lang.ref.Cleaner;

public class GlobalCleaner {
    public static final Cleaner CLEANER = Cleaner.create();
}

