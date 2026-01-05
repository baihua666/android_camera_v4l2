package com.hsj.sample;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.util.Pair;

import com.hsj.camera.CameraAPI;
import com.hsj.camera.CameraView;
import com.hsj.camera.IFrameCallback;
import com.hsj.camera.IRender;
import com.hsj.camera.ISurfaceCallback;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.Locale;

/**
 * @Author:Hsj
 * @Date:2021/5/10
 * @Class:MainActivity
 * @Desc:
 */
public final class MainActivity extends AppCompatActivity implements ISurfaceCallback {

    private static final String TAG = "MainActivity";
    // Usb device: productId
    private int pid;
    // Usb device: vendorId
    private int vid;
    // Dialog checked index
    private int index;
    // CameraAPI
    private CameraAPI camera;
    // IRender
    private IRender render;
    private Surface surface;
    private LinearLayout ll;

    // Video Recording
    private V4L2VideoRecorder videoRecorder;
    private Button btnStartRecord;
    private Button btnStopRecord;
    private int videoWidth;
    private int videoHeight;

//    yuv格式优先使用720*576
    private int frameFormat = CameraAPI.FRAME_FORMAT_YUYV;  // 使用 YUYV 格式（设备不支持 MJPEG）
//    private int frameFormat = CameraAPI.FRAME_FORMAT_MJPEG;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 添加测试页面入口
        findViewById(R.id.btn_path_test).setOnClickListener(v -> {
            android.content.Intent intent = new android.content.Intent(this, PathTestActivity.class);
            startActivity(intent);
        });

        findViewById(R.id.btn_create).setOnClickListener(v -> create());
        findViewById(R.id.btn_start).setOnClickListener(v -> start());
        findViewById(R.id.btn_stop).setOnClickListener(v -> stop());
        findViewById(R.id.btn_destroy).setOnClickListener(v -> destroy());
        ll = findViewById(R.id.ll);
        CameraView cameraView = findViewById(R.id.cameraView);
        this.render = cameraView.getRender(CameraView.COMMON);
        this.render.setSurfaceCallback(this);

        // 初始化录制按钮
        btnStartRecord = findViewById(R.id.btn_start_record);
        btnStopRecord = findViewById(R.id.btn_stop_record);
        btnStartRecord.setOnClickListener(v -> startRecording());
        btnStopRecord.setOnClickListener(v -> stopRecording());

        // 初始化保存调试帧按钮
        findViewById(R.id.btn_save_frame).setOnClickListener(v -> saveDebugFrame());

        //Request permission: /dev/video*
        requestPermissionV1();
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

    @Override
    protected void onStop() {
        super.onStop();
        stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroy();
    }

//==========================================Menu====================================================

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.item_camera) {
            showSingleChoiceDialog();
        }
        return super.onOptionsItemSelected(item);
    }

