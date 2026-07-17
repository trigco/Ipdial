package com.ipdial

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class IPDialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        android.util.Log.d("IPDialApp", "Application.onCreate")
        
        // Load PJSIP library on Main thread to ensure proper registration
        try {
            System.loadLibrary("c++_shared")
        } catch (t: Throwable) {
            android.util.Log.e("IPDialApp", "Failed to load c++_shared, might be normal", t)
        }
        try {
            System.loadLibrary("pjsua2")
        } catch (t: Throwable) {
            android.util.Log.e("IPDialApp", "Failed to load pjsua2", t)
        }

        // Register phone account for Telecom integration
        com.ipdial.service.TelecomHelper.registerPhoneAccount(this)

        // Emergency check for disabled launcher activity
        com.ipdial.util.AppIconHelper.forceEnableMainActivity(this)

        // Sync icon alias on startup
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val repo = com.ipdial.data.repository.AccountRepository(this@IPDialApplication)
                val currentAlias = repo.appIconAlias.first()
                if (currentAlias != "Default") {
                    com.ipdial.util.AppIconHelper.setAppIcon(this@IPDialApplication, currentAlias)
                }
            } catch (_: Exception) {}
        }
    }
}
