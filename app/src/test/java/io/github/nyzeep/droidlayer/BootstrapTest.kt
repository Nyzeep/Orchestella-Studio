package io.github.nyzeep.droidlayer

import org.junit.Assert.assertEquals
import org.junit.Test

class BootstrapTest {
    @Test
    fun applicationIdMatchesBootstrapChoice() {
        assertEquals("io.github.nyzeep.droidlayer", BuildConfig.APPLICATION_ID)
    }
}
