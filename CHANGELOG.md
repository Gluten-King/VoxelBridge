Task Completed:
1.  Analyzed the codebase and identified that `BlockEntityTextureResolver` was forcibly mapping all sign geometry (including text) to the sign wood texture.
2.  Modified `src/main/java/com/voxelbridge/export/exporter/blockentity/BlockEntityTextureResolver.java` to check if the current render texture is a "font" texture.
3.  If it is a font texture, the resolver now returns `null`, allowing the default resolution to proceed (which resolves to the actual font texture).
4.  This allows `BlockEntityRenderer.java`'s existing logic (`!isSignTexture(textureRes)`) to correctly identify the text geometry as "not a sign body" and skip it.

This prevents sign text (and its mesh) from being exported.
