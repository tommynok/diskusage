LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE    := scan
LOCAL_SRC_FILES := scan.c

include $(BUILD_EXECUTABLE)
