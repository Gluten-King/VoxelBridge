# Disposable world templates

The client automation phase will use one pristine minimal world per Minecraft
version, shared by Fabric and NeoForge. Each test run must copy the template into
its run directory and delete the copy afterward.

If a loader displays a compatibility/confirmation screen for the shared world,
add a platform-specific archive such as `neoforge-1.21.8.zip`. The test runner
prefers `<loader>-<version>.zip` and falls back to `<version>.zip`.

Do not build test geometry into these saves. The scene is recreated from the
text files under `golden/scenarios` on every run.