//===========================================Camera=================================================

    private void create() {
        if (this.camera == null) {
            CameraAPI camera = new CameraAPI();

            // 连接方式 1: 通过 USB PID/VID 连接（适用于 USB 设备）
            boolean ret = camera.create(pid, vid);

            // 连接方式 2: 通过设备路径直接连接（适用于所有 V4L2 设备）
            // 如果你知道设备路径，可以直接使用以下方式连接：
            // boolean ret = camera.connectByPath("/dev/video0");
            // 或者：
            // boolean ret = camera.connectByPath("/dev/video23");
            //
            // 优点：
            // - 更直接，无需知道 PID/VID
            // - 支持所有 V4L2 设备（不仅限 USB）
            // - 调试更方便
            int[][] supportFrameSize = camera.getSupportFrameSize();
            if (supportFrameSize == null || supportFrameSize.length == 0) {
                showToast("Get support preview size failed.");
            } else {
                // 打印所有支持的分辨率
                Log.d(TAG, "Supported resolutions (" + supportFrameSize.length + "):");
                for (int i = 0; i < supportFrameSize.length; i++) {
                    Log.d(TAG, "  [" + i + "] " + supportFrameSize[i][0] + "x" + supportFrameSize[i][1]);
                }

                // 选择最佳分辨率：优先 1080P，其次 720P，否则选择中间分辨率
                int[] selectedSize = selectBestResolution(supportFrameSize);
                final int width = selectedSize[0];
                final int height = selectedSize[1];
                Log.d(TAG, "Selected resolution: " + width + "x" + height);

                // 使用 MJPEG 格式以支持 1920x1080 高分辨率
                if (ret) {
                    ret = camera.setFrameSize(width, height, frameFormat);
                    if (!ret) {
                        Log.e(TAG, "setFrameSize failed for " + width + "x" + height + " MJPEG");
                        showToast("Set frame size failed");
                    } else {
                        // 获取驱动实际设置的分辨率（可能与请求的不同）
                        android.util.Pair<Integer, Integer> actualSize = camera.getActualFrameSize();
                        if (actualSize != null) {
                            this.videoWidth = actualSize.first;
                            this.videoHeight = actualSize.second;
                            Log.d(TAG, "Actual frame size: " + this.videoWidth + "x" + this.videoHeight +
                                  (this.videoWidth != width || this.videoHeight != height ?
                                   " (different from requested " + width + "x" + height + ")" : ""));
                        } else {
                            // 如果获取失败，使用请求的分辨率作为后备
                            this.videoWidth = width;
                            this.videoHeight = height;
                            Log.w(TAG, "Failed to get actual frame size, using requested: " + width + "x" + height);
                        }
                    }
                }
                if (ret) {
                    this.camera = camera;

                    // 启用自动曝光（解决画面偏暗问题）
                    boolean autoExposureResult = camera.setAutoExposure(true);
                    Log.d(TAG, "设置自动曝光: " + (autoExposureResult ? "成功" : "失败"));
                }
            }
        } else {
            showToast("Camera had benn created");
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
            // 第二优先：640x480
            for (int[] size : supportFrameSize) {
                if (size[0] == 640 && size[1] == 480) {
                    Log.d(TAG, "找到 640x480 分辨率（YUYV 备选）");
                    return size;
                }
            }
        }

        // 否则选择中间分辨率
        int index = supportFrameSize.length / 2;
        Log.d(TAG, "使用中间分辨率 [" + index + "]: " +
            supportFrameSize[index][0] + "x" + supportFrameSize[index][1]);
        return supportFrameSize[index];
    }

    private void start() {
        if (this.camera != null) {
            if (surface != null) this.camera.setPreview(surface);
            this.camera.setFrameCallback(frameCallback);
            this.camera.start();
        } else {
            showToast("Camera have not create");
        }
    }

    private final IFrameCallback frameCallback = frame -> {
        // 如果正在录制，将帧数据传递给 VideoRecorder
        if (videoRecorder != null && videoRecorder.isRecording()) {
            videoRecorder.writeFrame(frame);
        }
    };

    private void stop() {
        if (this.camera != null) {
            this.camera.stop();
        }
    }

    private void destroy() {
        if (this.camera != null) {
            this.camera.destroy();
            this.camera = null;
        }
    }

