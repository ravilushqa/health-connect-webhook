package com.ravil.healthcore

import android.app.Application
import com.ravil.healthcore.data.AppPreferences
import com.ravil.healthcore.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class HealthCoreApp : Application() {
    /** Work started from the UI ("Sync now", "Check connection") that must outlive a screen. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        AppPreferences(this).migrate()
        SyncScheduler.apply(this)
    }
}
