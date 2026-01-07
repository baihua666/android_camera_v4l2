# Change: 添加设备路径直接访问支持

## Why
当前系统仅支持通过 USB PID/VID 来查找和连接相机设备，需要遍历 sysfs 文件系统来匹配设备。这种方式存在以下局限：

1. **仅限 USB 设备**：只能连接 USB 摄像头，无法支持其他类型的 V4L2 设备
2. **查找开销**：需要遍历所有 video 设备节点并读取 modalias 文件
3. **灵活性差**：用户无法直接指定已知的设备路径（如 `/dev/video23`）
4. **调试困难**：测试特定设备时必须知道 PID/VID

通过添加设备路径直接访问方式，可以让用户更灵活地使用各种 V4L2 设备，简化开发和调试流程。

## What Changes
- **新增功能**：添加通过设备路径直接连接相机的 API
- **保持兼容**：保留现有的 PID/VID 连接方式
- **重构代码**：提取公共的设备打开和初始化逻辑

### 具体变更
1. 在 `CameraAPI` 类中添加新的 `connectByPath(const char* devicePath)` 方法
2. 在 Java 层添加对应的 JNI 接口
3. 重构 `connect()` 方法中的设备打开逻辑，提取为独立函数 `openDevice(const char* devicePath)`
4. 更新示例应用，展示两种连接方式的使用

## Impact

### 受影响的规范
- **新增规范**: `camera-device-connection` - 相机设备连接管理

### 受影响的代码
- `sdk_v4l2_camera/src/main/cpp/libcamera/CameraAPI.h` - 添加新方法声明
- `sdk_v4l2_camera/src/main/cpp/libcamera/CameraAPI.cpp` - 实现新方法和重构
- `sdk_v4l2_camera/src/main/cpp/libcamera/NativeAPI.cpp` - 添加 JNI 绑定
- `sdk_v4l2_camera/src/main/java/com/hsj/camera/CameraAPI.java` - 添加 Java API
- `sample/src/main/java/com/hsj/sample/MainActivity.java` - 更新示例代码

### 向后兼容性
✅ **完全兼容** - 保留所有现有 API，仅添加新的连接方式

### 风险评估
- **低风险**：仅添加新功能，不修改现有逻辑
- **测试范围**：需要测试多种设备路径格式和错误情况
