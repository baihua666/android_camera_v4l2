# 视频录制功能说明

本文档说明了为 Android Camera V4L2 sample 应用添加的视频录制功能。

## 功能概述

新增了完整的视频录制和保存功能，可以将 V4L2 相机的实时视频流录制为 MP4 文件。

## 主要特性

- ✅ 实时视频录制（H.264 编码）
- ✅ 自动保存为 MP4 格式
- ✅ 支持 MJPEG 输入格式
- ✅ 自动生成带时间戳的文件名
- ✅ 简单的开始/停止录制控制
- ✅ 录制状态指示（按钮颜色和启用状态）

## 技术实现

### 架构设计

```
相机帧数据 (MJPEG)
    ↓
IFrameCallback
    ↓
VideoRecorder
    ↓
MJPEG 解码 → Bitmap
    ↓
Bitmap → YUV420 转换
    ↓
MediaCodec (H.264 编码)
    ↓
MediaMuxer (MP4 封装)
    ↓
保存到文件
```

### 核心组件

#### 1. VideoRecorder 类
**位置**: `sample/src/main/java/com/hsj/sample/VideoRecorder.java`

**功能**:
- 使用 MediaCodec 进行 H.264 视频编码
- 使用 MediaMuxer 封装为 MP4 格式
- 将 MJPEG 帧转码为 H.264
- 管理编码器和复用器的生命周期

**主要方法**:
```java
// 开始录制
public boolean start()

// 写入一帧数据
public void writeFrame(ByteBuffer frameData)

// 停止录制
public void stop()

// 检查录制状态
public boolean isRecording()
```

**编码参数**:
- 视频编码: H.264 (AVC)
- 输出格式: MP4 (MPEG-4)
- 帧率: 30 FPS
- 比特率: 2 Mbps
- I 帧间隔: 1 秒
- 颜色格式: YUV420

#### 2. MainActivity 增强

**新增成员变量**:
```java
private VideoRecorder videoRecorder;  // 录制器实例
private Button btnStartRecord;        // 开始录制按钮
private Button btnStopRecord;         // 停止录制按钮
private int videoWidth;               // 视频宽度
private int videoHeight;              // 视频高度
```

**新增方法**:
- `startRecording()` - 开始录制视频
- `stopRecording()` - 停止录制视频

**修改的方法**:
- `create()` - 保存视频尺寸
- `frameCallback` - 将帧数据传递给 VideoRecorder

### UI 更新

#### 布局文件
**文件**: `sample/src/main/res/layout/activity_main.xml`

**变更**:
- 添加了第二行按钮布局
- 新增 "Start Record" 按钮（绿色）
- 新增 "Stop Record" 按钮（红色，初始禁用）

```xml
<LinearLayout>  <!-- 垂直布局 -->
    <!-- 第一行: create, start, stop, destroy -->
    <LinearLayout android:orientation="horizontal">
        ...
    </LinearLayout>

    <!-- 第二行: Start Record, Stop Record -->
    <LinearLayout android:orientation="horizontal">
        <Button android:id="@+id/btn_start_record" ... />
        <Button android:id="@+id/btn_stop_record" ... />
    </LinearLayout>
</LinearLayout>
```

#### 字符串资源
**文件**: `sample/src/main/res/values/strings.xml`

**新增**:
```xml
<string name="txt_start_record">Start Record</string>
<string name="txt_stop_record">Stop Record</string>
```

## 使用方法

### 基本流程

1. **选择 USB 设备**
   - 点击菜单按钮
   - 选择一个 USB 相机设备

2. **创建相机连接**
   - 点击 "create" 按钮
   - 等待相机初始化完成

3. **开始预览**
   - 点击 "start" 按钮
   - 查看相机预览画面

4. **开始录制**
   - 点击绿色的 "Start Record" 按钮
   - 提示 "Recording started: /path/to/video.mp4"
   - "Start Record" 按钮变为禁用
   - "Stop Record" 按钮变为启用

5. **停止录制**
   - 点击红色的 "Stop Record" 按钮
   - 提示 "Recording stopped"
   - 按钮状态恢复

6. **停止预览**
   - 点击 "stop" 按钮

