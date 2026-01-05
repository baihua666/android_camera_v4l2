//
// Created by Hsj on 2021/5/31.
//

#ifndef ANDROID_CAMERA_V4L2_CAMERAAPI_H
#define ANDROID_CAMERA_V4L2_CAMERAAPI_H

#include <vector>
#include <pthread.h>
#include "NativeAPI.h"
#include "CameraView.h"
#include "DecoderFactory.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    STATUS_CREATE   = 0,
    STATUS_OPEN     = 1,
    STATUS_INIT     = 2,
    STATUS_RUN      = 3,
}StatusInfo;

typedef enum {
    FRAME_FORMAT_MJPEG = 0,
    FRAME_FORMAT_YUYV  = 1,
    FRAME_FORMAT_DEPTH = 2,
} FrameFormat;

struct VideoBuffer {
    void *start;
    size_t length;
};

class CameraAPI {
private:
    int fd;
    int frameWidth;
    int frameHeight;
    int frameFormat;
    bool useMultiplanar;  // 是否使用多平面 API

    size_t pixelBytes;
    uint8_t* out_buffer;
    VideoBuffer* buffers;
    DecoderFactory* decoder;

    CameraView *preview;
    jobject frameCallback;
    jmethodID frameCallback_onFrame;

    pthread_t thread_camera;
    volatile StatusInfo status;
    inline const StatusInfo getStatus() const;

    // 调试功能：保存单帧数据
    volatile bool saveFrameRequested;
    char debugSavePath[256];

    ActionInfo prepareBuffer();
    static void* loopThread(void *args);
    void loopFrame(JNIEnv *env, CameraAPI *camera);
    void sendFrame(JNIEnv *env, uint8_t *data);
    void renderFrame(uint8_t *data);

    // 设备打开和验证
    ActionInfo openDevice(const char* devicePath);
    bool validateDevicePath(const char* devicePath);

    // 调试：保存帧数据到文件
    void saveFrameToFile(const uint8_t* data, size_t size, const char* suffix);

public:
    CameraAPI();
    ~CameraAPI();
    ActionInfo connect(unsigned int pid, unsigned int vid);
    ActionInfo connectByPath(const char* devicePath);
    ActionInfo autoExposure(bool isAuto);
    ActionInfo updateExposure(unsigned int level);
    ActionInfo getSupportSize(std::vector<std::pair<int, int>> &sizes);
    ActionInfo setFrameSize(int width, int height, int frame_format);
    void getActualFrameSize(int &width, int &height);
    ActionInfo setFrameCallback(JNIEnv *env, jobject frame_callback);
    ActionInfo setPreview(ANativeWindow *window);
    ActionInfo start();
    ActionInfo stop();
    ActionInfo close();
    ActionInfo destroy();

    // 调试接口：请求保存下一帧
    void requestSaveFrame(const char* savePath);
};

#ifdef __cplusplus
}  // extern "C"
#endif

#endif //ANDROID_CAMERA_V4L2_CAMERAAPI_H
