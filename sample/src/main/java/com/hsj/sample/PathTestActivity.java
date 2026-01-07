package com.hsj.sample;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import com.hsj.camera.CameraAPI;
import com.hsj.camera.CameraView;
import com.hsj.camera.IFrameCallback;
import com.hsj.camera.IRender;
import com.hsj.camera.ISurfaceCallback;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * @Author:Claude
 * @Date:2026/01/04
 * @Class:PathTestActivity
 * @Desc:测试页面 - 通过设备路径连接相机进行预览和录制
 *       使用与 MainActivity 相同的 CameraView + ISurfaceCallback 方式
 */
public class PathTestActivity extends AppCompatActivity implements ISurfaceCallback {

    private static final String TAG = "PathTestActivity";
    private static final String PREFS_NAME = "PathTestPrefs";
    private static final String KEY_DEVICE_PATH = "device_path";
    private static final String DEFAULT_DEVICE_PATH = "/dev/video0";

    // UI 组件
    private EditText etDevicePath;
    private Button btnConnect;
    private Button btnDisconnect;
    private Button btnStart;
    private Button btnStop;
    private Button btnStartRecord;
    private Button btnStopRecord;
    private Button btnSaveFrame;
    private Button btnScanDevices;
    private ScrollView scrollScanResult;
    private TextView tvScanResult;

    // 相机组件 - 使用与 MainActivity 相同的方式
    private CameraAPI camera;
    private CameraView cameraView;
    private IRender render;
    private Surface surface;

    // 录制组件
    private V4L2VideoRecorder videoRecorder;
    private int videoWidth;
    private int videoHeight;
    //    yuv格式优先使用720*576
    private int frameFormat = CameraAPI.FRAME_FORMAT_YUYV;  // 使用 YUYV 格式（设备不支持 MJPEG）
//        private int frameFormat = CameraAPI.FRAME_FORMAT_MJPEG;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_path_test);

        // 初始化 UI
        initViews();

        // 初始化相机预览 - 使用与 MainActivity 相同的 CameraView
        cameraView = findViewById(R.id.cameraView);
        this.render = cameraView.getRender(CameraView.COMMON);
        this.render.setSurfaceCallback(this);

        // 恢复保存的设备路径
        restoreDevicePath();

