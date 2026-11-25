package com.mechanicalaxiom.notetrail

import org.junit.Assert.*
import org.junit.Test

class SessionManagerTest {

    @Test
    fun testIgcseDataHolderClear() {
        IgcseDataHolder.subjects = listOf(Subject("Test", "0000", emptyList()))
        assertFalse(IgcseDataHolder.subjects.isEmpty())
        
        IgcseDataHolder.clear()
        assertTrue(IgcseDataHolder.subjects.isEmpty())
    }
}
