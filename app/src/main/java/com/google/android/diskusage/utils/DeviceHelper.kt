package com.google.android.diskusage.utils

import java.io.File

object DeviceHelper {

    @Volatile
    private var isRootedCache: Boolean? = null

    @JvmStatic
    fun isDeviceRooted(): Boolean {
        isRootedCache?.let { return it }

        // 1. File checks
        val su = "su"
        val locations = arrayOf(
            "/system/bin/", "/system/xbin/", "/sbin/", "/system/sd/xbin/",
            "/system/bin/failsafe/", "/data/local/xbin/", "/data/local/bin/", "/data/local/",
            "/system/sbin/", "/usr/bin/", "/vendor/bin/", "/data/adb/ksu/bin/", "/data/adb/ap/bin/", "/data/adb/magisk/"
        )
        for (location in locations) {
            if (File("${location}${su}").exists()) {
                isRootedCache = true
                return true
            }
        }

        // 2. Execution check for KernelSU / APatch / Magisk
        for (cmd in arrayOf("su", "/system/bin/su", "/system/xbin/su")) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf(cmd, "-v"))
                val exitCode = p.waitFor()
                if (exitCode == 0 || p.inputStream.read() != -1) {
                    isRootedCache = true
                    return true
                }
            } catch (ignored: Throwable) {}
        }

        try {
            val p = Runtime.getRuntime().exec(arrayOf("which", "su"))
            if (p.waitFor() == 0 && p.inputStream.read() != -1) {
                isRootedCache = true
                return true
            }
        } catch (ignored: Throwable) {}

        isRootedCache = false
        return false
    }
}