7. **销毁连接**
   - 点击 "destroy" 按钮

### 录制文件位置

录制的视频文件保存在：
```
/storage/emulated/0/Android/data/com.hsj.sample/files/videos/video_YYYYMMDD_HHmmss.mp4
```

示例:
```
/sdcard/Android/data/com.hsj.sample/files/videos/video_20260101_153045.mp4
```

### 访问录制文件

#### 方法 1: 使用 ADB
```bash
# 查看录制的视频列表
adb shell ls /sdcard/Android/data/com.hsj.sample/files/videos/

# 下载视频到电脑
adb pull /sdcard/Android/data/com.hsj.sample/files/videos/video_20260101_153045.mp4 .
```

#### 方法 2: 使用文件管理器
1. 在 Android 设备上打开文件管理器
2. 导航到 `Android/data/com.hsj.sample/files/videos/`
3. 找到录制的 MP4 文件

#### 方法 3: 使用 Android Studio
1. 打开 Device File Explorer
2. 导航到 `/data/data/com.hsj.sample/files/videos/`
3. 右键点击文件 → Save As

## 权限说明

应用已经配置了必要的存储权限：

```xml
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"/>
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"/>
```

**注意**:
- Android 6.0+ 需要运行时权限（当前示例未实现）
- 应用使用 `getExternalFilesDir()` 不需要额外权限

## 代码示例

### 手动控制录制

```java
// 开始录制
VideoRecorder recorder = new VideoRecorder(width, height, outputPath);
if (recorder.start()) {
    Log.d(TAG, "Recording started");
}

// 在帧回调中写入数据
camera.setFrameCallback(frameData -> {
    if (recorder.isRecording()) {
        recorder.writeFrame(frameData);
    }
});

// 停止录制
recorder.stop();
```

### 自定义输出路径

```java
private void startRecording() {
    // 自定义输出目录
    File customDir = new File(Environment.getExternalStorageDirectory(), "MyVideos");
    if (!customDir.exists()) {
        customDir.mkdirs();
    }

    String outputPath = new File(customDir, "my_video.mp4").getAbsolutePath();
    videoRecorder = new VideoRecorder(videoWidth, videoHeight, outputPath);
    videoRecorder.start();
}
```

## 性能考虑

### 编码性能
- 使用硬件加速编码器（如果可用）
- 帧率: 30 FPS
- 比特率: 2 Mbps（可根据需要调整）

### 内存管理
- 每一帧都会创建临时 Bitmap 对象
- 使用后立即回收: `bitmap.recycle()`
- 避免内存泄漏

### 建议优化
1. **降低分辨率**: 使用较小的视频尺寸可以减少处理负担
2. **调整比特率**: 根据需求调整 `BIT_RATE` 常量
3. **使用缓冲**: 考虑使用帧缓冲队列来平滑编码

## 故障排除

### 问题 1: 录制失败

**症状**: 点击 "Start Record" 后显示 "Failed to start recording"

**可能原因**:
- 存储空间不足
- 没有创建相机连接
- 视频尺寸不支持

**解决方案**:
- 检查存储空间
- 先点击 "create" 和 "start" 按钮
- 查看 Logcat 日志获取详细错误信息

### 问题 2: 视频文件无法播放

**症状**: 生成的 MP4 文件无法在播放器中打开

**可能原因**:
- 录制时间太短（少于 1 秒）
- 录制过程中崩溃
- MediaMuxer 未正确停止

**解决方案**:
- 至少录制 2-3 秒
- 确保调用 `stop()` 方法
- 检查文件大小是否为 0

### 问题 3: 帧率不稳定

**症状**: 录制的视频播放时卡顿

**可能原因**:
- 相机帧率不稳定
- 编码器性能不足
- MJPEG 解码耗时

**解决方案**:
- 降低视频分辨率
- 调整比特率
- 使用更强大的设备测试

### 问题 4: 找不到录制文件

**症状**: 录制完成但找不到 MP4 文件

**可能原因**:
- 文件路径错误
- 权限不足
- 文件被系统清理

**解决方案**:
- 查看 Toast 提示的完整路径
- 使用 ADB 查找文件
- 检查应用数据目录

## 日志输出

