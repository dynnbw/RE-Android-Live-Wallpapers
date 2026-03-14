LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := galaxyvulkan
LOCAL_SRC_FILES := galaxyvk_jni.cpp
LOCAL_CPPFLAGS := -std=c++17
LOCAL_LDLIBS := -landroid -llog -lvulkan
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := fallvulkan
LOCAL_SRC_FILES := fallvk_jni.cpp
LOCAL_CPPFLAGS := -std=c++17
LOCAL_LDLIBS := -landroid -llog -lvulkan
include $(BUILD_SHARED_LIBRARY)