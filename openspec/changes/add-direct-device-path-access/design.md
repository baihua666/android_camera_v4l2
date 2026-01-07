# 设计文档：设备路径直接访问

## Context
当前 Android V4L2 Camera SDK 仅支持通过 USB PID/VID 来查找和连接相机设备。这种方式在以下场景中存在限制：

1. **非 USB 设备**：无法支持其他接口的 V4L2 设备（如虚拟设备、CSI 接口设备等）
2. **开发调试**：开发者需要查找设备的 PID/VID，而不能直接使用已知的设备路径
3. **多设备管理**：当系统中有多个相同型号设备时，难以指定特定设备

用户希望能够直接通过设备文件路径（如 `/dev/video23`）来访问相机，使 API 更加灵活和通用。

## Goals / Non-Goals

### Goals
- ✅ 支持通过设备路径字符串直接连接相机
- ✅ 保持与现有 PID/VID 连接方式的完全向后兼容
- ✅ 提供清晰的错误提示和验证
- ✅ 代码重构，消除重复逻辑

### Non-Goals
- ❌ 不移除或废弃现有的 PID/VID 连接方式
- ❌ 不自动扫描和枚举所有可用设备（可在未来版本添加）
- ❌ 不处理设备热插拔事件（现有问题，见 CameraAPI.cpp:133 TODO）
- ❌ 不修改录制和预览的核心逻辑

## Decisions

### 决策 1：API 设计
**选择**：添加独立的 `connectByPath()` 方法，而非修改现有 `connect()` 方法

**理由**：
- 保持 API 清晰度 - 两种连接方式语义不同
- 完全向后兼容 - 不影响现有代码
- 类型安全 - 避免参数重载混淆（字符串 vs 整数）

**替代方案**：
1. ❌ 重载 `connect()` 方法 - 可能造成 JNI 签名冲突
2. ❌ 使用单一 `connect()` 方法 + 配置对象 - 过度设计，增加复杂性

### 决策 2：设备路径验证
**选择**：在 Native 层进行设备路径验证

**验证内容**：
1. 路径格式检查（必须以 `/dev/video` 开头）
2. 文件存在性检查（`access()` 系统调用）
3. 设备打开测试（`open()` + 错误码处理）
4. V4L2 能力查询（`VIDIOC_QUERYCAP` ioctl）

**理由**：
- Native 层更接近系统调用，错误信息更准确
- 避免 JNI 跨越开销
- 可以复用现有的错误处理机制（ActionInfo 枚举）

### 决策 3：代码重构策略
**选择**：提取 `openDevice(const char* devicePath)` 私有方法

**重构内容**：
```cpp
// 新的私有方法
ActionInfo openDevice(const char* devicePath);

// 重构后的现有方法
ActionInfo connect(unsigned int target_pid, unsigned int target_vid) {
    // 1. 查找设备路径逻辑（保持不变）
    std::string dev_video_name = findDeviceByPidVid(target_pid, target_vid);
    // 2. 调用统一的打开方法
    return openDevice(dev_video_name.c_str());
}

// 新增的方法
ActionInfo connectByPath(const char* devicePath) {
    // 验证路径格式
    if (!validateDevicePath(devicePath)) {
        return ACTION_ERROR_INVALID_PATH;
    }
    // 调用统一的打开方法
    return openDevice(devicePath);
}
```

**理由**：
- DRY 原则 - 消除重复的设备打开逻辑
- 易于测试 - 可以独立测试设备打开逻辑
- 易于维护 - 设备打开逻辑的修改只需在一处进行

### 决策 4：错误码扩展
**选择**：在 `NativeAPI.h` 中添加新的错误码

```cpp
typedef enum {
    // ... 现有错误码 ...
    ACTION_ERROR_INVALID_PATH   = -100,  // 无效的设备路径格式
    ACTION_ERROR_DEVICE_ACCESS  = -101,  // 设备访问权限不足
} ActionInfo;
```

**理由**：
- 提供更精确的错误信息
- 便于 Java 层处理特定错误场景
- 保持与现有错误处理模式一致

## Architecture

