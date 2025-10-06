package org.example.app

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BasicAppTest {

    // PUBLIC_INTERFACE
    /**
     * A minimal test to ensure unit test discovery works for the app module.
     */
    @Test
    fun sanity_check_runs() {
        assertTrue(true, "Sanity check should run and pass")
    }
}
