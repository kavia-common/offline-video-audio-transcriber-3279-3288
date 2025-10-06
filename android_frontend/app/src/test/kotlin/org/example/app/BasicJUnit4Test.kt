package org.example.app

import org.junit.Assert.assertEquals
import org.junit.Test

class BasicJUnit4Test {

    // PUBLIC_INTERFACE
    /**
     * Minimal JUnit4 test to ensure Gradle discovers and runs unit tests in the app module.
     */
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }
}
