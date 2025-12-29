package com.voxelbridge.voxy.client.core.gl.shader;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class ShaderLoader {
    private ShaderLoader() {}

    public static String parse(String id) {
        ResourceLocation loc = ResourceLocation.parse(id);
        ResourceLocation shaderLoc = ResourceLocation.fromNamespaceAndPath(
            loc.getNamespace(),
            "shaders/" + loc.getPath()
        );
        try (var stream = Minecraft.getInstance().getResourceManager().open(shaderLoc)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        } catch (IOException e) {
            throw new RuntimeException("Failed to load shader: " + shaderLoc, e);
        }
    }
}
