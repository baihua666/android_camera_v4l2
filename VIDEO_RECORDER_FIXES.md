# VideoRecorder 修复说明

## 问题诊断

### 原始问题
使用 ffmpeg 和 ffprobe 分析录制的视频文件发现：

```bash
$ ffprobe video_20251113_072009.mp4

[mov,mp4,m4a,3gp,3g2,mj2] Could not find codec parameters for stream 0 (Video: none, none): unknown codec

Stream #0:0: Video: none, none
  codec_name=unknown
  width=0
  height=0
  duration=0.000000
  size=3232 bytes
```

**问题分析**：
- ❌ 文件只有 MP4 容器结构（3.2KB），没有实际视频数据
- ❌ 没有正确的编码器信息（codec_name=unknown）
- ❌ 视频尺寸为 0
- ❌ 缺少 mdat atom（媒体数据）

## 根本原因

### 1. **颜色格式不匹配**
```java
// 原代码 - 问题
format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
```

**问题**：`COLOR_FormatYUV420Flexible` 不是所有设备都支持，会导致编码器配置失败。

### 2. **没有选择合适的编码器**
原代码直接使用 `createEncoderByType()`，没有检查编码器是否支持指定的颜色格式。

### 3. **YUV 数据格式可能不正确**
没有区分不同的 YUV 格式（NV12, NV21, I420），使用了统一的转换方法。

### 4. **没有详细的错误日志**
无法定位具体哪一步失败。

## 修复方案

### 1. ✅ 动态选择颜色格式

```java
/**
 * 选择编码器支持的颜色格式
 */
private int selectColorFormat(String mimeType) {
    MediaCodecInfo codecInfo = selectCodec(mimeType);
    MediaCodecInfo.CodecCapabilities capabilities =
            codecInfo.getCapabilitiesForType(mimeType);

    // 优先使用的颜色格式（按兼容性排序）
    int[] preferredFormats = {
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,    // NV12 (最常见)
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,        // I420
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar, // NV21
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible       // Flexible
    };

    for (int preferredFormat : preferredFormats) {
        for (int format : capabilities.colorFormats) {
            if (format == preferredFormat) {
                Log.d(TAG, "Selected color format: " + format);
                return format;
            }
        }
    }

    return -1; // 没有找到支持的格式
}
```

### 2. ✅ 选择合适的编码器

```java
/**
 * 选择编码器
 */
private MediaCodecInfo selectCodec(String mimeType) {
    int numCodecs = MediaCodecList.getCodecCount();
    for (int i = 0; i < numCodecs; i++) {
        MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
        if (!codecInfo.isEncoder()) {
            continue;
        }

        String[] types = codecInfo.getSupportedTypes();
        for (String type : types) {
            if (type.equalsIgnoreCase(mimeType)) {
                Log.d(TAG, "Selected codec: " + codecInfo.getName());
                return codecInfo;
            }
        }
    }
    return null;
}
```

### 3. ✅ 根据颜色格式转换 YUV

```java
/**
 * 将 Bitmap 转换为 YUV 格式
 */
private byte[] convertBitmapToYUV(Bitmap bitmap, int colorFormat) {
    int w = bitmap.getWidth();
    int h = bitmap.getHeight();

    int[] argb = new int[w * h];
    bitmap.getPixels(argb, 0, w, 0, 0, w, h);

    byte[] yuv;

    if (colorFormat == COLOR_FormatYUV420SemiPlanar ||
        colorFormat == COLOR_FormatYUV420PackedSemiPlanar) {
        // NV12 or NV21
        yuv = new byte[w * h * 3 / 2];
        encodeYUV420SP(yuv, argb, w, h);
    } else if (colorFormat == COLOR_FormatYUV420Planar) {
        // I420
        yuv = new byte[w * h * 3 / 2];
        encodeYUV420P(yuv, argb, w, h);
    } else {
        // Flexible - 默认使用 NV12
        yuv = new byte[w * h * 3 / 2];
        encodeYUV420SP(yuv, argb, w, h);
    }

    return yuv;
}
```

