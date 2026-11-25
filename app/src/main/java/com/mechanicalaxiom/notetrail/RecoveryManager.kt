package com.mechanicalaxiom.notetrail

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object RecoveryManager {

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }

    private const val IV_SIZE = 12
    private const val TAG_SIZE = 128
    private const val ITERATIONS = 10000
    private const val KEY_LENGTH = 256

    private fun deriveKeyFromPassword(password: String, salt: String): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt.toByteArray(), ITERATIONS, KEY_LENGTH)
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = f.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Vault Backup: Stores the Cloud Master Key in Firestore, encrypted by the user's password.
     */
    suspend fun backupSyncVault(password: String) {
        val uid = auth.currentUser?.uid ?: run {
            Log.e("RecoveryManager", "Backup failed: No UID")
            return
        }
        val cloudMasterKey = CryptoManager.getCloudMasterKey() ?: run {
            Log.e("RecoveryManager", "Backup failed: No Cloud Master Key")
            return
        }
        
        Log.d("RecoveryManager", "Starting Vault Backup for user: $uid")
        try {
            val recoveryKey = deriveKeyFromPassword(password, uid)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, recoveryKey)
            val iv = cipher.iv
            val encryptedKeyBytes = cipher.doFinal(cloudMasterKey.encoded)
            
            val combined = ByteArray(iv.size + encryptedKeyBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encryptedKeyBytes, 0, combined, iv.size, encryptedKeyBytes.size)
            
            val encryptedKeyBase64 = encodeBase64(combined)
            
            db.collection("users").document(uid).collection("security").document("cloud_vault")
                .set(mapOf("encryptedVaultKey" to encryptedKeyBase64), SetOptions.merge())
                .await()
            Log.i("RecoveryManager", "Vault Backup Successful")
        } catch (e: Exception) {
            Log.e("RecoveryManager", "Vault Backup Failed: ${e.message}")
            throw e
        }
    }
    
    /**
     * Vault Restoration: Recovers the Cloud Master Key using the user's password.
     */
    suspend fun restoreSyncVault(context: Context, password: String): Boolean {
        val uid = auth.currentUser?.uid ?: return false
        Log.d("RecoveryManager", "Starting Vault Restoration for user: $uid")
        
        try {
            val doc = db.collection("users").document(uid).collection("security").document("cloud_vault").get().await()
            val encryptedKeyBase64 = doc.getString("encryptedVaultKey") ?: run {
                Log.w("RecoveryManager", "Restore failed: No encrypted vault key found in Firestore")
                return false
            }
            
            val recoveryKey = deriveKeyFromPassword(password, uid)
            val combined = decodeBase64(encryptedKeyBase64)
            
            val iv = ByteArray(IV_SIZE)
            val ciphertext = ByteArray(combined.size - IV_SIZE)
            System.arraycopy(combined, 0, iv, 0, IV_SIZE)
            System.arraycopy(combined, IV_SIZE, ciphertext, 0, ciphertext.size)
            
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(TAG_SIZE, iv)
            cipher.init(Cipher.DECRYPT_MODE, recoveryKey, spec)
            
            val keyBytes = cipher.doFinal(ciphertext)
            val cloudMasterKey = SecretKeySpec(keyBytes, "AES")
            CryptoManager.setCloudMasterKey(context, cloudMasterKey)
            Log.i("RecoveryManager", "Vault Restoration Successful")
            return true
        } catch (e: Exception) {
            Log.e("RecoveryManager", "Vault Restoration Failed: ${e.message}")
            return false
        }
    }

    suspend fun hasVaultBackup(): Boolean {
        val uid = auth.currentUser?.uid ?: return false
        return try {
            val doc = db.collection("users").document(uid).collection("security").document("cloud_vault").get().await()
            doc.exists()
        } catch (e: Exception) {
            Log.e("RecoveryManager", "Failed to check vault backup: ${e.message}")
            false
        }
    }

    private fun encodeBase64(data: ByteArray): String {
        return try {
            android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            java.util.Base64.getEncoder().encodeToString(data)
        }
    }

    private fun decodeBase64(data: String): ByteArray {
        return try {
            android.util.Base64.decode(data, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            java.util.Base64.getDecoder().decode(data)
        }
    }
}
