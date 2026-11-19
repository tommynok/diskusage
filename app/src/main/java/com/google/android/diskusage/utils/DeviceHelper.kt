package com.google.android.diskusage.utils

import android.content.Context
import android.content.pm.PackageManager
import com.google.android.diskusage.porter.PorterClient
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

    /**
     * Executes 'su -c id' to verify if superuser is actually granted and usable.
     * Always destroys the probe process to avoid lingering zombie processes.
     */
    @JvmStatic
    fun requestRoot(): Boolean {
        for (cmd in arrayOf("su", "/system/bin/su", "/system/xbin/su")) {
            var p: Process? = null
            try {
                p = Runtime.getRuntime().exec(arrayOf(cmd, "-c", "id"))
                val exitCode = p.waitFor()
                if (exitCode == 0) {
                    isRootedCache = true
                    return true
                }
            } catch (ignored: Throwable) {
            } finally {
                try { p?.destroy() } catch (ignored: Throwable) {}
            }
        }
        return false
    }

    /**
     * Returns true only if root execution has been verified and granted.
     * F10 fix: Never assume root purely based on file existence of /system/bin/su.
     */
    @JvmStatic
    fun isDeviceRooted(): Boolean {
        isRootedCache?.let { return it }

        // Fast check: verify if su file exists in any known path before attempting exec
        val suLocations = arrayOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/sd/xbin/su",
            "/data/local/xbin/su", "/data/local/bin/su", "/data/local/su",
            "/system/sbin/su", "/usr/bin/su", "/vendor/bin/su",
            "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su", "/data/adb/magisk/su"
        )
        var suBinaryPresent = false
        for (loc in suLocations) {
            if (File(loc).exists()) {
                suBinaryPresent = true
                break
            }
        }

        if (!suBinaryPresent) {
            isRootedCache = false
            return false
        }

        // Verify that su actually works
        val works = requestRoot()
        isRootedCache = works
        return works
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
    fun isPorterGranted(): Boolean {
        return try {
            PorterClient.isGranted()
        } catch (t: Throwable) {
            false
        }
    }

    @JvmStatic
    fun isPorterRoot(): Boolean {
        return try {
            PorterClient.isGranted() && PorterClient.getUid() == 0
        } catch (t: Throwable) {
            false
        }
    }

    @JvmStatic
    fun isElevated(): Boolean {
        return isDeviceRooted() || isShizukuGranted() || isPorterGranted()
    }
}
