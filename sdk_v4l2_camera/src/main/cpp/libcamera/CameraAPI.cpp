//
// Created by Hsj on 2021/5/31.
//

#include "CameraAPI.h"
#include "Common.h"
#include <malloc.h>
#include <sstream>
#include <fstream>
#include <cstring>
#include <cstdio>
#include <cassert>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <sys/ioctl.h>
#include <linux/videodev2.h>

#ifdef __cplusplus
extern "C" {
#endif

#define TAG "CameraAPI"
#define MAX_BUFFER_COUNT 4
#define MAX_DEV_VIDEO_INDEX 99

// 兼容性定义：确保 V4L2 多平面 API 支持
#ifndef V4L2_CAP_VIDEO_CAPTURE_MPLANE
#define V4L2_CAP_VIDEO_CAPTURE_MPLANE 0x00001000
#endif

#ifndef V4L2_CAP_DEVICE_CAPS
#define V4L2_CAP_DEVICE_CAPS 0x80000000
#endif

#ifndef V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE
#define V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE 9
#endif

CameraAPI::CameraAPI() :
        fd(0),
        pixelBytes(0),
        frameWidth(0),
        frameHeight(0),
        frameFormat(0),
        useMultiplanar(false),
        thread_camera(0),
        status(STATUS_CREATE),
        preview(NULL),
        decoder(NULL),
        buffers(NULL),
        out_buffer(NULL),
        frameCallback(NULL),
        frameCallback_onFrame(NULL),
        saveFrameRequested(false) {
    memset(debugSavePath, 0, sizeof(debugSavePath));
}

CameraAPI::~CameraAPI() {
    destroy();
}

//=======================================Private====================================================

inline const StatusInfo CameraAPI::getStatus() const { return status; }

ActionInfo CameraAPI::prepareBuffer() {
    // 根据设备类型选择正确的缓冲区类型
    enum v4l2_buf_type buf_type = static_cast<v4l2_buf_type>(
        useMultiplanar ? V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE : V4L2_BUF_TYPE_VIDEO_CAPTURE);

    //1-request buffers
    struct v4l2_requestbuffers buffer1;
    memset(&buffer1, 0, sizeof(buffer1));
    buffer1.count = MAX_BUFFER_COUNT;
    buffer1.type = buf_type;
    buffer1.memory = V4L2_MEMORY_MMAP;
    if (0 > ioctl(fd, VIDIOC_REQBUFS, &buffer1)) {
        LOGE(TAG, "prepareBuffer: ioctl VIDIOC_REQBUFS failed: %s", strerror(errno));
        return ACTION_ERROR_START;
    }

    //2-query memory
    buffers = (struct VideoBuffer *) calloc(MAX_BUFFER_COUNT, sizeof(*buffers));
    for (unsigned int i = 0; i < MAX_BUFFER_COUNT; ++i) {
        struct v4l2_buffer buffer2;
        struct v4l2_plane planes[1];
        memset(&buffer2, 0, sizeof(buffer2));
        memset(planes, 0, sizeof(planes));

        buffer2.type = buf_type;
        buffer2.memory = V4L2_MEMORY_MMAP;
        buffer2.index = i;

        if (useMultiplanar) {
            buffer2.m.planes = planes;
            buffer2.length = 1;
        }

        if (0 > ioctl(fd, VIDIOC_QUERYBUF, &buffer2)) {
            LOGE(TAG, "prepareBuffer: ioctl VIDIOC_QUERYBUF failed: %s", strerror(errno));
            return ACTION_ERROR_START;
        }

        if (useMultiplanar) {
            buffers[i].length = buffer2.m.planes[0].length;
            buffers[i].start = mmap(NULL, buffer2.m.planes[0].length,
                                   PROT_READ | PROT_WRITE, MAP_SHARED, fd,
                                   buffer2.m.planes[0].m.mem_offset);
        } else {
            buffers[i].length = buffer2.length;
            buffers[i].start = mmap(NULL, buffer2.length,
                                   PROT_READ | PROT_WRITE, MAP_SHARED, fd,
                                   buffer2.m.offset);
        }

        if (MAP_FAILED == buffers[i].start) {
            LOGE(TAG, "prepareBuffer: mmap failed");
            return ACTION_ERROR_START;
        }
    }

    //3-queue buffers
    for (unsigned int i = 0; i < MAX_BUFFER_COUNT; ++i) {
        struct v4l2_buffer buffer3;
        struct v4l2_plane planes[1];
        memset(&buffer3, 0, sizeof(buffer3));
        memset(planes, 0, sizeof(planes));

        buffer3.type = buf_type;
        buffer3.memory = V4L2_MEMORY_MMAP;
        buffer3.index = i;

        if (useMultiplanar) {
            buffer3.m.planes = planes;
            buffer3.length = 1;
        }

        if (0 > ioctl(fd, VIDIOC_QBUF, &buffer3)) {
            LOGE(TAG, "prepareBuffer: ioctl VIDIOC_QBUF failed: %s", strerror(errno));
            return ACTION_ERROR_START;
        }
    }

    LOGD(TAG, "prepareBuffer: success (%s)", useMultiplanar ? "multiplanar" : "single-planar");
    return ACTION_SUCCESS;
}

void* CameraAPI::loopThread(void *args) {
    auto *camera = reinterpret_cast<CameraAPI *>(args);
    if (LIKELY(camera)) {
        JavaVM *vm = getVM();
        JNIEnv *env;
        // attach to JavaVM
        vm->AttachCurrentThread(&env, NULL);
        // never return until finish previewing
        camera->loopFrame(env, camera);
        // detach from JavaVM
        vm->DetachCurrentThread();
    }
    pthread_exit(NULL);
}

//uint64_t time0 = 0;
//uint64_t time1 = 0;

void CameraAPI::loopFrame(JNIEnv *env, CameraAPI *camera) {
    fd_set fds;
    struct timeval tv;
    struct v4l2_buffer buffer;
    struct v4l2_plane planes[1];

    memset(&buffer, 0, sizeof(buffer));
    memset(planes, 0, sizeof(planes));

    buffer.type = camera->useMultiplanar ? V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE : V4L2_BUF_TYPE_VIDEO_CAPTURE;
    buffer.memory = V4L2_MEMORY_MMAP;

    if (camera->useMultiplanar) {
        buffer.m.planes = planes;
        buffer.length = 1;
    }

    const int fd_count = camera->fd + 1;
    int frame_count = 0;
    LOGD(TAG, "loopFrame: started (fd=%d, multiplanar=%s)", camera->fd, camera->useMultiplanar ? "YES" : "NO");

    while (STATUS_RUN == camera->getStatus()) {
        tv.tv_sec = 1;
        tv.tv_usec = 0;
        FD_ZERO (&fds);
        FD_SET (camera->fd, &fds);

        int ret = select(fd_count, &fds, NULL, NULL, &tv);
        if (ret < 0) {
            LOGE(TAG, "Loop frame: select failed: %s", strerror(errno));
            continue;
        } else if (ret == 0) {
            LOGW(TAG, "Loop frame: select timeout (no data for 1 second)");
            continue;
        }

        // 有数据可读
        if (0 > ioctl(camera->fd, VIDIOC_DQBUF, &buffer)) {
            LOGE(TAG, "Loop frame: VIDIOC_DQBUF failed: %s", strerror(errno));
            break;
        }

        frame_count++;
        if (frame_count % 30 * 10 == 1) {  // 每 30 帧打印一次
            LOGD(TAG, "Loop frame: received frame #%d, index=%d", frame_count, buffer.index);
        }

        if (camera->frameFormat == FRAME_FORMAT_MJPEG) {
            //MJPEG->NV12/YUV422
            uint32_t bytesused = camera->useMultiplanar ? buffer.m.planes[0].bytesused : buffer.bytesused;
            uint8_t *data = camera->decoder->convert2YUV(camera->buffers[buffer.index].start, bytesused);

            // 调试：保存帧数据
            if (camera->saveFrameRequested) {
                camera->saveFrameRequested = false;
                // 保存原始 MJPEG 数据
                camera->saveFrameToFile((const uint8_t*)camera->buffers[buffer.index].start, bytesused, "mjpeg");
                // 保存解码后的 YUV 数据
                camera->saveFrameToFile(data, camera->pixelBytes, "yuv_decoded");
            }

            //Render->RGBA
            renderFrame(data);

            //Data->Java
            sendFrame(env, data);
        } else {
            //YUYV
            memcpy(out_buffer, camera->buffers[buffer.index].start, camera->pixelBytes);

            // 调试：保存帧数据
            if (camera->saveFrameRequested) {
                camera->saveFrameRequested = false;
                // 保存原始 YUYV 数据
                camera->saveFrameToFile(out_buffer, camera->pixelBytes, "yuyv_raw");

                // 额外分析 YUYV 数据中的 Y 分量
                LOGD(TAG, "loopFrame: YUYV analysis - checking Y values at different positions:");
                for (int row = 0; row < 5 && row * camera->frameWidth * 2 < camera->pixelBytes; row++) {
                    int offset = row * camera->frameWidth * 2 * (camera->frameHeight / 5);
                    if (offset + 8 <= camera->pixelBytes) {
                        LOGD(TAG, "  Row %d: Y0=%d U=%d Y1=%d V=%d Y2=%d U=%d Y3=%d V=%d",
                             row,
                             out_buffer[offset], out_buffer[offset+1],
                             out_buffer[offset+2], out_buffer[offset+3],
                             out_buffer[offset+4], out_buffer[offset+5],
                             out_buffer[offset+6], out_buffer[offset+7]);
                    }
                }
            }

            //Render->YUYV
            renderFrame(out_buffer);

            //YUYV->Java
            sendFrame(env, out_buffer);
        }

        if (0 > ioctl(camera->fd, VIDIOC_QBUF, &buffer)) {
            LOGW(TAG, "Loop frame: ioctl VIDIOC_QBUF %s", strerror(errno));
            continue;
        }
    }

    LOGD(TAG, "loopFrame: stopped (total frames: %d)", frame_count);
}

void CameraAPI::renderFrame(uint8_t *data) {
    static int render_call_count = 0;
    render_call_count++;
    if (render_call_count <= 3 || render_call_count % 1000 == 0) {
        LOGD(TAG, "renderFrame: call #%d, preview=%p, data=%p", render_call_count, preview, data);
    }
    if (LIKELY(preview && data)) {
        preview->render(data);
    } else {
        if (render_call_count <= 3) {
            LOGW(TAG, "renderFrame: skipped - preview=%p, data=%p", preview, data);
        }
    }
}

void CameraAPI::sendFrame(JNIEnv *env, uint8_t *data) {
    if (frameCallback_onFrame && LIKELY(data)) {
        jobject frame = env->NewDirectByteBuffer(data, pixelBytes);
        env->CallVoidMethod(frameCallback, frameCallback_onFrame, frame);
        env->DeleteLocalRef(frame);
        env->ExceptionClear();
    }
}

//=======================================Private====================================================

bool CameraAPI::validateDevicePath(const char* devicePath) {
    if (!devicePath || strlen(devicePath) == 0) {
        LOGW(TAG, "validateDevicePath: device path is null or empty");
        return false;
    }

    // 检查是否以 /dev/video 开头
    if (strncmp(devicePath, "/dev/video", 10) != 0) {
        LOGW(TAG, "validateDevicePath: invalid device path format: %s", devicePath);
        return false;
    }

    // 检查 /dev/video 后面是否跟着数字
    const char* numPart = devicePath + 10;
    if (strlen(numPart) == 0 || !isdigit(numPart[0])) {
        LOGW(TAG, "validateDevicePath: device path must end with number: %s", devicePath);
        return false;
    }

    // 检查文件是否存在
    if (access(devicePath, F_OK) != 0) {
        LOGW(TAG, "validateDevicePath: device path does not exist: %s", devicePath);
        return false;
    }

    return true;
}

ActionInfo CameraAPI::openDevice(const char* devicePath) {
    if (STATUS_CREATE != getStatus()) {
        LOGW(TAG, "openDevice: error status, %d", getStatus());
        return ACTION_ERROR_CREATE_HAD;
    }

    // 打开设备文件
    fd = open(devicePath, O_RDWR | O_NONBLOCK);
    if (0 > fd) {
        LOGE(TAG, "openDevice: %s failed, %s", devicePath, strerror(errno));
        if (errno == EACCES || errno == EPERM) {
            return ACTION_ERROR_DEVICE_ACCESS;
        }
        return ACTION_ERROR_OPEN_FAIL;
    }

    // 查询设备能力
    struct v4l2_capability cap;
    memset(&cap, 0, sizeof(cap));
    if (0 > ioctl(fd, VIDIOC_QUERYCAP, &cap)) {
        LOGE(TAG, "openDevice: ioctl VIDIOC_QUERYCAP failed, %s", strerror(errno));
        ::close(fd);
        fd = 0;
        return ACTION_ERROR_START;
    }

    // 打印设备能力信息（用于调试）
    LOGD(TAG, "openDevice: device capabilities:");
    LOGD(TAG, "  driver: %s", cap.driver);
    LOGD(TAG, "  card: %s", cap.card);
    LOGD(TAG, "  bus_info: %s", cap.bus_info);
    LOGD(TAG, "  version: %u.%u.%u", (cap.version >> 16) & 0xFF, (cap.version >> 8) & 0xFF, cap.version & 0xFF);
    LOGD(TAG, "  capabilities: 0x%08X", cap.capabilities);
    LOGD(TAG, "  device_caps: 0x%08X", cap.device_caps);

    // 检查是否支持视频捕获（支持单平面或多平面API）
    uint32_t caps = (cap.capabilities & V4L2_CAP_DEVICE_CAPS) ? cap.device_caps : cap.capabilities;
    bool supportsCapture = (caps & V4L2_CAP_VIDEO_CAPTURE) || (caps & V4L2_CAP_VIDEO_CAPTURE_MPLANE);

    if (!supportsCapture) {
        LOGE(TAG, "openDevice: device does not support video capture");
        LOGE(TAG, "  V4L2_CAP_VIDEO_CAPTURE: %s", (caps & V4L2_CAP_VIDEO_CAPTURE) ? "YES" : "NO");
        LOGE(TAG, "  V4L2_CAP_VIDEO_CAPTURE_MPLANE: %s", (caps & V4L2_CAP_VIDEO_CAPTURE_MPLANE) ? "YES" : "NO");
        ::close(fd);
        fd = 0;
        return ACTION_ERROR_START;
    }

    // 设置多平面标志
    useMultiplanar = (caps & V4L2_CAP_VIDEO_CAPTURE_MPLANE) != 0;
    LOGD(TAG, "openDevice: video capture supported (capabilities=0x%08X, multiplanar=%s)",
         caps, useMultiplanar ? "YES" : "NO");

    LOGD(TAG, "openDevice: %s succeed", devicePath);
    status = STATUS_OPEN;
    return ACTION_SUCCESS;
}

//=======================================Public=====================================================

ActionInfo CameraAPI::connect(unsigned int target_pid, unsigned int target_vid) {
    ActionInfo action = ACTION_SUCCESS;
    if (STATUS_CREATE == getStatus()) {
        std::string modalias;
        std::string dev_video_name;
        for (int i = 0; i <= MAX_DEV_VIDEO_INDEX; ++i) {
            int vid = 0, pid = 0;
            dev_video_name.append("video").append(std::to_string(i));
            if (!(std::ifstream("/sys/class/video4linux/" + dev_video_name + "/device/modalias") >> modalias)) {
                LOGD(TAG, "dev/%s : read modalias failed", dev_video_name.c_str());
            } else if (modalias.size() < 14 || modalias.substr(0, 5) != "usb:v" || modalias[9] != 'p') {
                LOGD(TAG, "dev/%s : format is not a usb of modalias", dev_video_name.c_str());
            } else if (!(std::istringstream(modalias.substr(5, 4)) >> std::hex >> vid)) {
                LOGD(TAG, "dev/%s : read vid failed", dev_video_name.c_str());
            } else if (!(std::istringstream(modalias.substr(10, 4)) >> std::hex >> pid)) {
                LOGD(TAG, "dev/%s : read pid failed", dev_video_name.c_str());
            } else {
                LOGD(TAG, "dev/%s : vid=%d, pid=%d", dev_video_name.c_str(), vid, pid);
            }
            if (target_pid == pid && target_vid == vid) {
                dev_video_name.insert(0, "/dev/");
                break;
            } else {
                modalias.clear();
                dev_video_name.clear();
            }
        }
        if (dev_video_name.empty()) {
            LOGW(TAG, "connect: no target device");
            action = ACTION_ERROR_NO_DEVICE;
        } else {
            // 使用统一的设备打开方法
            action = openDevice(dev_video_name.data());
        }
    } else {
        LOGW(TAG, "open: error status, %d", getStatus());
        action = ACTION_ERROR_CREATE_HAD;
    }
    return action;
}

ActionInfo CameraAPI::connectByPath(const char* devicePath) {
    ActionInfo action = ACTION_SUCCESS;

    // 验证设备路径格式
    if (!validateDevicePath(devicePath)) {
        LOGW(TAG, "connectByPath: invalid device path: %s", devicePath ? devicePath : "null");
        return ACTION_ERROR_INVALID_PATH;
    }

    // 使用统一的设备打开方法
    action = openDevice(devicePath);

    return action;
}

ActionInfo CameraAPI::autoExposure(bool isAuto) {
    if (STATUS_OPEN <= getStatus()) {
        struct v4l2_control ctrl;
        //SAFE_CLEAR(ctrl)
        ctrl.id = V4L2_CID_EXPOSURE_AUTO;
        ctrl.value = isAuto ? V4L2_EXPOSURE_AUTO : V4L2_EXPOSURE_MANUAL;
        if (0 > ioctl(fd, VIDIOC_S_CTRL, &ctrl)) {
            LOGW(TAG, "autoExposure: ioctl VIDIOC_S_CTRL failed, %s", strerror(errno));
            return ACTION_ERROR_AUTO_EXPOSURE;
        } else {
            LOGD(TAG, "autoExposure: success");
            return ACTION_SUCCESS;
        }
    } else {
        LOGW(TAG, "autoExposure: error status, %d", getStatus());
        return ACTION_ERROR_AUTO_EXPOSURE;
    }
}

ActionInfo CameraAPI::updateExposure(unsigned int level) {
    if (STATUS_OPEN <= getStatus()) {
        struct v4l2_control ctrl;
        //SAFE_CLEAR(ctrl)
        ctrl.id = V4L2_CID_EXPOSURE_ABSOLUTE;
        ctrl.value = level;
        if (0 > ioctl(fd, VIDIOC_S_CTRL, &ctrl)) {
            LOGE(TAG, "updateExposure: ioctl failed, %s", strerror(errno));
            return ACTION_ERROR_SET_EXPOSURE;
        } else {
            LOGD(TAG, "updateExposure: success");
            return ACTION_SUCCESS;
        }
    } else {
        LOGW(TAG, "updateExposure: error status, %d", getStatus());
        return ACTION_ERROR_SET_EXPOSURE;
    }
}

ActionInfo CameraAPI::getSupportSize(std::vector<std::pair<int, int>> &sizes) {
    if (STATUS_OPEN <= getStatus()) {
        struct v4l2_frmsizeenum frmsize;
        struct v4l2_fmtdesc fmtdesc;
        memset(&fmtdesc, 0, sizeof(fmtdesc));
        memset(&frmsize, 0, sizeof(frmsize));

        // 根据设备类型选择正确的缓冲区类型
        fmtdesc.type = useMultiplanar ? V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE : V4L2_BUF_TYPE_VIDEO_CAPTURE;
        fmtdesc.index = 0;

        LOGD(TAG, "getSupportSize: using %s API", useMultiplanar ? "multiplanar" : "single-planar");

        while (ioctl(fd, VIDIOC_ENUM_FMT, &fmtdesc) == 0) {
            // 打印格式信息
            char fourcc[5] = {0};
            fourcc[0] = fmtdesc.pixelformat & 0xFF;
            fourcc[1] = (fmtdesc.pixelformat >> 8) & 0xFF;
            fourcc[2] = (fmtdesc.pixelformat >> 16) & 0xFF;
            fourcc[3] = (fmtdesc.pixelformat >> 24) & 0xFF;
            LOGD(TAG, "  Format[%d]: %s (%s), flags=0x%08X",
                 fmtdesc.index, fmtdesc.description, fourcc, fmtdesc.flags);

            frmsize.pixel_format = fmtdesc.pixelformat;
            frmsize.index = 0;

            while (ioctl(fd, VIDIOC_ENUM_FRAMESIZES, &frmsize) == 0) {
                if (frmsize.type == V4L2_FRMSIZE_TYPE_DISCRETE) {
                    // 对于压缩格式（如 MJPEG），添加到列表
                    if (fmtdesc.flags & V4L2_FMT_FLAG_COMPRESSED) {
                        sizes.emplace_back(frmsize.discrete.width, frmsize.discrete.height);
                        LOGD(TAG, "    Size[%d]: %dx%d (compressed)",
                             frmsize.index, frmsize.discrete.width, frmsize.discrete.height);
                    } else {
                        // 对于非压缩格式，也添加到列表（修改之前的行为）
                        sizes.emplace_back(frmsize.discrete.width, frmsize.discrete.height);
                        LOGD(TAG, "    Size[%d]: %dx%d (uncompressed)",
                             frmsize.index, frmsize.discrete.width, frmsize.discrete.height);
                    }
                } else if (frmsize.type == V4L2_FRMSIZE_TYPE_STEPWISE || frmsize.type == V4L2_FRMSIZE_TYPE_CONTINUOUS) {
                    LOGD(TAG, "    Size[%d]: %dx%d to %dx%d (step: %dx%d)",
                         frmsize.index,
                         frmsize.stepwise.min_width, frmsize.stepwise.min_height,
                         frmsize.stepwise.max_width, frmsize.stepwise.max_height,
                         frmsize.stepwise.step_width, frmsize.stepwise.step_height);
                    // 添加一些常见分辨率
                    if (frmsize.stepwise.max_width >= 1920 && frmsize.stepwise.max_height >= 1080) {
                        sizes.emplace_back(1920, 1080);
                    }
                    if (frmsize.stepwise.max_width >= 1280 && frmsize.stepwise.max_height >= 720) {
                        sizes.emplace_back(1280, 720);
                    }
                    if (frmsize.stepwise.max_width >= 640 && frmsize.stepwise.max_height >= 480) {
                        sizes.emplace_back(640, 480);
                    }
                } else {
                    LOGW(TAG, "    Size[%d]: unknown type=%d", frmsize.index, frmsize.type);
                }
                frmsize.index++;
            }
            fmtdesc.index++;
        }

        LOGD(TAG, "getSupportSize: found %zu resolutions", sizes.size());
        return ACTION_SUCCESS;
    } else {
        LOGW(TAG, "getSupportSize: error status, %d", getStatus());
        return ACTION_ERROR_GET_W_H;
    }
}

ActionInfo CameraAPI::setFrameSize(int width, int height, int frame_format) {
    if (STATUS_OPEN == getStatus()) {
        //1-set frame width and height
        struct v4l2_format format;
        memset(&format, 0, sizeof(format));

        // 根据设备类型选择正确的缓冲区类型
        format.type = useMultiplanar ? V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE : V4L2_BUF_TYPE_VIDEO_CAPTURE;

        if (useMultiplanar) {
            // 多平面 API
            format.fmt.pix_mp.width = width;
            format.fmt.pix_mp.height = height;
            format.fmt.pix_mp.field = V4L2_FIELD_ANY;
            format.fmt.pix_mp.pixelformat = frame_format ? V4L2_PIX_FMT_YUYV : V4L2_PIX_FMT_MJPEG;
            format.fmt.pix_mp.num_planes = 1;  // YUYV 和 MJPEG 都是单平面的打包格式

            // 设置色彩空间和量化范围（重要！）
            // 使用 ITU-R BT.709 色彩空间（适用于 HD 视频）
            format.fmt.pix_mp.colorspace = V4L2_COLORSPACE_REC709;
            format.fmt.pix_mp.ycbcr_enc = V4L2_YCBCR_ENC_709;
            // 使用全范围量化（0-255），而不是有限范围（16-235）
            format.fmt.pix_mp.quantization = V4L2_QUANTIZATION_FULL_RANGE;
            format.fmt.pix_mp.xfer_func = V4L2_XFER_FUNC_709;

            LOGD(TAG, "setFrameSize: multiplanar mode, %dx%d, format=%s, quantization=FULL_RANGE",
                 width, height, frame_format ? "YUYV" : "MJPEG");
        } else {
            // 单平面 API
            format.fmt.pix.width = width;
            format.fmt.pix.height = height;
            format.fmt.pix.field = V4L2_FIELD_ANY;
            format.fmt.pix.pixelformat = frame_format ? V4L2_PIX_FMT_YUYV : V4L2_PIX_FMT_MJPEG;

            // 设置色彩空间和量化范围
            format.fmt.pix.colorspace = V4L2_COLORSPACE_REC709;
            format.fmt.pix.ycbcr_enc = V4L2_YCBCR_ENC_709;
            format.fmt.pix.quantization = V4L2_QUANTIZATION_FULL_RANGE;
            format.fmt.pix.xfer_func = V4L2_XFER_FUNC_709;

            LOGD(TAG, "setFrameSize: single-planar mode, %dx%d, format=%s, quantization=FULL_RANGE",
                 width, height, frame_format ? "YUYV" : "MJPEG");
        }

        if (0 > ioctl(fd, VIDIOC_S_FMT, &format)) {
            LOGE(TAG, "setFrameSize: ioctl set format failed, %s", strerror(errno));
            return ACTION_ERROR_SET_W_H;
        }

        // 读取实际设置的格式
        if (useMultiplanar) {
            LOGD(TAG, "setFrameSize: actual format: %dx%d",
                 format.fmt.pix_mp.width, format.fmt.pix_mp.height);
            LOGD(TAG, "  colorspace=%d, ycbcr_enc=%d, quantization=%d, xfer_func=%d",
                 format.fmt.pix_mp.colorspace, format.fmt.pix_mp.ycbcr_enc,
                 format.fmt.pix_mp.quantization, format.fmt.pix_mp.xfer_func);
        } else {
            LOGD(TAG, "setFrameSize: actual format: %dx%d",
                 format.fmt.pix.width, format.fmt.pix.height);
            LOGD(TAG, "  colorspace=%d, ycbcr_enc=%d, quantization=%d, xfer_func=%d",
                 format.fmt.pix.colorspace, format.fmt.pix.ycbcr_enc,
                 format.fmt.pix.quantization, format.fmt.pix.xfer_func);
        }

        if (frame_format) { // YUYV
            pixelBytes = width * height * 2;
            out_buffer = (uint8_t *) calloc(1, pixelBytes);
        } else { // MJPEG
            decoder = new DecoderFactory();
            if (0 != decoder->init(width, height)){
                SAFE_DELETE(decoder);
                LOGE(TAG, "DecoderFactory init failed");
                return ACTION_ERROR_DECODER;
            } else if (PIXEL_FORMAT_NV12 == decoder->getPixelFormat()) {
                pixelBytes = width * height * 3 / 2;
            } else {
                pixelBytes = width * height * 2;
            }
        }

        //2-set frame fps
        struct v4l2_streamparm parm;
        memset(&parm, 0, sizeof(parm));
        parm.type = useMultiplanar ? V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE : V4L2_BUF_TYPE_VIDEO_CAPTURE;
        parm.parm.capture.timeperframe.numerator = 1;
        parm.parm.capture.timeperframe.denominator = 30;

        if (0 > ioctl(fd, VIDIOC_S_PARM, &parm)) {
            LOGW(TAG, "setFrameSize: ioctl set fps failed, %s", strerror(errno));
        } else {
            LOGD(TAG, "setFrameSize: fps set to 30");
        }

        //3-what function ?
        int min;
        if (useMultiplanar) {
            min = format.fmt.pix_mp.width * 2;
            if (format.fmt.pix_mp.plane_fmt[0].bytesperline < min) {
                format.fmt.pix_mp.plane_fmt[0].bytesperline = min;
            }
            min = format.fmt.pix_mp.plane_fmt[0].bytesperline * format.fmt.pix_mp.height;
            if (format.fmt.pix_mp.plane_fmt[0].sizeimage < min) {
                format.fmt.pix_mp.plane_fmt[0].sizeimage = min;
            }
        } else {
            min = format.fmt.pix.width * 2;
            if (format.fmt.pix.bytesperline < min) {
                format.fmt.pix.bytesperline = min;
            }
            min = format.fmt.pix.bytesperline * format.fmt.pix.height;
            if (format.fmt.pix.sizeimage < min) {
                format.fmt.pix.sizeimage = min;
            }
        }

        frameWidth = width;
        frameHeight = height;
        frameFormat = frame_format;
        status = STATUS_INIT;
        return ACTION_SUCCESS;
    } else {
        LOGW(TAG, "setFrameSize: error status, %d", getStatus());
        return ACTION_ERROR_SET_W_H;
    }
}

void CameraAPI::getActualFrameSize(int &width, int &height) {
    width = frameWidth;
    height = frameHeight;
}

ActionInfo CameraAPI::setFrameCallback(JNIEnv *env, jobject frame_callback) {
    if (STATUS_INIT == getStatus()) {
        if (!env->IsSameObject(frameCallback, frame_callback)) {
            if (frameCallback) {
                env->DeleteGlobalRef(frameCallback);
            }
            if (frame_callback) {
                jclass clazz = env->GetObjectClass(frame_callback);
                if (LIKELY(clazz)) {
                    frameCallback = frame_callback;
                    frameCallback_onFrame = env->GetMethodID(clazz, "onFrame","(Ljava/nio/ByteBuffer;)V");
                }
                env->ExceptionClear();
                if (!frameCallback_onFrame) {
                    env->DeleteGlobalRef(frameCallback);
                    frameCallback = NULL;
                    frameCallback_onFrame = NULL;
                }
            }
        }
        return ACTION_SUCCESS;
    } else {
        LOGW(TAG, "setFrameCallback: error status, %d", getStatus());
        return ACTION_ERROR_CALLBACK;
    }
}

ActionInfo CameraAPI::setPreview(ANativeWindow *window) {
    LOGD(TAG, "setPreview: window=%p, status=%d, frameFormat=%d, frameSize=%dx%d",
         window, getStatus(), frameFormat, frameWidth, frameHeight);
    if (STATUS_INIT == getStatus()) {
        if (preview != NULL) {
            preview->destroy();
            SAFE_DELETE(preview);
        }
        if (LIKELY(window != NULL)) {
            PixelFormat pixelFormat = PIXEL_FORMAT_ERROR;
            if (decoder != NULL) {
                pixelFormat = decoder->getPixelFormat();
                LOGD(TAG, "setPreview: using decoder pixelFormat=%d", pixelFormat);
            } else if (frameFormat == FRAME_FORMAT_YUYV) {
                pixelFormat = PIXEL_FORMAT_YUYV;
                LOGD(TAG, "setPreview: using YUYV pixelFormat=%d", pixelFormat);
            } else if (frameFormat == FRAME_FORMAT_DEPTH) {
                pixelFormat = PIXEL_FORMAT_DEPTH;
                LOGD(TAG, "setPreview: using DEPTH pixelFormat=%d", pixelFormat);
            }
            preview = new CameraView(frameWidth, frameHeight, pixelFormat, window);
            LOGD(TAG, "setPreview: created CameraView, preview=%p", preview);
        } else {
            LOGW(TAG, "setPreview: window is NULL!");
        }
        return ACTION_SUCCESS;
    } else {
        LOGW(TAG, "setPreview: error status, %d", getStatus());
        return ACTION_ERROR_SET_PREVIEW;
    }
}

ActionInfo CameraAPI::start() {
    ActionInfo action = ACTION_ERROR_START;
    if (STATUS_INIT == getStatus()) {
        if (ACTION_SUCCESS == prepareBuffer()) {
            //1-start stream
            enum v4l2_buf_type type = static_cast<v4l2_buf_type>(
                useMultiplanar ? V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE : V4L2_BUF_TYPE_VIDEO_CAPTURE);
            if (0 > ioctl(fd, VIDIOC_STREAMON, &type)) {
                LOGE(TAG, "start: ioctl VIDIOC_STREAMON failed, %s", strerror(errno));
            } else {
                status = STATUS_RUN;
                //3-start thread loop frame
                if (0 == pthread_create(&thread_camera, NULL, loopThread, (void *) this)) {
                    LOGD(TAG, "start: success");
                    action = ACTION_SUCCESS;
                } else {
                    LOGE(TAG, "start: pthread_create failed");
                }
            }
        } else {
            LOGE(TAG, "start: error prepare buffer, %d", getStatus());
        }
    } else {
        LOGW(TAG, "start: error status, %d", getStatus());
    }
    return action;
}

ActionInfo CameraAPI::stop() {
    ActionInfo action = ACTION_SUCCESS;
    if (STATUS_RUN == getStatus()) {
        status = STATUS_INIT;
        //1-stop thread
        if (0 == pthread_join(thread_camera, NULL)) {
            LOGD(TAG, "stop: pthread_join success");
        } else {
            LOGE(TAG, "stop: pthread_join failed, %s", strerror(errno));
            action = ACTION_ERROR_STOP;
        }
        //3-stop preview
        if (preview) preview->pause();
        //4-stop stream
        enum v4l2_buf_type type = static_cast<v4l2_buf_type>(
            useMultiplanar ? V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE : V4L2_BUF_TYPE_VIDEO_CAPTURE);
        if (0 > ioctl(fd, VIDIOC_STREAMOFF, &type)) {
            LOGE(TAG, "stop: ioctl failed: %s", strerror(errno));
            action = ACTION_ERROR_STOP;
        } else {
            LOGD(TAG, "stop: ioctl VIDIOC_STREAMOFF success");
        }
        //5-release buffer
        for (int i = 0; i < MAX_BUFFER_COUNT; ++i) {
            if (0 != munmap(buffers[i].start, buffers[i].length)) {
                LOGW(TAG, "stop: munmap failed");
            }
        }
    } else {
        LOGW(TAG, "stop: error status, %d", getStatus());
        action = ACTION_ERROR_STOP;
    }
    return action;
}

ActionInfo CameraAPI::close() {
    ActionInfo action = ACTION_SUCCESS;
    if (STATUS_INIT == getStatus()) {
        status = STATUS_CREATE;
        //1-close fd
        if (0 > ::close(fd)) {
            LOGE(TAG, "close: failed, %s", strerror(errno));
            action = ACTION_ERROR_CLOSE;
        } else {
            LOGD(TAG, "close: success");
        }
        //2-release buffer
        SAFE_FREE(buffers)
        SAFE_FREE(out_buffer)
        //3-destroy decoder
        SAFE_DELETE(decoder)
        //4-preview destroy
        if (preview != NULL) {
            preview->destroy();
            SAFE_DELETE(preview);
        }
        //5-release frameCallback
        JNIEnv *env = getEnv();
        if (env && frameCallback_onFrame) {
            env->DeleteGlobalRef(frameCallback);
            frameCallback_onFrame = NULL;
            frameCallback = NULL;
        }
    } else {
        LOGW(TAG, "close: error status, %d", getStatus());
    }
    return action;
}

ActionInfo CameraAPI::destroy() {
    // 清理 preview（CameraView），释放 ANativeWindow 引用
    // 这是修复 HDMI 首次插入黑屏问题的关键：
    // 如果不清理 preview，旧的 CameraView 会持有 ANativeWindow 连接，
    // 导致下次 setPreview 时出现 "BufferQueueProducer: already connected" 错误
    if (preview) {
        preview->destroy();
        SAFE_DELETE(preview);
    }
    fd = 0;
    pixelBytes = 0;
    frameWidth = 0;
    frameHeight = 0;
    frameFormat = 0;
    useMultiplanar = false;
    thread_camera = 0;
    status = STATUS_CREATE;
    frameCallback = NULL;
    frameCallback_onFrame = NULL;
    saveFrameRequested = false;
    memset(debugSavePath, 0, sizeof(debugSavePath));
    SAFE_FREE(buffers)
    SAFE_FREE(out_buffer)
    SAFE_DELETE(decoder)
    LOGD(TAG, "destroy");
    return ACTION_SUCCESS;
}

void CameraAPI::saveFrameToFile(const uint8_t* data, size_t size, const char* suffix) {
    if (!data || size == 0 || strlen(debugSavePath) == 0) {
        LOGE(TAG, "saveFrameToFile: invalid params");
        return;
    }

    char filename[512];
    snprintf(filename, sizeof(filename), "%s/frame_%dx%d_%s.raw",
             debugSavePath, frameWidth, frameHeight, suffix);

    FILE* file = fopen(filename, "wb");
    if (file) {
        size_t written = fwrite(data, 1, size, file);
        fclose(file);
        LOGD(TAG, "saveFrameToFile: saved %zu bytes to %s", written, filename);
    } else {
        LOGE(TAG, "saveFrameToFile: failed to open %s: %s", filename, strerror(errno));
    }

    // 同时输出数据统计信息用于分析
    if (size >= 16) {
        // 计算数据统计
        uint64_t sum = 0;
        uint8_t minVal = 255, maxVal = 0;
        int zeroCount = 0;
        for (size_t i = 0; i < size; i++) {
            sum += data[i];
            if (data[i] < minVal) minVal = data[i];
            if (data[i] > maxVal) maxVal = data[i];
            if (data[i] == 0) zeroCount++;
        }
        double avg = (double)sum / size;

        LOGD(TAG, "saveFrameToFile: data stats - min=%d, max=%d, avg=%.2f, zeros=%d (%.1f%%)",
             minVal, maxVal, avg, zeroCount, (100.0 * zeroCount / size));

        // 打印前 64 字节的十六进制
        LOGD(TAG, "saveFrameToFile: first 64 bytes:");
        char hexBuf[256];
        for (int row = 0; row < 4 && row * 16 < size; row++) {
            int offset = 0;
            for (int col = 0; col < 16 && row * 16 + col < size; col++) {
                offset += snprintf(hexBuf + offset, sizeof(hexBuf) - offset, "%02X ", data[row * 16 + col]);
            }
            LOGD(TAG, "  %s", hexBuf);
        }
    }
}

void CameraAPI::requestSaveFrame(const char* savePath) {
    if (savePath && strlen(savePath) > 0) {
        strncpy(debugSavePath, savePath, sizeof(debugSavePath) - 1);
        debugSavePath[sizeof(debugSavePath) - 1] = '\0';
        saveFrameRequested = true;
        LOGD(TAG, "requestSaveFrame: will save next frame to %s", debugSavePath);
    } else {
        LOGE(TAG, "requestSaveFrame: invalid save path");
    }
}

#ifdef __cplusplus
}  // extern "C"
#endif