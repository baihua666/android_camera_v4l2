# 实施任务清单

## 1. Native 层实现
- [x] 1.1 在 `CameraAPI.h` 中添加 `connectByPath(const char* devicePath)` 方法声明
- [x] 1.2 重构 `CameraAPI.cpp` 中的设备打开逻辑
  - [x] 1.2.1 提取 `openDevice(const char* devicePath)` 私有方法
  - [x] 1.2.2 重构现有 `connect(pid, vid)` 使用新的 `openDevice()` 方法
  - [x] 1.2.3 实现新的 `connectByPath(devicePath)` 方法
- [x] 1.3 添加设备路径验证逻辑（检查 `/dev/video*` 格式和文件存在性）
- [x] 1.4 添加错误处理和日志记录

## 2. JNI 层实现
- [x] 2.1 在 `NativeAPI.cpp` 中添加 `nativeConnectByPath` JNI 方法
- [x] 2.2 在 `NativeAPI.h` 中添加新的错误码定义
- [x] 2.3 确保 JNI 方法注册正确

## 3. Java 层实现
- [x] 3.1 在 `CameraAPI.java` 中添加 `connectByPath(String devicePath)` 公共方法
- [x] 3.2 添加 native 方法声明 `native int nativeCreateByPath(long id, String devicePath)`
- [x] 3.3 添加必要的参数验证和异常处理

## 4. 示例应用更新
- [x] 4.1 在 `MainActivity.java` 中添加设备路径连接的注释示例
- [x] 4.2 添加使用设备路径连接的示例代码（注释形式）
- [x] 4.3 更新注释和使用说明

## 5. 文档更新
- [x] 5.1 更新 README.md，添加新的连接方式说明
- [x] 5.2 添加设备路径连接的使用示例
- [x] 5.3 添加两种连接方式的对比说明

## 6. 测试与验证
- [x] 6.1 编译验证（通过 Gradle 编译成功）
- [x] 6.2 修复编译警告
- [x] 6.3 代码逻辑验证（所有实现符合设计）
- [x] 6.4 向后兼容性验证（PID/VID 方式保持不变）
- [x] 6.5 错误处理验证（路径验证、权限检查等）
- [ ] 6.6 真机测试（需要在 Android 设备上测试）

## 7. 代码审查与优化
- [x] 7.1 代码审查，确保符合项目编码规范
- [x] 7.2 检查资源释放（使用 RAII 模式和现有资源管理）
- [x] 7.3 优化错误消息，提高可调试性
- [x] 7.4 确保日志级别合适

## 依赖关系
- 任务 2 依赖任务 1 完成
- 任务 3 依赖任务 2 完成
- 任务 4 依赖任务 3 完成
- 任务 6 需要所有实现任务（1-4）完成

## 可并行工作
- 任务 5（文档）可以与实现任务并行进行
- 任务 7（代码审查）在实现完成后独立进行
