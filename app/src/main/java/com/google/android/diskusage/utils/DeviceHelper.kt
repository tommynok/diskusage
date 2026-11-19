package com.google.android.diskusage.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import com.google.android.diskusage.porter.PorterClient
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

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
     * Executes 'su -c id' allowing full time for the user to respond to root manager prompts
     * (KernelSU, APatch, SukiSU, Magisk, SuperSU, LOS su, etc.).
     */
    @JvmStatic
    fun requestRoot(): Boolean {
        for (cmd in arrayOf("su", "/system/bin/su", "/system/xbin/su")) {
            var p: Process? = null
            try {
                p = Runtime.getRuntime().exec(arrayOf(cmd, "-c", "id"))
                val exitCode = p.waitFor()
                if (exitCode == 0) {
                    val line = BufferedReader(InputStreamReader(p.inputStream)).readLine() ?: ""
                    if (line.contains("uid=0")) {
                        isRootedCache = true
                        return true
                    }
                }
            } catch (ignored: Throwable) {
            } finally {
                try { p?.destroy() } catch (ignored: Throwable) {}
            }
        }
        isRootedCache = false
        return false
    }

    /**
     * Quick probe that waits at most 800ms for 'su -c id'.
     * If root was already granted, modern root managers return uid=0 within 10-30ms.
     */
    @JvmStatic
    fun requestRootFastProbe(): Boolean {
        for (cmd in arrayOf("su", "/system/bin/su", "/system/xbin/su")) {
            var p: Process? = null
            try {
                p = Runtime.getRuntime().exec(arrayOf(cmd, "-c", "id"))
                val proc = p
                var success = false
                val waitThread = Thread {
                    try {
                        val exitCode = proc.waitFor()
                        if (exitCode == 0) {
                            val line = BufferedReader(InputStreamReader(proc.inputStream)).readLine() ?: ""
                            if (line.contains("uid=0")) {
                                success = true
                            }
                        }
                    } catch (ignored: Throwable) {}
                }
                waitThread.start()
                waitThread.join(800)
                if (waitThread.isAlive) {
                    waitThread.interrupt()
                    proc.destroy()
                } else if (success) {
                    isRootedCache = true
                    return true
                }
            } catch (ignored: Throwable) {
            } finally {
                try { p?.destroy() } catch (ignored: Throwable) {}
            }
        }
        isRootedCache = false
        return false
    }

    /**
     * Probes root status in a background thread and triggers callback on completion.
     */
    @JvmStatic
    fun probeRootAsync(onComplete: ((Boolean) -> Unit)? = null) {
        val t = Thread({
            val granted = requestRootFastProbe()
            onComplete?.invoke(granted)
        }, "du-root-probe")
        t.isDaemon = true
        t.start()
    }

    /**
     * Returns true if root execution is verified and granted.
     * Safe to call on main thread (returns cached state or false without blocking).
     * On background worker thread, performs fast probe if cache is uninitialized.
     */
    @JvmStatic
    fun isDeviceRooted(): Boolean {
        isRootedCache?.let { return it }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return requestRootFastProbe()
        }
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
    fun getShizukuUid(): Int {
        return try {
            if (isShizukuGranted()) Shizuku.getUid() else -1
        } catch (t: Throwable) {
            -1
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
    fun getPorterUid(): Int {
        return try {
            if (isPorterGranted()) PorterClient.getUid() else -1
        } catch (t: Throwable) {
            -1
        }
    }

    @JvmStatic
    fun isPorterRoot(): Boolean {
        return try {
            isPorterGranted() && PorterClient.getUid() == 0
        } catch (t: Throwable) {
            false
        }
    }

    @JvmStatic
    fun isElevated(): Boolean {
        return isDeviceRooted() || isPorterGranted() || isShizukuGranted()
    }
}
