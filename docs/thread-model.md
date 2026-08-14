# Export thread model

An export owns one `ExportSession`. Configuration is snapshotted before work
starts and is immutable for the lifetime of that session.

- Minecraft resource reloads, renderer invocation, and GPU texture readback go
  through `ClientExecutor` or an exact-runtime capture bridge.
- Region planning is version-neutral. Chunk readiness and Minecraft object
  access remain in the exact runtime.
- Contract sinks consume borrowed primitive arrays synchronously. A sink that
  buffers work must copy the arrays before returning.
- Atlas, renderer, glyph, animation, progress, and deduplication caches are
  session attributes. They must not be static or shared across sequential
  scenes in one client launch.
- Closing a session runs registered cleanup actions in reverse order, clears
  attributes, and detaches UI progress state.

No exporter worker may wait for itself through the client executor. Calls that
are already on the client thread execute inline; other calls are submitted and
joined by the runtime adapter.