### 4. ✅ 改进的 I420 转换（修复边界问题）

```java
/**
 * RGB 转 YUV420P (I420)
 */
private void encodeYUV420P(byte[] yuv420p, int[] argb, int width, int height) {
    final int frameSize = width * height;
    final int uvSize = frameSize / 4;

    int yIndex = 0;
    int uIndex = frameSize;
    int vIndex = frameSize + uvSize;

    // ... 转换逻辑 ...

    if (j % 2 == 0 && index % 2 == 0) {
        // 添加边界检查
        if (uIndex < frameSize + uvSize) {
            yuv420p[uIndex++] = (byte) ((U < 0) ? 0 : ((U > 255) ? 255 : U));
        }
        if (vIndex < yuv420p.length) {
            yuv420p[vIndex++] = (byte) ((V < 0) ? 0 : ((V > 255) ? 255 : V));
        }
    }
}
```

### 5. ✅ 增强的日志输出

```java
// 启动时的日志
Log.d(TAG, "Recording started: " + outputPath);
Log.d(TAG, "Video format: " + width + "x" + height + " @ " + FRAME_RATE + "fps");
Log.d(TAG, "Using color format: " + colorFormat);

// 编码过程中的日志
Log.d(TAG, "Output format changed: " + newFormat);
Log.d(TAG, "Muxer started with track: " + videoTrackIndex);
Log.d(TAG, "Wrote sample: size=" + bufferInfo.size + ", pts=" + bufferInfo.presentationTimeUs);

// 停止时的日志
Log.d(TAG, "Draining encoder...");
Log.d(TAG, "Recording stopped. Total frames: " + frameIndex);
```

### 6. ✅ 降低帧率和比特率

```java
private static final int FRAME_RATE = 15;  // 从 30 降低到 15
private static final int BIT_RATE = 1000000; // 从 2Mbps 降低到 1Mbps
```

**原因**：降低参数可以提高稳定性，减少编码失败的可能性。

### 7. ✅ 帧率控制

```java
// 控制帧率 - 只处理部分帧
if (frameIndex % 2 != 0) { // 每隔一帧处理一次
    frameIndex++;
    return;
}
```

**原因**：避免编码器过载，确保每一帧都能正确编码。

## 测试验证

### 1. 查看 Logcat 日志

```bash
adb logcat -s VideoRecorder:D
```

**期望输出**：
```
D/VideoRecorder: Selected codec: OMX.qcom.video.encoder.avc
D/VideoRecorder: Selected color format: 21 (NV12)
D/VideoRecorder: Recording started: /path/to/video.mp4
D/VideoRecorder: Video format: 640x480 @ 15fps
D/VideoRecorder: Output format changed: {mime=video/avc, ...}
D/VideoRecorder: Muxer started with track: 0
D/VideoRecorder: Encoded frame: 30
D/VideoRecorder: Wrote sample: size=15234, pts=2000000
...
D/VideoRecorder: Draining encoder...
D/VideoRecorder: Recording stopped. Total frames: 150
```

### 2. 验证视频文件

```bash
# 检查文件大小（应该 > 100KB）
ls -lh /path/to/video.mp4

# 使用 ffprobe 验证
ffprobe -v error -show_format -show_streams video.mp4
```

**期望输出**：
```
[STREAM]
codec_name=h264
codec_type=video
width=640
height=480
duration=10.000000
[/STREAM]

[FORMAT]
format_name=mov,mp4,m4a,3gp,3g2,mj2
duration=10.000000
size=1500000  # 约 1.5MB
bit_rate=1200000
[/FORMAT]
```

### 3. 播放视频

```bash
# 使用 ffplay 播放
ffplay video.mp4

# 或使用 VLC 等播放器
```

## 常见问题排查

### 问题 1: 编码器仍然失败

