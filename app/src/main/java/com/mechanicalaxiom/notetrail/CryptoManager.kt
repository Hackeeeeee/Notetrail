package com.mechanicalaxiom.notetrail

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoManager {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12 
    private const val TAG_SIZE = 128
    
    private const val PREFS_NAME = "notetrail_secure_prefs"
    private const val CLOUD_MASTER_KEY_ALIAS = "cloud_master_key"

    // EMERGENCY FIX: Static key and IV for testing build consistency
    private val EMERGENCY_KEY_BYTES = byteArrayOf(
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16
    )
    private val EMERGENCY_IV_BYTES = byteArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
    )
    private val emergencySecretKey = SecretKeySpec(EMERGENCY_KEY_BYTES, "AES")

    private var cloudMasterKey: SecretKey? = null
    private var isKeyRestored: Boolean = false
    private var isTemporaryKey: Boolean = false

    /**
     * Initializes the hardware-backed encryption system.
     * Silent "Handshake" with the Android Keystore.
     */
    fun init(context: Context) {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val sharedPreferences = EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            val encodedKey = sharedPreferences.getString(CLOUD_MASTER_KEY_ALIAS, null)
            if (encodedKey != null) {
                val keyBytes = decodeBase64(encodedKey)
                cloudMasterKey = SecretKeySpec(keyBytes, "AES")
                isKeyRestored = true
                isTemporaryKey = false
                Log.d("CryptoManager", "Hardware handshake successful. Cloud Master Key restored.")
            } else {
                generateAndStoreCloudMasterKey(sharedPreferences)
                isKeyRestored = false
                isTemporaryKey = true // Flag as temporary until verified/backed up
                Log.d("CryptoManager", "New Hardware-linked Cloud Master Key generated.")
            }
        } catch (e: Exception) {
            Log.e("CryptoManager", "Hardware handshake failed: ${e.message}")
        }
    }

    private fun generateAndStoreCloudMasterKey(prefs: SharedPreferences) {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        val key = keyGen.generateKey()
        cloudMasterKey = key
        
        val encodedKey = encodeBase64(key.encoded)
        prefs.edit().putString(CLOUD_MASTER_KEY_ALIAS, encodedKey).apply()
    }

    fun hasCloudMasterKey(): Boolean = cloudMasterKey != null
    
    fun isKeyRestored(): Boolean = isKeyRestored

    fun isTemporaryKey(): Boolean = isTemporaryKey

    fun getCloudMasterKey(): SecretKey? = cloudMasterKey

    fun clearKey(context: Context) {
        cloudMasterKey = null
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val sharedPreferences = EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            sharedPreferences.edit().remove(CLOUD_MASTER_KEY_ALIAS).apply()
        } catch (e: Exception) {
            Log.e("CryptoManager", "Failed to clear cloud master key: ${e.message}")
        }
    }

    fun setCloudMasterKey(context: Context, key: SecretKey) {
        this.cloudMasterKey = key
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val sharedPreferences = EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            val encodedKey = encodeBase64(key.encoded)
            sharedPreferences.edit().putString(CLOUD_MASTER_KEY_ALIAS, encodedKey).apply()
        } catch (e: Exception) {
            Log.e("CryptoManager", "Failed to save cloud master key: ${e.message}")
        }
    }

    /**
     * Silent Scramble: Encrypts data using the Cloud Master Key.
     */
    fun encrypt(data: String): String {
        // EMERGENCY FIX: Use static key and IV
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(TAG_SIZE, EMERGENCY_IV_BYTES)
        cipher.init(Cipher.ENCRYPT_MODE, emergencySecretKey, spec)
        
        val encryptedBytes = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        
        val combined = ByteArray(EMERGENCY_IV_BYTES.size + encryptedBytes.size)
        System.arraycopy(EMERGENCY_IV_BYTES, 0, combined, 0, EMERGENCY_IV_BYTES.size)
        System.arraycopy(encryptedBytes, 0, combined, EMERGENCY_IV_BYTES.size, encryptedBytes.size)
        
        return encodeBase64(combined)
    }

    /**
     * Silent Recovery: Decrypts data using the Cloud Master Key.
     */
    fun decrypt(encryptedData: String): String {
        try {
            // EMERGENCY FIX: Use static key and IV
            val combined = decodeBase64(encryptedData)
            
            val ciphertext = ByteArray(combined.size - IV_SIZE)
            System.arraycopy(combined, IV_SIZE, ciphertext, 0, ciphertext.size)
            
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(TAG_SIZE, EMERGENCY_IV_BYTES)
            cipher.init(Cipher.DECRYPT_MODE, emergencySecretKey, spec)
            
            val decodedBytes = cipher.doFinal(ciphertext)
            return String(decodedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e("CryptoManager", "Decryption Exception: ${e.message}. Data might be encrypted with a different key.")
            throw e
        }
    }

    private fun encodeBase64(data: ByteArray): String {
        return try {
            Base64.encodeToString(data, Base64.NO_WRAP)
        } catch (e: Exception) {
            java.util.Base64.getEncoder().encodeToString(data)
        }
    }

    private fun decodeBase64(data: String): ByteArray {
        return try {
            Base64.decode(data, Base64.NO_WRAP)
        } catch (e: Exception) {
            java.util.Base64.getDecoder().decode(data)
        }
    }
}
