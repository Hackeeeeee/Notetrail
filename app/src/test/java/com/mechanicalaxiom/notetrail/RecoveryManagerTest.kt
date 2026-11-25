package com.mechanicalaxiom.notetrail

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class RecoveryManagerTest {

    @Test
    fun testDeterministicVaultDerivation() {
        val password = "StrongPassword123!"
        val uid = "test_user_uid"
        
        val key1 = deriveKey(password, uid)
        val key2 = deriveKey(password, uid)
        
        assertArrayEquals("Same password should yield same vault restoration key", key1.encoded, key2.encoded)
    }

    private fun deriveKey(password: String, salt: String): SecretKeySpec {
        val iterations = 10000
        val keyLength = 256
        val spec = javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt.toByteArray(), iterations, keyLength)
        val f = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = f.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }
}