**症状**: 日志显示 "No supported color format found"

**解决方案**:
1. 查看完整的可用格式列表：
   ```java
   Log.e(TAG, "Available formats:");
   for (int format : capabilities.colorFormats) {
       Log.e(TAG, "  - " + format);
   }
   ```
2. 根据设备支持的格式调整 `preferredFormats` 数组

### 问题 2: MJPEG 解码失败

**症状**: 日志显示 "Failed to decode MJPEG frame"

**可能原因**:
- 相机输出的不是标准 MJPEG 格式
- 帧数据损坏

**解决方案**:
1. 保存原始帧数据到文件检查：
   ```java
   FileOutputStream fos = new FileOutputStream("/sdcard/frame.jpg");
   fos.write(frameBytes);
   fos.close();
   ```
2. 检查是否是有效的 JPEG 图像

### 问题 3: 视频文件过小

**症状**: 文件只有几 KB

**可能原因**:
- MediaMuxer 没有正确启动
- 编码后的数据没有写入

**解决方案**:
1. 检查日志中是否有 "Muxer started"
2. 检查日志中是否有 "Wrote sample"
3. 确保录制时长足够（至少 3-5 秒）

### 问题 4: 视频无法播放

**症状**: 文件存在但播放器无法打开

**可能原因**:
- 没有正确调用 stop()
- MediaMuxer 没有正确关闭

**解决方案**:
1. 确保在 Activity onDestroy 时调用 stopRecording()
2. 检查日志中是否有 "Draining encoder"

## 性能优化建议

### 1. 使用异步处理

当前实现在主线程中进行编码，可能导致 UI 卡顿。建议：

```java
// 使用单独的线程处理编码
private HandlerThread encoderThread;
private Handler encoderHandler;

public boolean start() {
    encoderThread = new HandlerThread("VideoEncoder");
    encoderThread.start();
    encoderHandler = new Handler(encoderThread.getLooper());
    // ...
}

public void writeFrame(ByteBuffer frameData) {
    encoderHandler.post(() -> {
        // 编码逻辑
    });
}
```

### 2. 使用 Surface 输入

更高效的方式是使用 Surface 直接输入：

```java
Surface inputSurface = mediaCodec.createInputSurface();
// 直接渲染到 Surface，避免 Bitmap 转换
```

### 3. 优化 Bitmap 处理

```java
// 重用 Bitmap 对象
BitmapFactory.Options options = new BitmapFactory.Options();
options.inMutable = true;
options.inBitmap = reusableBitmap;
Bitmap bitmap = BitmapFactory.decodeByteArray(frameBytes, 0, frameBytes.length, options);
```

## 测试检查清单

- [ ] 录制至少 5 秒视频
- [ ] 检查文件大小 > 100KB
- [ ] 使用 ffprobe 验证编码信息
- [ ] 在多个播放器中测试播放
- [ ] 查看 Logcat 确认没有错误
- [ ] 测试不同分辨率
- [ ] 测试长时间录制（1-2 分钟）

## 修改文件清单

### 修改的文件
- `sample/src/main/java/com/hsj/sample/VideoRecorder.java` - 完全重写

### 主要改进
1. ✅ 动态选择编码器和颜色格式
2. ✅ 改进的 YUV 转换（支持多种格式）
3. ✅ 增强的错误处理和日志
4. ✅ 降低默认参数以提高稳定性
5. ✅ 帧率控制避免过载
6. ✅ 边界检查防止数组越界

## 下一步建议

1. **添加诊断工具**
   - 创建一个测试页面显示编码器信息
   - 实时显示编码状态和帧率

2. **性能监控**
   - 监控编码延迟
   - 监控内存使用
   - 监控丢帧情况

3. **用户选项**
   - 允许用户选择视频质量
   - 允许用户选择分辨率
   - 显示估计的文件大小

---

**创建日期**: 2026-01-01
**修复版本**: 2.0.0