### 调用流程
```
Java Layer (MainActivity.java)
    ↓ connectByPath("/dev/video23")
Java API (CameraAPI.java)
    ↓ nativeConnectByPath(id, "/dev/video23")
JNI Layer (NativeAPI.cpp)
    ↓ Java_com_hsj_camera_CameraAPI_nativeConnectByPath()
Native API (CameraAPI.cpp)
    ↓ connectByPath("/dev/video23")
    ↓ validateDevicePath("/dev/video23") ✓
    ↓ openDevice("/dev/video23")
        ↓ open("/dev/video23", O_RDWR | O_NONBLOCK)
        ↓ ioctl(fd, VIDIOC_QUERYCAP, &cap)
        ↓ return fd
    ↓ status = STATUS_OPEN
    ↓ return ACTION_SUCCESS
```

### 类图变更
```
CameraAPI (修改)
├── 新增公共方法
│   └── ActionInfo connectByPath(const char* devicePath)
├── 新增私有方法
│   ├── ActionInfo openDevice(const char* devicePath)
│   └── bool validateDevicePath(const char* devicePath)
└── 重构现有方法
    └── ActionInfo connect(unsigned int pid, unsigned int vid)
        └── 调用 openDevice() 而非内联实现
```

## Risks / Trade-offs

### 风险 1：设备路径安全性
**风险**：用户可能传入恶意路径（如 `/dev/null`, `/etc/passwd`）

**缓解措施**：
1. 严格验证路径格式（必须匹配 `/dev/video[0-9]+`）
2. 使用白名单而非黑名单验证
3. 在 open() 前进行 realpath() 解析，检测符号链接攻击

### 风险 2：权限问题
**风险**：设备文件权限不足导致打开失败

**缓解措施**：
1. 提供清晰的错误消息（ACTION_ERROR_DEVICE_ACCESS）
2. 在文档中说明权限要求（chmod 666 /dev/video*）
3. 在日志中记录详细的 errno 信息

### 风险 3：性能影响
**影响**：设备路径验证可能增加连接延迟

**分析**：
- 路径验证开销 < 1ms（字符串匹配 + access() 系统调用）
- 相比现有 PID/VID 查找（遍历文件系统），性能实际提升
- 对整体连接时间影响可忽略（< 1%）

## Migration Plan

### 阶段 1：实现新功能（本次变更）
1. 添加 `connectByPath()` API
2. 重构设备打开逻辑
3. 更新示例应用展示两种方式

### 阶段 2：推广新 API（未来）
1. 在文档中推荐优先使用 `connectByPath()`（更简单、更通用）
2. 保持 `connect(pid, vid)` 用于 USB 设备的便捷场景

### 阶段 3：长期维护
1. 继续维护两种 API，无废弃计划
2. 可能在未来添加设备枚举 API（`listDevices()`）

### 回滚计划
如果新功能出现严重问题：
1. 删除新增的 `connectByPath()` 相关代码
2. 恢复 `connect(pid, vid)` 的原始实现（从重构前恢复）
3. 不影响现有用户（因为是新增功能）

## Open Questions

### Q1: 是否需要支持相对路径？
**讨论**：用户可能输入 `video0` 而非 `/dev/video0`

**决定**：❌ 不支持相对路径
- 理由：绝对路径更明确，避免歧义
- 用户可以在应用层添加路径前缀处理

### Q2: 是否需要设备能力检查？
**讨论**：在 `connectByPath()` 时检查设备是否支持视频捕获

**决定**：✅ 需要检查
- 使用现有的 `VIDIOC_QUERYCAP` ioctl
- 验证设备具有 `V4L2_CAP_VIDEO_CAPTURE` 能力
- 理由：早期失败，提供清晰错误信息

### Q3: 是否支持符号链接？
**讨论**：用户可能创建符号链接 `/dev/camera -> /dev/video0`

**决定**：✅ 支持符号链接
- 使用 `realpath()` 解析符号链接
- 验证解析后的路径仍为 `/dev/video*`
- 理由：增强灵活性，常见用例

## Implementation Notes

### 代码规范遵循
1. 文件长度：新增逻辑较少，不会超过 200-300 行限制
2. 命名规范：使用 camelCase（`connectByPath`），与现有代码一致
3. 错误处理：使用现有的 `ActionInfo` 枚举模式
4. 日志规范：使用 `LOGD/LOGE/LOGW` 宏，与现有代码一致

### 测试策略
1. 单元测试：Native 层的设备打开逻辑
2. 集成测试：完整的连接 -> 预览 -> 录制流程
3. 错误路径测试：无效路径、权限不足、设备不存在等
4. 兼容性测试：确保 PID/VID 方式仍正常工作
