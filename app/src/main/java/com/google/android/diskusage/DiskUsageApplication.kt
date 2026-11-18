package com.google.android.diskusage

import android.app.Application
import eu.darken.porter.bridge.PorterShizukuBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class DiskUsageApplication : Application() {
    companion object {
        private var instance: DiskUsageApplication? = null
        fun getInstance() = instance
            ?: throw IllegalStateException("DiskUsage application is not created!")
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            PorterShizukuBridge.start(applicationScope)
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }
}
