package com.mechanicalaxiom.notetrail

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

object SessionManager {
    private const val TAG = "SessionManager"

    fun logout(context: Context) {
        Log.d(TAG, "Initiating global logout and data wipe...")

        // 1. Firebase Sign Out
        FirebaseAuth.getInstance().signOut()

        // 2. Clear In-Memory State
        IgcseDataHolder.clear()

        // 3. Clear SharedPreferences
        clearAllPreferences(context)

        // 4. Wipe Encryption Keys
        CryptoManager.clearKey(context)

        // 5. Clear Firestore Local Cache
        try {
            FirebaseFirestore.getInstance().clearPersistence()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear Firestore persistence: ${e.message}")
        }

        // 6. Delete Local Snapshots/Notes
        deleteLocalData(context)

        // 7. Redirect to Login
        val intent = Intent(context, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        context.startActivity(intent)
    }

    fun clearAllPreferences(context: Context) {
        val prefs = listOf(
            "IgcseProgressPrefs",
            "NoteTrailPrefs",
            "LoginPrefs"
        )
        prefs.forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit {
                clear()
            }
        }
    }

    private fun deleteLocalData(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val storageDir = File(context.getExternalFilesDir(null), "AnnotatedPapers")
                if (storageDir.exists()) {
                    storageDir.deleteRecursively()
                    Log.d(TAG, "Local annotated papers wiped.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete local data: ${e.message}")
            }
        }
    }
}
