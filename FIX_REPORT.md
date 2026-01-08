修复完成。

**主要变更：**

1.  **`mc-1.21.1-fabric/.../export/texture/MinecraftTextureAccess.java`**:
    *   引入了 `runOnMainThread` 辅助方法。
    *   将 `readAnimationMetadata`, `hasResource`, `listPngResources`, `openResource` 等方法的实现逻辑封装在 `runOnMainThread` 中。
    *   确保所有对 `ResourceManager` 的访问都强制调度到 Minecraft 主线程执行，解决了后台线程访问非线程安全资源管理器导致的纹理丢失问题。

2.  **`mc-1.21.1-fabric/.../platform/texture/TextureLoader.java`**:
    *   修复了 `runOnMainThread` 中的回退逻辑。
    *   之前的实现在 `submitAndJoin` 异常时会回退到当前线程执行 `task.get()`。如果当前是后台线程，这会导致不安全的资源访问。
    *   现在的实现会在异常时记录错误并返回 `null`，避免了潜在的崩溃和数据损坏，这可能解释了“部分纹理获取失败”的问题。

请重新构建项目并测试导出功能。由于环境限制，我无法在此会话中运行构建命令验证，但代码逻辑已根据 Fabric 资源管理的线程安全要求进行了修正。