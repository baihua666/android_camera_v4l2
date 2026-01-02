# 视频录制调试指南

## 当前状态

录制的视频文件仍然为空（3.2KB），说明没有实际的视频数据被写入。

## 诊断步骤

### 步骤 1: 重新安装应用

```bash
# 确保使用最新的代码
./gradlew :sample:assembleDebug

# 安装到设备
adb install -r sample/build/outputs/apk/debug/sample-debug.apk
```

### 步骤 2: 清除旧日志并开始监控

```bash
# 清除旧日志
adb logcat -c

# 监控 VideoRecorder 和 MainActivity 的日志
adb logcat -s VideoRecorder:D MainActivity:D
```

### 步骤 3: 执行录制操作

在应用中按以下顺序操作：
1. 选择 USB 设备
2. 点击 "create"
3. 点击 "start"
4. **等待 2-3 秒确保预览正常**
5. 点击 "Start Record"
6. **等待至少 10 秒**
7. 点击 "Stop Record"

### 步骤 4: 分析日志

#### 正常流程应该看到的日志：

```
D/VideoRecorder: Selected codec: OMX.qcom.video.encoder.avc
D/VideoRecorder: Selected color format: 21
D/VideoRecorder: Recording started: /path/to/video.mp4
D/VideoRecorder: Video format: 640x480 @ 15fps
D/VideoRecorder: First frame received: size=XXXXX bytes
D/VideoRecorder: First 10 bytes: FF D8 FF E0 ...
D/VideoRecorder: Bitmap decoded: 640x480, config=ARGB_8888
D/VideoRecorder: YUV data size: 460800 bytes
D/VideoRecorder: Input buffer capacity: 460800
D/VideoRecorder: First frame queued to encoder, pts=12345
D/VideoRecorder: Output format changed: {mime=video/avc, ...}
D/VideoRecorder: Muxer started with track: 0
D/VideoRecorder: Processed frame: 10
D/VideoRecorder: Wrote sample: size=15234, pts=2000000
D/VideoRecorder: Processed frame: 20
...
D/VideoRecorder: Draining encoder...
D/VideoRecorder: Recording stopped. Total frames: 150
```

#### 可能的错误情况：

**情况 1: 没有收到帧数据**
```
W/VideoRecorder: writeFrame called but not recording or codec is null
```
**原因**: frameCallback 没有被正确设置或相机没有启动
**解决**: 确保先点击 "start" 按钮

**情况 2: MJPEG 解码失败**
```
E/VideoRecorder: !!! Failed to decode MJPEG frame 0, data size: XXXXX
D/VideoRecorder: Saved debug frame to: /sdcard/debug_frame_0.jpg
```
**原因**: 相机输出的不是标准 MJPEG 格式
**解决**: 检查 /sdcard/debug_frame_0.jpg 文件

**情况 3: 编码器无法获取输入缓冲区**
```
W/VideoRecorder: !!! No input buffer available, index=-1
```
**原因**: 编码器配置错误或过载
**解决**: 检查颜色格式是否支持

**情况 4: Muxer 没有启动**
```
W/VideoRecorder: Muxer hasn't started, dropping frame
```
**原因**: MediaCodec 没有输出格式变更事件
**解决**: 检查编码器配置

### 步骤 5: 收集完整日志

```bash
# 保存完整日志到文件
adb logcat -d > /Users/tubao/temp/logcat.txt
```

将 `logcat.txt` 文件发送给我，我可以分析具体问题。

### 步骤 6: 检查调试帧

如果 MJPEG 解码失败，会保存调试帧：

```bash
# 下载调试帧
adb pull /sdcard/debug_frame_0.jpg /Users/tubao/temp/

# 查看图片
open /Users/tubao/temp/debug_frame_0.jpg
```

如果图片能正常打开，说明 MJPEG 数据是有效的，问题在别处。

## 可能的问题和解决方案

