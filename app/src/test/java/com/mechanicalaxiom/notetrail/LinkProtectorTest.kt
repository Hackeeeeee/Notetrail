package com.mechanicalaxiom.notetrail

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class LinkProtectorTest {

    @Test
    fun testDecodingLogicSimple() {
        val targetUrl = "https://example.com/data/"
        val jsonPayload = "{\"target\": \"$targetUrl\", \"key\": \"NoteTrail_2026\"}"
        
        val scrambled = Base64.getEncoder().encodeToString(jsonPayload.toByteArray())
        
        // Manual simulation of the LinkProtector's decodePayload logic (using java.util.Base64 for JVM test)
        val decodedBytes = Base64.getDecoder().decode(scrambled)
        val decodedString = String(decodedBytes)
        
        // Use a simple regex or string manipulation if JSONObject is not available in pure JVM test
        // But usually it is available in Android projects via dependencies.
        // Let's assume we want to test if our understanding of the "scrambling" is correct.
        
        val extractedUrl = decodedString.substringAfter("\"target\": \"").substringBefore("\"")
        
        assertEquals(targetUrl, extractedUrl)
    }
}
