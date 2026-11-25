package com.mechanicalaxiom.notetrail

import android.util.Base64
import android.util.Log
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings
import org.json.JSONObject

object LinkProtector {

    private const val TAG = "LinkProtector"
    private const val CONFIG_KEY = "api_config_payload"
    
    // Default fallback if Remote Config is not yet fetched or fails
    private var cachedLink: String = "https://raw.githubusercontent.com/Hackeeeeee/Igcse-data/main/"

    fun init() {
        val remoteConfig = FirebaseRemoteConfig.getInstance()
        val configSettings = remoteConfigSettings {
            minimumFetchIntervalInSeconds = 3600 // Fetch every hour
        }
        remoteConfig.setConfigSettingsAsync(configSettings)
        
        Log.d(TAG, "Fetching Remote Config...")
        remoteConfig.fetchAndActivate().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                Log.d(TAG, "Remote Config fetch successful")
                updateLinkFromConfig()
            } else {
                Log.e(TAG, "Remote Config fetch failed: ${task.exception?.message}")
            }
        }
    }

    private fun updateLinkFromConfig() {
        val scrambled = FirebaseRemoteConfig.getInstance().getString(CONFIG_KEY)
        if (scrambled.isNotEmpty()) {
            val payload = decodePayloadToJSON(scrambled)
            if (payload != null) {
                if (payload.has("target")) {
                    cachedLink = payload.getString("target")
                    Log.d(TAG, "Link updated from config")
                }
                if (payload.has("token")) {
                    val token = payload.getString("token")
                    Log.d(TAG, "GitHub Token found in payload (length: ${token.length})")
                    com.mechanicalaxiom.notetrail.ui.theme.GitHubScanner.updateToken(token)
                    Log.d(TAG, "GitHub Token updated from config")
                } else {
                    Log.e(TAG, "CRITICAL: 'token' field MISSING from config payload keys: ${payload.keys()}")
                }
            }
        }
    }

    private fun decodePayloadToJSON(scrambled: String): JSONObject? {
        return try {
            // Aggressively clean the Base64 string of ANY whitespace or invisible characters
            val cleanedBase64 = scrambled.replace("\\s".toRegex(), "")
                .replace("[^A-Za-z0-9+/=]".toRegex(), "")
            
            val decodedBytes = Base64.decode(cleanedBase64, Base64.DEFAULT)
            val decodedString = String(decodedBytes, Charsets.UTF_8)
            
            // Aggressively clean the JSON string of invisible Unicode characters (like \u2028)
            // and keep only standard printable ASCII/JSON characters
            val sanitizedJson = decodedString.replace("[^\\x20-\\x7E]".toRegex(), "").trim()
            
            JSONObject(sanitizedJson)
        } catch (e: Exception) {
            Log.e(TAG, "Decoding/Sanitization failed: ${e.message}")
            null
        }
    }

    fun getHiddenLink(): String {
        val scrambled = FirebaseRemoteConfig.getInstance().getString(CONFIG_KEY)
        if (scrambled.isNotEmpty()) {
            val payload = decodePayloadToJSON(scrambled)
            if (payload != null) {
                if (payload.has("target")) {
                    cachedLink = payload.getString("target")
                }
                if (payload.has("token")) {
                    val token = payload.getString("token")
                    com.mechanicalaxiom.notetrail.ui.theme.GitHubScanner.updateToken(token)
                }
            }
        }
        return cachedLink
    }
    
    /**
     * Helper to add User-Agent to any request
     */
    fun getUserAgent(): String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
}