//=============================================Other================================================

    private boolean requestPermission() {
        boolean result;
        Process process = null;
        DataOutputStream dos = null;
        try {
            process = Runtime.getRuntime().exec("su");
            dos = new DataOutputStream(process.getOutputStream());
            dos.writeBytes("chmod 666 /dev/video*\n");
            dos.writeBytes("exit\n");
            dos.flush();
            result = (process.waitFor() == 0);
        } catch (Exception e) {
            e.printStackTrace();
            result = false;
        } finally {
            try {
                if (dos != null) {
                    dos.close();
                }
                if (process != null) {
                    process.destroy();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        Log.d(TAG, "request video rw permission: " + result);
        return result;
    }

    private boolean requestPermissionV1() {
        try {
            // 使用系统API直接设置权限
//            java.lang.reflect.Method setGid = android.os.Process.class.getMethod("setGid", int.class);
//            java.lang.reflect.Method setUid = android.os.Process.class.getMethod("setUid", int.class);
//
//            // 如果系统支持，设置到root权限 (需要系统签名)
//            setGid.invoke(null, 0);
//            setUid.invoke(null, 0);

            // 或者直接使用chmod命令（不需要su）
            Runtime.getRuntime().exec("chmod 777 /dev/video*");
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }


    private void showSingleChoiceDialog() {
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        Collection<UsbDevice> values = usbManager.getDeviceList().values();
        final UsbDevice[] devices = values.toArray(new UsbDevice[]{});
        int size = devices.length;
        if (size == 0) {
            showToast("No Usb device to be found");
        } else {
            // stop and destroy
            stop();
            destroy();
            this.ll.setVisibility(View.GONE);
            // get Usb devices name
            String[] items = new String[size];
            for (int i = 0; i < size; ++i) {
                items[i] = "Device: " + devices[i].getProductName();
            }
            // dialog
            if (index >= size) index = 0;
            AlertDialog.Builder ad = new AlertDialog.Builder(this);
            ad.setTitle(R.string.select_usb_device);
            ad.setSingleChoiceItems(items, index, (dialog, which) -> index = which);
            ad.setPositiveButton(R.string.btn_confirm, (dialog, which) -> {
                this.pid = devices[index].getProductId();
                this.vid = devices[index].getVendorId();
                this.ll.setVisibility(View.VISIBLE);
            });
            ad.show();
        }
    }

    @Override
    public void onSurface(Surface surface) {
        if (surface == null) stop();
        this.surface = surface;
    }

    private void showToast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private boolean saveFile(String dstFile, ByteBuffer data) {
        if (TextUtils.isEmpty(dstFile)) return false;
        boolean ret = false;
        FileChannel fc = null;
        try {
            fc = new FileOutputStream(dstFile).getChannel();
            fc.write(data);
            ret = true;
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (fc != null) {
                try {
                    fc.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return ret;
    }

//==========================================Video Recording=========================================

    /**
     * 生成录制输出文件路径
     * 业务层负责生成路径，便于自定义存储策略
     */
    private String generateOutputPath() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
        String timestamp = sdf.format(new Date());
        File videoDir = new File(getExternalFilesDir(null), "videos");
        if (!videoDir.exists()) {
            videoDir.mkdirs();
        }
        return new File(videoDir, "video_" + timestamp + ".mp4").getAbsolutePath();
    }

    /**
     * 开始录制视频
     */
    private void startRecording() {
        if (this.camera == null) {
            showToast("Please create camera first");
            return;
        }

        if (videoRecorder != null && videoRecorder.isRecording()) {
            showToast("Already recording");
            return;
        }

        // 业务层生成输出文件路径
        String outputPath = generateOutputPath();

        // 创建 V4L2VideoRecorder，传入路径
        videoRecorder = new V4L2VideoRecorder(videoWidth, videoHeight, outputPath);

        // 开始录制
        if (videoRecorder.start()) {
            btnStartRecord.setEnabled(false);
            btnStopRecord.setEnabled(true);
            showToast("Recording started: " + outputPath);
            Log.d(TAG, "Recording started: " + outputPath);
        } else {
            videoRecorder = null;
            showToast("Failed to start recording");
        }
    }

    /**
     * 停止录制视频
     */
    private void stopRecording() {
        if (videoRecorder == null || !videoRecorder.isRecording()) {
            showToast("Not recording");
            return;
        }

        videoRecorder.stop();
        videoRecorder = null;

        btnStartRecord.setEnabled(true);
        btnStopRecord.setEnabled(false);

        showToast("Recording stopped");
        Log.d(TAG, "Recording stopped");
    }

//==========================================Debug Frame Save=========================================

    /**
     * 保存调试帧 - 用于分析预览黑屏问题
     * 保存的文件可以通过 ADB pull 获取并分析
     */
    private void saveDebugFrame() {
        if (this.camera == null) {
            showToast("Please create camera first");
            return;
        }

        // 保存到应用私有目录，确保有写入权限
        File debugDir = new File(getExternalFilesDir(null), "debug_frames");
        if (!debugDir.exists()) {
            debugDir.mkdirs();
        }

        String savePath = debugDir.getAbsolutePath();
        this.camera.saveDebugFrame(savePath);

        showToast("正在保存帧到: " + savePath);
        Log.d(TAG, "Saving debug frame to: " + savePath);
        Log.d(TAG, "使用以下命令获取文件:");
        Log.d(TAG, "  adb pull " + savePath + "/ ./");
    }

}