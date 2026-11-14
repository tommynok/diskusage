LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE    := scan
LOCAL_SRC_FILES := scan.c

LOCAL_LDFLAGS   += -Wl,-e,main

include $(BUILD_SHARED_LIBRARY)
