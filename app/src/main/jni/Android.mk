LOCAL_PATH := $(call my-dir)

# 四个壁纸的 VK 渲染器打进同一个库。
#
# 原来是每个壁纸一个 .so（4 个库 × 4 个 ABI = 16 个文件）。壁纸数量涨上去时这个数字
# 是线性膨胀的，而且每份都各自静态链一遍 libc++。合并之后：
#   二进制 16 → 4（每 ABI 一个）、ndk-build 模块 4 → 1、libc++ 只链一份。
#
# 代价：四个渲染器都在同一个库里（未用到的代码页不会被换入，实际开销很小）；
# 某一家的原生崩溃本来就带走整个进程，隔离性并没有变差。
include $(CLEAR_VARS)
LOCAL_MODULE := rebornvk
LOCAL_SRC_FILES := \
    galaxyvk_jni.cpp \
    fallvk_jni.cpp \
    grassvk_jni.cpp \
    galaxy4vk_jni.cpp
LOCAL_CPPFLAGS := -std=c++17
LOCAL_LDLIBS := -landroid -llog -lvulkan
include $(BUILD_SHARED_LIBRARY)
