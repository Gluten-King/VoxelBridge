package com.voxelbridge.pipeline.contract;

import java.util.Objects;

/** Stable resource identity independent of Mojang mapping names. */
public record ResourceId(String namespace, String path) implements Comparable<ResourceId> {

    public ResourceId {
        namespace = requirePart(namespace, "namespace");
        path = requirePart(path, "path");
        if (namespace.indexOf(':') >= 0 || path.indexOf(':') >= 0) {
            throw new IllegalArgumentException("ResourceId parts must not contain ':'");
        }
    }

    public static ResourceId parse(String value) {
        Objects.requireNonNull(value, "value");
        int separator = value.indexOf(':');
        return separator < 0
            ? new ResourceId("minecraft", value)
            : new ResourceId(value.substring(0, separator), value.substring(separator + 1));
    }

    private static String requirePart(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    @Override
    public int compareTo(ResourceId other) {
        int namespaceOrder = namespace.compareTo(other.namespace);
        return namespaceOrder != 0 ? namespaceOrder : path.compareTo(other.path);
    }

    @Override
    public String toString() {
        return namespace + ':' + path;
    }
}
