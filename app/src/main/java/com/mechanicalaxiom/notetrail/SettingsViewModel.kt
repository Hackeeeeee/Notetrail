package com.mechanicalaxiom.notetrail

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val TAG = "SettingsViewModel"

    fun deleteUserAccount(onComplete: (Boolean, String?) -> Unit) {
        val user = auth.currentUser
        val userId = user?.uid ?: return onComplete(false, "No user logged in")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val userRef = db.collection("users").document(userId)

                // 1. Delete Firestore Data (including known subcollections)
                val progressDataRef = userRef.collection("progress").document("data")
                val settingsPrefRef = userRef.collection("settings").document("preferences")
                
                // Note: Annotations are stored in a subcollection of the user document as well:
                // users/{uid}/annotations/{paperKey}/pages/{pageIdx}
                // We'll delete what we can from the client side.
                
                progressDataRef.delete().await()
                settingsPrefRef.delete().await()
                
                // Attempt to delete annotations collection docs (shallow)
                try {
                    val annotations = userRef.collection("annotations").get().await()
                    for (doc in annotations) {
                        // For each paper, try to delete its pages subcollection (best effort)
                        val pages = doc.reference.collection("pages").get().await()
                        for (page in pages) {
                            page.reference.delete().await()
                        }
                        doc.reference.delete().await()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to delete some annotations", e)
                }

                userRef.delete().await()

                // 2. Finally, delete the Auth account
                user.delete().await()

                onComplete(true, null)
            } catch (e: Exception) {
                Log.e(TAG, "Account deletion failed", e)
                if (e is com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException) {
                    onComplete(false, "Please log in again to delete your account.")
                } else {
                    onComplete(false, e.localizedMessage ?: "Unknown error occurred")
                }
            }
        }
    }
}