### 开始录制
```
D/MainActivity: Recording started: /sdcard/Android/data/com.hsj.sample/files/videos/video_20260101_153045.mp4
D/VideoRecorder: Recording started: /sdcard/Android/data/com.hsj.sample/files/videos/video_20260101_153045.mp4
D/VideoRecorder: Muxer started
```

### 停止录制
```
D/VideoRecorder: Recording stopped. Total frames: 450
D/MainActivity: Recording stopped
```

### 错误日志
```
E/VideoRecorder: Failed to start recording
E/VideoRecorder: Error writing frame
E/VideoRecorder: Error encoding frame
```

## 扩展建议

### 1. 添加运行时权限请求
```java
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
    requestPermissions(new String[]{
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    }, REQUEST_CODE);
}
```

### 2. 添加录制时长显示
```java
private long recordingStartTime;
private Handler handler = new Handler();
private Runnable updateTimerRunnable = new Runnable() {
    @Override
    public void run() {
        long elapsed = System.currentTimeMillis() - recordingStartTime;
        updateTimerDisplay(elapsed);
        handler.postDelayed(this, 1000);
    }
};
```

### 3. 支持暂停/恢复录制
```java
public void pause() {
    isRecording = false;
}

public void resume() {
    isRecording = true;
}
```

### 4. 添加音频录制
需要使用 MediaRecorder 或 AudioRecord + 音频编码器

### 5. 添加录制参数配置
允许用户选择分辨率、比特率、帧率等参数

## 性能数据

### 测试配置
- 分辨率: 640x480
- 帧率: 30 FPS
- 比特率: 2 Mbps
- 格式: MJPEG → H.264

### 预期性能
- CPU 使用率: 30-50%
- 内存占用: 增加 ~50MB
- 文件大小: ~15MB/分钟
- 延迟: < 100ms

## 技术细节

### MJPEG 到 H.264 转换流程

1. **接收 MJPEG 帧** (ByteBuffer)
2. **解码为 Bitmap** (BitmapFactory)
3. **提取像素数据** (getPixels → int[] ARGB)
4. **转换为 YUV420** (RGB → YUV 颜色空间转换)
5. **输入到编码器** (MediaCodec)
6. **编码为 H.264** (硬件/软件编码)
7. **写入 MP4 文件** (MediaMuxer)

### YUV420 转换公式
```
Y = (66*R + 129*G + 25*B + 128) >> 8 + 16
U = (-38*R - 74*G + 112*B + 128) >> 8 + 128
V = (112*R - 94*G - 18*B + 128) >> 8 + 128
```

## 相关文件清单

### 新增文件
- `sample/src/main/java/com/hsj/sample/VideoRecorder.java` - 视频录制类

### 修改文件
- `sample/src/main/java/com/hsj/sample/MainActivity.java` - 添加录制控制逻辑
- `sample/src/main/res/layout/activity_main.xml` - 添加录制按钮
- `sample/src/main/res/values/strings.xml` - 添加字符串资源

### 配置文件
- `sample/src/main/AndroidManifest.xml` - 已有必要权限

## 版本历史

### v1.0.0 (2026-01-01)
- ✅ 初始实现
- ✅ 支持 MJPEG 输入
- ✅ H.264 编码输出
- ✅ MP4 格式保存
- ✅ 基本的开始/停止控制

## 常见问题 (FAQ)

**Q: 支持哪些视频格式？**
A: 输入支持 MJPEG，输出为 H.264/MP4。

**Q: 可以录制音频吗？**
A: 当前版本不支持音频录制，只录制视频。

**Q: 最大录制时长是多少？**
A: 没有硬性限制，受存储空间限制。

**Q: 录制会影响预览吗？**
A: 不会，预览和录制使用不同的数据流。

**Q: 可以改变视频质量吗？**
A: 可以，修改 `VideoRecorder.java` 中的 `BIT_RATE` 常量。

**Q: 支持哪些 Android 版本？**
A: API 21 (Android 5.0) 及以上版本。

## 联系方式

如有问题或建议，请查看主项目文档或提交 Issue。

---

**创建日期**: 2026-01-01
**版本**: 1.0.0
**作者**: HSJ
