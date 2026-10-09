package io.github.nyzeep.orchestella

import org.junit.Assert.assertEquals
import org.junit.Test

class BootstrapTest {
    @Test
    fun applicationIdMatchesBootstrapChoice() {
        assertEquals("io.github.nyzeep.orchestella", BuildConfig.APPLICATION_ID)
    }
}
