package com.voxelbridge.pipeline.contract;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** Immutable capability set supplied by one exact-version runtime adapter. */
public final class RuntimeCapabilities {
    private final Set<RuntimeCapability> values;

    private RuntimeCapabilities(Collection<RuntimeCapability> values) {
        EnumSet<RuntimeCapability> copy = values.isEmpty()
            ? EnumSet.noneOf(RuntimeCapability.class)
            : EnumSet.copyOf(values);
        this.values = Collections.unmodifiableSet(copy);
    }

    public static RuntimeCapabilities of(RuntimeCapability... capabilities) {
        EnumSet<RuntimeCapability> values = EnumSet.noneOf(RuntimeCapability.class);
        if (capabilities != null) Collections.addAll(values, capabilities);
        return new RuntimeCapabilities(values);
    }

    public boolean supports(RuntimeCapability capability) {
        return values.contains(capability);
    }

    public Set<RuntimeCapability> values() {
        return values;
    }
}
