cmake_minimum_required(VERSION 3.10.2)
project("camera_v4l2")

# 构建类型由 Android Gradle Plugin 自动传递，跟随 APK 的 buildType
# debug -> CMAKE_BUILD_TYPE=Debug, release -> CMAKE_BUILD_TYPE=Release

# Debug 构建：保留调试符号，添加 -g 标志
set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG} -g -O0")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -g -O0")
# Release 构建：strip 符号
set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} -s")
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -s")

# set common name
set(MODULE_MAIN camera)
set(DIR_YUV     libyuv)
set(DIR_CAMERA  libcamera)
set(DIR_JPEG    libjpeg-turbo)

# log switch
option(LOG_SWITCH "option for print log" ON)
if(LOG_SWITCH)
    add_definitions(-DLOG_SWITCH)
endif(LOG_SWITCH)

# module yuv
add_subdirectory(${DIR_YUV})
# module jpeg
add_subdirectory(${DIR_JPEG})

# moudle main
include_directories(${DIR_CAMERA} ${DIR_JPEG} ${DIR_YUV}/include)
aux_source_directory(${DIR_CAMERA} DIR_SRC)
add_library(${MODULE_MAIN} SHARED ${DIR_SRC})

# find system support library
find_library(log-lib log)

# target link libraries turbojpeg
target_link_libraries(${MODULE_MAIN} ${log-lib} turbojpeg yuv mediandk android #[[jnigraphics]])