package com.voxelbridge.pipeline.resource;

import com.voxelbridge.pipeline.contract.ResourceId;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic, version-neutral fallback order for LabPBR companion textures. */
public final class PbrResourceCandidates {
    private static final Pattern CTM_BASE =
        Pattern.compile("^(block|entity)/(ctm|connected|continuity)/([^/]+)");

    private PbrResourceCandidates() {}

    public static List<ResourceId> candidates(ResourceId base, String suffix) {
        if (base == null) return List.of();
        if (!"_n".equals(suffix) && !"_s".equals(suffix)) {
            throw new IllegalArgumentException("PBR suffix must be _n or _s");
        }
        String path = base.path();
        Set<String> paths = new LinkedHashSet<>();
        paths.add(buildPath(path, suffix));
        if (path.matches(".*/\\d+$")) {
            paths.add(buildPath(path.replaceFirst("/\\d+$", ""), suffix));
        }
        if (path.endsWith("_overlay")) {
            paths.add(buildPath(path.substring(0, path.length() - "_overlay".length()), suffix));
        }
        if (path.matches(".*_\\d+$")) {
            paths.add(buildPath(path.replaceFirst("_\\d+$", ""), suffix));
        }
        Matcher matcher = CTM_BASE.matcher(path);
        if (matcher.find()) {
            paths.add(buildPath(matcher.group(1) + "/" + matcher.group(3), suffix));
        }
        List<ResourceId> result = new ArrayList<>(paths.size());
        for (String candidate : paths) result.add(new ResourceId(base.namespace(), candidate));
        return List.copyOf(result);
    }

    private static String buildPath(String basePath, String suffix) {
        if (basePath.startsWith("optifine/cit/") || basePath.startsWith("textures/")) {
            return basePath + suffix + ".png";
        }
        return "textures/" + basePath + suffix + ".png";
    }
}
