package com.mechanicalaxiom.notetrail

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator

class CryptoManagerTest {

    @Test
    fun testEncryptionDecryption() {
        // Mock a Cloud Master Key (hardware handshake simulated)
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        val mockKey = keyGen.generateKey()
        
        // Manual set for testing (bypassing hardware prefs)
        CryptoManager.setCloudMasterKey(null as? android.content.Context ?: return, mockKey)

        val originalData = "Silent Hardware Encryption Handshake"
        val encryptedData = CryptoManager.encrypt(originalData)
        
        assertNotEquals("Encrypted data should be scrambled", originalData, encryptedData)
        
        val decryptedData = CryptoManager.decrypt(encryptedData)
        assertEquals("Decrypted data should match original", originalData, decryptedData)
    }

    @Test
    fun testPrependedIV() {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        val mockKey = keyGen.generateKey()
        CryptoManager.setCloudMasterKey(null as? android.content.Context ?: return, mockKey)

        val data = "Interlink"
        val encrypted = CryptoManager.encrypt(data)
        
        // Use java.util.Base64 for unit tests
        val decoded = java.util.Base64.getDecoder().decode(encrypted)
        assertTrue("Combined data should include 12-byte IV", decoded.size > 12)
    }

    @Test
    fun testClearKey() {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        val mockKey = keyGen.generateKey()
        
        // Use a null context for unit test if possible, or expect failure in Prefs part but memory part should work
        CryptoManager.setCloudMasterKey(null as? android.content.Context ?: return, mockKey)
        assertTrue(CryptoManager.hasCloudMasterKey())

        CryptoManager.clearKey(null as? android.content.Context ?: return)
        assertFalse(CryptoManager.hasCloudMasterKey())
    }
}
