修复了 Fabric 平台下纹理获取的线程安全问题。

主要修改内容：
1.  **`MinecraftTextureAccess.java`**:
    *   新增 `runOnMainThread` 辅助方法，确保所有涉及 `ResourceManager` 的操作（如 `readAnimationMetadata`, `hasResource`, `listPngResources`, `openResource`）都在主线程执行。
    *   修复了后台线程直接访问 `ResourceManager` 导致的纹理丢失问题。
    *   对 `hasResource` 等方法进行了空值安全处理。

2.  **`TextureLoader.java`**:
    *   修改了 `runOnMainThread` 方法。当 `mc.submitAndJoin` 执行失败或抛出异常时，不再回退到当前线程（可能是后台线程）执行，而是记录错误并返回 `null`。
    *   防止了因主线程任务提交失败而导致的非线程安全访问，解决了潜在的“部分纹理获取失败”或崩溃问题。

这些修改确保了在 Fabric 环境下，无论是在主线程还是导出线程（后台）调用纹理相关 API，都能安全地通过主线程访问 Minecraft 资源管理器。