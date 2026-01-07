# Project Context

## Purpose
Android V4L2 Camera 是一个基于 V4L2 (Video4Linux2) 协议的 Android 相机 SDK 项目。

**核心目标：**
- 封装基于 V4L2 协议的 Android 相机功能
- 支持双摄像头 30 FPS 采集
- 支持多种图像格式（MJPEG、YUYV）的采集和渲染
- 提供视频录制和编码功能
- 实现高性能图像处理和渲染

**主要功能：**
- 支持设置分辨率和原始图像采集格式（MJPEG、YUYV）
- 使用 NdkMediaCodec 进行 MJPEG 硬件解码
- 使用 OpenGL ES 渲染 YUYV、NV12、NV21、DEPTH 等多种图像格式
- 支持视频录制和编码到 MP4
- 支持美颜滤镜功能

## Tech Stack

### Android 平台
- **Android SDK**: API 21-29 (minSdk: 21, targetSdk: 29, compileSdk: 29)
- **Build Tools**: 29.0.3
- **Gradle**: Gradle 构建系统
- **AndroidX**: JetPack 组件库

### 编程语言
- **Java**: JDK 1.8 (主要业务逻辑)
- **C/C++**: Native 层实现 (JNI/NDK)
- **CMake**: 3.10.2 (Native 构建工具)

### 核心技术
- **V4L2**: Video4Linux2 协议用于相机访问
- **OpenGL ES**: 用于高性能图像渲染
- **NdkMediaCodec**: Android 硬件编解码器
- **JNI**: Java Native Interface 桥接层

### 第三方库
- **libjpeg-turbo**: JPEG 图像编解码库
- **libyuv**: Google 的 YUV 图像处理库
- **LeakCanary**: 内存泄漏检测工具 (debug)

### 架构类型
- ARM64-v8a (仅支持 64 位架构)

## Project Conventions

### Code Style
- **文件长度限制**: 避免单个文件超过 200-300 行代码，需及时重构
- **命名规范**:
  - Java 类使用大驼峰命名（PascalCase）
  - 方法和变量使用小驼峰命名（camelCase）
  - 常量使用全大写下划线分隔（UPPER_SNAKE_CASE）
- **代码整洁**: 保持代码库整洁有序，避免重复代码
- **注释规范**: 仅在逻辑不明显的地方添加注释，代码应自解释
- **Imports**: 避免未使用的导入

### Architecture Patterns
- **模块化设计**:
  - `sdk_v4l2_camera`: 核心 SDK 库模块
  - `sample`: 示例应用模块
- **分层架构**:
  - Java 层: 提供 API 接口和业务逻辑（CameraAPI, CameraView）
  - JNI 层: Java 和 Native 代码桥接（NativeAPI）
  - Native 层: C++ 实现核心功能（V4L2 相机操作、图像处理）
- **渲染模式**: 使用接口模式（IRender）支持多种渲染器（RenderCommon, RenderDepth, RenderBeauty）
- **回调机制**: 使用回调接口（IFrameCallback, ISurfaceCallback）处理异步事件
- **工厂模式**: 使用 DecoderFactory 创建解码器实例

### Testing Strategy
- **单元测试**: 使用 JUnit 4.13
- **UI 测试**: 使用 AndroidX Test (Espresso)
- **测试运行器**: AndroidJUnitRunner
- **测试数据**: 仅在测试中使用 mock 数据，不在 dev/prod 环境中使用假数据
- **覆盖要求**: 对所有主要功能编写完整的测试

### Git Workflow
- **主分支**: `main`
- **提交信息**: 简洁明了，描述变更的原因而非具体内容
- **最近提交历史**:
  - 代码优化
  - 解决色差问题
  - 适配编码录制
  - 增加签名文件
- **版本管理**: 使用构建时间戳作为 versionCode (yyMMddHH)

## Domain Context

### V4L2 协议
V4L2 (Video4Linux version 2) 是 Linux 系统中用于视频设备的 API 标准。在 Android 设备上需要特殊权限才能访问 `/dev/video*` 设备节点。

### 权限要求
在 Android 设备上运行时需要授予设备节点读写权限：
```bash
adb shell
su
chmod 666 /dev/video*
```

### 图像格式
- **MJPEG**: Motion JPEG，使用 JPEG 压缩的视频流
- **YUYV**: YUV 4:2:2 格式
- **NV12/NV21**: YUV 4:2:0 格式
- **DEPTH**: 深度图像格式

### 硬件解码
使用 NdkMediaCodec 进行 MJPEG 硬件解码。如果设备不支持硬件解码，建议使用 libjpeg-turbo 作为软件解码方案。

## Important Constraints

### 技术约束
- **架构限制**: 仅支持 ARM64-v8a 架构
- **API 级别**: 最低支持 Android 5.0 (API 21)
- **设备权限**: 需要 root 权限才能访问 V4L2 设备节点
- **性能要求**: 双摄像头需达到 30 FPS

### 开发约束
- **构建工具**: Android Studio 4.0+, CMake 3.10.2
- **环境管理**: 不可覆盖 .env 文件，需先确认
- **代码变更**: 仅修改与任务相关的代码，避免不必要的重构
- **向后兼容**: 避免使用临时兼容性补丁，直接修改代码

### SELinux 约束
项目包含 SELinux 配置文件（`selinux_fix.te`, `file_contexts_fix`），用于处理设备访问权限问题。

## External Dependencies

### 第三方库
- **libjpeg-turbo**: https://github.com/libjpeg-turbo/libjpeg-turbo
  - 用途: JPEG 图像编解码
- **libyuv**: https://chromium.googlesource.com/external/libyuv
  - 用途: YUV 图像格式转换和处理

### Android 系统库
- **mediandk**: Android Media NDK 库（编解码）
- **android**: Android 核心 NDK 库
- **log**: Android 日志库

### 设备依赖
- 设备必须支持 V4L2 协议
- 设备需要有可访问的 `/dev/video*` 节点
- 建议设备支持硬件 MJPEG 解码

### 签名文件
项目包含 `sign/` 目录用于 APK 签名
