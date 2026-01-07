# Android Studio Native 调试配置指南

## 问题诊断
如果两台设备都无法使用 Native 调试，很可能是 Android Studio 配置问题，而非设备问题。

## 解决方案

### 1. 配置 Run/Debug Configuration

#### 步骤 1：编辑配置
1. 点击 Run → Edit Configurations...
2. 选择您的 app 配置

#### 步骤 2：配置 Debugger 选项卡
- **Debug type**: 选择 `Dual (Java + Native)`
- **Symbol Directories**: 点击 `+` 添加以下路径：
  ```
  /Users/tubao/code/private/tripod/CameraDemo/android_camera_v4l2/sdk_v4l2_camera/build/intermediates/cxx/Debug/4l5b2p2w/obj/arm64-v8a
  ```
- **LLDB Startup Commands**: 添加：
  ```
  settings set target.debug-file-search-paths /Users/tubao/code/private/tripod/CameraDemo/android_camera_v4l2/sdk_v4l2_camera/build/intermediates/cxx/Debug/4l5b2p2w/obj/arm64-v8a
  ```

#### 步骤 3：配置 General 选项卡
- **Installation Options**:
  - ☑ Deploy APK from app bundle
  - ☑ Install Flags: `-r`

### 2. 检查 Android Studio LLDB 插件

1. File → Settings (macOS: Preferences)
2. Plugins → 搜索 "LLDB"
3. 确保 "Android Native Development" 插件已启用
4. 如果禁用，启用后重启 Android Studio

### 3. 重启 ADB 和 Android Studio

```bash
# 重启 ADB
adb kill-server
adb start-server
adb devices

# 然后重启 Android Studio
```

### 4. 使用 LLDB 日志诊断

在 Run Configuration 的 Debugger 选项卡：
- **LLDB Startup Commands** 添加：
  ```
  log enable lldb all
  log enable gdb-remote all
  ```

启动调试后查看 LLDB 日志：
- View → Tool Windows → Debug
- 在 Console 标签查看 LLDB 输出

### 5. 常见问题排查

#### 问题 A：断点显示为灰色（未生效）
**原因**: LLDB 找不到调试符号
**解决**:
- 确认 Symbol Directories 配置正确
- 检查 `.so` 文件路径：
  ```bash
  find sdk_v4l2_camera/build -name "libcamera.so" -type f
  ```
- 确保使用 Debug 构建（不是 Release）

#### 问题 B：应用卡在 "Waiting for debugger"
**原因**: LLDB 无法连接
**解决**:
- 检查设备 USB 连接
- 尝试 `adb kill-server && adb start-server`
- 检查防火墙是否阻止 ADB 端口（5037, 5554-5585）

#### 问题 C：LLDB 报错 "Unable to start lldb-server"
**原因**: 设备固件限制
**解决**:
- 方案 1: 使用 `jniDebuggable false` + 日志调试
- 方案 2: 使用 root 设备
- 方案 3: 使用模拟器（推荐用于开发）

### 6. 验证配置

#### 测试步骤：
1. 在 `CameraAPI.cpp` 的 `openDevice()` 方法第一行设置断点
2. 点击 Debug 按钮（绿色虫子）
3. 应用启动后，点击"连接设备"按钮
4. 断点应该触发

#### 预期日志：
```
Connected to the target VM, address: 'localhost:xxxxx', transport: 'socket'
Attaching to process...
Process attached
```

### 7. 备选方案：使用 Android Emulator

如果物理设备都不支持 Native 调试：

1. 创建 AVD (Android Virtual Device)：
   - Tools → Device Manager → Create Device
   - 选择 Pixel 系列
   - System Image: **下载带 Google APIs 的版本**
   - 确保选择 arm64-v8a 架构

2. 模拟器完全支持 LLDB 调试，无任何限制

### 8. 最终确认清单

- [ ] NDK 版本已指定（26.3.11579264）
- [ ] `jniDebuggable true` 已设置
- [ ] `doNotStrip` 已配置（保留符号）
- [ ] Symbol Directories 已添加
- [ ] Debug type 设置为 Dual
- [ ] `.lldbinit` 文件已创建
- [ ] 重新构建 Debug 版本
- [ ] 重启 ADB 和 Android Studio

## 实用建议

**如果物理设备仍然无法调试：**
1. **主力开发**: 使用模拟器进行 Native 代码开发和调试
2. **功能测试**: 使用物理设备测试实际硬件功能（如相机）
3. **日志调试**: 在物理设备上使用 LOGD/LOGE 调试
4. **混合方式**:
   - 在模拟器上调试和验证 Native 逻辑
   - 在物理设备上测试硬件集成
   - 使用日志输出关键变量值

## 当前项目已完成的配置

✓ NDK 版本指定
✓ Debug 构建类型配置
✓ Symbol 保留配置
✓ .lldbinit 配置文件

**下一步：** 按照上述步骤在 Android Studio 中配置 Run Configuration
