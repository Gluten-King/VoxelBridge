修复了 Fabric 平台下纹理获取的线程安全问题。

主要修改内容：
1.  **`MinecraftTextureAccess.java`**:
    *   新增 `runOnMainThread` 辅助方法，确保所有涉及 `ResourceManager` 的操作（如 `readAnimationMetadata`, `hasResource`, `listPngResources`, `openResource`）都在主线程执行。
    *   修复了后台线程直接访问 `ResourceManager` 导致的纹理丢失问题。
    *   对 `hasResource` 等方法进行了空值安全处理。

2.  **`TextureLoader.java`**:
    *   修改了 `runOnMainThread` 方法。当 `mc.submitAndJoin` 执行失败或抛出异常时，不再回退到当前线程（可能是后台线程）执行，而是记录错误并返回 `null`。
    *   防止了因主线程任务提交失败而导致的非线程安全访问，解决了潜在的“部分纹理获取失败”或崩溃问题。

3.  **`BlockExporter.java` (mc-1.21.1-fabric only)**:
    *   **改进的透明度判断**：现在使用 `!state.isOpaqueFullCube()` 替代 `!state.isSolidBlock()` 来判定方块是否“视觉透明”。这解决了部分模组方块（如 Compact Glass）虽声明为 Solid 但实际渲染透明导致的剔除错误（底部面丢失）。
    *   **Compact/CTM 强制合并**：对于非不透明方块（Glass, Compact Blocks），如果相邻方块是**同一种方块** (`state.isOf(neighbor.getBlock())`)，则强制剔除内部面。这修复了 CTM 模组中 `isSideInvisible` 判定失效导致内部面独立渲染的问题（Center independent）。
    *   **边界严格保留**：对于不同种类的方块，仅当相邻方块也是**非不透明** (`!isOpaqueFullCube`) 时才允许剔除。这意味着透明方块与实心/不透明方块（如石头、草地）接触时，边界通过强制保留，防止模型出现漏洞。
    *   新增 `getNeighborState` 方法以安全获取相邻方块状态。

这些修改确保了在 Fabric 环境下，无论是在主线程还是导出线程（后台）调用纹理相关 API，都能安全地通过主线程访问 Minecraft 资源管理器。