        // 请求设备权限
        requestPermissionV1();
    }

    /**
     * ISurfaceCallback 回调 - 与 MainActivity 相同
     */
    @Override
    public void onSurface(Surface surface) {
        if (surface == null) {
            stop();
        }
        this.surface = surface;
        Log.d(TAG, "onSurface: " + (surface != null ? "获取到 Surface" : "Surface 为空"));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (render != null) {
            render.onRender(true);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (render != null) {
            render.onRender(false);
        }
    }

    /**
     * 点击空白区域隐藏键盘
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            View v = getCurrentFocus();
            if (v instanceof EditText) {
                // 检查点击是否在 EditText 外部
                int[] location = new int[2];
                v.getLocationOnScreen(location);
                float x = ev.getRawX();
                float y = ev.getRawY();

                if (x < location[0] || x > location[0] + v.getWidth() ||
                    y < location[1] || y > location[1] + v.getHeight()) {
                    // 点击在 EditText 外部，隐藏键盘
                    hideKeyboard(v);
                    v.clearFocus();
                }
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    /**
     * 隐藏软键盘
     */
    private void hideKeyboard(View view) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private void initViews() {
        etDevicePath = findViewById(R.id.et_device_path);
        btnConnect = findViewById(R.id.btn_connect);
        btnDisconnect = findViewById(R.id.btn_disconnect);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        btnStartRecord = findViewById(R.id.btn_start_record);
        btnStopRecord = findViewById(R.id.btn_stop_record);
        btnSaveFrame = findViewById(R.id.btn_save_frame);
        btnScanDevices = findViewById(R.id.btn_scan_devices);
        scrollScanResult = findViewById(R.id.scroll_scan_result);
        tvScanResult = findViewById(R.id.tv_scan_result);

        // 设置按钮点击事件
        btnConnect.setOnClickListener(v -> connectByPath());
        btnDisconnect.setOnClickListener(v -> disconnect());
        btnStart.setOnClickListener(v -> start());
        btnStop.setOnClickListener(v -> stop());
        btnStartRecord.setOnClickListener(v -> startRecording());
        btnStopRecord.setOnClickListener(v -> stopRecording());
        btnSaveFrame.setOnClickListener(v -> saveDebugFrame());
        btnScanDevices.setOnClickListener(v -> scanDevices());

        // 初始化按钮状态
        updateButtonStates(false, false);
    }

    /**
     * 恢复保存的设备路径
     */
    private void restoreDevicePath() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String savedPath = prefs.getString(KEY_DEVICE_PATH, DEFAULT_DEVICE_PATH);
        etDevicePath.setText(savedPath);
        Log.d(TAG, "恢复保存的设备路径: " + savedPath);
    }

    /**
     * 保存设备路径
     */
    private void saveDevicePath(String devicePath) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_DEVICE_PATH, devicePath).apply();
        Log.d(TAG, "保存设备路径: " + devicePath);
    }

    /**
     * 通过设备路径连接相机
     */
    private void connectByPath() {
        String devicePath = etDevicePath.getText().toString().trim();

        if (TextUtils.isEmpty(devicePath)) {
            showToast("请输入设备路径");
            return;
        }

        if (this.camera != null) {
            showToast("相机已连接，请先断开");
            return;
        }

        try {
            CameraAPI camera = new CameraAPI();

            // 使用新的设备路径连接方式
            Log.d(TAG, "尝试连接设备: " + devicePath);
            boolean ret = camera.connectByPath(devicePath);

            if (!ret) {
                showToast("连接失败：无法打开设备 " + devicePath);
                return;
            }

            // 获取支持的分辨率
            int[][] supportFrameSize = camera.getSupportFrameSize();
            if (supportFrameSize == null || supportFrameSize.length == 0) {
                showToast("获取支持的分辨率失败");
                return;
            }

            // 打印所有支持的分辨率
            Log.d(TAG, "支持的分辨率数量: " + supportFrameSize.length);
            for (int i = 0; i < supportFrameSize.length; i++) {
                Log.d(TAG, "  [" + i + "] " + supportFrameSize[i][0] + "x" + supportFrameSize[i][1]);
            }

            // 选择最佳分辨率
            int[] selectedSize = selectBestResolution(supportFrameSize);
            final int width = selectedSize[0];
            final int height = selectedSize[1];
            Log.d(TAG, "选择的分辨率: " + width + "x" + height);

            // 设置帧大小
            ret = camera.setFrameSize(width, height, frameFormat);
            if (!ret) {
                Log.e(TAG, "设置帧大小失败: " + width + "x" + height);
                showToast("设置分辨率失败");
                return;
            }

            // 获取实际设置的分辨率
            android.util.Pair<Integer, Integer> actualSize = camera.getActualFrameSize();
            if (actualSize != null) {
                this.videoWidth = actualSize.first;
                this.videoHeight = actualSize.second;
                Log.d(TAG, "实际帧大小: " + this.videoWidth + "x" + this.videoHeight);
            } else {
                this.videoWidth = width;
                this.videoHeight = height;
                Log.w(TAG, "无法获取实际帧大小，使用请求的: " + width + "x" + height);
            }

            this.camera = camera;

            // 启用自动曝光（解决画面偏暗问题）
            boolean autoExposureResult = camera.setAutoExposure(true);
            Log.d(TAG, "设置自动曝光: " + (autoExposureResult ? "成功" : "失败"));

            showToast("连接成功: " + devicePath);
            updateButtonStates(true, false);

            // 保存成功连接的设备路径
            saveDevicePath(devicePath);

        } catch (IllegalArgumentException e) {
            showToast("参数错误: " + e.getMessage());
            Log.e(TAG, "连接失败", e);
        } catch (Exception e) {
            showToast("连接失败: " + e.getMessage());
            Log.e(TAG, "连接失败", e);
        }
    }

    /**
     * 选择最佳分辨率
     * YUYV 格式优先：720x576 > 640x480 > 中间分辨率
     * MJPEG 格式优先：1920x1080
     */
    private int[] selectBestResolution(int[][] supportFrameSize) {
        // MJPEG 格式：优先选择 1080P
        if (frameFormat == CameraAPI.FRAME_FORMAT_MJPEG) {
            for (int[] size : supportFrameSize) {
                if (size[0] == 1920 && size[1] == 1080) {
                    Log.d(TAG, "找到 1080P 分辨率（MJPEG）");
                    return size;
                }
            }
        }
        // YUYV 格式：优先选择 720x576（根据用户反馈）
        else if (frameFormat == CameraAPI.FRAME_FORMAT_YUYV) {
            // 第一优先：720x576
            for (int[] size : supportFrameSize) {
                if (size[0] == 720 && size[1] == 576) {
                    Log.d(TAG, "找到 720x576 分辨率（YUYV 优先）");
                    return size;
                }
            }
            // debug，直接返回720x576
//            return new int[]{720, 576};

            // 第二优先：640x480
//            for (int[] size : supportFrameSize) {
//                if (size[0] == 640 && size[1] == 480) {
//                    Log.d(TAG, "找到 640x480 分辨率（YUYV 备选）");
//                    return size;
//                }
//            }
        }

        // 否则选择中间分辨率
        int index = supportFrameSize.length / 2;
        Log.d(TAG, "使用中间分辨率 [" + index + "]: " +
            supportFrameSize[index][0] + "x" + supportFrameSize[index][1]);
        return supportFrameSize[index];
    }

    /**
     * 断开相机连接
     */
    private void disconnect() {
        if (this.camera != null) {
            this.camera.destroy();
            this.camera = null;
            showToast("已断开连接");
            updateButtonStates(false, false);
        }
    }

    /**
     * 开始预览 - 与 MainActivity 相同的逻辑
     */
    private void start() {
        if (this.camera != null) {
            if (surface != null) {
                this.camera.setPreview(surface);
            } else {
                Log.w(TAG, "start: surface 为空！");
            }
            this.camera.setFrameCallback(frameCallback);
            this.camera.start();
            showToast("预览已启动");
            updateButtonStates(true, true);
        } else {
            showToast("请先连接相机");
        }
    }

    /**
     * 停止预览
     */
    private void stop() {
        if (this.camera != null) {
            this.camera.stop();
            showToast("预览已停止");
            updateButtonStates(true, false);
        }
    }

    /**
     * 帧回调 - 用于录制
     */
    private final IFrameCallback frameCallback = frame -> {
        if (videoRecorder != null && videoRecorder.isRecording()) {
            videoRecorder.writeFrame(frame);
        }
    };

    /**
     * 开始录制
     */
    private void startRecording() {
        if (camera == null) {
            showToast("请先连接相机");
            return;
        }

        if (videoRecorder != null && videoRecorder.isRecording()) {
            showToast("正在录制中");
            return;
        }

        try {
            // 生成输出文件路径
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String outputPath = new File(getExternalFilesDir(null),
                "path_test_" + timestamp + ".mp4").getAbsolutePath();

            videoRecorder = new V4L2VideoRecorder(videoWidth, videoHeight, outputPath);

            if (videoRecorder.start()) {
                btnStartRecord.setEnabled(false);
                btnStopRecord.setEnabled(true);
                showToast("开始录制");
                Log.d(TAG, "录制已启动: " + outputPath);
            } else {
                showToast("启动录制失败");
                videoRecorder = null;
            }
        } catch (Exception e) {
            showToast("录制失败: " + e.getMessage());
            Log.e(TAG, "启动录制失败", e);
            videoRecorder = null;
        }
    }

    /**
     * 停止录制
     */
    private void stopRecording() {
        if (videoRecorder != null) {
            videoRecorder.stop();
            videoRecorder = null;

            btnStartRecord.setEnabled(true);
            btnStopRecord.setEnabled(false);

            showToast("录制已停止");
            Log.d(TAG, "录制已停止");
        }
    }

    /**
     * 保存调试帧 - 用于分析预览黑屏问题
     */
    private void saveDebugFrame() {
        if (camera == null) {
            showToast("请先连接相机");
            return;
        }

        // 保存到应用私有目录
        File debugDir = new File(getExternalFilesDir(null), "debug_frames");
        if (!debugDir.exists()) {
            debugDir.mkdirs();
        }

        String savePath = debugDir.getAbsolutePath();
        camera.saveDebugFrame(savePath);

        showToast("正在保存帧，查看Logcat");
        Log.d(TAG, "=== 保存调试帧 ===");
        Log.d(TAG, "保存路径: " + savePath);
        Log.d(TAG, "获取文件命令: adb pull " + savePath + "/ ./");
        // 播放命令示例（YUYV格式）：
        Log.d(TAG, "播放命令示例: ffplay -f rawvideo -pixel_format yuyv422 -video_size 720x576 frame_720x576_yuyv_raw.raw");
    }

    /**
     * 更新按钮状态
     */
    private void updateButtonStates(boolean connected, boolean previewing) {
        btnConnect.setEnabled(!connected);
        btnDisconnect.setEnabled(connected);
        btnStart.setEnabled(connected && !previewing);
        btnStop.setEnabled(connected && previewing);
        btnStartRecord.setEnabled(connected && previewing);
        btnStopRecord.setEnabled(false);
        btnSaveFrame.setEnabled(connected && previewing);
        etDevicePath.setEnabled(!connected);
    }

    /**
     * 请求设备权限
     */
    private void requestPermissionV1() {
//        new Thread(() -> {
//            try {
//                Process su = Runtime.getRuntime().exec("su");
//                DataOutputStream outputStream = new DataOutputStream(su.getOutputStream());
//                outputStream.writeBytes("chmod 666 /dev/video*\n");
//                outputStream.flush();
//                outputStream.writeBytes("exit\n");
//                outputStream.flush();
//                su.waitFor();
//                outputStream.close();
//                Log.d(TAG, "设备权限授予成功");
//            } catch (IOException | InterruptedException e) {
//                Log.e(TAG, "设备权限授予失败: " + e.getMessage());
//            }
//        }).start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRecording();
        disconnect();
    }

    private void showToast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    /**
     * 扫描所有视频设备，查找有有效信号的设备
     */
    private void scanDevices() {
        // 确保当前没有连接
        if (camera != null) {
            showToast("请先断开当前连接");
            return;
        }

        btnScanDevices.setEnabled(false);
        btnScanDevices.setText("扫描中...");
        scrollScanResult.setVisibility(View.VISIBLE);
        tvScanResult.setText("开始扫描设备...\n");

        // 在后台线程中扫描
        new Thread(() -> {
            StringBuilder result = new StringBuilder();
            result.append("扫描结果：\n");
            result.append("================\n\n");

            final int[] validDeviceCount = {0};
            final int[] totalDeviceCount = {0};

            // 扫描 video0 到 video44
            for (int i = 0; i <= 44; i++) {
                String devicePath = "/dev/video" + i;

                // 更新UI显示当前扫描进度
                final String progressMsg = "正在扫描: " + devicePath;
                runOnUiThread(() -> tvScanResult.append(progressMsg + "\n"));

                // 测试设备
                DeviceScanResult scanResult = testDevice(devicePath);

                if (scanResult != null) {
                    totalDeviceCount[0]++;
                    String deviceInfo = String.format("[%s]\n", devicePath);

                    if (scanResult.hasValidSignal) {
                        validDeviceCount[0]++;
                        deviceInfo += String.format("  ✓ 有效信号！\n");
                        deviceInfo += String.format("  分辨率: %dx%d\n", scanResult.width, scanResult.height);
                        deviceInfo += String.format("  Y值范围: %d-%d (平均:%d)\n",
                            scanResult.minY, scanResult.maxY, scanResult.avgY);
                        deviceInfo += String.format("  格式: %s\n", scanResult.format);
                    } else {
                        deviceInfo += String.format("  ✗ 无信号或黑屏\n");
                        deviceInfo += String.format("  Y值: %d (接近黑色)\n", scanResult.avgY);
                    }
                    deviceInfo += "\n";

                    final String info = deviceInfo;
                    runOnUiThread(() -> tvScanResult.append(info));
                    result.append(deviceInfo);
                }

                // 短暂延迟，避免过快扫描
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            // 显示汇总
            final String summary = String.format(
                "================\n扫描完成！\n可用设备: %d\n有效信号: %d\n",
                totalDeviceCount[0], validDeviceCount[0]
            );
            result.append(summary);

            final int finalValidCount = validDeviceCount[0];
            runOnUiThread(() -> {
                tvScanResult.append(summary);
                btnScanDevices.setEnabled(true);
                btnScanDevices.setText("扫描所有设备（查找有效信号）");

                if (finalValidCount > 0) {
                    showToast("发现 " + finalValidCount + " 个有效设备！");
                } else {
                    showToast("未发现有效信号设备");
                }
            });

            Log.d(TAG, "设备扫描完成:\n" + result.toString());
        }).start();
    }

    /**
     * 设备扫描结果
     */
    private static class DeviceScanResult {
        boolean hasValidSignal;
        int width;
        int height;
        String format;
        int minY;
        int maxY;
        int avgY;
    }

    /**
     * 测试单个设备
     */
    private DeviceScanResult testDevice(String devicePath) {
        CameraAPI testCamera = null;
        try {
            // 1. 尝试连接设备
            testCamera = new CameraAPI();
            boolean connected = testCamera.connectByPath(devicePath);

            if (!connected) {
                Log.d(TAG, "testDevice: 无法连接 " + devicePath);
                return null;  // 设备不存在或无法打开
            }

            // 2. 获取支持的分辨率
            int[][] supportFrameSize = testCamera.getSupportFrameSize();
            if (supportFrameSize == null || supportFrameSize.length == 0) {
                testCamera.destroy();
                return null;
            }

            // 3. 选择一个较小的分辨率进行测试（640x480 或中间分辨率）
            int[] selectedSize = selectTestResolution(supportFrameSize);
            int width = selectedSize[0];
            int height = selectedSize[1];

            // 4. 设置帧大小
            boolean ret = testCamera.setFrameSize(width, height, frameFormat);
            if (!ret) {
                testCamera.destroy();
                return null;
            }

            // 5. 启动预览（不设置surface，只获取帧数据）
            final boolean[] frameReceived = {false};
            final int[] yValues = new int[10];  // 采样10个像素的Y值
            final int[] frameCount = {0};

            testCamera.setFrameCallback((frame) -> {
                if (frameCount[0] == 0 && frame != null && frame.capacity() >= 20) {
                    // 采样前10个Y值（YUYV格式：Y0 U0 Y1 V0...）
                    for (int i = 0; i < 10 && i * 2 < frame.capacity(); i++) {
                        yValues[i] = frame.get(i * 2) & 0xFF;
                    }
                    frameReceived[0] = true;
                }
                frameCount[0]++;
            });

            testCamera.start();

            // 6. 等待接收到帧（最多等待2秒）
            long startTime = System.currentTimeMillis();
            while (!frameReceived[0] && System.currentTimeMillis() - startTime < 2000) {
                Thread.sleep(100);
            }

            testCamera.stop();
            testCamera.destroy();

            // 7. 分析结果
            if (frameReceived[0]) {
                DeviceScanResult result = new DeviceScanResult();
                result.width = width;
                result.height = height;
                result.format = frameFormat == CameraAPI.FRAME_FORMAT_YUYV ? "YUYV" : "MJPEG";

                // 计算Y值统计
                int sum = 0;
                int min = 255;
                int max = 0;
                for (int y : yValues) {
                    sum += y;
                    if (y < min) min = y;
                    if (y > max) max = y;
                }
                result.avgY = sum / yValues.length;
                result.minY = min;
                result.maxY = max;

                // 判断是否有效信号：平均Y值 > 50 或者 Y值范围 > 30
                result.hasValidSignal = (result.avgY > 50) || (result.maxY - result.minY > 30);

                Log.d(TAG, String.format("testDevice: %s - Y范围[%d-%d] 平均=%d 有效=%b",
                    devicePath, min, max, result.avgY, result.hasValidSignal));

                return result;
            }

        } catch (Exception e) {
            Log.e(TAG, "testDevice: 测试设备失败 " + devicePath, e);
        } finally {
            if (testCamera != null) {
                try {
                    testCamera.destroy();
                } catch (Exception e) {
                    // ignore
                }
            }
        }

        return null;
    }

    /**
     * 选择测试用的分辨率（选择较小的分辨率以加快扫描速度）
     */
    private int[] selectTestResolution(int[][] supportFrameSize) {
        // 优先选择 640x480
        for (int[] size : supportFrameSize) {
            if (size[0] == 640 && size[1] == 480) {
                return size;
            }
        }
        // 否则选择第一个分辨率
        return supportFrameSize[0];
    }
}
