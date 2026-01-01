package com.hsj.sample;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

// libyuv库（用于性能对比测试，复用 Buffer 优化）
import io.github.crow_misia.libyuv.Yuy2Buffer;
import io.github.crow_misia.libyuv.Nv12Buffer;
import io.github.crow_misia.libyuv.I420Buffer;

/**
 * @Author: Hsj
 * @Date: 2026-01-01
 * @Class: V4L2VideoRecorder
 * @Desc: V4L2 相机视频录制类，负责将 YUYV/MJPEG 帧数据编码并保存为 MP4 文件
 *        可在其他 V4L2 相机项目中复用
 */
public class V4L2VideoRecorder {

    private static final String TAG = "V4L2VideoRecorder";
    private static final String MIME_TYPE = "video/avc"; // H.264
    private static final int FRAME_RATE = 30; // 编码器帧率配置
    private static final int I_FRAME_INTERVAL = 2; // I帧间隔（秒）
    private static final int BIT_RATE = 2000000; // 2Mbps

    // ========== 编码器性能优化参数 ==========
    private static final int INPUT_TIMEOUT_USEC = 5000;   // 输入 buffer 超时（5ms）
    private static final int OUTPUT_TIMEOUT_USEC = 0;     // 输出 buffer 非阻塞轮询

    // ========== 性能测试开关 ==========
    // 设置为 true 使用 libyuv 库进行转换（需要修复API调用）
    // 设置为 false 使用手动Java转换
    private static final boolean USE_LIBYUV = true;

    private MediaCodec mediaCodec;
    private MediaMuxer mediaMuxer;
    private int videoTrackIndex = -1;
    private boolean muxerStarted = false;
    private boolean isRecording = false;

    private int width;
    private int height;
    private String outputPath;

    private long frameIndex = 0;
    private long startTime = 0;
    private int colorFormat = -1;

    // 性能统计
    private static final int STATS_INTERVAL = 30;  // 每30帧输出一次统计
    private long totalConversionTimeNs = 0;   // YUYV→YUV420转换总耗时(纳秒)
    private long totalEncodingTimeNs = 0;     // 编码器处理总耗时(纳秒)
    private long totalFrameTimeNs = 0;        // 总帧处理耗时(纳秒)
    private long statsFrameCount = 0;         // 统计帧数
    private long minFrameTimeNs = Long.MAX_VALUE;  // 最小帧耗时
    private long maxFrameTimeNs = 0;               // 最大帧耗时

    // ========== libyuv 复用 Buffer（避免每帧分配） ==========
    private Yuy2Buffer reusableYuy2Buffer;    // 复用的 YUYV 输入 buffer
    private Nv12Buffer reusableNv12Buffer;    // 复用的 NV12 输出 buffer
    private I420Buffer reusableI420Buffer;    // 复用的 I420 输出 buffer
    private byte[] reusableYuv420Array;       // 复用的输出字节数组

    // ========== 编码器复用对象（避免每帧分配） ==========
    private MediaCodec.BufferInfo reusableBufferInfo;  // 复用的 BufferInfo

    public V4L2VideoRecorder(int width, int height, String outputPath) {
        this.width = width;
        this.height = height;
        this.outputPath = outputPath;
    }

    /**
     * 开始录制
     */
    public synchronized boolean start() {
        if (isRecording) {
            Log.w(TAG, "Already recording");
            return false;
        }

        try {
            // 创建输出文件
            File outputFile = new File(outputPath);
            if (outputFile.exists()) {
                outputFile.delete();
            }

            // 查找支持的颜色格式
            colorFormat = selectColorFormat(MIME_TYPE);
            if (colorFormat == -1) {
                Log.e(TAG, "No supported color format found");
                return false;
            }
            Log.d(TAG, "Using color format: " + colorFormat);

            // 配置 MediaFormat
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);

            // ========== 硬件编码器性能优化 ==========
            // 使用 Baseline Profile（编码速度更快，兼容性更好）
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31);

