package com.mechanicalaxiom.notetrail

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.initialize

class IgcseApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Check if we are in an isolated process to avoid SharedPreferences crash
        if (android.os.Process.isIsolated()) {
            Log.d("IgcseApp", "Isolated process detected, skipping SharedPreferences and Firestore")
            return
        }

        // Initialize Firebase
        try {
            Firebase.initialize(this)
            // Initialize App Check with Play Integrity
            val firebaseAppCheck = Firebase.appCheck
            firebaseAppCheck.installAppCheckProviderFactory(
                PlayIntegrityAppCheckProviderFactory.getInstance()
            )
            Log.d("IgcseApp", "Firebase App Check initialized with Play Integrity")
        } catch (e: Exception) {
            Log.e("IgcseApp", "Firebase initialization failed: ${e.message}")
        }

        // Initialize Firestore Settings Globally - ONLY ONCE HERE
        try {
            val firestore = FirebaseFirestore.getInstance()
            val settings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
                .build()
            firestore.firestoreSettings = settings
            Log.d("IgcseApp", "Firestore initialized successfully with persistence")
        } catch (e: Exception) {
            Log.e("IgcseApp", "Firestore already started or init failed: ${e.message}")
        }

        // Initialize Theme to prevent startup lag/recreation
        try {
            val prefs = getSharedPreferences("NoteTrailPrefs", MODE_PRIVATE)
            val mode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            AppCompatDelegate.setDefaultNightMode(mode)
        } catch (e: Exception) {
            Log.e("IgcseApp", "Failed to load theme prefs: ${e.message}")
        }

        // Initialize CryptoManager (Silent Hardware Handshake)
        try {
            CryptoManager.init(this)
        } catch (e: Exception) {
            Log.e("IgcseApp", "CryptoManager init failed: ${e.message}")
        }

        // Initialize LinkProtector for secure URLs
        try {
            LinkProtector.init()
        } catch (e: Exception) {
            Log.e("IgcseApp", "LinkProtector init failed: ${e.message}")
        }
    }
}
