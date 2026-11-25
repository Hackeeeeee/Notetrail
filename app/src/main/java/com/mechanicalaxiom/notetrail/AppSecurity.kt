package com.mechanicalaxiom.notetrail

import android.util.Log
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings

object AppSecurity {
    private const val TAG = "AppSecurity"

    fun checkKillSwitch(onSuccess: () -> Unit, onKill: (String) -> Unit) {
        val remoteConfig = FirebaseRemoteConfig.getInstance()
        
        // Set default values so the app doesn't lock if fetch fails or parameter is missing
        val defaults = mapOf(
            "is_app_active" to true,
            "app_status_message" to "NoteTrail is temporarily offline for maintenance."
        )
        remoteConfig.setDefaultsAsync(defaults)

        val configSettings = remoteConfigSettings {
            minimumFetchIntervalInSeconds = 0 
        }
        remoteConfig.setConfigSettingsAsync(configSettings)

        Log.d(TAG, "Fetching Remote Config...")
        remoteConfig.fetchAndActivate().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val isActive = remoteConfig.getBoolean("is_app_active")
                val message = remoteConfig.getString("app_status_message")
                Log.d(TAG, "Remote Config Fetch Successful. is_app_active: $isActive, message: $message")

                if (isActive) {
                    onSuccess()
                } else {
                    onKill(message.ifEmpty { "NoteTrail is temporarily offline for maintenance." })
                }
            } else {
                Log.e(TAG, "Remote Config Fetch Failed", task.exception)
                // On failure, we use the default (true) which calls onSuccess
                onSuccess()
            }
        }
    }
}
