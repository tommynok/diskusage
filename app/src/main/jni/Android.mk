LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE    := libscan
LOCAL_SRC_FILES := scan.c

TARGET_EXE_EXT := .so

include $(BUILD_EXECUTABLE)