### 问题 1: frameCallback 没有被调用

**检查**: MainActivity.java 中的 frameCallback

```java
private final IFrameCallback frameCallback = frame -> {
    // 如果正在录制，将帧数据传递给 VideoRecorder
    if (videoRecorder != null && videoRecorder.isRecording()) {
        videoRecorder.writeFrame(frame);
    }
};
```

**确认**: 在 start() 方法中设置了 callback

```java
this.camera.setFrameCallback(frameCallback);
```

### 问题 2: 相机格式不是 MJPEG

**检查**: create() 方法中的格式设置

```java
camera.setFrameSize(width, height, CameraAPI.FRAME_FORMAT_MJPEG);
```

**解决**: 确保相机支持 MJPEG 格式

### 问题 3: 编码器不支持当前颜色格式

**现象**: 日志显示 "No supported color format found"

**解决**:
1. 查看日志中的 "Available formats:"
2. 手动添加支持的格式到 preferredFormats 数组

### 问题 4: 内存不足

**现象**: OutOfMemoryError 或应用崩溃

**解决**:
1. 降低视频分辨率
2. 降低帧率
3. 增加帧跳过间隔

## 快速测试命令集合

### 一键诊断脚本

创建文件 `/Users/tubao/temp/debug_record.sh`:

```bash
#!/bin/bash

echo "===== 清除旧日志 ====="
adb logcat -c

echo "===== 等待录制操作... ====="
echo "请在应用中进行录制操作（等待至少10秒）"
echo "按回车键保存日志..."
read

echo "===== 保存日志 ====="
adb logcat -d > /Users/tubao/temp/logcat_full.txt
adb logcat -d -s VideoRecorder:D MainActivity:D > /Users/tubao/temp/logcat_filtered.txt

echo "===== 下载视频文件 ====="
adb pull /sdcard/Android/data/com.hsj.sample/files/videos/ /Users/tubao/temp/videos/

echo "===== 下载调试帧 ====="
adb pull /sdcard/debug_frame_0.jpg /Users/tubao/temp/ 2>/dev/null || echo "无调试帧"

echo "===== 分析视频文件 ====="
for f in /Users/tubao/temp/videos/*.mp4; do
    echo "--- $f ---"
    ls -lh "$f"
    ffprobe -v error -show_format -show_streams "$f" | grep -E "(codec_name|width|height|duration|size)"
done

echo "===== 完成 ====="
echo "日志已保存到:"
echo "  - /Users/tubao/temp/logcat_full.txt"
echo "  - /Users/tubao/temp/logcat_filtered.txt"
```

运行脚本：

```bash
chmod +x /Users/tubao/temp/debug_record.sh
/Users/tubao/temp/debug_record.sh
```

## 关键检查点

请在日志中查找以下关键信息：

- [ ] `Selected codec:` - 编码器名称
- [ ] `Selected color format:` - 颜色格式编号
- [ ] `Recording started:` - 录制开始
- [ ] `First frame received:` - 收到第一帧
- [ ] `First 10 bytes:` - 帧数据头部（应该是 `FF D8 FF E0` 开始的 JPEG）
- [ ] `Bitmap decoded:` - Bitmap 解码成功
- [ ] `YUV data size:` - YUV 数据大小
- [ ] `First frame queued to encoder` - 第一帧进入编码器
- [ ] `Output format changed:` - 编码器输出格式确定
- [ ] `Muxer started with track:` - Muxer 启动
- [ ] `Wrote sample:` - 实际写入数据
- [ ] `Processed frame:` - 处理的帧数

## 下一步

1. 按照上述步骤进行测试
2. 收集完整日志
3. 检查是否有错误信息
4. 将关键日志部分发给我

如果看到错误，请特别关注带 `!!!` 标记的日志行，这些是严重错误。

---

**创建日期**: 2026-01-01
**用于调试**: 视频录制为空问题