            // 使用 CBR（恒定码率）模式，编码更稳定
            format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);

            // 设置较低的复杂度（部分编码器支持）
            try {
                format.setInteger(MediaFormat.KEY_COMPLEXITY, 0); // 最低复杂度
            } catch (Exception ignored) {
                // 部分编码器不支持此参数
            }

            Log.d(TAG, "Encoder config: Profile=Baseline, Level=3.1, Mode=CBR, BitRate=" + BIT_RATE);

            // 创建 MediaCodec
            mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE);
            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mediaCodec.start();

            // 创建 MediaMuxer
            mediaMuxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            isRecording = true;
            frameIndex = 0;
            startTime = System.nanoTime();

            // 初始化复用对象（避免每帧分配）
            reusableBufferInfo = new MediaCodec.BufferInfo();
            if (USE_LIBYUV) {
                initReusableBuffers();
            }

            Log.d(TAG, "Recording started: " + outputPath);
            Log.d(TAG, "Video format: " + width + "x" + height + " @ " + FRAME_RATE + "fps");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to start recording", e);
            release();
            return false;
        }
    }

    /**
     * 写入一帧数据（支持 YUYV 或 MJPEG）
     */
    public synchronized void writeFrame(ByteBuffer frameData) {
        if (!isRecording || mediaCodec == null) {
            Log.w(TAG, "writeFrame called but not recording or codec is null");
            return;
        }

        try {
            // 将 ByteBuffer 转换为字节数组
            frameData.rewind();
            byte[] frameBytes = new byte[frameData.remaining()];
            frameData.get(frameBytes);

            // 第一帧：输出详细信息并检测格式
            if (frameIndex == 0) {
                Log.d(TAG, "First frame received: size=" + frameBytes.length + " bytes");
                Log.d(TAG, "Expected YUYV size: " + (width * height * 2) + " bytes");
                Log.d(TAG, "First 10 bytes: " +
                    String.format("%02X %02X %02X %02X %02X %02X %02X %02X %02X %02X",
                    frameBytes[0], frameBytes[1], frameBytes[2], frameBytes[3], frameBytes[4],
                    frameBytes[5], frameBytes[6], frameBytes[7], frameBytes[8], frameBytes[9]));

                // 检测是 MJPEG 还是 YUYV
                if (frameBytes.length == width * height * 2) {
                    Log.d(TAG, "Detected format: YUYV (size matches " + width + "x" + height + "x2)");
                } else if (frameBytes[0] == (byte)0xFF && frameBytes[1] == (byte)0xD8) {
                    Log.d(TAG, "Detected format: MJPEG (JPEG header found)");
                } else {
                    Log.w(TAG, "Unknown format, size=" + frameBytes.length);
                }
            }

            // 判断数据格式：YUYV 或 MJPEG（不限制帧率，按摄像头原始帧率录制）
            boolean isYUYV = (frameBytes.length == width * height * 2);

            if (isYUYV) {
                // 直接处理 YUYV 数据
                encodeYUYVFrame(frameBytes);
            } else {
                // 尝试解码 MJPEG
                Bitmap bitmap = BitmapFactory.decodeByteArray(frameBytes, 0, frameBytes.length);
                if (bitmap == null) {
                    Log.e(TAG, "!!! Failed to decode MJPEG frame " + frameIndex +
                        ", data size: " + frameBytes.length);
                    if (frameIndex < 3) {
                        saveDebugFrame(frameBytes, frameIndex);
                    }
                    frameIndex++;
                    return;
                }

                // 第一帧：输出 Bitmap 信息
                if (frameIndex == 0) {
                    Log.d(TAG, "Bitmap decoded: " + bitmap.getWidth() + "x" + bitmap.getHeight() +
                        ", config=" + bitmap.getConfig());
                }

                // 确保 Bitmap 尺寸正确
                if (bitmap.getWidth() != width || bitmap.getHeight() != height) {
                    Log.d(TAG, "Scaling bitmap from " + bitmap.getWidth() + "x" + bitmap.getHeight() +
                        " to " + width + "x" + height);
                    Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true);
                    bitmap.recycle();
                    bitmap = scaledBitmap;
                }

                // 将 Bitmap 转换为 YUV 并编码
                encodeFrame(bitmap);
                bitmap.recycle();
            }

            frameIndex++;

            // 定期输出日志
            if (frameIndex % 10 == 0) {
                Log.d(TAG, "Processed frame: " + frameIndex);
            }

        } catch (Exception e) {
            Log.e(TAG, "!!! Error writing frame " + frameIndex, e);
            e.printStackTrace();
        }
    }

    /**
     * 编码 YUYV 格式的帧
     */
    private void encodeYUYVFrame(byte[] yuyvData) {
        long frameStartTime = System.nanoTime();
        long conversionTime = 0;
        long encodingTime = 0;

        try {
            // 获取输入缓冲区
            int inputBufferIndex = mediaCodec.dequeueInputBuffer(INPUT_TIMEOUT_USEC);
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = mediaCodec.getInputBuffer(inputBufferIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();

                    // ===== 计时：YUYV→YUV420 转换 =====
                    long conversionStart = System.nanoTime();
                    byte[] yuv420Data = yuyvToYUV420(yuyvData, width, height, colorFormat);
                    conversionTime = System.nanoTime() - conversionStart;

                    // 第一帧：输出转换信息并保存原始数据用于诊断
                    if (frameIndex == 0) {
                        Log.d(TAG, "YUYV input size: " + yuyvData.length + " bytes");
                        Log.d(TAG, "YUV420 output size: " + yuv420Data.length + " bytes");
                        Log.d(TAG, "Input buffer capacity: " + inputBuffer.capacity());

                        // 保存原始YUYV数据和转换后的YUV420数据用于离线分析
                        saveRawDebugData(yuyvData, yuv420Data);
                    }

                    inputBuffer.put(yuv420Data);

                    // ===== 计时：编码器处理 =====
                    long encodingStart = System.nanoTime();

                    // 提交到编码器
                    long presentationTimeUs = (System.nanoTime() - startTime) / 1000;
                    mediaCodec.queueInputBuffer(inputBufferIndex, 0, yuv420Data.length,
                            presentationTimeUs, 0);

                    if (frameIndex == 0) {
                        Log.d(TAG, "First YUYV frame queued to encoder, pts=" + presentationTimeUs);
                    }

                    // 获取输出数据
                    drainEncoder(false);

                    encodingTime = System.nanoTime() - encodingStart;
                } else {
                    Log.e(TAG, "!!! Input buffer is null for index " + inputBufferIndex);
                    return;
                }
            } else {
                Log.w(TAG, "!!! No input buffer available, index=" + inputBufferIndex);
                return;
            }

        } catch (Exception e) {
            Log.e(TAG, "!!! Error encoding YUYV frame", e);
            e.printStackTrace();
            return;
        }

        // ===== 更新性能统计 =====
        long frameTime = System.nanoTime() - frameStartTime;
        updatePerformanceStats(conversionTime, encodingTime, frameTime);
    }

    /**
     * 更新性能统计并定期输出
     */
    private void updatePerformanceStats(long conversionTimeNs, long encodingTimeNs, long frameTimeNs) {
        totalConversionTimeNs += conversionTimeNs;
        totalEncodingTimeNs += encodingTimeNs;
        totalFrameTimeNs += frameTimeNs;
        statsFrameCount++;

        // 更新最小/最大帧耗时
        if (frameTimeNs < minFrameTimeNs) minFrameTimeNs = frameTimeNs;
        if (frameTimeNs > maxFrameTimeNs) maxFrameTimeNs = frameTimeNs;

        // 每 STATS_INTERVAL 帧输出一次统计
        if (statsFrameCount >= STATS_INTERVAL) {
            float avgConversionMs = (totalConversionTimeNs / statsFrameCount) / 1_000_000f;
            float avgEncodingMs = (totalEncodingTimeNs / statsFrameCount) / 1_000_000f;
            float avgFrameMs = (totalFrameTimeNs / statsFrameCount) / 1_000_000f;
            float minFrameMs = minFrameTimeNs / 1_000_000f;
            float maxFrameMs = maxFrameTimeNs / 1_000_000f;
            float fps = 1000f / avgFrameMs;

            Log.i(TAG, String.format(java.util.Locale.US,
                "⏱ Performance [%d frames]: Convert=%.2fms, Encode=%.2fms, Total=%.2fms (min=%.2f, max=%.2f), FPS=%.1f",
                statsFrameCount, avgConversionMs, avgEncodingMs, avgFrameMs, minFrameMs, maxFrameMs, fps));

            // 性能警告
            if (avgFrameMs > 66.7f) {  // 低于15fps
                Log.w(TAG, "⚠ Performance warning: Frame time > 66.7ms, may cause dropped frames");
            }

            // 重置统计
            totalConversionTimeNs = 0;
            totalEncodingTimeNs = 0;
            totalFrameTimeNs = 0;
            statsFrameCount = 0;
            minFrameTimeNs = Long.MAX_VALUE;
            maxFrameTimeNs = 0;
        }
    }

    /**
     * 初始化复用 Buffer（在 start() 时调用一次）
     * 避免每帧分配内存，减少 GC 压力
     */
    private void initReusableBuffers() {
        try {
            int frameSize = width * height;

            // 预分配 libyuv buffer
            reusableYuy2Buffer = Yuy2Buffer.Factory.allocate(width, height);
            if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar ||
                colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar) {
                reusableNv12Buffer = Nv12Buffer.Factory.allocate(width, height);
                Log.d(TAG, "✅ Initialized reusable buffers: YUY2 + NV12");
            } else {
                reusableI420Buffer = I420Buffer.Factory.allocate(width, height);
                Log.d(TAG, "✅ Initialized reusable buffers: YUY2 + I420");
            }

            // 预分配输出数组
            reusableYuv420Array = new byte[frameSize * 3 / 2];

            // 预分配复用的 BufferInfo
            reusableBufferInfo = new MediaCodec.BufferInfo();

            Log.d(TAG, "✅ Reusable buffers initialized: " +
                "YUYV=" + (width * height * 2) + " bytes, " +
                "YUV420=" + (frameSize * 3 / 2) + " bytes");

        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to init reusable buffers, will use fallback: " + e.getMessage());
            releaseReusableBuffers();
        }
    }

    /**
     * 释放复用 Buffer（在 release() 时调用）
     */
    private void releaseReusableBuffers() {
        reusableYuy2Buffer = null;
        reusableNv12Buffer = null;
        reusableI420Buffer = null;
        reusableYuv420Array = null;
        reusableBufferInfo = null;
    }

    /**
     * 将 YUYV 转换为 YUV420 (NV12 或 I420)
     *
     * 根据 USE_LIBYUV 开关选择转换方法：
     * - true: 使用 libyuv 库（NEON优化，复用buffer）
     * - false: 使用手动Java转换（稳定，但性能较低）
     */
    private byte[] yuyvToYUV420(byte[] yuyv, int width, int height, int colorFormat) {
        if (USE_LIBYUV) {
            return yuyvToYUV420LibyuvOptimized(yuyv, width, height, colorFormat);
        } else {
            return yuyvToYUV420Fallback(yuyv, width, height, colorFormat);
        }
    }

    /**
     * 优化版 libyuv 转换（复用 Buffer，减少 GC）
     *
     * 优化点：
     * 1. 复用 Yuy2Buffer/Nv12Buffer/I420Buffer（避免每帧 allocate）
     * 2. 复用输出 byte[] 数组
     * 3. 直接操作 ByteBuffer 减少拷贝
     */
    private byte[] yuyvToYUV420LibyuvOptimized(byte[] yuyv, int width, int height, int colorFormat) {
        // 检查复用 buffer 是否可用
        if (reusableYuy2Buffer == null || reusableYuv420Array == null) {
            if (frameIndex == 0) {
                Log.w(TAG, "⚠ Reusable buffers not available, using fallback");
            }
            return yuyvToYUV420Fallback(yuyv, width, height, colorFormat);
        }

        int frameSize = width * height;

        try {
            // 1. 将 YUYV 数据写入复用的 Yuy2Buffer
            ByteBuffer yuy2ByteBuffer = reusableYuy2Buffer.getPlane().getBuffer();
            yuy2ByteBuffer.clear();
            yuy2ByteBuffer.put(yuyv);
            yuy2ByteBuffer.flip();

            // 2. 转换并提取数据
            if (reusableNv12Buffer != null) {
                // NV12 格式
                reusableYuy2Buffer.convertTo(reusableNv12Buffer);

                // 提取 Y 平面
                ByteBuffer yBuffer = reusableNv12Buffer.getPlaneY().getBuffer();
                yBuffer.rewind();
                yBuffer.get(reusableYuv420Array, 0, frameSize);

                // 提取 UV 平面
                ByteBuffer uvBuffer = reusableNv12Buffer.getPlaneUV().getBuffer();
                uvBuffer.rewind();
                uvBuffer.get(reusableYuv420Array, frameSize, frameSize / 2);

                if (frameIndex == 0) {
                    Log.d(TAG, "✅ Using optimized libyuv: YUY2 → NV12 (reusable buffers)");
                }
            } else if (reusableI420Buffer != null) {
                // I420 格式
                reusableYuy2Buffer.convertTo(reusableI420Buffer);

                // 提取 Y 平面
                ByteBuffer yBuffer = reusableI420Buffer.getPlaneY().getBuffer();
                yBuffer.rewind();
                yBuffer.get(reusableYuv420Array, 0, frameSize);

                // 提取 U 平面
                ByteBuffer uBuffer = reusableI420Buffer.getPlaneU().getBuffer();
                uBuffer.rewind();
                uBuffer.get(reusableYuv420Array, frameSize, frameSize / 4);

                // 提取 V 平面
                ByteBuffer vBuffer = reusableI420Buffer.getPlaneV().getBuffer();
                vBuffer.rewind();
                vBuffer.get(reusableYuv420Array, frameSize + frameSize / 4, frameSize / 4);

                if (frameIndex == 0) {
                    Log.d(TAG, "✅ Using optimized libyuv: YUY2 → I420 (reusable buffers)");
                }
            } else {
                // 没有可用的输出 buffer
                return yuyvToYUV420Fallback(yuyv, width, height, colorFormat);
            }

            return reusableYuv420Array;

        } catch (Exception e) {
            if (frameIndex == 0) {
                Log.e(TAG, "❌ Optimized libyuv failed: " + e.getMessage());
            }
            return yuyvToYUV420Fallback(yuyv, width, height, colorFormat);
        }
    }

    /**
     * 备用方案：手动 YUYV 到 YUV420 转换
     *
     * YUYV格式 (4:2:2): Y0 U0 Y1 V0 Y2 U1 Y3 V1 ...
     *   - 每2个像素共享一对UV（水平2:1采样）
     *   - 每行都有完整的UV数据
     *
     * YUV420 (4:2:0):
     *   - Y平面: width × height
     *   - UV平面: (width/2) × (height/2)，每4个像素（2×2块）共享一对UV
     *
     * 转换策略：
     *   - Y平面：直接复制所有Y值
     *   - UV平面：水平每2像素取一对，垂直平均相邻两行的UV值
     */
    private byte[] yuyvToYUV420Fallback(byte[] yuyv, int width, int height, int colorFormat) {
        int frameSize = width * height;
        byte[] yuv420 = new byte[frameSize * 3 / 2];
        final int yuyvLineStride = width * 2;  // YUYV每行字节数 = width × 2

        // ========== Y 平面提取 ==========
        // YUYV格式：Y0 U0 Y1 V0 Y2 U1 Y3 V1 ...
        // Y值在偶数位置 (0, 2, 4, ...)
        int yIndex = 0;
        for (int j = 0; j < height; j++) {
            int lineStart = j * yuyvLineStride;
            for (int i = 0; i < width; i++) {
                // YUYV格式：Y在偶数位置 (0, 2, 4, ...)
                yuv420[yIndex++] = yuyv[lineStart + i * 2];
            }
        }

        // ========== UV 平面提取 ==========
        // YUYV格式：Y0 U0 Y1 V0 Y2 U1 Y3 V1 ...
        // U在位置1,5,9... V在位置3,7,11...
        if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar ||
            colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar) {
            // NV12格式：UVUV...
            int uvIndex = frameSize;
            for (int j = 0; j < height; j += 2) {  // 每2行采样一次（垂直下采样）
                int lineStart = j * yuyvLineStride;

                for (int i = 0; i < width; i += 2) {  // 每2列采样一次（水平下采样）
                    int col = i * 2;  // YUYV中的列位置

                    // YUYV格式: Y0 U0 Y1 V0，U在+1，V在+3
                    byte u = yuyv[lineStart + col + 1];  // U在位置1
                    byte v = yuyv[lineStart + col + 3];  // V在位置3

                    yuv420[uvIndex++] = u;  // U
                    yuv420[uvIndex++] = v;  // V
                }
            }

            // 调试：输出第一帧的前几个UV值
            if (frameIndex == 0) {
                Log.d(TAG, String.format("Fallback UV first 16 bytes (YUYV→NV12): " +
                    "%02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X",
                    yuv420[frameSize] & 0xFF, yuv420[frameSize+1] & 0xFF,
                    yuv420[frameSize+2] & 0xFF, yuv420[frameSize+3] & 0xFF,
                    yuv420[frameSize+4] & 0xFF, yuv420[frameSize+5] & 0xFF,
                    yuv420[frameSize+6] & 0xFF, yuv420[frameSize+7] & 0xFF,
                    yuv420[frameSize+8] & 0xFF, yuv420[frameSize+9] & 0xFF,
                    yuv420[frameSize+10] & 0xFF, yuv420[frameSize+11] & 0xFF,
                    yuv420[frameSize+12] & 0xFF, yuv420[frameSize+13] & 0xFF,
                    yuv420[frameSize+14] & 0xFF, yuv420[frameSize+15] & 0xFF));
            }

        } else {
            // I420格式：U和V分别存储（UUU...VVV...）
            int uIndex = frameSize;
            int vIndex = frameSize + frameSize / 4;

            for (int j = 0; j < height; j += 2) {
                int lineStart = j * yuyvLineStride;

                for (int i = 0; i < width; i += 2) {
                    int col = i * 2;

                    yuv420[uIndex++] = yuyv[lineStart + col + 1];  // YUYV: U在位置1
                    yuv420[vIndex++] = yuyv[lineStart + col + 3];  // YUYV: V在位置3
                }
            }
        }

        Log.d(TAG, "✅ Using YUYV→YUV420 fallback conversion");
        return yuv420;
    }

    /**
     * 保存调试帧数据
     */
    private void saveDebugFrame(byte[] frameBytes, long index) {
        try {
            java.io.File debugFile = new java.io.File("/sdcard/debug_frame_" + index + ".jpg");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(debugFile);
            fos.write(frameBytes);
            fos.close();
            Log.d(TAG, "Saved debug frame to: " + debugFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to save debug frame", e);
        }
    }

    /**
     * 保存原始YUYV和转换后的YUV420数据用于离线分析
     *
     * 使用方法：
     * 1. adb pull /sdcard/debug_yuyv_720x480.raw /Users/tubao/temp/
     * 2. adb pull /sdcard/debug_yuv420_720x480.yuv /Users/tubao/temp/
     * 3. 使用ffplay查看原始数据：
     *    ffplay -f rawvideo -pixel_format yuyv422 -video_size 720x480 debug_yuyv_720x480.raw
     *    ffplay -f rawvideo -pixel_format nv12 -video_size 720x480 debug_yuv420_720x480.yuv
     * 4. 或使用YUView工具打开查看
     */
    private void saveRawDebugData(byte[] yuyvData, byte[] yuv420Data) {
        try {
            // 保存原始YUYV数据
            String yuyvPath = String.format("/sdcard/debug_yuyv_%dx%d.raw", width, height);
            java.io.File yuyvFile = new java.io.File(yuyvPath);
            java.io.FileOutputStream yuyvFos = new java.io.FileOutputStream(yuyvFile);
            yuyvFos.write(yuyvData);
            yuyvFos.close();
            Log.d(TAG, "✅ Saved raw YUYV data to: " + yuyvFile.getAbsolutePath());
            Log.d(TAG, "   View with: ffplay -f rawvideo -pixel_format yuyv422 -video_size " +
                  width + "x" + height + " " + yuyvFile.getName());

            // 保存转换后的YUV420数据
            String yuv420Path = String.format("/sdcard/debug_yuv420_%dx%d.yuv", width, height);
            java.io.File yuv420File = new java.io.File(yuv420Path);
            java.io.FileOutputStream yuv420Fos = new java.io.FileOutputStream(yuv420File);
            yuv420Fos.write(yuv420Data);
            yuv420Fos.close();
            Log.d(TAG, "✅ Saved converted YUV420 data to: " + yuv420File.getAbsolutePath());
            Log.d(TAG, "   View with: ffplay -f rawvideo -pixel_format nv12 -video_size " +
                  width + "x" + height + " " + yuv420File.getName());

            // 输出详细的数据分析
            Log.d(TAG, "========== RAW DATA ANALYSIS ==========");
            Log.d(TAG, "YUYV size: " + yuyvData.length + " bytes (expected: " + (width * height * 2) + ")");
            Log.d(TAG, "YUV420 size: " + yuv420Data.length + " bytes (expected: " + (width * height * 3 / 2) + ")");

            // 输出YUYV前32字节（16像素的数据）
            StringBuilder yuyvHex = new StringBuilder("YUYV first 32 bytes:\n");
            for (int i = 0; i < Math.min(32, yuyvData.length); i += 4) {
                yuyvHex.append(String.format("  [%02d-%02d] Y=%02X U=%02X Y=%02X V=%02X\n",
                    i, i+3,
                    yuyvData[i] & 0xFF, yuyvData[i+1] & 0xFF,
                    yuyvData[i+2] & 0xFF, yuyvData[i+3] & 0xFF));
            }
            Log.d(TAG, yuyvHex.toString());

            // 输出YUV420的Y平面前16字节
            int frameSize = width * height;
            StringBuilder yHex = new StringBuilder("YUV420 Y plane first 16 bytes: ");
            for (int i = 0; i < Math.min(16, frameSize); i++) {
                yHex.append(String.format("%02X ", yuv420Data[i] & 0xFF));
            }
            Log.d(TAG, yHex.toString());

            // 输出YUV420的UV平面前16字节
            StringBuilder uvHex = new StringBuilder("YUV420 UV plane first 16 bytes: ");
            for (int i = 0; i < 16 && (frameSize + i) < yuv420Data.length; i++) {
                uvHex.append(String.format("%02X ", yuv420Data[frameSize + i] & 0xFF));
            }
            Log.d(TAG, uvHex.toString());
            Log.d(TAG, "=======================================");

        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to save raw debug data", e);
            e.printStackTrace();
        }
    }

    /**
     * 编码一帧
     */
    private void encodeFrame(Bitmap bitmap) {
        try {
            // 获取输入缓冲区
            int inputBufferIndex = mediaCodec.dequeueInputBuffer(INPUT_TIMEOUT_USEC);
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = mediaCodec.getInputBuffer(inputBufferIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();

                    // 将 Bitmap 转换为 YUV
                    byte[] yuvData = convertBitmapToYUV(bitmap, colorFormat);
                    if (yuvData == null) {
                        Log.e(TAG, "!!! Failed to convert bitmap to YUV");
                        return;
                    }

                    // 第一帧：输出 YUV 数据信息
                    if (frameIndex == 0) {
                        Log.d(TAG, "YUV data size: " + yuvData.length + " bytes");
                        Log.d(TAG, "Input buffer capacity: " + inputBuffer.capacity());
                    }

                    inputBuffer.put(yuvData);

                    // 提交到编码器
                    long presentationTimeUs = (System.nanoTime() - startTime) / 1000;
                    mediaCodec.queueInputBuffer(inputBufferIndex, 0, yuvData.length,
                            presentationTimeUs, 0);

                    if (frameIndex == 0) {
                        Log.d(TAG, "First frame queued to encoder, pts=" + presentationTimeUs);
                    }
                } else {
                    Log.e(TAG, "!!! Input buffer is null for index " + inputBufferIndex);
                }
            } else {
                Log.w(TAG, "!!! No input buffer available, index=" + inputBufferIndex);
            }

            // 获取输出数据
            drainEncoder(false);

        } catch (Exception e) {
            Log.e(TAG, "!!! Error encoding frame", e);
            e.printStackTrace();
        }
    }

    /**
     * 从编码器中取出编码后的数据
     */
    private void drainEncoder(boolean endOfStream) {
        if (endOfStream && mediaCodec != null) {
            try {
                int inputBufferIndex = mediaCodec.dequeueInputBuffer(INPUT_TIMEOUT_USEC);
                if (inputBufferIndex >= 0) {
                    mediaCodec.queueInputBuffer(inputBufferIndex, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error signaling end of stream", e);
            }
        }

        // 使用复用的 BufferInfo（避免每次调用都分配新对象）
        MediaCodec.BufferInfo bufferInfo = (reusableBufferInfo != null)
            ? reusableBufferInfo : new MediaCodec.BufferInfo();
        while (true) {
            int outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, OUTPUT_TIMEOUT_USEC);

            if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) {
                    break;
                }
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) {
                    Log.w(TAG, "Format changed twice");
                    break;
                }
                MediaFormat newFormat = mediaCodec.getOutputFormat();
                Log.d(TAG, "Output format changed: " + newFormat);
                videoTrackIndex = mediaMuxer.addTrack(newFormat);
                mediaMuxer.start();
                muxerStarted = true;
                Log.d(TAG, "Muxer started with track: " + videoTrackIndex);
            } else if (outputBufferIndex < 0) {
                // 忽略
            } else {
                ByteBuffer outputBuffer = mediaCodec.getOutputBuffer(outputBufferIndex);
                if (outputBuffer == null) {
                    Log.e(TAG, "Output buffer was null");
                    break;
                }

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0;
                }

                if (bufferInfo.size != 0) {
                    if (!muxerStarted) {
                        Log.w(TAG, "Muxer hasn't started, dropping frame");
                    } else {
                        outputBuffer.position(bufferInfo.offset);
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size);

                        mediaMuxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo);

                        if (frameIndex % 30 == 0) {
                            Log.d(TAG, "Wrote sample: size=" + bufferInfo.size +
                                    ", pts=" + bufferInfo.presentationTimeUs);
                        }
                    }
                }

                mediaCodec.releaseOutputBuffer(outputBufferIndex, false);

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            }
        }
    }

    /**
     * 停止录制
     */
    public synchronized void stop() {
        if (!isRecording) {
            Log.w(TAG, "Not recording");
            return;
        }

        isRecording = false;

        try {
            if (mediaCodec != null) {
                Log.d(TAG, "Draining encoder...");
                drainEncoder(true);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error draining encoder", e);
        }

        release();

        Log.d(TAG, "Recording stopped. Total frames: " + frameIndex);
    }

    /**
     * 释放资源
     */
    private void release() {
        if (mediaCodec != null) {
            try {
                mediaCodec.stop();
                mediaCodec.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing codec", e);
            }
            mediaCodec = null;
        }

        if (mediaMuxer != null) {
            try {
                // 只有当muxer已启动且有帧被写入时才调用stop()
                if (muxerStarted && frameIndex > 0) {
                    mediaMuxer.stop();
                }
                mediaMuxer.release();
            } catch (Exception e) {
                // 忽略MPEG4Writer的内部警告（不影响功能）
                Log.w(TAG, "Muxer stop/release warning (can be ignored): " + e.getMessage());
            }
            mediaMuxer = null;
        }

        // 释放 libyuv 复用 buffer
        releaseReusableBuffers();

        muxerStarted = false;
        videoTrackIndex = -1;
    }

    /**
     * 检查是否正在录制
     */
    public boolean isRecording() {
        return isRecording;
    }

    /**
     * 选择编码器支持的颜色格式
     */
    private int selectColorFormat(String mimeType) {
        MediaCodecInfo codecInfo = selectCodec(mimeType);
        if (codecInfo == null) {
            return -1;
        }

        MediaCodecInfo.CodecCapabilities capabilities =
                codecInfo.getCapabilitiesForType(mimeType);

        // 优先使用的颜色格式
        int[] preferredFormats = {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar, // NV12
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,     // I420
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar, // NV21
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        };

        for (int preferredFormat : preferredFormats) {
            for (int format : capabilities.colorFormats) {
                if (format == preferredFormat) {
                    Log.d(TAG, "Selected color format: " + format);
                    return format;
                }
            }
        }

        Log.e(TAG, "No preferred color format found. Available formats:");
        for (int format : capabilities.colorFormats) {
            Log.e(TAG, "  - " + format);
        }

        return -1;
    }

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

    /**
     * 将 Bitmap 转换为 YUV 格式
     */
    private byte[] convertBitmapToYUV(Bitmap bitmap, int colorFormat) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();

        int[] argb = new int[w * h];
        bitmap.getPixels(argb, 0, w, 0, 0, w, h);

        byte[] yuv;

        if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar ||
            colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar) {
            // NV12 or NV21
            yuv = new byte[w * h * 3 / 2];
            encodeYUV420SP(yuv, argb, w, h);
        } else if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
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

    /**
     * RGB 转 YUV420SP (NV12/NV21)
     */
    private void encodeYUV420SP(byte[] yuv420sp, int[] argb, int width, int height) {
        final int frameSize = width * height;

        int yIndex = 0;
        int uvIndex = frameSize;

        int R, G, B, Y, U, V;
        int index = 0;

        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                R = (argb[index] & 0xff0000) >> 16;
                G = (argb[index] & 0xff00) >> 8;
                B = (argb[index] & 0xff);

                // RGB to YUV (BT.601)
                Y = ((66 * R + 129 * G + 25 * B + 128) >> 8) + 16;
                U = ((-38 * R - 74 * G + 112 * B + 128) >> 8) + 128;
                V = ((112 * R - 94 * G - 18 * B + 128) >> 8) + 128;

                yuv420sp[yIndex++] = (byte) ((Y < 0) ? 0 : ((Y > 255) ? 255 : Y));

                if (j % 2 == 0 && index % 2 == 0 && uvIndex < yuv420sp.length - 1) {
                    yuv420sp[uvIndex++] = (byte) ((U < 0) ? 0 : ((U > 255) ? 255 : U));
                    yuv420sp[uvIndex++] = (byte) ((V < 0) ? 0 : ((V > 255) ? 255 : V));
                }

                index++;
            }
        }
    }

    /**
     * RGB 转 YUV420P (I420)
     */
    private void encodeYUV420P(byte[] yuv420p, int[] argb, int width, int height) {
        final int frameSize = width * height;
        final int uvSize = frameSize / 4;

        int yIndex = 0;
        int uIndex = frameSize;
        int vIndex = frameSize + uvSize;

        int R, G, B, Y, U, V;
        int index = 0;

        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                R = (argb[index] & 0xff0000) >> 16;
                G = (argb[index] & 0xff00) >> 8;
                B = (argb[index] & 0xff);

                // RGB to YUV (BT.601)
                Y = ((66 * R + 129 * G + 25 * B + 128) >> 8) + 16;
                U = ((-38 * R - 74 * G + 112 * B + 128) >> 8) + 128;
                V = ((112 * R - 94 * G - 18 * B + 128) >> 8) + 128;

                yuv420p[yIndex++] = (byte) ((Y < 0) ? 0 : ((Y > 255) ? 255 : Y));

                if (j % 2 == 0 && index % 2 == 0) {
                    if (uIndex < frameSize + uvSize) {
                        yuv420p[uIndex++] = (byte) ((U < 0) ? 0 : ((U > 255) ? 255 : U));
                    }
                    if (vIndex < yuv420p.length) {
                        yuv420p[vIndex++] = (byte) ((V < 0) ? 0 : ((V > 255) ? 255 : V));
                    }
                }

                index++;
            }
        }
    }
}
