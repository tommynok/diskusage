package com.google.android.diskusage.utils

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.File

object DeviceHelper {

    @Volatile
    private var isRootedCache: Boolean? = null

    @JvmStatic
    fun setDeviceRooted(rooted: Boolean) {
        isRootedCache = rooted
    }

    @JvmStatic
    fun clearRootCache() {
        isRootedCache = null
    }

    @JvmStatic
    fun requestRoot(): Boolean {
        for (cmd in arrayOf("su", "/system/bin/su", "/system/xbin/su")) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf(cmd, "-c", "id"))
                val exitCode = p.waitFor()
                if (exitCode == 0) {
                    isRootedCache = true
                    return true
                }
            } catch (ignored: Throwable) {}
        }
        return false
    }

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

        // Do not cache false permanently so su can be attempted on demand
        return false
    }

    @JvmStatic
    fun isShizukuGranted(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            false
        }
    }

    @JvmStatic
    fun isShizukuRoot(): Boolean {
        return try {
            isShizukuGranted() && Shizuku.getUid() == 0
        } catch (t: Throwable) {
            false
        }
    }

    @JvmStatic
    fun isPorterInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo("eu.darken.porter", 0)
            true
        } catch (e: Throwable) {
            false
        }
    }

    @JvmStatic
    fun isShizukuInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (e: Throwable) {
            false
        }
    }

    @JvmStatic
    fun isElevated(): Boolean {
        return isDeviceRooted() || isShizukuGranted()
    }
}
